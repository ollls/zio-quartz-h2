package io.quartz.util

import zio.{ZIO, Chunk}
import zio.stream.{ZChannel, ZPipeline}

object Chunked11 {

  def parseTrail(in: Chunk[Byte], pos: Int): Boolean = {
    if (in.size - pos < 4) return false // more data
    if (in(pos) == '\r' && in(pos + 1) == '\n') {

      var idx = pos + 2
      var bb: Byte = 0
      var pbb: Byte = 0
      var splitAt = 0
      var found = false

      while (idx < in.size && found == false) {
        pbb = bb
        bb = in(idx)
        idx += 1
        if (bb == '\n' && pbb == '\r') {
          splitAt = idx - 2
          found = true
        }
      }

      if (found == false) return false // more data

      // we will ignore trailing block here, but it can be provided, right after empty block for further use.
      // TODO
      val trailingHeaders = new String(in.slice(pos + 2, splitAt).toArray)
      true
    } else throw new Exception("BadInboundDataError")
    true
  }

  def produceChunk(in: Chunk[Byte]): (Option[Chunk[Byte]], Chunk[Byte], Boolean) = {
    var bb: Byte = 0
    var pbb: Byte = 0
    var splitAt: Int = 0
    var idx: Int = 0
    var stop: Boolean = false

    // extract size
    while (idx < in.size && stop == false) {
      pbb = bb
      bb = in(idx)
      if (bb == '\n' && pbb == '\r') {
        splitAt = idx - 2 + 1
        stop = true
      } else {
        idx += 1
      }
    }

    val str_str = new String(in.slice(0, splitAt).toArray)

    val chunkSize =
      try { Integer.parseInt(new String(in.slice(0, splitAt).toArray), 16) }
      catch { case e: NumberFormatException => 0 }

    if (chunkSize > 0 && chunkSize < in.size - idx + 2 + 1) {
      val chunk = in.slice(splitAt + 2, splitAt + 2 + chunkSize)
      val leftOver = in.slice(splitAt + 2 + chunkSize + 2, in.size)

      (Some(chunk), leftOver, false)

    } else {
      if (chunkSize == 0) {
        if (parseTrail(in, splitAt) == true) (None, in, true)
        else (None, in, false) // more data
      } else (None, in, false)
    }

  }

  def produceChunk1(
      in: Chunk[Byte],
      result: Option[ZChannel[Any, Exception, zio.Chunk[Byte], Any, Exception, Chunk[zio.Chunk[Byte]], Any]] = None
  ): (Option[ZChannel[Any, Exception, Chunk[Byte], Any, Exception, Chunk[Chunk[Byte]], Any]], Chunk[Byte], Boolean) = {
    val r = produceChunk(in)

    var chunk_opt = r._1
    val leftover = r._2
    val eof = r._3

    if (chunk_opt.isDefined) {
      val chunk = chunk_opt.get
      produceChunk1(
        leftover,
        if (result.isDefined == false) Some(ZChannel.write(Chunk.single(chunk)))
        else Some(result.get *> ZChannel.write(Chunk.single(chunk)))
      )
    } else (result, leftover, eof)
  }

  def chunkedDecode: zio.stream.ZPipeline[Any, Exception, Byte, Chunk[Byte]] = {

    def chunk_converter(
        leftOver: Chunk[Byte]
    ): ZChannel[Any, Exception, Chunk[Byte], Any, Exception, Chunk[Chunk[Byte]], Any] = {
      val converter: ZChannel[Any, Exception, Chunk[Byte], Any, Exception, Chunk[Chunk[Byte]], Any] = ZChannel.readWith(
        (in: Chunk[Byte]) => {
          val res = produceChunk(leftOver ++ in)

          res match {
            case (Some(chunk), leftover, false) => {
              if (leftover.size == 0) ZChannel.write(Chunk.single(chunk)) *> chunk_converter(leftover)
              else {
                val (channel_opt, leftover1, isEof) = produceChunk1(leftover)
                if (isEof == false) {
                  if (channel_opt.isDefined == false)
                    ZChannel.write(Chunk.single(chunk)) *> chunk_converter(leftover1)
                  else
                    ZChannel.write(Chunk.single(chunk)) *> channel_opt.get *> chunk_converter(leftover1)

                } else {
                  ZChannel.write(Chunk.single(chunk)) *> ZChannel.succeed(true) // r._1 //*> ZChannel.succeed()
                }
              }

            }
            case (None, leftover, false) => { chunk_converter(leftover) } // no chunk yet but data coming
            case _                       => ZChannel.fail(new Exception("chunkedDecode: Unexpected error"))
          }
        },
        (err: Exception) => ZChannel.fail(err),
        (done: Any) => ZChannel.succeed(true)
      )
      converter
    }

    ZPipeline.fromChannel(chunk_converter(Chunk.empty))

  }
}
