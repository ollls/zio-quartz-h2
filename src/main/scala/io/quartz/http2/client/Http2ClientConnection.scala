package io.quartz.http2

import scala.collection.mutable.ArrayBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.ByteBuffer
import io.quartz.http2.Constants._
import io.quartz.http2.model.{Request, Response, Method, Headers, ContentType, StatusCode}
import io.quartz.http2.model.Method._
import io.quartz.netio._
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import concurrent.duration.DurationInt
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.MapHasAsScala
import io.quartz.http2.routes.Routes
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import zio.{ZIO, Task, Promise, Queue, Fiber, Chunk, Ref, Semaphore}
import zio.stream.{ZStream, ZChannel, ZPipeline}
import io.quartz.http2.routes.HttpRoute
import concurrent.duration.DurationInt
import java.net.URI
import Http2ClientConnection.EmptyStream

case class ClientResponse(
    status: StatusCode,
    headers: Headers,
    stream: ZStream[Any, Throwable, Byte]
) {
  def transferEncoding(): List[String] = headers.getMval("transfer-encoding")

  def body = stream.runCollect.map(_.toArray)

  def bodyAsText = body.map(String(_))
}

object Http2ClientConnection {

  val EmptyStream = ZStream.empty

  /** Daemon process, sends outbound packets from Queue to the IOChannel.
    * @param ch
    *   outbound IOChannel to write data to
    * @param outq
    *   the Queue to read data from
    * @return
    *   a Fiber that represents the running computation
    */
  def outBoundWorker(ch: IOChannel, outq: Queue[ByteBuffer]) = (for {
    bb <- outq.take
    _ <- ch.write(bb)
  } yield ()).catchAll(e => ZIO.logError(("Client: outBoundWorker - " + e.toString())))

  /** Reads data from the given IOChannel and processes it with the packet_handler. This function reads data from the
    * IOChannel in chunks representing Http2 packets, converts them to packets using the makePacketStream function, and
    * processes each packet with the packet_handler.
    * @param ch
    *   the IOChannel to read data from
    * @return
    *   a Fiber that represents the running computation
    */
  def make(ch: IOChannel, uri: URI, timeOutMs: Int, incomingWindowSize: Int = 65535) = {
    for {
      outq <- Queue.bounded[ByteBuffer](1)
      f0 <- outBoundWorker(ch, outq).forever.fork
      refEncoder <- Ref.make[HeaderEncoder](null)
      refDecoder <- Ref.make[HeaderDecoder](null)
      refsId <- Ref.make(1)
      hSem <- Semaphore.make(permits = 1)
      awaitSettings <- Promise.make[Throwable, Boolean]
      settings0 <- Ref.make(
        Http2Settings()
      ) // will be loaded with server data when awaitSettings is completed
      inboundWindow <- Ref.make[Long](incomingWindowSize)
      globalBytesOfPendingInboundData <- Ref.make(0)
      globalTransmitWindow <- Ref.make[Long](
        65535L
      ) // set in upddateInitialWindowSizeAllStreams
    } yield (
      Http2ClientConnection(
        ch,
        timeOutMs,
        uri,
        refsId,
        refEncoder,
        refDecoder,
        outq,
        f0,
        hSem,
        awaitSettings,
        settings0,
        globalBytesOfPendingInboundData,
        inboundWindow,
        globalTransmitWindow,
        incomingWindowSize
      )
    )

  }
}

/** Represents a HTTP/2 connection.
  *
  * @constructor
  *   Creates a new HTTP/2 connection with the specified parameters.
  * @param ch
  *   The underlying IO channel of the connection.
  * @param sets
  *   The HTTP/2 settings for the connection.
  * @param streamIdRef
  *   connection streamIds, starts from 1,3,5 ...
  * @param headerEncoderRef
  *   The header encoder for the connection.
  * @param headerDecoderRef
  *   The header decoder for the connection.
  * @param outq
  *   The output queue for the connection.
  * @param outBoundFiber
  *   The output fiber for the connection.
  * @param hSem
  *   The semaphore for the connection.
  */
