package io.quartz.http2

import scala.collection.mutable.ArrayBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.ByteBuffer
import io.quartz.http2.Constants._
import io.quartz.http2.model.{Request, Response, Headers, ContentType, StatusCode}
import io.quartz.netio._
import scala.collection.mutable
import concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.MapHasAsScala
import io.quartz.http2.routes.Routes._
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

import zio.{ZIO, Task, Promise, Queue, Chunk, Ref, Semaphore}
import zio.stream.{ZStream, ZChannel, ZPipeline}
import io.quartz.http2.routes._

object Http2Connection {
  private[this] def outBoundWorkerProc(
      ch: IOChannel,
      outq: Queue[ByteBuffer],
      shutdownPromise: Promise[Throwable, Boolean]
  ): Task[Boolean] = {
    (for {
      bb <- outq.take
      // empty array is a termination signal
      _ <- ZIO.when(bb.array().length == 0)(ZIO.fail(new Exception("Shutdown outbound H2 packet sender")))
      _ <- ch.write(bb)
    } yield (true)).catchAll(e =>
      ZIO.logDebug("outBoundWorkerProc fiber: " + e.toString()) *> outq.shutdown *> shutdownPromise.succeed(true) *> ZIO
        .succeed(false)
    )
  }

  def make[Env](
      ch: IOChannel,
      id: Long,
      maxStreams: Int,
      keepAliveMs: Int,
      httpRoute: HttpRoute[Env],
      in_winSize: Int,
      http11request: Option[Request]
  ): ZIO[Env, Throwable, Http2Connection[Env]] = {
    for {
      _ <- ZIO.logDebug("Http2Connection.make()")
      shutdownPromise <- Promise.make[Throwable, Boolean]

      // 1024 for performance
      outq <- Queue.bounded[ByteBuffer](1024)

      http11Req_ref <- Ref.make[Option[Request]](http11request)
      hSem <- Semaphore.make(permits = 1)
      hSem2 <- Semaphore.make(permits = 1)

      globalTransmitWindow <- Ref.make[Long](65535)
      globalInboundWindow <- Ref.make[Long](65535)

      globalBytesOfPendingInboundData <- Ref.make(0L)

      runMe2 = outBoundWorkerProc(ch, outq, shutdownPromise)
        .repeatUntil(_ == false)
        .fork

      _ <- runMe2

      c <- ZIO.attempt(
        new Http2Connection(
          ch,
          id,
          httpRoute,
          http11Req_ref,
          outq,
          globalTransmitWindow,
          globalBytesOfPendingInboundData,
          globalInboundWindow,
          shutdownPromise,
          hSem,
          hSem2,
          maxStreams,
          keepAliveMs,
          in_winSize
        )
      )

    } yield c
  }

  private[this] def windowsUpdate[Env](
      c: Http2ConnectionCommon,
      streamId: Int,
      received: Ref[Long],
      window: Ref[Long],
      len: Int
  ) =
    for {
      bytes_received <- received.getAndUpdate(_ - len)
      bytes_available <- window.getAndUpdate(_ - len)
      send_update <- ZIO.succeed(
        bytes_received < c.INITIAL_WINDOW_SIZE * 0.7 && bytes_available < c.INITIAL_WINDOW_SIZE * 0.3
      )
      upd = c.INITIAL_WINDOW_SIZE - bytes_available.toInt
      _ <- ZIO.when(send_update)(
        c.sendFrame(Frames.mkWindowUpdateFrame(streamId, upd)) *> window.update(_ + upd) *> ZIO.logDebug(
          s"Send UPDATE_WINDOW $upd streamId= $streamId"
        )
      )
    } yield (send_update)

  def parseFrame(bb: ByteBuffer) = {
    val sbb = bb.slice();

    val len = Frames.getLengthField(sbb)
    val frameType = sbb.get()
    val flags = sbb.get()
    val streamId = Frames.getStreamId(sbb)

    (len, frameType, flags, streamId)
  }

