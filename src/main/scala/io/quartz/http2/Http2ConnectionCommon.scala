package io.quartz.http2

import java.nio.ByteBuffer
import scala.collection.mutable.ArrayBuffer
import zio.{ZIO, Task, Promise, Queue, Chunk, Ref, Semaphore}
import io.quartz.http2.model.{Request, Response, Headers, ContentType, StatusCode}
import io.quartz.http2.Constants._

trait Http2ConnectionCommon(
    val INITIAL_WINDOW_SIZE: Int,
    val globalBytesOfPendingInboundData: Ref[Long],
    val globalInboundWindow: Ref[Long],
    val globalTransmitWindow: Ref[Long],
    val outq: Queue[ByteBuffer],
    val hSem2: Semaphore /*not used */
) {
  def getStream(id: Int): Option[Http2StreamCommon]
  def sendFrame(b: ByteBuffer) = outq.offer(b)

  protected def takeSlice(buf: ByteBuffer, len: Int): ByteBuffer = {
    val head = buf.slice.limit(len)
    buf.position(len)
    head
  }

  /** Generate stream header frames from the provided header sequence
    *
    * If the compressed representation of the headers exceeds the MAX_FRAME_SIZE setting of the peer, it will be broken
    * into a HEADERS frame and a series of CONTINUATION frames.
    */
  //////////////////////////////
  protected def headerFrame(
      streamId: Int,
      settings: Http2Settings,
      priority: Priority,
      endStream: Boolean,
      headerEncoder: HeaderEncoder,
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

  private case class txWindow_SplitDataFrame(buffer: ByteBuffer, dataLen: Int)

  private def splitDataFrames(
      bb: ByteBuffer,
      requiredLen: Long
  ): (txWindow_SplitDataFrame, Option[txWindow_SplitDataFrame]) = {
    val original_bb = bb.slice()
    val len = Frames.getLengthField(bb)
    val frameType = bb.get()
    val flags = bb.get()
    val streamId = Frames.getStreamId(bb)

    val endStream = if ((flags & Flags.END_STREAM) != 0) true else false

    if (requiredLen < len) {
      val buf0 = Array.ofDim[Byte](requiredLen.toInt)
      bb.get(buf0)

      val dataFrame1 = Frames.mkDataFrame(streamId, false, padding = 0, ByteBuffer.wrap(buf0))
      val dataFrame2 = Frames.mkDataFrame(streamId, endStream, padding = 0, bb)

      (
        txWindow_SplitDataFrame(dataFrame1, requiredLen.toInt),
        Some(txWindow_SplitDataFrame(dataFrame2, len - requiredLen.toInt))
      )

    } else (txWindow_SplitDataFrame(original_bb, len), None)
  }

  private def txWindow_Transmit(stream: Http2StreamCommon, bb: ByteBuffer, data_len: Int): Task[Long] = {
    for {
      tx_g <- globalTransmitWindow.get
      tx_l <- stream.transmitWindow.get
      bytesCredit <- ZIO.succeed(Math.min(tx_g, tx_l))

      _ <-
        if (bytesCredit > 0)
          (for {
            rlen <- ZIO.succeed(Math.min(bytesCredit, data_len))
            frames <- ZIO.attempt(splitDataFrames(bb, rlen))
            _ <- sendFrame(frames._1.buffer)
            _ <- globalTransmitWindow.update(_ - rlen)
            _ <- stream.transmitWindow.update(_ - rlen)

            _ <- frames._2 match {
              case Some(f0) =>
                for {
                  b <- stream.outXFlowSync.take
                  _ <- ZIO.when(b == false)(ZIO.fail(new java.lang.InterruptedException()))
                  _ <- txWindow_Transmit(stream, f0.buffer, f0.dataLen)
                } yield ()
              case None => ZIO.unit
            }

          } yield ())
        else for {
          b <- stream.outXFlowSync.take
          _ <- ZIO.when(b == false)(ZIO.fail(new java.lang.InterruptedException()))
          _ <- txWindow_Transmit(stream, bb, data_len)
        } yield()  

    } yield (bytesCredit)
  }

  /** Generate stream data frame(s) for the specified data
    *
    * If the data exceeds the peers MAX_FRAME_SIZE setting, it is fragmented into a series of frames.
    */
  protected def dataFrame(
      sts: Http2Settings,
      streamId: Int,
      endStream: Boolean,
      data: ByteBuffer
  ): scala.collection.immutable.Seq[ByteBuffer] = {
    val limit =
      sts.MAX_FRAME_SIZE - 128

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
      acc.toSeq
    }
  }

  protected def sendDataFrame(streamId: Int, bb: => ByteBuffer): Task[Unit] =
    for {
      t <- ZIO.attempt(Http2Connection.parseFrame(bb))
      len = t._1
      _ <- ZIO.logTrace(s"sendDataFrame() - $len bytes")
      opt_D <- ZIO.attempt(getStream(streamId))
      _ <- opt_D match {
        case Some(ce) =>
          for {
            _ <- txWindow_Transmit(ce, bb, len)
          } yield ()
        case None => ZIO.logError("sendDataFrame lost streamId")
      }
    } yield ()

}