class Http2ClientConnection(
    ch: IOChannel,
    timeOutMs: Int,
    uri: URI,
    streamIdRef: Ref[Int],
    headerEncoderRef: Ref[HeaderEncoder],
    headerDecoderRef: Ref[HeaderDecoder],
    outq: Queue[ByteBuffer],
    outBoundFiber: Fiber[Throwable, Nothing],
    hSem: Semaphore,
    awaitSettings: Promise[Throwable, Boolean],
    settings1: Ref[Http2Settings],
    val globalBytesOfPendingInboundData: Ref[Int],
    val inboundWindow: Ref[Long],
    transmitWindow: Ref[Long],
    INITIAL_WINDOW_SIZE: Int
) {
  import scala.jdk.CollectionConverters.*
  class Http2ClientStream(
      val streamId: Int,
      val d: Promise[Throwable, (Byte, Headers)],
      val header: ArrayBuffer[ByteBuffer],
      val inDataQ: Queue[ByteBuffer],
      val inboundWindow: Ref[Long],
      val transmitWindow: Ref[Long],
      val bytesOfPendingInboundData: Ref[Int],
      val outXFlowSync: Queue[Unit]
  )
  val streamTbl =
    java.util.concurrent.ConcurrentHashMap[Int, Http2ClientStream](100).asScala

  private def openStream(streamId: Int, in_win: Int) = (for {
    d <- Promise.make[Throwable, (Byte, Headers)]
    inboundWindow <- Ref.make[Long](in_win)
    header <- ZIO.succeed(ArrayBuffer.empty[ByteBuffer])
    inDataQ <- Queue.unbounded[ByteBuffer]
    pendingInBytes <- Ref.make(0)
    transmitWindow <- Ref.make[Long](
      65535L
    ) // set in upddateInitialWindowSizeAllStreams
    xFlowSync <- Queue.unbounded[Unit]
  } yield (Http2ClientStream(
    streamId,
    d,
    header,
    inDataQ,
    inboundWindow,
    transmitWindow,
    pendingInBytes,
    outXFlowSync = xFlowSync
  )))
    .tap(c => ZIO.succeed(this.streamTbl.put(streamId, c)))

  private[this] def decrementGlobalPendingInboundData(decrement: Int) =
    globalBytesOfPendingInboundData.update(_ - decrement)

  private[this] def incrementGlobalPendingInboundData(increment: Int) =
    globalBytesOfPendingInboundData.update(_ + increment)

  private def triggerStreamRst(streamId: Int, flags: Byte) = {
    updateStreamWith(
      5,
      streamId,
      c =>
        for {
          _ <- c.d.complete(null).unit
        } yield ()
    )
  }

  private def triggerStream(streamId: Int, flags: Byte): Task[Unit] = {
    updateStreamWith(
      5,
      streamId,
      c =>
        for {
          headerDecoder <- headerDecoderRef.get
          headers <- ZIO.attempt(headerDecoder.decodeHeaders(c.header.toSeq))
          _ <- c.d.complete(ZIO.succeed(flags, headers)).unit
        } yield ()
    )
  }

  private def accumHeaders(streamId: Int, bb: ByteBuffer): Task[Unit] =
    updateStreamWith(2, streamId, c => ZIO.attempt(c.header.addOne(bb)))

  private def updateStreamWith(
      num: Int,
      streamId: Int,
      run: Http2ClientStream => Task[Unit]
  ): Task[Unit] = {
    for {
      opt_D <- ZIO.attempt(streamTbl.get(streamId))
      _ <- opt_D match { // Option( null ) gives None
        case None =>
          ZIO.logError(
            s"Client: updateStreamWith() invalid streamId - $streamId, code=$num"
          ) *> ZIO.fail(
            ErrorGen(streamId, Error.PROTOCOL_ERROR, "invalid stream id")
          )
        case Some(con_rec) => run(con_rec)
      }
    } yield ()

  }

  /** Creates a stream of HTTP/2 packets from the specified IO channel.
    *
    * @param ch
    *   The IO channel to read packets from.
    * @param keepAliveMs
    *   The keep-alive time in milliseconds.
    * @param leftOver
    *   The leftover bytes left from TLS negotiation, if any
    * @return
    *   A fs2 stream of HTTP/2 packets.
    */
  private[this] def makePacketStream(
      ch: IOChannel,
      keepAliveMs: Int,
      leftOver: Chunk[Byte]
  ): ZStream[Any, Throwable, Chunk[Byte]] = {

    val s0 = ZStream.fromChunk(leftOver)
    val s1 =
      ZStream
        .repeatZIO(ch.read(keepAliveMs))
        .flatMap(c0 => ZStream.fromChunk(c0))
    val s = s0 ++ s1
    s.via(packetStreamPipe)
  }

  private[this] def packetStreamPipe: ZPipeline[Any, Exception, Byte, Chunk[Byte]] = {
    def go2(carryOver: Chunk[Byte]): ZChannel[Any, Exception, Chunk[Byte], Any, Exception, Chunk[Chunk[Byte]], Any] = {
      val chunk = carryOver
      val bb = ByteBuffer.wrap(chunk.toArray)
      val len = Frames.getLengthField(bb) + 3 + 1 + 1 + 4

      if (chunk.size == len) {
        ZChannel.write(Chunk.single(chunk)) *> go(Chunk.empty[Byte])
      } else if (chunk.size > len) {
        ZChannel.write(Chunk.single(chunk.take(len))) *> go2(chunk.drop(len))
      } else {
        go(chunk)
      }
    }

    def go(carryOver: Chunk[Byte]): ZChannel[Any, Exception, Chunk[Byte], Any, Exception, Chunk[Chunk[Byte]], Any] = {
      ZChannel.readWith(
        ((chunk0: Chunk[Byte]) => {

          val chunk = carryOver ++ chunk0

          val bb = ByteBuffer.wrap(chunk.toArray)
          val len = Frames.getLengthField(bb) + 3 + 1 + 1 + 4

          if (chunk.size == len) {
            ZChannel.write(Chunk.single(chunk)) *> go(Chunk.empty[Byte])
          } else if (chunk.size > len) {
            ZChannel.write(Chunk.single(chunk.take(len))) *> go2(chunk.drop(len))
          } else go(chunk)

        }),
        (err: Exception) => ZChannel.fail(err),
        (done: Any) => ZChannel.succeed(true)
      )
    }
    ZPipeline.fromChannel(go(Chunk.empty[Byte]))
  }

  def settings = settings1.get

  def inBoundWorker(ch: IOChannel, timeOutMs: Int) =
    makePacketStream(ch, timeOutMs, Chunk.empty[Byte])
      .foreach(p => packet_handler(p))
      .catchAll(e => {
        ZIO.logError("Client: inBoundWorker - " + e.toString()) *> dropStreams()
      })

  def dropStreams() = for {
    streams <- ZIO.attempt(this.streamTbl.values.toList)
    _ <- ZIO.foreach(streams)(_.d.complete(null))
    _ <- ZIO.foreach(streams)(_.inDataQ.offer(null))
  } yield ()

  /** packet_handler
    * @param packet
    * @return
    */
  def packet_handler(packet: Chunk[Byte]) = {
    val buffer = ByteBuffer.wrap(packet.toArray)
    val packet0 = buffer.slice // preserve reference to whole packet

    val len = Frames.getLengthField(buffer)
    val frameType = buffer.get()
    val flags = buffer.get()
    val streamId = Frames.getStreamId(buffer)
    for {
      _ <- ZIO.logTrace(
        s"Client: frametype=$frameType with streamId=$streamId len=$len flags=$flags"
      )

      _ <- frameType match {
        case FrameTypes.HEADERS =>
          val padLen: Byte =
            if ((flags & Flags.PADDED) != 0) buffer.get()
            else 0 // advance one byte padding len 1
          val lim = buffer.limit() - padLen
          buffer.limit(lim)
          for {
            _ <- accumHeaders(streamId, buffer)
            _ <- ZIO.when((flags & Flags.END_HEADERS) != 0)(triggerStream(streamId, flags))
          } yield ()

        // padding for CONTINUATION ??? read about it
        case FrameTypes.CONTINUATION =>
          for {
            _ <- accumHeaders(streamId, buffer)
            _ <- ZIO.when((flags & Flags.END_HEADERS) != 0)(triggerStream(streamId, flags))
          } yield ()

        case FrameTypes.PING =>
          var data = new Array[Byte](8)
          buffer.get(data)
          if ((flags & Flags.ACK) == 0) {
            for {
              _ <- ZIO.when(streamId != 0)(
                ZIO.fail(
                  ErrorGen(
                    streamId,
                    Error.PROTOCOL_ERROR,
                    "Ping streamId not 0"
                  )
                )
              )
              _ <- sendFrame(Frames.mkPingFrame(ack = true, data))
            } yield ()
          } else ZIO.unit // else if (this.start)

        case FrameTypes.DATA => accumData(streamId, packet0, len)

        case FrameTypes.SETTINGS =>
          ZIO.when(Flags.ACK(flags) == true)(awaitSettings.complete(ZIO.succeed(true))) *>
            ZIO.when(Flags.ACK(flags) == false)(for {
              current_size <- settings1.get.map(settings => settings.INITIAL_WINDOW_SIZE)
              res <- ZIO.attempt(Http2Settings.fromSettingsArray(buffer, len))

              _ <- settings1.set(res)
              _ <- upddateInitialWindowSizeAllStreams(
                current_size,
                res.INITIAL_WINDOW_SIZE
              )
            } yield ())

        case FrameTypes.WINDOW_UPDATE => {
          val increment = buffer.getInt() & Masks.INT31
          ZIO.logDebug(
            s"Client: WINDOW_UPDATE $increment $streamId"
          ) *> this
            .updateWindow(streamId, increment)
            .catchAll {
              case e @ ErrorRst(streamId, code, name) =>
                ZIO.logError("Client: Reset frane") *> sendFrame(
                  Frames.mkRstStreamFrame(streamId, code)
                )
              case e @ _ => ZIO.fail(e)
            }
        }
        case FrameTypes.GOAWAY => ZIO.unit

        case FrameTypes.RST_STREAM =>
          triggerStreamRst(streamId, flags) *>
            ZIO.logError(
              s"Client: Reset stream (RST_STREAM) - streamId = $streamId"
            )

        case _ => ZIO.unit
      }
    } yield ()
  }

  /** Takes a slice of the specified length from the specified ByteBuffer.
    *
    * @param buf
    *   The ByteBuffer to take the slice from.
    * @param len
    *   The length of the slice to take.
    * @return
    *   A new ByteBuffer containing the specified slice of the input ByteBuffer.
    */
  private def takeSlice(buf: ByteBuffer, len: Int): ByteBuffer = {
    val head = buf.slice.limit(len)
    buf.position(len)
    head
  }

  /** H2_ClientConnect() initiate incoming connections
    */
  def H2_ClientConnect(): Task[Http2Settings] = for {
    _ <- inBoundWorker(ch, timeOutMs).fork // init incoming packet reader
    _ <- ch.write(Constants.getPrefaceBuffer())

    s <- ZIO.succeed(Http2Settings()).tap(s => ZIO.succeed { s.INITIAL_WINDOW_SIZE = INITIAL_WINDOW_SIZE })

    _ <- sendFrame(Frames.makeSettingsFrameClient(ack = false, s))

    win_sz <- inboundWindow.get

    _ <- ZIO.when(INITIAL_WINDOW_SIZE > 65535)(sendFrame(Frames.mkWindowUpdateFrame(0, win_sz.toInt - 65535)))

    _ <- ZIO.when(INITIAL_WINDOW_SIZE > 65535)(
      ZIO.logDebug(s"Client: Send initial WINDOW UPDATE global ${win_sz - 65535} streamId=0")
    )

    _ <- awaitSettings.await

    settings <- settings1.get
    _ <- sendFrame(Frames.makeSettingsAckFrame())

    _ <- headerEncoderRef.set(new HeaderEncoder(settings.HEADER_TABLE_SIZE))
    _ <- headerDecoderRef.set(
      new HeaderDecoder(
        settings.MAX_HEADER_LIST_SIZE,
        settings.HEADER_TABLE_SIZE
      )
    )

  } yield (settings)

  def close() = for {
    _ <- outBoundFiber.interrupt
  } yield ()

  def doDelete(
      path: String,
      stream: ZStream[Any, Throwable, Byte] = EmptyStream,
      headers: Headers = Headers()
  ) = doMethod(DELETE, path, stream, headers)

  def doPut(
      path: String,
      stream: ZStream[Any, Throwable, Byte] = EmptyStream,
      headers: Headers = Headers()
  ) = doMethod(PUT, path, stream, headers)

  def doPost(
      path: String,
      stream: ZStream[Any, Throwable, Byte] = EmptyStream,
      headers: Headers = Headers()
  ) = doMethod(POST, path, stream, headers)

  def doGet(
      path: String,
      stream: ZStream[Any, Throwable, Byte] = EmptyStream,
      headers: Headers = Headers()
  ) = doMethod(GET, path, stream, headers)

  def doMethod(
      method: Method,
      path: String,
      s0: ZStream[Any, Throwable, Byte] = EmptyStream,
      h0: Headers = Headers()
  ): Task[ClientResponse] = {
    val h1 =
      h0 + (":path" -> path) + (":method" -> method.name) + (":scheme" -> uri
        .getScheme()) + (":authority" -> uri
        .getAuthority())

    val endStreamInHeaders = if (s0 == EmptyStream) true else false
    for {
      _ <- awaitSettings.await
      settings <- settings1.get
      headerEncoder <- headerEncoderRef.get
      // HEADERS /////

      stream <- ZIO.scoped {
        hSem.withPermitScoped *> (for {
          streamId <- streamIdRef.getAndUpdate(_ + 2)
          stream <- openStream(streamId, INITIAL_WINDOW_SIZE)
          bb <- ZIO.attempt(headerFrame(streamId, settings, Priority.NoPriority, endStreamInHeaders, headerEncoder, h1))
          _ <- ZIO.foreach(bb)(b => sendFrame(b))

          _ <- ZIO.when(INITIAL_WINDOW_SIZE > 65535 && endStreamInHeaders == false)(
            sendFrame(Frames.mkWindowUpdateFrame(streamId, INITIAL_WINDOW_SIZE - 65535))
          )

          _ <- ZIO.when(INITIAL_WINDOW_SIZE > 65535 && endStreamInHeaders == false)(
            ZIO.logDebug(s"Client: Send initial WINDOW UPDATE ${INITIAL_WINDOW_SIZE - 65535} streamId=$streamId")
          )

        } yield (stream))
      }

      // DATA /////
      pref <- Ref.make[Chunk[Byte]](Chunk.empty[Byte])
      streamId = stream.streamId

      _ <-
        if (endStreamInHeaders == false)
          s0.chunks
            .foreach { chunk =>
              for {
                chunk0 <- pref.get
                _ <- ZIO.when(chunk0.nonEmpty)(
                  ZIO.foreach(
                    dataFrame(streamId, false, ByteBuffer.wrap(chunk0.toArray), settings.MAX_FRAME_SIZE - 128)
                  )(b => sendDataFrame(streamId, b))
                )

                _ <- pref.set(chunk)
              } yield ()
            }
        else ZIO.unit

      lastChunk <- pref.get
      _ <- (ZIO
        .when(endStreamInHeaders == false)(
          ZIO.foreach(dataFrame(streamId, true, ByteBuffer.wrap(lastChunk.toArray), settings.MAX_FRAME_SIZE - 128))(b =>
            sendDataFrame(streamId, b)
          )
        ))
        .unit

      // END OF DATA /////

      _ <- ZIO.logDebug(s"Client: Stream Id: $streamId ${GET.name} $path")
      _ <- ZIO.logTrace(s"Client: Stream Id: $streamId request headers: ${h1.printHeaders(" | ")})")

      // wait for response
      pair <- stream.d.await
      _ <- ZIO.when(pair == null)(ZIO.fail(new java.nio.channels.ClosedChannelException()))
      flags = pair._1
      h = pair._2
      _ <- ZIO.logTrace(
        s"Client: Stream Id: $streamId header received from remote"
      )

      status <- ZIO.attempt(h.get(":status") match {
        case None        => StatusCode.InternalServerError
        case Some(value) => StatusCode(value.toInt)
      })

      data_stream <-
        if ((flags & Flags.END_STREAM) == Flags.END_STREAM) ZIO.attempt(EmptyStream)
        else ZIO.attempt(makeDataStream(this, stream.inDataQ))

      code <- ZIO.attempt(h.get(":status").get)
      _ <- ZIO.logDebug(
        s"Client: Stream Id: $streamId response $code  ${GET.name} $path"
      )

      _ <- ZIO.logTrace(s"Client: Stream Id: $streamId response headers: ${h.printHeaders(" | ")})")

    } yield (ClientResponse(status, h, data_stream))
  }

  /** Sends an HTTP/2 frame by offering it to the outbound queue.
    *
    * @param b
    *   The HTTP/2 packet to send.
    */
  def sendFrame(b: ByteBuffer) = outq.offer(b)

  /** Generate stream header frames from the provided header sequence
    *
    * If the compressed representation of the headers exceeds the MAX_FRAME_SIZE setting of the peer, it will be broken
    * into a HEADERS frame and a series of CONTINUATION frames.
    */
  //////////////////////////////
  private[this] def headerFrame( // TODOD pbly not working for multi-packs
      streamId: Int,
      settings: Http2Settings,
      priority: Priority,
      endStream: Boolean,
      headerEncoder: HeaderEncoder,
      headers: Headers
  ): Seq[ByteBuffer] = {
    val rawHeaders = headerEncoder.encodeHeaders(headers)

    val limit = settings.MAX_FRAME_SIZE - 61

    val headersPrioritySize =
      if (priority.isDefined) 5 else 0 // priority(4) + weight(1), padding = 0

    if (rawHeaders.remaining() + headersPrioritySize <= limit) {
      val acc = new ArrayBuffer[ByteBuffer]
      acc.addOne(
        Frames.mkHeaderFrame(
          streamId,
          priority,
          endHeaders = true,
          endStream,
          padding = 0,
          rawHeaders
        )
      )

      acc.toSeq
    } else {
      // need to fragment
      val acc = new ArrayBuffer[ByteBuffer]

      val headersBuf = takeSlice(rawHeaders, limit - headersPrioritySize)
      acc += Frames.mkHeaderFrame(
        streamId,
        priority,
        endHeaders = false,
        endStream,
        padding = 0,
        headersBuf
      )

      while (rawHeaders.hasRemaining) {
        val size = math.min(limit, rawHeaders.remaining)
        val continueBuf = takeSlice(rawHeaders, size)
        val endHeaders = !rawHeaders.hasRemaining
        acc += Frames.mkContinuationFrame(streamId, endHeaders, continueBuf)
      }
      acc.toSeq
    }
  }

  private def parseFrame(bb: ByteBuffer) = {
    val sbb = bb.slice();

    val len = Frames.getLengthField(sbb)
    val frameType = sbb.get()
    val flags = sbb.get()
    val streamId = Frames.getStreamId(sbb)

    (len, frameType, flags, streamId)
  }

  private[this] def dataEvalEffectProducer(
      c: Http2ClientConnection,
      q: Queue[ByteBuffer]
  ): Task[ByteBuffer] = {
    for {
      bb <- q.take
      _ <- ZIO.when(bb == null)(ZIO.fail(java.nio.channels.ClosedChannelException()))
      tp <- ZIO.attempt(parseFrame(bb))
      streamId = tp._4
      len = tp._1

      o_stream <- ZIO.attempt(c.streamTbl.get(streamId))
      _ <- ZIO.when(o_stream.isEmpty)(
        ZIO.fail(
          ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")
        )
      )
      stream <- ZIO.attempt(o_stream.get)

      ////////////////////////////////////////////////
      // global counter with Window Update.
      // now we track both global and local during request.stream processing
      // globalBytesOfPendingInboundData shared reference betweem streams
      //////////////////////////////////////////////
      _ <- c.decrementGlobalPendingInboundData(len)
      bytes_pending <- c.globalBytesOfPendingInboundData.get
      bytes_available <- c.inboundWindow.get
      globalWinIncrement <- ZIO.succeed {
        if (INITIAL_WINDOW_SIZE > bytes_pending) INITIAL_WINDOW_SIZE - bytes_pending
        else INITIAL_WINDOW_SIZE
      }
      _ <- ZIO.when(globalWinIncrement > INITIAL_WINDOW_SIZE * 0.7 && bytes_available < INITIAL_WINDOW_SIZE * 0.3)(
        c.sendFrame(Frames.mkWindowUpdateFrame(0, globalWinIncrement))
      )

      _ <- ZIO.when(globalWinIncrement > INITIAL_WINDOW_SIZE * 0.7 && bytes_available < INITIAL_WINDOW_SIZE * 0.3)(
        this.inboundWindow.update(_ + globalWinIncrement)
      )

      _ <- ZIO.when(globalWinIncrement > INITIAL_WINDOW_SIZE * 0.7 && bytes_available < INITIAL_WINDOW_SIZE * 0.3)(
        ZIO.logDebug(s"Client: Send WINDOW UPDATE global $globalWinIncrement $bytes_available")
      )
      //////////////////////////////////////////////
      // local counter
      _ <- c.updateStreamWith(
        0,
        streamId,
        c => c.bytesOfPendingInboundData.update(_ - len)
      )

      bytes_available_per_stream <- stream.inboundWindow.get

      bytes_pending_per_stream <- c.globalBytesOfPendingInboundData.get
      streamWinIncrement <- ZIO.attempt {
        if (INITIAL_WINDOW_SIZE > bytes_pending_per_stream) INITIAL_WINDOW_SIZE - bytes_pending_per_stream
        else INITIAL_WINDOW_SIZE
      }
      _ <-
        if (streamWinIncrement > INITIAL_WINDOW_SIZE * 0.7 && bytes_available_per_stream < INITIAL_WINDOW_SIZE * 0.3) {
          ZIO.logDebug(
            s"Client: Send WINDOW UPDATE local on processing incoming data=$streamWinIncrement localWin=$bytes_available_per_stream"
          ) *> c
            .sendFrame(
              Frames.mkWindowUpdateFrame(
                streamId,
                if (streamWinIncrement > 0) streamWinIncrement else INITIAL_WINDOW_SIZE
              )
            ) *>
            stream.inboundWindow.update(_ + streamWinIncrement)
        } else {
          ZIO.logTrace(
            "Client: >>>>>>>>>> still processing incoming data, pause remote, pending data = " + bytes_pending_per_stream
          ) *> ZIO.unit
        }

    } yield (bb)
  }

  private[this] def makeDataStream(
      c: Http2ClientConnection,
      q: Queue[ByteBuffer]
  ): ZStream[Any, Throwable, Byte] = {
    val dataStream0 = ZStream.repeatZIO(dataEvalEffectProducer(c, q)).takeUntil { buffer =>
      val len = Frames.getLengthField(buffer)
      val frameType = buffer.get()
      val flags = buffer.get()
      val _ = Frames.getStreamId(buffer)

      val padLen: Byte = if ((flags & Flags.PADDED) != 0) buffer.get() else 0 // advance one byte padding len 1

      val lim = buffer.limit() - padLen
      buffer.limit(lim)
      val continue: Boolean = ((flags & Flags.END_STREAM) == 0)
      !continue
    }
    dataStream0.flatMap(b => ZStream.fromChunk(Chunk.fromByteBuffer(b)))
  }

  private[this] def accumData(
      streamId: Int,
      bb: ByteBuffer,
      dataSize: Int
  ): Task[Unit] = {
    for {
      o_c <- ZIO.attempt(this.streamTbl.get(streamId))
      _ <- ZIO.when(o_c.isEmpty)(ZIO.fail(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")))
      c <- ZIO.attempt(o_c.get)
      _ <- this.inboundWindow.update(_ - dataSize) *>
        this.incrementGlobalPendingInboundData(dataSize) *>
        c.inboundWindow.update(_ - dataSize) *>
        c.bytesOfPendingInboundData.update(_ + dataSize)

      _ <- c.inDataQ.offer(bb)

    } yield ()

  }

  case class txWindow_SplitDataFrame(buffer: ByteBuffer, dataLen: Int)

  private[this] def splitDataFrames(
      bb: ByteBuffer,
      requiredLen: Long
  ): (txWindow_SplitDataFrame, Option[txWindow_SplitDataFrame]) = {
    val original_bb = bb.slice()
    val len = Frames.getLengthField(bb)
    val frameType = bb.get()
    val flags = bb.get()
    val streamId = Frames.getStreamId(bb)

    if (requiredLen < len) {
      val buf0 = Array.ofDim[Byte](requiredLen.toInt)
      bb.get(buf0)

      val dataFrame1 =
        Frames.mkDataFrame(streamId, false, padding = 0, ByteBuffer.wrap(buf0))
      val dataFrame2 = Frames.mkDataFrame(streamId, false, padding = 0, bb)

      (
        txWindow_SplitDataFrame(dataFrame1, requiredLen.toInt),
        Some(txWindow_SplitDataFrame(dataFrame2, len - requiredLen.toInt))
      )

    } else (txWindow_SplitDataFrame(original_bb, len), None)
  }

  /*
    When the value of SETTINGS_INITIAL_WINDOW_SIZE changes, a receiver MUST adjust
    the size of all stream flow-control windows that it maintains by the
    difference between the new value and the old value.
   */
  private[this] def updateInitiallWindowSize(stream: Http2ClientStream, currentWinSize: Int, newWinSize: Int) = {
    ZIO.logInfo(s"Http2Connection.upddateInitialWindowSize( $currentWinSize, $newWinSize)") *>
      stream.transmitWindow.update(txBytesLeft => newWinSize - (currentWinSize - txBytesLeft)) *> stream.outXFlowSync
        .offer(())
  }

  private[this] def upddateInitialWindowSizeAllStreams(currentSize: Int, newSize: Int) = {
    ZIO.logTrace(s"Http2Connection.upddateInitialWindowSizeAllStreams($currentSize, $newSize)") *>
      ZIO.foreach(streamTbl.values.toSeq)(stream => updateInitiallWindowSize(stream, currentSize, newSize))
  }

  private[this] def txWindow_Transmit(
      stream: Http2ClientStream,
      bb: ByteBuffer,
      data_len: Int
  ): Task[Long] = {
    for {
      tx_g <- transmitWindow.get
      tx_l <- stream.transmitWindow.get
      bytesCredit <- ZIO.succeed(Math.min(tx_g, tx_l))

      _ <-
        if (bytesCredit > 0)
          (for {
            rlen <- ZIO.succeed(Math.min(bytesCredit, data_len))
            frames <- ZIO.attempt(splitDataFrames(bb, rlen))

            _ <- sendFrame(frames._1.buffer)

            _ <- transmitWindow.update(_ - rlen)
            _ <- stream.transmitWindow.update(_ - rlen)

            _ <- frames._2 match {
              case Some(f0) =>
                stream.outXFlowSync.take *> txWindow_Transmit(
                  stream,
                  f0.buffer,
                  f0.dataLen
                )
              case None => ZIO.unit
            }

          } yield ())
        else stream.outXFlowSync.take *> txWindow_Transmit(stream, bb, data_len)

    } yield (bytesCredit)
  }

  /** Generate stream data frame(s) for the specified data
    *
    * If the data exceeds the peers MAX_FRAME_SIZE setting, it is fragmented into a series of frames.
    */
  def dataFrame(
      streamId: Int,
      endStream: Boolean,
      data: ByteBuffer,
      frameSize: Int
  ): scala.collection.immutable.Seq[ByteBuffer] = {
    val limit = frameSize
    // settings.MAX_FRAME_SIZE - 128

    if (data.remaining <= limit) {

      val acc = new ArrayBuffer[ByteBuffer]
      acc.addOne(Frames.mkDataFrame(streamId, endStream, padding = 0, data))

      acc.toSeq
    } else { // need to fragment
      val acc = new ArrayBuffer[ByteBuffer]
      while (data.hasRemaining) {
        val len = math.min(data.remaining(), limit)
        val cur_pos = data.position()
        val thisData = data.slice.limit(len)
        data.position(cur_pos + len)
        val eos = endStream && !data.hasRemaining
        acc.addOne(Frames.mkDataFrame(streamId, eos, padding = 0, thisData))
      }
      acc.toSeq
    }
  }

  private[this] def sendDataFrame(streamId: Int, bb: ByteBuffer): Task[Unit] =
    for {
      t <- ZIO.attempt(parseFrame(bb))
      len = t._1
      _ <- ZIO.logTrace(s"Client: sendDataFrame() - $len bytes")
      opt_D <- ZIO.attempt(streamTbl.get(streamId))
      _ <- opt_D match {
        case Some(ce) =>
          for {
            _ <- txWindow_Transmit(ce, bb, len)
          } yield ()
        case None => ZIO.logError("Client: sendDataFrame lost streamId")
      }
    } yield ()

  private[this] def updateAndCheckGlobalTx(streamId: Int, inc: Int) = {
    for {
      _ <- transmitWindow.update(_ + inc)

      rs <- transmitWindow.get
      // murky issue when cloudfromt servers sends 2147483648 and max int 2147483647
      // h2spect requires dynamic realoc for INITIAL_WINDOW_SIZE ( I will double check on that one)
      // and peps on stackoverflow say that only UPDATE_WINDOW can change that and INITIAL_WINDOW_SIZE is simply ignored !!??
      // cloudfront sends 65536 instead of 65535 !
      // transmit Windows here made as Long, so we have a luxury not to worry much for now
      // commented out temporary?
      /*
      _ <- IO
        .raiseError(
          ErrorGen(
            streamId,
            Error.FLOW_CONTROL_ERROR,
            "Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1"
          )
        )
        .whenA(rs > Integer.MAX_VALUE)*/
    } yield ()
  }

  private[this] def updateWindow(streamId: Int, inc: Int): Task[Unit] = {
    // IO.println( "Update Window()") >>
    ZIO.when(inc == 0)(
      ZIO.fail(
        ErrorGen(
          streamId,
          Error.PROTOCOL_ERROR,
          "Sends a WINDOW_UPDATE frame with a flow control window increment of 0"
        )
      )
    ) *> (if (streamId == 0)
            updateAndCheckGlobalTx(streamId, inc) *>
              ZIO
                .foreach(streamTbl.values.toSeq)(stream =>
                  for {
                    _ <- stream.transmitWindow.update(_ + inc)
                    rs <- stream.transmitWindow.get

                    _ <- ZIO.when(rs >= Integer.MAX_VALUE)(
                      ZIO.fail(
                        ErrorGen(
                          streamId,
                          Error.FLOW_CONTROL_ERROR,
                          "Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1"
                        )
                      )
                    )
                    _ <- stream.outXFlowSync.offer(())
                  } yield ()
                )
                .unit
          else
            updateStreamWith(
              1,
              streamId,
              stream =>
                for {
                  _ <- stream.transmitWindow.update(_ + inc)
                  rs <- stream.transmitWindow.get
                  _ <- ZIO.when(rs >= Integer.MAX_VALUE)(
                    ZIO.fail(
                      ErrorRst(
                        streamId,
                        Error.FLOW_CONTROL_ERROR,
                        ""
                      )
                    )
                  )

                  _ <- stream.outXFlowSync.offer(())
                } yield ()
            ))
  }

}
