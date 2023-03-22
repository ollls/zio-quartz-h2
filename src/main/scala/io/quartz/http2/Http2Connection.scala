package io.quartz.http2

import scala.collection.mutable.ArrayBuffer
import java.nio.channels.AsynchronousSocketChannel

import java.nio.ByteBuffer

import io.quartz.http2.Constants._

import io.quartz.http2.model.{Request, Response, Headers, ContentType, StatusCode}
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

import zio.{ZIO, Task, Promise, Queue, Chunk, Ref, Semaphore}
import zio.stream.{ZStream, ZChannel, ZPipeline}
import io.quartz.http2.routes.HttpRoute

object Http2Connection {

  val FAST_MODE = true

  private[this] def outBoundWorkerProc(
      ch: IOChannel,
      outq: Queue[ByteBuffer],
      shutdown: Promise[Throwable, Boolean]
  ): Task[Boolean] = {
    for {
      bb <- outq.take
      res <- if (bb == null) ZIO.succeed(true) else ZIO.succeed(false)
      _ <- ch.write(bb).when(res == false)
      _ <- ZIO.logDebug("Shutdown outbound H2 packet sender").when(res == true)
      _ <- shutdown.succeed(true).when(res == true)
    } yield (res)
  }

  def make[Env](
      ch: IOChannel,
      id: Long,
      maxStreams: Int,
      keepAliveMs: Int,
      httpRoute: HttpRoute[Env],
      in_winSize: Int,
      http11request: Option[Request]
  ): Task[Http2Connection[Env]] = {
    for {
      _ <- ZIO.logDebug("Http2Connection.make()")
      shutdownPromise <- Promise.make[Throwable, Boolean]

      outq <- Queue.bounded[ByteBuffer](1)
      outDataQEventQ <- Queue.unbounded[Boolean]
      http11Req_ref <- Ref.make[Option[Request]](http11request)
      hSem <- Semaphore.make(permits = 1)

      globalTransmitWindow <- Ref.make[Long](65535) // (default_server_settings.INITIAL_WINDOW_SIZE)
      globalInboundWindow <- Ref.make(65535) // (default_server_settings.INITIAL_WINDOW_SIZE)

      globalBytesOfPendingInboundData <- Ref.make(0)

      runMe2 = outBoundWorkerProc(ch, outq, shutdownPromise)
        .catchAll(e => ZIO.logDebug("outBoundWorkerProc fiber: " + e.toString()))
        .repeatUntil(_ == true)
        .fork

      _ <- runMe2

      c <- ZIO.attempt(
        new Http2Connection(
          ch,
          id,
          httpRoute,
          http11Req_ref,
          outq,
          outDataQEventQ,
          globalTransmitWindow,
          globalBytesOfPendingInboundData,
          globalInboundWindow,
          shutdownPromise,
          hSem,
          maxStreams,
          keepAliveMs,
          in_winSize
        )
      )
      runMe = c.streamDataOutWorker
        .catchAll(e => ZIO.logError("streamDataOutWorker fiber: " + e.toString()))
        .repeatUntil(_ == true)
        .fork

      _ <- runMe

      _ <- ZIO.logTrace("Http2Connection.make() - Start data worker")

    } yield c
  }

  private[this] def parseFrame(bb: ByteBuffer) = {
    val sbb = bb.slice();

    val len = Frames.getLengthField(sbb)
    val frameType = sbb.get()
    val flags = sbb.get()
    val streamId = Frames.getStreamId(sbb)

    (len, frameType, flags, streamId)
  }