  def dataEvalEffectProducer[Env](
      c: Http2ConnectionCommon,
      q: Queue[ByteBuffer]
  ): Task[ByteBuffer] = {
    for {
      bb <- q.take
      _ <- ZIO.when(bb == null || bb.remaining() == 0)(ZIO.fail(new java.nio.channels.ClosedChannelException()))
      tp <- ZIO.attempt(parseFrame(bb))
      streamId = tp._4
      len = tp._1
      o_stream <- ZIO.attempt(c.getStream((streamId)))
      _ <- ZIO.when(o_stream.isEmpty)(ZIO.fail(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")))
      stream <- ZIO.attempt(o_stream.get)
      //////////////////////////////////////////////////////////////////////////////////////////////////////////
      _ <- windowsUpdate[Env](c, 0, c.globalBytesOfPendingInboundData, c.globalInboundWindow, len)
      _ <- windowsUpdate[Env](c, streamId, stream.bytesOfPendingInboundData, stream.inboundWindow, len)
    } yield (bb)
  }

  def makeDataStream[Env](
      c: Http2ConnectionCommon,
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

  def packetStreamPipe: ZPipeline[Any, Exception, Byte, Chunk[Byte]] = {
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

  def makePacketStream(
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
}

class Http2StreamCommon(
    val bytesOfPendingInboundData: Ref[Long],
    val inboundWindow: Ref[Long],
    val transmitWindow: Ref[Long],
    val outXFlowSync: Queue[Boolean]
)

class Http2Stream(
    active: Ref[Boolean],
    val d: Promise[Throwable, Headers],
    val header: ArrayBuffer[ByteBuffer],
    val trailing_header: ArrayBuffer[ByteBuffer],
    val inDataQ: Queue[ByteBuffer], // accumulate data packets in stream function
    outXFlowSync: Queue[Boolean], // flow control sync queue for data frames
    transmitWindow: Ref[Long],
    syncUpdateWindowQ: Queue[Unit],
    bytesOfPendingInboundData: Ref[Long],
    inboundWindow: Ref[Long],
    val contentLenFromHeader: Promise[Throwable, Option[Int]],
    val trailingHeader: Promise[Throwable, Headers],
    val done: Promise[Throwable, Unit]
) extends Http2StreamCommon(bytesOfPendingInboundData, inboundWindow, transmitWindow, outXFlowSync) {
  var endFlag = false // half-closed if true
  var endHeadersFlag = false
  var contentLenFromDataFrames = 0

}

class Http2Connection[Env](
    ch: IOChannel,
    val id: Long,
    httpRoute: HttpRoute[Env],
    httpReq11: Ref[Option[Request]],
    outq: Queue[ByteBuffer],
    globalTransmitWindow: Ref[Long],
    globalBytesOfPendingInboundData: Ref[Long],
    globalInboundWindow: Ref[Long],
    shutdownD: Promise[Throwable, Boolean],
    hSem: Semaphore,
    hSem2: Semaphore,
    MAX_CONCURRENT_STREAMS: Int,
    HTTP2_KEEP_ALIVE_MS: Int,
    INITIAL_WINDOW_SIZE: Int
) extends Http2ConnectionCommon(
      INITIAL_WINDOW_SIZE,
      globalBytesOfPendingInboundData,
      globalInboundWindow,
      globalTransmitWindow,
      outq,
      hSem2
    ) {

  val settings: Http2Settings = new Http2Settings()
  val settings_client = new Http2Settings()
  var settings_done = false

  var concurrentStreams = new AtomicInteger(0)

  val streamTbl = new java.util.concurrent.ConcurrentHashMap[Int, Http2Stream](100).asScala
  def getStream(id: Int): Option[Http2StreamCommon] = streamTbl.get(id)

  var start = true
  // streamID of the header which is currently fetching from remote, any other header will trigger GoAway
  var headerStreamId = 0
  var lastStreamId = 0

  val headerEncoder = new HeaderEncoder(settings.HEADER_TABLE_SIZE)
  val headerDecoder = new HeaderDecoder(settings.MAX_HEADER_LIST_SIZE, settings.HEADER_TABLE_SIZE)

  // var statRefresh = 0

  private[this] def decrementGlobalPendingInboundData(decrement: Int) =
    globalBytesOfPendingInboundData.update(_ - decrement)
  private[this] def incrementGlobalPendingInboundData(increment: Int) =
    globalBytesOfPendingInboundData.update(_ + increment)

  def shutdown: Task[Unit] = for {
    qShutdown <- outq.isShutdown
    _ <- ZIO.when(qShutdown == false)(outq.offer(ByteBuffer.allocate(0)))
    _ <- shutdownD.await.unit
    _ <- ZIO.logDebug("Http2Connection.shutdown")
  } yield ()

  /*
    When the value of SETTINGS_INITIAL_WINDOW_SIZE changes, a receiver MUST adjust
    the size of all stream flow-control windows that it maintains by the
    difference between the new value and the old value.
   */
  private[this] def updateInitiallWindowSize(stream: Http2Stream, currentWinSize: Int, newWinSize: Int) = {
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
      _ <- globalTransmitWindow.update(_ + inc)
      rs <- globalTransmitWindow.get
      _ <- ZIO.when(rs >= Integer.MAX_VALUE)(
        ZIO.fail(
          ErrorGen(
            streamId,
            Error.FLOW_CONTROL_ERROR,
            "Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1"
          )
        )
      )

    } yield ()
  }

  private[this] def updateAndCheck(streamId: Int, stream: Http2Stream, inc: Int) = {
    for {
      _ <- stream.transmitWindow.update(_ + inc)
      rs <- stream.transmitWindow.get
      _ <- ZIO
        .fail(
          ErrorRst(
            streamId,
            Error.FLOW_CONTROL_ERROR,
            "Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1"
          )
        )
        .when(rs >= Integer.MAX_VALUE)
      _ <- stream.outXFlowSync.offer(true)
    } yield ()

  }

  private[this] def updateWindowStream(streamId: Int, inc: Int) = {
    streamTbl.get(streamId) match {
      case None         => ZIO.logDebug(s"Update window, streamId=$streamId invalid or closed already")
      case Some(stream) => updateAndCheck(streamId, stream, inc)
    }
  }

  private[this] def updateWindow(streamId: Int, inc: Int): Task[Unit] = {
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
                .foreach(streamTbl.values.toSeq)(stream => updateAndCheck(streamId, stream, inc))
                .unit
          else updateWindowStream(streamId, inc))
  }

  private[this] def handleStreamErrors(streamId: Int, e: Throwable): Task[Unit] = {
    e match {
      case e @ ErrorGen(streamId, code, name) =>
        ZIO.logError(s"handleStreamErrors: streamID = $streamId ${e.name}") *>
          ch.write(Frames.mkGoAwayFrame(streamId, code, name.getBytes)).unit *> this.ch.close()
      case _ => ZIO.logError(s"handleStreamErrors: " + e.toString) *> ZIO.fail(e)
    }
  }

  private[this] def interceptContentLen(c: Http2Stream, hdr: Headers) = {
    hdr.get("content-length") match {
      case Some(cl) =>
        c.contentLenFromHeader
          .succeed(try { Some(cl.toInt) }
          catch { case e: java.lang.NumberFormatException => None })
          .unit

      case None => c.contentLenFromHeader.succeed(None).unit
    }
  }

  private[this] def openStream11(streamId: Int, request: Request): ZIO[Env, Throwable, Unit] = {
    for {
      nS <- ZIO.attempt(concurrentStreams.get)
      _ <- ZIO.logInfo(s"Open upgraded http/1.1 stream: $streamId  total = ${streamTbl.size} active = ${nS}")

      d <- Promise.make[Throwable, Headers] // start stream, after done with headers and continuations
      done <- Promise.make[Throwable, Unit]
      trailingHdr <- Promise.make[Throwable, Headers] // safe access to trailing header, only when they are fully ready

      contentLenFromHeader <- Promise.make[Throwable, Option[Int]]

      header <- ZIO.succeed(ArrayBuffer.empty[ByteBuffer])
      trailing_header <- ZIO.succeed(ArrayBuffer.empty[ByteBuffer])

      dataOut <- Queue.bounded[ByteBuffer](1) // up to MAX_CONCURRENT_STREAMS users
      xFlowSync <- Queue.unbounded[Boolean]
      dataIn <- Queue.unbounded[ByteBuffer]
      transmitWindow <- Ref.make[Long](settings_client.INITIAL_WINDOW_SIZE)

      localInboundWindowSize <- Ref.make[Long](INITIAL_WINDOW_SIZE)
      _ <-
        if (INITIAL_WINDOW_SIZE > 65535L) sendFrame(Frames.mkWindowUpdateFrame(streamId, INITIAL_WINDOW_SIZE - 65535))
        else ZIO.unit
      _ <- ZIO.when(INITIAL_WINDOW_SIZE > 65535L)(
        ZIO.logDebug(s"Send UPDATE WINDOW, streamId = $streamId: ${INITIAL_WINDOW_SIZE - 65535}")
      )

      updSyncQ <- Queue.dropping[Unit](1)
      pendingInBytes <- Ref.make(0L)
      active <- Ref.make(true)

      c <- ZIO.attempt(
        new Http2Stream(
          active,
          d,
          header,
          trailing_header,
          inDataQ = dataIn,
          outXFlowSync = xFlowSync,
          transmitWindow,
          updSyncQ,
          pendingInBytes,
          inboundWindow = localInboundWindowSize,
          contentLenFromHeader,
          trailingHdr,
          done
        )
      )

      _ <- ZIO.attempt(this.streamTbl.put(streamId, c))

      streamFork = route2(streamId, request)
      _ <- (streamFork.catchAll(e => handleStreamErrors(streamId, e))).fork

    } yield ()

  }

  private[this] def openStream(streamId: Int, flags: Int): ZIO[Env, Throwable, Unit] =
    for {
      nS <- ZIO.attempt(concurrentStreams.get)
      _ <- ZIO.logDebug(s"Open stream: $streamId  total = ${streamTbl.size} active = ${nS}")

      d <- Promise.make[Throwable, Headers] // start stream, after done with headers and continuations
      done <- Promise.make[Throwable, Unit]
      trailingHdr <- Promise.make[Throwable, Headers] // safe access to trailing header, only when they are fully ready

      contentLenFromHeader <- Promise.make[Throwable, Option[Int]]

      header <- ZIO.succeed(ArrayBuffer.empty[ByteBuffer])
      trailing_header <- ZIO.succeed(ArrayBuffer.empty[ByteBuffer])

      dataOut <- Queue.bounded[ByteBuffer](1) // up to MAX_CONCURRENT_STREAMS users
      xFlowSync <- Queue.unbounded[Boolean]
      dataIn <- Queue.unbounded[ByteBuffer]
      transmitWindow <- Ref.make[Long](settings_client.INITIAL_WINDOW_SIZE)

      localInboundWindowSize <- Ref.make[Long](INITIAL_WINDOW_SIZE)
      _ <-
        if (INITIAL_WINDOW_SIZE > 65535L) sendFrame(Frames.mkWindowUpdateFrame(streamId, INITIAL_WINDOW_SIZE - 65535))
        else ZIO.unit
      _ <- ZIO.when(INITIAL_WINDOW_SIZE > 65535L)(
        ZIO.logDebug(s"Send UPDATE WINDOW, streamId = $streamId: ${INITIAL_WINDOW_SIZE - 65535}")
      )

      updSyncQ <- Queue.dropping[Unit](1)
      pendingInBytes <- Ref.make(0L)

      active <- Ref.make(true)

      c <- ZIO.attempt(
        new Http2Stream(
          active,
          d,
          header,
          trailing_header,
          inDataQ = dataIn,
          outXFlowSync = xFlowSync,
          transmitWindow,
          updSyncQ,
          pendingInBytes,
          inboundWindow = localInboundWindowSize,
          contentLenFromHeader,
          trailingHdr,
          done
        )
      )

      _ <- ZIO.attempt(concurrentStreams.incrementAndGet())
      _ <- ZIO.attempt(this.streamTbl.put(streamId, c))

      streamFork = for {
        h <- d.await
        _ <- interceptContentLen(c, h)
        r <- ZIO.attempt(
          Request(
            id,
            streamId,
            h,
            if ((flags & Flags.END_STREAM) == Flags.END_STREAM) {
              ZStream.empty
            } else Http2Connection.makeDataStream(this, dataIn),
            ch.secure(),
            ch.sniServerNames(),
            trailingHdr
          )
        )
        _ <- route2(streamId, r)

      } yield ()
      _ <- streamFork.catchAll(e => handleStreamErrors(streamId, e)).fork
    } yield ()

  private[this] def checkForTrailingHeaders(streamId: Int, flags: Int): Task[Boolean] = {
    for {
      o <- ZIO.attempt(streamTbl.get(streamId)) // if already in the table, we process trailing headers.
      trailing <- o match {
        case Some(e) =>
          ZIO.when(e.endFlag == false && e.endHeadersFlag == false)(
            ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "A second HEADERS frame without the END_STREAM flag"))
          ) *>
            ZIO.when(e.endFlag == true && e.endHeadersFlag == false)(
              ZIO.fail(
                ErrorGen(
                  streamId,
                  Error.PROTOCOL_ERROR,
                  "A second (trailing?) HEADERS frame without the END_HEADER flag"
                )
              )
            ) *> ZIO.when(e.endFlag == true && e.endHeadersFlag == true)(
              ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "A second HEADERS frame on closed stream"))
            ) *> ZIO.succeed(true)
        case None => ZIO.succeed(false)
      }
    } yield (trailing)
  }

  private[this] def doStreamHeaders(streamId: Int, flags: Int): ZIO[Env, Throwable, Boolean] = {
    for {
      trailing <- checkForTrailingHeaders(streamId, flags)
      _ <- ZIO.when(trailing == false)(openStream(streamId, flags))
    } yield (trailing)
  }

  private[this] def updateStreamWith(num: Int, streamId: Int, run: Http2Stream => Task[Unit]): Task[Unit] = {
    for {
      opt_D <- ZIO.succeed(streamTbl.get(streamId))
      _ <- opt_D match { // Option( null ) gives None
        case None =>
          ZIO.logError(
            s"updateStreamWith() invalid streamId - $streamId, code=$num"
          ) *> ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "invalid stream id"))
        case Some(con_rec) => run(con_rec)
      }
    } yield ()

  }

  private[this] def accumData(streamId: Int, bb: ByteBuffer, dataSize: Int): Task[Unit] = {
    for {
      o_c <- ZIO.attempt(this.streamTbl.get(streamId))
      _ <- ZIO.when(o_c.isEmpty)(ZIO.fail(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")))
      c <- ZIO.attempt(o_c.get)
      _ <- ZIO.succeed(c.contentLenFromDataFrames += dataSize)

      // localWin_sz <- c.inboundWindow.get
      // _ <- this.globalInboundWindow.update(_ - dataSize) *>
      _ <- this.incrementGlobalPendingInboundData(dataSize)
      //  c.inboundWindow.update(_ - dataSize) *>
      _ <- c.bytesOfPendingInboundData.update(_ + dataSize)

      _ <- c.inDataQ.offer(bb)

    } yield ()

  }

  private[this] def accumHeaders(streamId: Int, bb: ByteBuffer): Task[Unit] =
    updateStreamWith(2, streamId, c => ZIO.succeed(c.header.addOne(bb)))

  private[this] def accumTrailingHeaders(streamId: Int, bb: ByteBuffer): Task[Unit] =
    updateStreamWith(3, streamId, c => ZIO.succeed(c.trailing_header.addOne(bb)))

  private[this] def finalizeTrailingHeaders(streamId: Int): Task[Unit] = {
    updateStreamWith(
      4,
      streamId,
      c =>
        for {
          // close data stream, which is stuck without END_STREAM due to addion of trailing header.
          _ <- c.inDataQ.offer(Frames.mkDataFrame(streamId, true, 0, ByteBuffer.allocate(0)))
          _ <- c.trailingHeader.succeed(headerDecoder.decodeHeaders(c.trailing_header.toSeq))
        } yield ()
    )
  }

  private[this] def setEmptyTrailingHeaders(streamId: Int): Task[Unit] = {
    updateStreamWith(6, streamId, c => c.trailingHeader.succeed(Headers()).unit)
  }

  private[this] def triggerStream(streamId: Int): Task[Unit] = {
    updateStreamWith(
      5,
      streamId,
      c =>
        for {
          _ <- c.d.succeed(headerDecoder.decodeHeaders(c.header.toSeq)).unit
        } yield ()
    )

  }

  private[this] def markEndOfHeaders(streamId: Int): Task[Unit] =
    updateStreamWith(7, streamId, c => ZIO.succeed { c.endHeadersFlag = true })

  private[this] def markEndOfStream(streamId: Int): Task[Unit] =
    updateStreamWith(8, streamId, c => ZIO.succeed { c.endFlag = true })

  private[this] def markEndOfStreamWithData(streamId: Int): Task[Unit] =
    updateStreamWith(
      9,
      streamId,
      c =>
        for {
          _ <- ZIO.succeed { c.endFlag = true }
          contentLenFromHeader <- c.contentLenFromHeader.await
          _ <- ZIO.when(contentLenFromHeader.isDefined && c.contentLenFromDataFrames != contentLenFromHeader.get)(
            ZIO.fail(
              ErrorGen(
                streamId,
                Error.PROTOCOL_ERROR,
                "HEADERS frame with the content-length header field which does not equal the DATA frame payload length"
              )
            )
          )
        } yield ()
    )

  private[this] def haveHeadersEnded(streamId: Int): Task[Boolean] = {
    for {
      opt <- ZIO.attempt(streamTbl.get(streamId))
      b <- opt match {
        case Some(s0) => ZIO.succeed(s0.endHeadersFlag)
        case None     => ZIO.fail(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id"))
      }
    } yield (b)
  }

  private[this] def hasEnded(streamId: Int): Task[Boolean] = {
    for {
      opt <- ZIO.succeed(streamTbl.get(streamId))
      b <- opt match {
        case Some(s0) => ZIO.succeed(s0.endFlag)
        case None     => ZIO.fail(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id"))
      }
    } yield (b)
  }

  ////////////////////////////////////////////
  def route2(streamId: Int, request: Request): ZIO[Env, Throwable, Unit] = {

    val T = for {
      _ <- ZIO.logTrace(s"H2 streamId = $streamId ${request.method.name} ${request.path} ")
      _ <- ZIO.logDebug("request.headers: " + request.headers.printHeaders(" | "))

      _ <- ZIO.when(request.headers.tbl.size == 0)(
        ZIO.fail(ErrorGen(streamId, Error.COMPRESSION_ERROR, "empty headers: COMPRESSION_ERROR"))
      )

      _ <- ZIO.when(request.headers.ensureLowerCase == false)(
        ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Upercase letters in the header keys"))
      )

      _ <- ZIO.when(request.headers.validatePseudoHeaders == false)(
        ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Invalid pseudo-header field"))
      )

      _ <- ZIO.when(request.headers.get("connection").isDefined == true)(
        ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Connection-specific header field forbidden"))
      )

      _ <- ZIO.when(request.headers.get("te").isDefined && request.headers.get("te").get != "trailers")(
        ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "TE header field with any value other than trailers"))
      )

      response_o <- (httpRoute(request)).catchAll {
        case e: (java.io.FileNotFoundException) =>
          ZIO.logError(e.toString) *> ZIO.succeed(None)

        case e: (java.nio.file.NoSuchFileException) =>
          ZIO.logError(e.toString) *> ZIO.succeed(None)

        case e =>
          ZIO.logError(e.toString) *>
            ZIO.succeed(Some(Response.Error(StatusCode.InternalServerError)))
      }

      _ <- response_o match {
        case Some(response) =>
          for {
            _ <- ZIO.logInfo(
              s"H2 connId=$id streamId=$streamId ${request.method.name} ${request.path} ${response.code.toString()}"
            )
            _ <- ZIO.logTrace("response.headers: " + response.headers.printHeaders(" | "))
            endStreamInHeaders <- if (response.stream == Response.EmptyStream) ZIO.succeed(true) else ZIO.succeed(false)
            _ <- ZIO.logDebug(
              s"Send response code: ${response.code.toString()} only header = $endStreamInHeaders"
            )

            _ <- ZIO.scoped {
              hSem.withPermitScoped *> ZIO.foreach(
                headerFrame(
                  streamId,
                  settings,
                  Priority.NoPriority,
                  endStreamInHeaders,
                  headerEncoder,
                  response.headers
                )
              )((b => sendFrame(b)))
            }
            /*
            _ <- hSem.acquire.bracket { _ =>
              headerFrame(streamId, Priority.NoPriority, endStreamInHeaders, response.headers)
                .traverse(b => sendFrame(b))
            }(_ => hSem.release) */

            pref <- Ref.make[Chunk[Byte]](Chunk.empty[Byte])

            _ <-
              if (endStreamInHeaders == false)
                response.stream.chunks
                  .foreach { chunk =>
                    for {
                      chunk0 <- pref.get
                      _ <- ZIO.when(chunk0.nonEmpty)(
                        ZIO.foreach(dataFrame(settings, streamId, false, ByteBuffer.wrap(chunk0.toArray)))(b =>
                          sendDataFrame(streamId, b)
                        )
                      )

                      _ <- pref.set(chunk)
                    } yield ()
                  }
              else ZIO.unit

            lastChunk <- pref.get
            _ <- (ZIO
              .when(endStreamInHeaders == false)(
                ZIO.foreach(dataFrame(settings, streamId, true, ByteBuffer.wrap(lastChunk.toArray)))(b =>
                  sendDataFrame(streamId, b)
                )
              ))

            _ <- ZIO.when(endStreamInHeaders == true)(updateStreamWith(10, streamId, c => c.done.succeed(()).unit))

          } yield ()

        case None =>
          for {
            o44 <- ZIO.succeed(Response.Error(StatusCode.NotFound)) // 404
            _ <- ZIO.logTrace("response.headers: " + o44.headers.printHeaders(" | "))
            _ <- ZIO.logDebug(s"Send response code: ${o44.code.toString()}")
            _ <- ZIO.logError(
              s"H2 connId=$id streamId=$streamId ${request.method.name} ${request.path} ${StatusCode.NotFound.toString()}"
            )

            _ <- ZIO.scoped {
              hSem.withPermitScoped.tap(_ =>
                for {
                  bb2 <- ZIO.succeed(
                    headerFrame(streamId, settings, Priority.NoPriority, true, headerEncoder, o44.headers)
                  )
                  _ <- ZIO.foreach(bb2)(b => sendFrame(b))
                  _ <- updateStreamWith(10, streamId, c => c.done.succeed(()).unit)

                } yield ()
              )
            }
          } yield ()
      }
      _ <- closeStream(streamId)
    } yield ()

    T
  }

  def closeStream(streamId: Int): Task[Unit] = {
    for {
      _ <- ZIO.succeed(concurrentStreams.decrementAndGet())
      _ <- ZIO.attempt(streamTbl.remove(streamId))
      _ <- ZIO.logDebug(s"Close stream: $streamId")
    } yield ()
  }

  def processIncoming(leftOver: Chunk[Byte]): ZIO[Env, Nothing, Unit] = (for {
    _ <- ZIO.logTrace(s"Http2Connection.processIncoming() leftOver= ${leftOver.size}")
    _ <- Http2Connection.makePacketStream(ch, HTTP2_KEEP_ALIVE_MS, leftOver)
      .foreach(packet => { packet_handler(httpReq11, packet) })
  } yield ()).catchAll {
    case e @ TLSChannelError(_) =>
      ZIO.logDebug(s"connid = ${this.id} ${e.toString} ${e.getMessage()}") *>
        ZIO.logError(s"connId=${this.id} ${e.toString()}")
    case e: java.nio.channels.ClosedChannelException =>
      ZIO.logInfo(s"Connection connId=${this.id} closed by remote")
    case e @ ErrorGen(streamId, code, name) =>
      ZIO.logError(s"Forced disconnect connId=${this.id} code=${e.code} ${name}") *>
        sendFrame(Frames.mkGoAwayFrame(streamId, code, name.getBytes)).unit
    case e @ _ => {
      ZIO.logError(e.toString())
    }
  }

  ////////////////////////////////////////////////////
  def packet_handler(
      http11request: Ref[Option[Request]],
      packet: Chunk[Byte]
  ): ZIO[Env, Throwable, Unit] = {

    val buffer = ByteBuffer.wrap(packet.toArray)
    val packet0 = buffer.slice // preserve reference to whole packet

    val len = Frames.getLengthField(buffer)
    val frameType = buffer.get()
    val flags = buffer.get()
    val streamId = Frames.getStreamId(buffer)
    for {
      _ <- ZIO.logDebug(s"frametype=$frameType with streamId=$streamId len=$len flags=$flags")
      _ <-
        if (len > settings.MAX_FRAME_SIZE)
          ZIO.fail(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "HEADERS exceeds SETTINGS_MAX_FRAME_SIZE"))
        else if (streamId != 0 && streamId % 2 == 0)
          ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "even-numbered stream identifier"))
        else {
          frameType match {
            case FrameTypes.RST_STREAM =>
              val code: Long = buffer.getInt() & Masks.INT32 // java doesn't have unsigned integers
              for {
                _ <- ZIO.when(len != 4)(
                  ZIO.fail(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "RST_STREAM: FRAME_SIZE_ERROR"))
                )

                o_s <- ZIO.attempt(this.streamTbl.get(streamId))
                _ <- ZIO.logInfo(s"Reset Stream $streamId present=${o_s.isDefined} code=$code")
                _ <- ZIO.when(o_s.isEmpty)(
                  ZIO.fail(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      s"Reset Stream $streamId present=${o_s.isDefined} code=$code"
                    )
                  )
                )

                _ <- markEndOfStream(streamId)

              } yield ()

            case FrameTypes.HEADERS =>
              val padLen: Byte = if ((flags & Flags.PADDED) != 0) buffer.get() else 0 // advance one byte padding len 1

              val priority = if ((flags & Flags.PRIORITY) != 0) {
                val rawInt = buffer.getInt();
                val weight = buffer.get()

                val dependentID = Flags.DepID(rawInt)
                val exclusive = Flags.DepExclusive(rawInt)
                Some(dependentID, exclusive, weight)
              } else None
              val lim = buffer.limit() - padLen
              buffer.limit(lim)
              if (headerStreamId == 0) headerStreamId = streamId
              if (headerStreamId != streamId)
                ZIO.fail(
                  ErrorGen(
                    streamId,
                    Error.PROTOCOL_ERROR,
                    "HEADERS frame to another stream while sending the header blocks"
                  )
                )
              else {
                for {
                  _ <- ZIO.when(streamId == 0)(
                    ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "streamId is 0 for HEADER"))
                  )

                  _ <- ZIO.when(streamId <= lastStreamId)(
                    ZIO.fail(
                      ErrorGen(
                        streamId,
                        Error.PROTOCOL_ERROR,
                        "stream's Id number is less than previously used Id number"
                      )
                    )
                  )

                  _ <- ZIO.succeed { lastStreamId = streamId }

                  _ <- priority match {
                    case Some(t3) =>
                      ZIO.when(t3._1 == streamId)(
                        ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "HEADERS frame depends on itself"))
                      )

                    case None => ZIO.unit
                  }

                  // total <- IO(this.streamTbl.size)
                  total <- ZIO.attempt(this.concurrentStreams.get())

                  _ <- ZIO.when(total >= settings.MAX_CONCURRENT_STREAMS)(
                    ZIO.fail(
                      ErrorGen(
                        streamId,
                        Error.PROTOCOL_ERROR,
                        "MAX_CONCURRENT_STREAMS exceeded, with total streams = " + total
                      )
                    )
                  )

                  o_s <- ZIO.attempt(this.streamTbl.get(streamId))
                  _ <- o_s match {
                    case Some(s) =>
                      ZIO.fail(ErrorGen(streamId, Error.STREAM_CLOSED, "STREAM_CLOSED")).when(s.endFlag)
                    case None => ZIO.unit
                  }
                  trailing <- doStreamHeaders(streamId, flags)
                  _ <- ZIO.when(trailing == true)(ZIO.logDebug(s"trailing headers: $trailing"))
                  // currently cannot do trailing without END_STREAM ( no continuation for trailing, seems this is stated in RFC, spec test requires it)
                  _ <- ZIO.when(((flags & Flags.END_STREAM) == 0) && trailing)(
                    ZIO.fail(
                      ErrorGen(streamId, Error.INTERNAL_ERROR, "Second HEADERS frame without the END_STREAM flag")
                    )
                  )

                  _ <- accumHeaders(streamId, buffer).when(trailing == false)
                  _ <- accumTrailingHeaders(streamId, buffer).when(trailing == true)

                  _ <- ZIO.when((flags & Flags.END_STREAM) != 0)(markEndOfStream(streamId))
                  _ <- ZIO.when((flags & Flags.END_HEADERS) != 0)(markEndOfHeaders(streamId))

                  // if no body reset trailing headers to empty
                  _ <- ZIO
                    .when(((flags & Flags.END_STREAM) != 0) && trailing == false)(setEmptyTrailingHeaders(streamId))

                  _ <- ZIO.when(((flags & Flags.END_HEADERS) != 0) && (trailing == false))(triggerStream(streamId))

                  _ <- finalizeTrailingHeaders(streamId).when((flags & Flags.END_HEADERS) != 0 && trailing == true)

                  _ <- ZIO.when((flags & Flags.END_HEADERS) != 0)(ZIO.succeed { headerStreamId = 0 })

                } yield ()
              }

            case FrameTypes.CONTINUATION =>
              // TODO: CONTINUATION for trailing headers not supported yet.
              for {
                b1 <- haveHeadersEnded(streamId)
                _ <- ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "END HEADERS")).when(b1)
                _ <- ZIO.when((flags & Flags.END_HEADERS) != 0)(markEndOfHeaders(streamId))
                _ <- ZIO.when((flags & Flags.END_STREAM) != 0)(markEndOfStream(streamId))
                _ <- accumHeaders(streamId, buffer)
                _ <- ZIO.when((flags & Flags.END_HEADERS) != 0)(triggerStream(streamId))

              } yield ()

            case FrameTypes.DATA =>
              // DATA padLen = 0, len= 7, limit=16
              // val true_padding = buffer.limit() - len - Constants.HeaderSize
              // val true_padding = packet.size - len - Constants.HeaderSize
              val padLen: Byte = if ((flags & Flags.PADDED) != 0) buffer.get() else 0
              val padByte = if ((flags & Flags.PADDED) != 0) 1 else 0
              // println(
              //  "DATA padLen = " + padLen + ", len= " + len + ", packet.size=" + packet.size + " padByte = " + padByte + "Constants.HeaderSize =" + Constants.HeaderSize
              // )
              for {
                headersEnded <- haveHeadersEnded(streamId)
                closed <- hasEnded(streamId)

                t1 <- ZIO.succeed(packet.size.toLong - padLen - Constants.HeaderSize - padByte)
                t2 <- ZIO.succeed(len.toLong - padByte - padLen)
                _ <- ZIO.when(t1 != t2)(
                  ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "DATA frame with invalid pad length"))
                )

                _ <- ZIO.when(headersEnded == false)(
                  ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "HEADERS not finished but DATA frame received"))
                )

                b <- hasEnded(streamId)
                _ <- ZIO.fail(ErrorGen(streamId, Error.STREAM_CLOSED, "STREAM_CLOSED")).when(b)
                // streams ends with data, no trailing headers for sure, reset to empty
                _ <- ZIO.when(((flags & Flags.END_STREAM) != 0))(setEmptyTrailingHeaders(streamId))
                _ <- accumData(streamId, packet0, len)
                _ <- ZIO.when((flags & Flags.END_STREAM) != 0)(markEndOfStreamWithData(streamId))
              } yield ()

            case FrameTypes.WINDOW_UPDATE => {
              val increment = buffer.getInt() & Masks.INT31
              for {
                _ <- ZIO.when(streamId > lastStreamId)(
                  ZIO.fail(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      "stream's Id number is less than previously used Id number"
                    )
                  )
                )
                _ <- ZIO.logDebug(s"WINDOW_UPDATE $increment $streamId") *> this
                  .updateWindow(streamId, increment)
                  .catchAll {
                    case e @ ErrorRst(streamId, code, name) =>
                      ZIO.logError("Reset frame") *> sendFrame(Frames.mkRstStreamFrame(streamId, code))
                    case e @ _ => ZIO.fail(e)
                  }
              } yield ()
            }

            case FrameTypes.PING =>
              var data = new Array[Byte](8)
              buffer.get(data)
              if ((flags & Flags.ACK) == 0) {
                for {
                  _ <- ZIO.when(streamId != 0)(
                    ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Ping streamId not 0"))
                  )

                  _ <- sendFrame(Frames.mkPingFrame(ack = true, data))
                } yield ()
              } else ZIO.unit // else if (this.start)

            case FrameTypes.GOAWAY =>
              ZIO.when(streamId != 0)(
                ZIO.fail(
                  ErrorGen(streamId, Error.PROTOCOL_ERROR, "GOAWAY frame with a stream identifier other than 0x0")
                )
              ) *> ZIO
                .attempt {
                  val lastStream = Flags.DepID(buffer.getInt())
                  val code: Long =
                    buffer.getInt() & Masks.INT32 // java doesn't have unsigned integers
                  val data = new Array[Byte](buffer.remaining)
                  buffer.get(data)
                  data
                }
                .flatMap(data =>
                  ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "GOAWAY frame received " + new String(data)))
                )

            case FrameTypes.PRIORITY =>
              val rawInt = buffer.getInt();
              val weight = buffer.get()
              val dependentId = Flags.DepID(rawInt)
              val exclusive = Flags.DepExclusive(rawInt)

              for {
                _ <- ZIO.logDebug("PRIORITY frane received")
                _ <- ZIO.when(headerStreamId != 0)(
                  ZIO.fail(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      "PRIORITY frame to another stream while sending the headers blocks"
                    )
                  )
                )

                _ <- ZIO.when(streamId == 0)(
                  ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "PRIORITY frame with 0x0 stream identifier"))
                )

                _ <- ZIO.when(len != 5)(
                  ZIO.fail(
                    ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "PRIORITY frame with a length other than 5 octets")
                  )
                )

                _ <- ZIO.when(streamId == dependentId)(
                  ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "PRIORITY frame depends on itself"))
                )

              } yield ()

            /* When the value of SETTINGS_INITIAL_WINDOW_SIZE changes, a receiver MUST adjust
          the size of all stream flow-control windows that it maintains by the
           difference between the new value and the old value.
             */

            case FrameTypes.PUSH_PROMISE =>
              ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "PUSH_PROMISE frame"))

            case FrameTypes.SETTINGS =>
              (for {
                _ <- ZIO.when(len % 6 != 0)(
                  ZIO.fail(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      "ends a SETTINGS frame with a length other than a multiple of 6 octets"
                    )
                  )
                )

                _ <- ZIO.when(len > 0 && Flags.ACK(flags))(
                  ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "SETTINGS frame with ACK flag and payload"))
                )

                _ <- ZIO.when(streamId != 0)(
                  ZIO.fail(
                    ErrorGen(streamId, Error.PROTOCOL_ERROR, "SETTINGS frame with a stream identifier other than 0x0")
                  )
                )

                _ <-
                  if (Flags.ACK(flags) == false) {
                    for {
                      res <- ZIO.attempt(Http2Settings.fromSettingsArray(buffer, len)).onError { _ =>
                        sendFrame(Frames.mkPingFrame(ack = true, Array.fill[Byte](8)(0x0)))
                      }

                      _ <- ZIO.when(settings_done == true && res.MAX_FRAME_SIZE < settings_client.MAX_FRAME_SIZE)(
                        ZIO.fail(
                          ErrorGen(
                            streamId,
                            Error.PROTOCOL_ERROR,
                            "SETTINGS_MAX_FRAME_SIZE (0x5): Sends the value below the initial value"
                          )
                        )
                      )

                      _ <- ZIO.when(res.MAX_FRAME_SIZE > 0xffffff)(
                        ZIO.fail(
                          ErrorGen(
                            streamId,
                            Error.PROTOCOL_ERROR,
                            "SETTINGS_MAX_FRAME_SIZE (0x5): Sends the value above the maximum allowed frame size"
                          )
                        )
                      )

                      _ <- ZIO.when(res.ENABLE_PUSH != 1 && res.ENABLE_PUSH != 0)(
                        ZIO.fail(
                          ErrorGen(
                            streamId,
                            Error.PROTOCOL_ERROR,
                            "SETTINGS_ENABLE_PUSH (0x2): Sends the value other than 0 or 1"
                          )
                        )
                      )

                      _ <- ZIO.when((res.INITIAL_WINDOW_SIZE & Masks.INT32) > Integer.MAX_VALUE)(
                        ZIO.fail(
                          ErrorGen(
                            streamId,
                            Error.FLOW_CONTROL_ERROR,
                            "SETTINGS_INITIAL_WINDOW_SIZE (0x4): Sends the value above the maximum flow control window size"
                          )
                        )
                      )

                      ws <- ZIO.succeed(this.settings_client.INITIAL_WINDOW_SIZE)
                      _ <- ZIO.attempt(Http2Settings.copy(this.settings_client, res))

                      _ <- upddateInitialWindowSizeAllStreams(ws, res.INITIAL_WINDOW_SIZE)
                      _ <- ZIO.succeed(this.settings.MAX_CONCURRENT_STREAMS = this.MAX_CONCURRENT_STREAMS)
                      _ <- ZIO.succeed(this.settings.INITIAL_WINDOW_SIZE = this.INITIAL_WINDOW_SIZE)

                      _ <- ZIO.logDebug(s"Remote INITIAL_WINDOW_SIZE ${this.settings_client.INITIAL_WINDOW_SIZE}")
                      _ <- ZIO.logDebug(s"Server INITIAL_WINDOW_SIZE ${this.settings.INITIAL_WINDOW_SIZE}")

                      _ <- ZIO.when(settings_done == false)(
                        sendFrame(Frames.makeSettingsFrame(ack = false, this.settings))
                      )
                      _ <- sendFrame(Frames.makeSettingsAckFrame())

                      // re-adjust inbound window if exceeds default
                      _ <- this.globalInboundWindow.set(INITIAL_WINDOW_SIZE)
                      _ <-
                        if (INITIAL_WINDOW_SIZE > 65535) {
                          sendFrame(Frames.mkWindowUpdateFrame(streamId, INITIAL_WINDOW_SIZE - 65535))
                        } else ZIO.unit
                      _ <- ZIO.when(INITIAL_WINDOW_SIZE > 65535L)(
                        ZIO.logDebug(s"Send UPDATE WINDOW global: ${INITIAL_WINDOW_SIZE - 65535}")
                      )

                      _ <- ZIO.succeed {
                        if (settings_done == false) settings_done = true
                      }

                    } yield ()
                  } else
                    ZIO.when(start)(ZIO.attempt { start = false } *> http11request.get.flatMap {
                      case Some(x) => {
                        val stream = x.stream
                        val th = x.trailingHeaders
                        val h = x.headers.drop("connection")
                        this.openStream11(1, Request(id, 1, h, stream, ch.secure(), ch.sniServerNames(), th))
                      }
                      case None => ZIO.unit
                    })

              } yield ()).catchAll {
                case _: scala.MatchError => ZIO.logDebug("Settings match error") *> ZIO.unit
                case e @ _               => ZIO.fail(e)
              }
            case _ =>
              for {
                _ <- ZIO.when(headerStreamId != 0)(
                  ZIO.fail(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      "Sends an unknown extension frame in the middle of a header block"
                    )
                  )
                )
              } yield ()

          }
        }
    } yield ()
  }

}
