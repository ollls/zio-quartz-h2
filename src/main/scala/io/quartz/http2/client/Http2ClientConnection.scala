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
import io.quartz.http2.routes.Routes._
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

  def bodyAsText = body.map(new String(_))
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
  } yield ()).catchAll(e => ZIO.logDebug(("Client: outBoundWorker - " + e.toString())))

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
      hSem2 <- Semaphore.make(permits = 1)
      awaitSettings <- Promise.make[Throwable, Boolean]
      settings0 <- Ref.make(
        new Http2Settings()
      ) // will be loaded with server data when awaitSettings is completed
      inboundWindow <- Ref.make[Long](incomingWindowSize)
      globalBytesOfPendingInboundData <- Ref.make(0L)
      globalTransmitWindow <- Ref.make[Long](
        65535L
      ) // set in upddateInitialWindowSizeAllStreams
    } yield (
      new Http2ClientConnection(
        ch,
        timeOutMs,
        uri,
        refsId,
        refEncoder,
        refDecoder,
        outq,
        f0,
        hSem,
        hSem2,
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
    hSem2: Semaphore,
    awaitSettings: Promise[Throwable, Boolean],
    settings1: Ref[Http2Settings],
    globalBytesOfPendingInboundData: Ref[Long],
    inboundWindow: Ref[Long],
    transmitWindow: Ref[Long],
    INITIAL_WINDOW_SIZE: Int
) extends Http2ConnectionCommon(
      INITIAL_WINDOW_SIZE,
      globalBytesOfPendingInboundData,
      inboundWindow,
      transmitWindow,
      outq,
      hSem2
    ) {


  class Http2ClientStream(
      val streamId: Int,
      val d: Promise[Throwable, (Byte, Headers)],
      val header: ArrayBuffer[ByteBuffer],
      val inDataQ: Queue[ByteBuffer],
      inboundWindow: Ref[Long],
      transmitWindow: Ref[Long],
      bytesOfPendingInboundData: Ref[Long],
      outXFlowSync: Queue[Boolean]
  ) extends Http2StreamCommon(bytesOfPendingInboundData, inboundWindow, transmitWindow, outXFlowSync)

  val streamTbl =
    new java.util.concurrent.ConcurrentHashMap[Int, Http2ClientStream](100).asScala

  def getStream(id: Int): Option[Http2StreamCommon] = streamTbl.get(id)

  private def openStream(streamId: Int, in_win: Int) = (for {
    d <- Promise.make[Throwable, (Byte, Headers)]
    inboundWindow <- Ref.make[Long](in_win)
    header <- ZIO.succeed(ArrayBuffer.empty[ByteBuffer])
    inDataQ <- Queue.unbounded[ByteBuffer]
    pendingInBytes <- Ref.make(0L)
    transmitWindow <- Ref.make[Long](
      65535L
    ) // set in upddateInitialWindowSizeAllStreams
    xFlowSync <- Queue.unbounded[Boolean]
  } yield (new Http2ClientStream(
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
    s.via(Http2Connection.packetStreamPipe)
  }

  def settings = settings1.get

  def inBoundWorker(ch: IOChannel, timeOutMs: Int) =
    makePacketStream(ch, timeOutMs, Chunk.empty[Byte])
      .foreach(p => packet_handler(p))
      .catchAll(e => {
        ZIO.logDebug("Client: inBoundWorker - " + e.toString()) *> dropStreams()
      })

  def dropStreams() = for {
    _ <- awaitSettings.complete(ZIO.succeed(true))
    streams <- ZIO.attempt(this.streamTbl.values.toList)
    _ <- ZIO.foreach(streams)(_.d.complete(ZIO.attempt(null)))
    _ <- ZIO.foreach(streams)(_.inDataQ.offer(ByteBuffer.allocate(0)))
    _ <- ZIO.foreach(streams)(s1 => s1.outXFlowSync.offer(false) *> s1.outXFlowSync.offer(false))
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

  /** H2_ClientConnect() initiate incoming connections
    */
  def H2_ClientConnect(): Task[Http2Settings] = for {
    _ <- inBoundWorker(ch, timeOutMs).fork // init incoming packet reader
    _ <- ch.write(Constants.getPrefaceBuffer())

    s <- ZIO.succeed( new Http2Settings()).tap(s => ZIO.succeed { s.INITIAL_WINDOW_SIZE = INITIAL_WINDOW_SIZE })

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
    _ <- ch.close()
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
      sts <- settings1.get
      streamId = stream.streamId

      _ <-
        if (endStreamInHeaders == false)
          s0.chunks
            .foreach { chunk =>
              for {
                chunk0 <- pref.get
                _ <- ZIO.when(chunk0.nonEmpty)(
                  ZIO.foreach(
                    dataFrame(sts, streamId, false, ByteBuffer.wrap(chunk0.toArray))
                  )(b => sendDataFrame(streamId, b))
                )

                _ <- pref.set(chunk)
              } yield ()
            }
            .catchSome { case _: java.lang.InterruptedException => ZIO.unit }
        else ZIO.unit

      lastChunk <- pref.get
      _ <- (ZIO
        .when(endStreamInHeaders == false)(
          ZIO.foreach(dataFrame(sts, streamId, true, ByteBuffer.wrap(lastChunk.toArray)))(b =>
            sendDataFrame(streamId, b)
          )
        ))
        .catchSome { case _: java.lang.InterruptedException => ZIO.unit }
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
        else ZIO.attempt( Http2Connection.makeDataStream(this, stream.inDataQ))

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

  private def parseFrame(bb: ByteBuffer) = {
    val sbb = bb.slice();

    val len = Frames.getLengthField(sbb)
    val frameType = sbb.get()
    val flags = sbb.get()
    val streamId = Frames.getStreamId(sbb)

    (len, frameType, flags, streamId)
  }

  private[this] def accumData(streamId: Int, bb: ByteBuffer, dataSize: Int): Task[Unit] = {
    for {
      o_c <- ZIO.attempt(this.streamTbl.get(streamId))
      _ <- ZIO.when(o_c.isEmpty)(ZIO.fail(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")))
      c <- ZIO.attempt(o_c.get)
      _ <- this.incrementGlobalPendingInboundData(dataSize)
      _ <- c.bytesOfPendingInboundData.update(_ + dataSize)
      _ <- c.inDataQ.offer(bb)
    } yield ()
  }

  /*
    When the value of SETTINGS_INITIAL_WINDOW_SIZE changes, a receiver MUST adjust
    the size of all stream flow-control windows that it maintains by the
    difference between the new value and the old value.
   */
  private[this] def updateInitiallWindowSize(stream: Http2ClientStream, currentWinSize: Int, newWinSize: Int) = {
    ZIO.logInfo(s"Http2Connection.upddateInitialWindowSize( $currentWinSize, $newWinSize)") *>
      stream.transmitWindow.update(txBytesLeft => newWinSize - (currentWinSize - txBytesLeft)) *> stream.outXFlowSync
        .offer(true)
  }

  private[this] def upddateInitialWindowSizeAllStreams(currentSize: Int, newSize: Int) = {
    ZIO.logTrace(s"Http2Connection.upddateInitialWindowSizeAllStreams($currentSize, $newSize)") *>
      ZIO.foreach(streamTbl.values.toSeq)(stream => updateInitiallWindowSize(stream, currentSize, newSize))
  }

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
                    _ <- stream.outXFlowSync.offer(true)
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

                  _ <- stream.outXFlowSync.offer(true)
                } yield ()
            ))
  }

}