  private[this] def dataEvalEffectProducer[Env](
      c: Http2Connection[Env],
      q: Queue[ByteBuffer]
  ): Task[ByteBuffer] = {
    for {
      bb <- q.take
      _ <- ZIO.fail(java.nio.channels.ClosedChannelException()).when(bb == null)
      tp <- ZIO.attempt(parseFrame(bb))
      streamId = tp._4
      len = tp._1

      o_stream <- ZIO.attempt(c.streamTbl.get(streamId))
      _ <- ZIO.fail(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")).when(o_stream.isEmpty)
      stream <- ZIO.attempt(o_stream.get)

      ////////////////////////////////////////////////
      // global counter with Window Update.
      // now we track both global and local during request.stream processing
      // globalBytesOfPendingInboundData shared reference betweem streams
      //////////////////////////////////////////////
      _ <- c.decrementGlobalPendingInboundData(len)
      bytes_pending <- c.globalBytesOfPendingInboundData.get
      bytes_available <- c.globalInboundWindow.get
      globalWinIncrement <- ZIO.succeed {
        if (c.INITIAL_WINDOW_SIZE > bytes_pending) c.INITIAL_WINDOW_SIZE - bytes_pending
        else c.INITIAL_WINDOW_SIZE
      }
      _ <- c
        .sendFrame(Frames.mkWindowUpdateFrame(0, globalWinIncrement))
        .when(globalWinIncrement > c.INITIAL_WINDOW_SIZE * 0.7 && bytes_available < c.INITIAL_WINDOW_SIZE * 0.3)

      _ <- c.globalInboundWindow
        .update(_ + globalWinIncrement)
        .when(globalWinIncrement > c.INITIAL_WINDOW_SIZE * 0.7 && bytes_available < c.INITIAL_WINDOW_SIZE * 0.3)

      _ <- ZIO
        .logDebug(s"Send WINDOW UPDATE global $globalWinIncrement $bytes_available")
        .when(globalWinIncrement > c.INITIAL_WINDOW_SIZE * 0.7 && bytes_available < c.INITIAL_WINDOW_SIZE * 0.3)
      //////////////////////////////////////////////
      // local counter
      _ <- c.updateStreamWith(
        0,
        streamId,
        c => c.bytesOfPendingInboundData.update(_ - len)
      )

      bytes_available_per_stream <- stream.inboundWindow.get

      bytes_pending_per_stream <- c.globalBytesOfPendingInboundData.get
      streamWinIncrement <- ZIO.succeed {
        if (c.INITIAL_WINDOW_SIZE > bytes_pending_per_stream) c.INITIAL_WINDOW_SIZE - bytes_pending_per_stream
        else c.INITIAL_WINDOW_SIZE
      }
      _ <-
        if (
          streamWinIncrement > c.INITIAL_WINDOW_SIZE * 0.7 && bytes_available_per_stream < c.INITIAL_WINDOW_SIZE * 0.3
        ) {
          ZIO.logDebug(
            s"Send WINDOW UPDATE local on processing incoming data=$streamWinIncrement localWin=$bytes_available_per_stream"
          ) *> c
            .sendFrame(
              Frames.mkWindowUpdateFrame(
                streamId,
                if (streamWinIncrement > 0) streamWinIncrement else c.INITIAL_WINDOW_SIZE
              )
            ) *>
            stream.inboundWindow.update(_ + streamWinIncrement)
        } else {
          ZIO.logTrace(
            ">>>>>>>>>> still processing incoming data, pause remote, pending data = " + bytes_pending_per_stream
          ) *> ZIO.unit
        }

    } yield (bb)
  }

  private[this] def makeDataStream[Env](
      c: Http2Connection[Env],
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
}

case class Http2Stream(
    active: Ref[Boolean],
    d: Promise[Throwable, Headers],
    header: ArrayBuffer[ByteBuffer],
    trailing_header: ArrayBuffer[ByteBuffer],
    inDataQ: Queue[ByteBuffer], // accumulate data packets in stream function
    outDataQ: Queue[ByteBuffer], // priority outbound queue for all data frames, scanned by one thread
    outXFlowSync: Queue[Unit], // flow control sync queue for data frames
    transmitWindow: Ref[Long],
    syncUpdateWindowQ: Queue[Unit],
    bytesOfPendingInboundData: Ref[Int], // metric
    inboundWindow: Ref[Long],
    contentLenFromHeader: Promise[Throwable, Option[Int]],
    trailingHeader: Promise[Throwable, Headers],
    done: Promise[Throwable, Unit]
) {
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
    outDataQEventQ: Queue[Boolean],
    globalTransmitWindow: Ref[Long],
    val globalBytesOfPendingInboundData: Ref[Int], // metric
    val globalInboundWindow: Ref[Int],
    shutdownD: Promise[Throwable, Boolean],
    hSem: Semaphore,
    MAX_CONCURRENT_STREAMS: Int,
    HTTP2_KEEP_ALIVE_MS: Int,
    val INITIAL_WINDOW_SIZE: Int
) {

  val settings: Http2Settings = new Http2Settings()
  val settings_client = new Http2Settings()
  var settings_done = false

  var concurrentStreams = new AtomicInteger(0)

  var start = true
  // streamID of the header which is currently fetching from remote, any other header will trigger GoAway
  var headerStreamId = 0
  var lastStreamId = 0

  val headerEncoder = new HeaderEncoder(settings.HEADER_TABLE_SIZE)
  val headerDecoder = new HeaderDecoder(settings.MAX_HEADER_LIST_SIZE, settings.HEADER_TABLE_SIZE)

  val streamTbl = java.util.concurrent.ConcurrentHashMap[Int, Http2Stream](100).asScala

  // var statRefresh = 0

  private[this] def decrementGlobalPendingInboundData(decrement: Int) =
    globalBytesOfPendingInboundData.update(_ - decrement)
  private[this] def incrementGlobalPendingInboundData(increment: Int) =
    globalBytesOfPendingInboundData.update(_ + increment)

  def shutdown: Task[Unit] =
    ZIO.logInfo("Http2Connection.shutdown") *> outDataQEventQ.offer(true) *> outq.offer(null) *> shutdownD.await.unit

  //////////////////////////////////////////
  private[this] def streamDataOutBoundProcessor(streamId: Int, c: Http2Stream): Task[Unit] = {
    for {
      isEmpty <- c.outDataQ.isEmpty
      bb <- if (!isEmpty) c.outDataQ.take else ZIO.succeed(null) // null to None
      opt <- ZIO.succeed(Option(bb))

      res <- opt match {
        case Some(data_frame) =>
          for {
            t <- ZIO.attempt(Http2Connection.parseFrame(data_frame))
            // len = t._1
            // frameType = t._2
            flags = t._3
            // streamId = t._4
            _ <- this.sendFrameLowPriority(data_frame)
            x <- outDataQEventQ.take
            _ <- outDataQEventQ.take.when(
              x == true
            )
            _ <- outDataQEventQ
              .offer(true)
              .when(x == true) // rare case when shutdown requested interleaved with packet send which is false
            // _ <- IO.println("Yeld true").whenA(x == true) // we need to preserve true as flag to termnate fiber

            // up <- c.active.get
            // _ <- IO(streamTbl.remove(streamId)).whenA(up == false)

            _ <- c.done.succeed(()).when(Flags.END_STREAM(flags) == true)

          } yield ()

        case None =>
          for {
            _ <- ZIO.unit
            // up <- c.active.get
            // _ <- c.done.complete(()).whenA(up == false)
            // x <- outDataQEventQ.take
            // _ <- outDataQEventQ.offer(x)
            // _ <- IO.println( "Yeld true1" ) //.whenA( x == true )

            // up <- c.active.get
            // _ <- IO.println("REM").whenA(up == false)
            // _ <- IO(streamTbl.remove(streamId)).whenA(up == false)

          } yield ()
      }
    } yield ()

  }

  //////////////////////////////////////////
  private[this] def streamDataOutWorker: Task[Boolean] = {
    for {
      x <- outDataQEventQ.take
      _ <- outDataQEventQ.offer(x)

      // _ <- this.streamTbl.iterator.toSeq.foldMap[Task[Unit]](c => streamDataOutBoundProcessor(c._1, c._2))
      _ <- ZIO.foreach(this.streamTbl.iterator.toSeq)(c => streamDataOutBoundProcessor(c._1, c._2))

      _ <- ZIO.logDebug("Shutdown H2 outbound data packet priority manager").when(x == true)

    } yield (x)
  }

  /*
    When the value of SETTINGS_INITIAL_WINDOW_SIZE changes, a receiver MUST adjust
    the size of all stream flow-control windows that it maintains by the
    difference between the new value and the old value.
   */
  private[this] def updateInitiallWindowSize(stream: Http2Stream, currentWinSize: Int, newWinSize: Int) = {
    ZIO.logInfo(s"Http2Connection.upddateInitialWindowSize( $currentWinSize, $newWinSize)") *>
      stream.transmitWindow.update(txBytesLeft => newWinSize - (currentWinSize - txBytesLeft)) *> stream.outXFlowSync
        .offer(())
  }

  private[this] def upddateInitialWindowSizeAllStreams(currentSize: Int, newSize: Int) = {
    ZIO.logTrace(s"Http2Connection.upddateInitialWindowSizeAllStreams($currentSize, $newSize)") *>
      ZIO.foreach(streamTbl.values.toSeq)(stream => updateInitiallWindowSize(stream, currentSize, newSize))
  }

  private[this] def updateAndCheckGlobalTx(streamId: Int, inc: Int) = {
    for {
      _ <- globalTransmitWindow.update(_ + inc)
      rs <- globalTransmitWindow.get
      _ <- ZIO
        .fail(
          ErrorGen(
            streamId,
            Error.FLOW_CONTROL_ERROR,
            "Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1"
          )
        )
        .when(rs >= Integer.MAX_VALUE)
    } yield ()
  }

  private[this] def updateWindow(streamId: Int, inc: Int): Task[Unit] = {
    // IO.println( "Update Window()") >>
    ZIO
      .fail(
        ErrorGen(
          streamId,
          Error.PROTOCOL_ERROR,
          "Sends a WINDOW_UPDATE frame with a flow control window increment of 0"
        )
      )
      .when(inc == 0) *> (if (streamId == 0)
                            updateAndCheckGlobalTx(streamId, inc) *>
                              ZIO
                                .foreach(streamTbl.values.toSeq)(stream =>
                                  for {
                                    _ <- stream.transmitWindow.update(_ + inc)
                                    rs <- stream.transmitWindow.get
                                    // _ <- IO.println("RS = " + rs)
                                    _ <- ZIO
                                      .fail(
                                        ErrorGen(
                                          streamId,
                                          Error.FLOW_CONTROL_ERROR,
                                          "Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1"
                                        )
                                      )
                                      .when(rs >= Integer.MAX_VALUE)
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
                                  _ <- ZIO
                                    .fail(
                                      ErrorRst(
                                        streamId,
                                        Error.FLOW_CONTROL_ERROR,
                                        ""
                                      )
                                    )
                                    .when(rs >= Integer.MAX_VALUE)
                                  _ <- stream.outXFlowSync.offer(())
                                } yield ()
                            ))
  }

  private[this] def handleStreamErrors(streamId: Int, e: Throwable): Task[Unit] = {
    e match {
      case e @ ErrorGen(streamId, code, name) =>
        ZIO.logError("handleStreamErrors: " + e.toString) *>
          ch.write(Frames.mkGoAwayFrame(streamId, code, name.getBytes)).unit *> this.ch.close()
      case _ => ZIO.logError("handleStreamErrors:: " + e.toString) *> ZIO.fail(e)
    }
  }

  private[this] def interceptContentLen(c: Http2Stream, hdr: Headers) = {
    hdr.get("content-length") match {
      case Some(cl) =>
        c.contentLenFromHeader
          .succeed(try { Some(cl.toInt) }
          catch case e: java.lang.NumberFormatException => None)
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
      xFlowSync <- Queue.unbounded[Unit]
      dataIn <- Queue.unbounded[ByteBuffer]
      transmitWindow <- Ref.make[Long](settings_client.INITIAL_WINDOW_SIZE)

      localInboundWindowSize <- Ref.make[Long](INITIAL_WINDOW_SIZE)
      _ <-
        if (INITIAL_WINDOW_SIZE > 65535L) sendFrame(Frames.mkWindowUpdateFrame(streamId, INITIAL_WINDOW_SIZE - 65535))
        else ZIO.unit
      _ <- ZIO
        .logDebug(s"Send UPDATE WINDOW, streamId = $streamId: ${INITIAL_WINDOW_SIZE - 65535}")
        .when(INITIAL_WINDOW_SIZE > 65535L)

      updSyncQ <- Queue.dropping[Unit](1)
      pendingInBytes <- Ref.make(0)
      active <- Ref.make(true)

      c <- ZIO.attempt(
        Http2Stream(
          active,
          d,
          header,
          trailing_header,
          inDataQ = dataIn,
          outDataQ = dataOut,
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

      // usedId <- usedStreamIdCounter.get
      // _ <- usedStreamIdCounter.set(streamId)

      // _ <- IO
      //  .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Sends a HEADERS frame on previously closed(used) stream"))
      //  .whenA(streamId <= usedId)

      nS <- ZIO.attempt(concurrentStreams.get)
      _ <- ZIO.logDebug(s"Open stream: $streamId  total = ${streamTbl.size} active = ${nS}")

      d <- Promise.make[Throwable, Headers] // start stream, after done with headers and continuations
      done <- Promise.make[Throwable, Unit]
      trailingHdr <- Promise.make[Throwable, Headers] // safe access to trailing header, only when they are fully ready

      contentLenFromHeader <- Promise.make[Throwable, Option[Int]]

      header <- ZIO.succeed(ArrayBuffer.empty[ByteBuffer])
      trailing_header <- ZIO.succeed(ArrayBuffer.empty[ByteBuffer])

      dataOut <- Queue.bounded[ByteBuffer](1) // up to MAX_CONCURRENT_STREAMS users
      xFlowSync <- Queue.unbounded[Unit]
      dataIn <- Queue.unbounded[ByteBuffer]
      transmitWindow <- Ref.make[Long](settings_client.INITIAL_WINDOW_SIZE)

      localInboundWindowSize <- Ref.make[Long](INITIAL_WINDOW_SIZE)
      _ <-
        if (INITIAL_WINDOW_SIZE > 65535L) sendFrame(Frames.mkWindowUpdateFrame(streamId, INITIAL_WINDOW_SIZE - 65535))
        else ZIO.unit
      _ <- ZIO
        .logDebug(s"Send UPDATE WINDOW, streamId = $streamId: ${INITIAL_WINDOW_SIZE - 65535}")
        .when(INITIAL_WINDOW_SIZE > 65535L)

      updSyncQ <- Queue.dropping[Unit](1)
      pendingInBytes <- Ref.make(0)

      active <- Ref.make(true)

      c <- ZIO.attempt(
        Http2Stream(
          active,
          d,
          header,
          trailing_header,
          inDataQ = dataIn,
          outDataQ = dataOut,
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
          ZIO
            .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "A second HEADERS frame without the END_STREAM flag"))
            .when(e.endFlag == false && e.endHeadersFlag == false) *>
            ZIO
              .fail(
                ErrorGen(
                  streamId,
                  Error.PROTOCOL_ERROR,
                  "A second (trailing?) HEADERS frame without the END_HEADER flag"
                )
              )
              .when(e.endFlag == true && e.endHeadersFlag == false) *>
            ZIO
              .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "A second HEADERS frame on closed stream"))
              .when(e.endFlag == true && e.endHeadersFlag == true) *> ZIO.succeed(true)
        case None => ZIO.succeed(false)
      }
    } yield (trailing)
  }

  private[this] def doStreamHeaders(streamId: Int, flags: Int): ZIO[Env, Throwable, Boolean] = {
    for {
      trailing <- checkForTrailingHeaders(streamId, flags)
      _ <- openStream(streamId, flags).when(trailing == false)
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
      _ <- ZIO.fail(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")).when(o_c.isEmpty)
      c <- ZIO.attempt(o_c.get)
      _ <- ZIO.succeed(c.contentLenFromDataFrames += dataSize)

      localWin_sz <- c.inboundWindow.get
      _ <- this.globalInboundWindow.update(_ - dataSize) *>
        this.incrementGlobalPendingInboundData(dataSize) *>
        c.inboundWindow.update(_ - dataSize) *>
        c.bytesOfPendingInboundData.update(_ + dataSize)

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
          // http_headers <- ZIO.succeed(headerDecoder.decodeHeaders(c.trailing_header.toSeq))
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
          // headers <- ZIO.succeed(headerDecoder.decodeHeaders(c.header.toSeq))
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
          _ <- ZIO
            .fail(
              ErrorGen(
                streamId,
                Error.PROTOCOL_ERROR,
                "HEADERS frame with the content-length header field which does not equal the DATA frame payload length"
              )
            )
            .when(contentLenFromHeader.isDefined && c.contentLenFromDataFrames != contentLenFromHeader.get)

        } yield ()
    ).catchAll( e => ZIO.logError( s"markEndOfStream():  Stream $streamId closed already"))

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

      val dataFrame1 = Frames.mkDataFrame(streamId, false, padding = 0, ByteBuffer.wrap(buf0))
      val dataFrame2 = Frames.mkDataFrame(streamId, false, padding = 0, bb)

      (
        txWindow_SplitDataFrame(dataFrame1, requiredLen.toInt),
        Some(txWindow_SplitDataFrame(dataFrame2, len - requiredLen.toInt))
      )

    } else (txWindow_SplitDataFrame(original_bb, len), None)
  }

  private[this] def txWindow_Transmit(stream: Http2Stream, bb: ByteBuffer, data_len: Int): Task[Long] = {
    for {
      tx_g <- globalTransmitWindow.get
      tx_l <- stream.transmitWindow.get
      bytesCredit <- ZIO.succeed(Math.min(tx_g, tx_l))

      _ <-
        if (bytesCredit > 0)
          (for {
            rlen <- ZIO.succeed(Math.min(bytesCredit, data_len))
            frames <- ZIO.attempt(splitDataFrames(bb, rlen))

            // _ <- outDataQEventQ.offer(false) >> stream.outDataQ.offer(frames._1.buffer)

            _ <-
              if (Http2Connection.FAST_MODE == true) sendFrame(frames._1.buffer)
              else stream.outDataQ.offer(frames._1.buffer) *> outDataQEventQ.offer(false)

            _ <- globalTransmitWindow.update(_ - rlen)
            _ <- stream.transmitWindow.update(_ - rlen)

            _ <- frames._2 match {
              case Some(f0) =>
                stream.outXFlowSync.take *> txWindow_Transmit(stream, f0.buffer, f0.dataLen)
              case None => ZIO.unit
            }

          } yield ())
        else stream.outXFlowSync.take *> txWindow_Transmit(stream, bb, data_len)

    } yield (bytesCredit)
  }

  def sendDataFrame(streamId: Int, bb: ByteBuffer): Task[Unit] =
    for {
      t <- ZIO.attempt(Http2Connection.parseFrame(bb))
      len = t._1
      _ <- ZIO.logTrace(s"sendDataFrame() - $len bytes")
      opt_D <- ZIO.attempt(streamTbl.get(streamId))
      _ <- opt_D match {
        case Some(ce) =>
          for {
            _ <- txWindow_Transmit(ce, bb, len)
          } yield ()
        case None => ZIO.logError("sendDataFrame lost streamId")
      }
    } yield ()

  def sendFrame(b: ByteBuffer) = outq.offer(b)

  def sendFrameLowPriority(b: ByteBuffer) = outq.offer(b)

  ////////////////////////////////////////////

  def route2(streamId: Int, request: Request): ZIO[Env, Throwable, Unit] = {

    val T = for {
      _ <- ZIO.logDebug(s"Processing request for stream = $streamId ${request.method.name} ${request.path} ")
      _ <- ZIO.logDebug("request.headers: " + request.headers.printHeaders(" | "))

      _ <- ZIO
        .fail(ErrorGen(streamId, Error.COMPRESSION_ERROR, "empty headers: COMPRESSION_ERROR"))
        .when(request.headers.tbl.size == 0)

      _ <- ZIO
        .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Upercase letters in the header keys"))
        .when(request.headers.ensureLowerCase == false)

      _ <- ZIO
        .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Invalid pseudo-header field"))
        .when(request.headers.validatePseudoHeaders == false)

      _ <- ZIO
        .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Connection-specific header field forbidden"))
        .when(request.headers.get("connection").isDefined == true)

      _ <- ZIO
        .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "TE header field with any value other than trailers"))
        .when(request.headers.get("te").isDefined && request.headers.get("te").get != "trailers")

      response_o <- (httpRoute(request)).catchAll {
        case e: java.io.FileNotFoundException =>
          ZIO.logError(e.toString) *> ZIO.succeed(None)
        case e: java.nio.file.NoSuchFileException =>
          ZIO.logError(e.toString) *> ZIO.succeed(None)
        case e =>
          ZIO.logError(e.toString) *>
            ZIO.succeed(Some(Response.Error(StatusCode.InternalServerError)))
      }

      _ <- response_o match {
        case Some(response) =>
          for {
            _ <- ZIO.logTrace("response.headers: " + response.headers.printHeaders(" | "))
            endStreamInHeaders <- if (response.stream == Response.EmptyStream) ZIO.succeed(true) else ZIO.succeed(false)
            _ <- ZIO.logDebug(
              s"Send response code: ${response.code.toString()} only header = $endStreamInHeaders"
            )

            _ <- ZIO.scoped {
              hSem.withPermitScoped *> ZIO.foreach(
                headerFrame(streamId, Priority.NoPriority, endStreamInHeaders, response.headers)
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
                      _ <- ZIO
                        .foreach(dataFrame(streamId, false, ByteBuffer.wrap(chunk0.toArray)))(b =>
                          sendDataFrame(streamId, b)
                        )
                        .when(chunk0.nonEmpty)
                      _ <- pref.set(chunk)
                    } yield ()
                  }
              else ZIO.unit

            lastChunk <- pref.get
            _ <- (ZIO
              .foreach(dataFrame(streamId, true, ByteBuffer.wrap(lastChunk.toArray)))(b => sendDataFrame(streamId, b)))
              .when(endStreamInHeaders == false)
              .unit

            _ <- updateStreamWith(10, streamId, c => c.done.succeed(()).unit).when(endStreamInHeaders == true)

          } yield ()

        case None =>
          for {
            o44 <- ZIO.succeed(Response.Error(StatusCode.NotFound)) // 404
            _ <- ZIO.logTrace("response.headers: " + o44.headers.printHeaders(" | "))
            _ <- ZIO.logDebug(s"Send response code: ${o44.code.toString()}")

            _ <- ZIO.scoped {
              hSem.withPermitScoped.tap(_ =>
                for {
                  bb2 <- ZIO.succeed(headerFrame(streamId, Priority.NoPriority, true, o44.headers))
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

    // IO(T).bracket( c => c )(_ => closeStream(streamId))
  }

  def closeStream(streamId: Int): Task[Unit] = {
    for {
      _ <- updateStreamWith(12, streamId, c => c.done.await).when(Http2Connection.FAST_MODE == false)
      _ <- ZIO.succeed(concurrentStreams.decrementAndGet())
      // keep last HTTP2_MAX_CONCURRENT_STREAMS in closed state.
      // potentialy it's possible for client to reuse old streamId outside of that last MAX
      // _ <- IO(streamTbl.remove(streamId - 2 * Http2Connection.HTTP2_MAX_CONCURRENT_STREAMS))
      //  .whenA(streamId > 2 * Http2Connection.HTTP2_MAX_CONCURRENT_STREAMS)
      // _ <- IO.sleep( 50.millis )
      _ <- ZIO.attempt(streamTbl.remove(streamId))
      _ <- ZIO.logDebug(s"Close stream: $streamId")
    } yield ()

  }

  /** Generate stream data frame(s) for the specified data
    *
    * If the data exceeds the peers MAX_FRAME_SIZE setting, it is fragmented into a series of frames.
    */
  def dataFrame(
      streamId: Int,
      endStream: Boolean,
      data: ByteBuffer
  ): scala.collection.immutable.Seq[ByteBuffer] = {
    val limit =
      settings.MAX_FRAME_SIZE - 128

    if (data.remaining <= limit) {

      val acc = new ArrayBuffer[ByteBuffer]
      acc.addOne(Frames.mkDataFrame(streamId, endStream, padding = 0, data))

      acc.toSeq
    } else { // need to fragment
      // println("FRAGMENT - SHOULD NOT BE THERE")
      val acc = new ArrayBuffer[ByteBuffer]

      while (data.hasRemaining) {

        val len = math.min(data.remaining(), limit)

        val cur_pos = data.position()

        val thisData = data.slice.limit(len)

        data.position(cur_pos + len)

        val eos = endStream && !data.hasRemaining
        acc.addOne(Frames.mkDataFrame(streamId, eos, padding = 0, thisData))
      }
      // acc.foreach(c => println("------>" + c.limit()))
      acc.toSeq
    }
  }

  def takeSlice(buf: ByteBuffer, len: Int): ByteBuffer = {
    val head = buf.slice.limit(len)
    buf.position(len)
    head
  }

  /** Generate stream header frames from the provided header sequence
    *
    * If the compressed representation of the headers exceeds the MAX_FRAME_SIZE setting of the peer, it will be broken
    * into a HEADERS frame and a series of CONTINUATION frames.
    */
  // BROKEN TODO TODO!!!
  //////////////////////////////
  def headerFrame( // TODOD pbly not working for multi-packs
      streamId: Int,
      priority: Priority,
      endStream: Boolean,
      headers: Headers
  ): scala.collection.immutable.Seq[ByteBuffer] = {
    val rawHeaders = headerEncoder.encodeHeaders(headers)

    val limit = settings.MAX_FRAME_SIZE - 61

    val headersPrioritySize =
      if (priority.isDefined) 5 else 0 // priority(4) + weight(1), padding = 0

    if (rawHeaders.remaining() + headersPrioritySize <= limit) {
      val acc = new ArrayBuffer[ByteBuffer]
      acc.addOne(Frames.mkHeaderFrame(streamId, priority, endHeaders = true, endStream, padding = 0, rawHeaders))

      acc.toSeq
    } else {
      // need to fragment
      val acc = new ArrayBuffer[ByteBuffer]

      val headersBuf = takeSlice(rawHeaders, limit - headersPrioritySize)
      acc += Frames.mkHeaderFrame(streamId, priority, endHeaders = false, endStream, padding = 0, headersBuf)

      while (rawHeaders.hasRemaining) {
        val size = math.min(limit, rawHeaders.remaining)
        val continueBuf = takeSlice(rawHeaders, size)
        val endHeaders = !rawHeaders.hasRemaining
        acc += Frames.mkContinuationFrame(streamId, endHeaders, continueBuf)
      }
      acc.toSeq
    }
  }

  def processIncoming(leftOver: Chunk[Byte]): ZIO[Env, Throwable, Unit] = {
    ZIO.logTrace(s"Http2Connection.processIncoming() leftOver= ${leftOver.size}") *>
      Http2Connection
        .makePacketStream(ch, HTTP2_KEEP_ALIVE_MS, leftOver)
        .foreach(packet => { packet_handler(httpReq11, packet) })
    // .compile
    // .drain
  }.catchAll {
    case e @ ErrorGen(streamId, code, name) =>
      ZIO.logError(s"Http2Connnection.processIncoming() ${e.code} ${name}") *>
        sendFrame(Frames.mkGoAwayFrame(streamId, code, name.getBytes)).unit
    case e @ _ => {
      ZIO.logError(e.toString()) // >> */IO.raiseError(e)
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
                _ <- ZIO
                  .fail(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "RST_STREAM: FRAME_SIZE_ERROR"))
                  .when(len != 4)
                o_s <- ZIO.attempt(this.streamTbl.get(streamId))
                _ <- ZIO.logInfo(s"Reset Stream $streamId present=${o_s.isDefined} code=$code")
                _ <- ZIO
                  .fail(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      s"Reset Stream $streamId present=${o_s.isDefined} code=$code"
                    )
                  )
                  .when(o_s.isEmpty)

                _ <- markEndOfStream(streamId)

                // _ <- updateStreamWith(100, streamId, c => c.done.complete(()).void).whenA(o_s.isDefined)
                // _ <- closeStream(streamId).whenA(o_s.isDefined)
                // just abort wait and go with norlmal close()
                // _ <- updateStreamWith(78, streamId, c => c.done.complete(()).void).whenA(o_s.isDefined)
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
                  _ <- ZIO
                    .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "streamId is 0 for HEADER"))
                    .when(streamId == 0)
                  _ <- ZIO
                    .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, ""))
                    .when(lastStreamId != 0 && lastStreamId > streamId)
                  _ <- ZIO.succeed { lastStreamId = streamId }

                  _ <- priority match {
                    case Some(t3) =>
                      ZIO
                        .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "HEADERS frame depends on itself"))
                        .when(t3._1 == streamId)
                    case None => ZIO.unit
                  }

                  // total <- IO(this.streamTbl.size)
                  total <- ZIO.attempt(this.concurrentStreams.get())

                  _ <- ZIO
                    .fail(
                      ErrorGen(
                        streamId,
                        Error.PROTOCOL_ERROR,
                        "MAX_CONCURRENT_STREAMS exceeded, with total streams = " + total
                      )
                    )
                    .when(total >= settings.MAX_CONCURRENT_STREAMS)

                  o_s <- ZIO.attempt(this.streamTbl.get(streamId))
                  _ <- o_s match {
                    case Some(s) =>
                      ZIO.fail(ErrorGen(streamId, Error.STREAM_CLOSED, "STREAM_CLOSED")).when(s.endFlag)
                    case None => ZIO.unit
                  }
                  trailing <- doStreamHeaders(streamId, flags)
                  _ <- ZIO.logDebug(s"trailing headers: $trailing").when(trailing == true)
                  // currently cannot do trailing without END_STREAM ( no continuation for trailing, seems this is stated in RFC, spec test requires it)
                  _ <- ZIO
                    .fail(
                      ErrorGen(streamId, Error.INTERNAL_ERROR, "Second HEADERS frame without the END_STREAM flag")
                    )
                    .when(((flags & Flags.END_STREAM) == 0) && trailing)

                  _ <- accumHeaders(streamId, buffer).when(trailing == false)
                  _ <- accumTrailingHeaders(streamId, buffer).when(trailing == true)

                  _ <- markEndOfStream(streamId).when((flags & Flags.END_STREAM) != 0)
                  _ <- markEndOfHeaders(streamId).when((flags & Flags.END_HEADERS) != 0)

                  // if no body reset trailing headers to empty
                  _ <- setEmptyTrailingHeaders(streamId).when(((flags & Flags.END_STREAM) != 0) && trailing == false)

                  _ <- triggerStream(streamId).when(((flags & Flags.END_HEADERS) != 0) && (trailing == false))

                  _ <- finalizeTrailingHeaders(streamId).when((flags & Flags.END_HEADERS) != 0 && trailing == true)

                  _ <- ZIO
                    .succeed { headerStreamId = 0 }
                    .when((flags & Flags.END_HEADERS) != 0) // ready to tak new stream

                } yield ()
              }

            case FrameTypes.CONTINUATION =>
              // TODO: CONTINUATION for trailing headers not supported yet.
              for {
                b1 <- haveHeadersEnded(streamId)
                _ <- ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "END HEADERS")).when(b1)
                _ <- markEndOfHeaders(streamId).when((flags & Flags.END_HEADERS) != 0)
                _ <- markEndOfStream(streamId).when((flags & Flags.END_STREAM) != 0)
                _ <- accumHeaders(streamId, buffer)
                _ <- triggerStream(streamId).when((flags & Flags.END_HEADERS) != 0)

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

                t1: Long <- ZIO.succeed(packet.size.toLong - padLen - Constants.HeaderSize - padByte)
                t2: Long <- ZIO.succeed(len.toLong - padByte - padLen)
                _ <- ZIO
                  .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "DATA frame with invalid pad length"))
                  .when(t1 != t2)

                _ <- ZIO
                  .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "******CON or HEADERS nit finished"))
                  .when(headersEnded == false)

                b <- hasEnded(streamId)
                _ <- ZIO.fail(ErrorGen(streamId, Error.STREAM_CLOSED, "STREAM_CLOSED")).when(b)
                // streams ends with data, no trailing headers for sure, reset to empty
                _ <- setEmptyTrailingHeaders(streamId).when(((flags & Flags.END_STREAM) != 0))
                _ <- accumData(streamId, packet0, len)
                _ <- markEndOfStreamWithData(streamId).when((flags & Flags.END_STREAM) != 0)
              } yield ()

            case FrameTypes.WINDOW_UPDATE => {
              val increment = buffer.getInt() & Masks.INT31
              ZIO.logDebug(s"WINDOW_UPDATE $increment $streamId") *> this
                .updateWindow(streamId, increment)
                .catchAll {
                  case e @ ErrorRst(streamId, code, name) =>
                    ZIO.logError("Reset frane") *> sendFrame(Frames.mkRstStreamFrame(streamId, code))
                  case e @ _ => ZIO.fail(e)
                }
            }

            case FrameTypes.PING =>
              var data = new Array[Byte](8)
              buffer.get(data)
              if ((flags & Flags.ACK) == 0) {
                for {
                  _ <- ZIO
                    .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Ping streamId not 0"))
                    .when(streamId != 0)
                  _ <- sendFrame(Frames.mkPingFrame(ack = true, data))
                } yield ()
              } else ZIO.unit // else if (this.start)

            case FrameTypes.GOAWAY =>
              ZIO
                .fail(
                  ErrorGen(streamId, Error.PROTOCOL_ERROR, "GOAWAY frame with a stream identifier other than 0x0")
                )
                .when(streamId != 0) *>
                ZIO
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
                _ <- ZIO
                  .fail(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      "PRIORITY frame to another stream while sending the headers blocks"
                    )
                  )
                  .when(headerStreamId != 0)
                _ <- ZIO
                  .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "PRIORITY frame with 0x0 stream identifier"))
                  .when(streamId == 0)
                _ <- ZIO
                  .fail(
                    ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "PRIORITY frame with a length other than 5 octets")
                  )
                  .when(len != 5)
                _ <- ZIO
                  .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "PRIORITY frame depends on itself"))
                  .when(streamId == dependentId)
              } yield ()

            /* When the value of SETTINGS_INITIAL_WINDOW_SIZE changes, a receiver MUST adjust
          the size of all stream flow-control windows that it maintains by the
           difference between the new value and the old value.
             */

            case FrameTypes.PUSH_PROMISE =>
              ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "PUSH_PROMISE frame"))

            case FrameTypes.SETTINGS =>
              (for {
                _ <- ZIO
                  .fail(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      "ends a SETTINGS frame with a length other than a multiple of 6 octets"
                    )
                  )
                  .when(len % 6 != 0)

                _ <- ZIO
                  .fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "SETTINGS frame with ACK flag and payload"))
                  .when(len > 0 && Flags.ACK(flags))

                _ <- ZIO
                  .fail(
                    ErrorGen(streamId, Error.PROTOCOL_ERROR, "SETTINGS frame with a stream identifier other than 0x0")
                  )
                  .when(streamId != 0)

                _ <-
                  if (Flags.ACK(flags) == false) {
                    for {
                      res <- ZIO.attempt(Http2Settings.fromSettingsArray(buffer, len)).onError { _ =>
                        sendFrame(Frames.mkPingFrame(ack = true, Array.fill[Byte](8)(0x0)))
                      }

                      _ <- ZIO
                        .fail(
                          ErrorGen(
                            streamId,
                            Error.PROTOCOL_ERROR,
                            "SETTINGS_MAX_FRAME_SIZE (0x5): Sends the value below the initial value"
                          )
                        )
                        .when(settings_done == true && res.MAX_FRAME_SIZE < settings_client.MAX_FRAME_SIZE)

                      _ <- ZIO
                        .fail(
                          ErrorGen(
                            streamId,
                            Error.PROTOCOL_ERROR,
                            "SETTINGS_MAX_FRAME_SIZE (0x5): Sends the value above the maximum allowed frame size"
                          )
                        )
                        .when(res.MAX_FRAME_SIZE > 0xffffff)

                      _ <- ZIO
                        .fail(
                          ErrorGen(
                            streamId,
                            Error.PROTOCOL_ERROR,
                            "SETTINGS_ENABLE_PUSH (0x2): Sends the value other than 0 or 1"
                          )
                        )
                        .when(res.ENABLE_PUSH != 1 && res.ENABLE_PUSH != 0)

                      _ <- ZIO
                        .fail(
                          ErrorGen(
                            streamId,
                            Error.FLOW_CONTROL_ERROR,
                            "SETTINGS_INITIAL_WINDOW_SIZE (0x4): Sends the value above the maximum flow control window size"
                          )
                        )
                        .when((res.INITIAL_WINDOW_SIZE & Masks.INT32) > Integer.MAX_VALUE)

                      ws <- ZIO.succeed(this.settings_client.INITIAL_WINDOW_SIZE)
                      _ <- ZIO.attempt(Http2Settings.copy(this.settings_client, res))

                      _ <- upddateInitialWindowSizeAllStreams(ws, res.INITIAL_WINDOW_SIZE)
                      _ <- ZIO.succeed(this.settings.MAX_CONCURRENT_STREAMS = this.MAX_CONCURRENT_STREAMS)
                      _ <- ZIO.succeed(this.settings.INITIAL_WINDOW_SIZE = this.INITIAL_WINDOW_SIZE)

                      _ <- ZIO.logDebug(s"Remote INITIAL_WINDOW_SIZE ${this.settings_client.INITIAL_WINDOW_SIZE}")
                      _ <- ZIO.logDebug(s"Server INITIAL_WINDOW_SIZE ${this.settings.INITIAL_WINDOW_SIZE}")

                      _ <- sendFrame(Frames.makeSettingsFrame(ack = false, this.settings)).when(settings_done == false)
                      _ <- sendFrame(Frames.makeSettingsAckFrame())

                      // re-adjust inbound window if exceeds default
                      _ <- this.globalInboundWindow.set(INITIAL_WINDOW_SIZE)
                      _ <-
                        if (INITIAL_WINDOW_SIZE > 65535) {
                          sendFrame(Frames.mkWindowUpdateFrame(streamId, INITIAL_WINDOW_SIZE - 65535))
                        } else ZIO.unit
                      _ <- ZIO
                        .logDebug(s"Send UPDATE WINDOW global: ${INITIAL_WINDOW_SIZE - 65535}")
                        .when(INITIAL_WINDOW_SIZE > 65535L)

                      _ <- ZIO.succeed {
                        if (settings_done == false) settings_done = true
                      }

                    } yield ()
                  } else
                    (ZIO.attempt { start = false } *> http11request.get.flatMap {
                      case Some(x) => {
                        val stream = x.stream
                        val th = x.trailingHeaders
                        val h = x.headers.drop("connection")
                        this.openStream11(1, Request(id, 1, h, stream, th))
                      }
                      case None => ZIO.unit
                    }).when(start)

              } yield ()).catchAll {
                case _: scala.MatchError => ZIO.logDebug("Settings match error") *> ZIO.unit
                case e @ _               => ZIO.fail(e)
              }
            case _ =>
              for {
                _ <- ZIO
                  .fail(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      "Sends an unknown extension frame in the middle of a header block"
                    )
                  )
                  .when(headerStreamId != 0)

              } yield ()

          }
        }
    } yield ()
  }

}
