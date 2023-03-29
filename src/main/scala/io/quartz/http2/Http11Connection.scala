package io.quartz.http2
import io.quartz.http2.model.{Headers, Method, ContentType, StatusCode, Request, Response}
import io.quartz.netio.IOChannel
import io.quartz.util.ResponseWriters11
import io.quartz.util.Chunked11

import zio.{ZIO, Task, Chunk}
import zio.stream.ZStream

object Http11Connection {

  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100 // 104 MB

  def make[Env](ch: IOChannel, id: Long) = for {
    c <- ZIO.attempt(new Http11Connection(ch, id))
  } yield (c)
}

class Http11Connection[Env](ch: IOChannel, val id: Long) {
  def shutdown: Task[Unit] =
    ZIO.logInfo("Http11Connection.shutdown")

  def translateHeadersFrom11to2(headers: Headers): Headers = {
    val map = headers.tbl.map((k, v) => {
      val k1 = k.toLowerCase() match {
        case "host"    => ":authority"
        case k: String => k
      }
      (k1, v)
    })

    new Headers(map)
  }

  def processIncoming(headers0: Headers, leftOver: Chunk[Byte]): ZIO[Env, Throwable, Unit] = for {

    headers <- ZIO.attempt(translateHeadersFrom11to2(headers0))

    _ <- ZIO.debug("*************************************************")
    _ <- ZIO.debug(headers.printHeaders)
    _ <- ZIO.debug(s"leftover size = " + leftOver)
    _ <- ZIO.debug("*************************************************")

    /* TODO VALIDATION ?*/

    body_stream = (ZStream(leftOver) ++ ZStream.repeatZIO(ch.read())).flatMap(ZStream.fromChunk(_))

    isChunked <- ZIO.succeed(headers.getMval("transfer-encoding").exists(_.equalsIgnoreCase("chunked")))
    isContinue <- ZIO.succeed(headers.get("Expect").getOrElse("").equalsIgnoreCase("100-continue"))
    _ <- ZIO.when(isChunked && isContinue)(
      ResponseWriters11
        .writeNoBodyResponse(ch, StatusCode.Continue, "", false)
    )

    contentLen <- ZIO.succeed(headers.get("content-length").getOrElse("0"))
    contentLenL <- ZIO.fromTry(scala.util.Try(contentLen.toLong)).refineToOrDie[Exception]
    _ <-
      if (contentLenL > Http11Connection.MAX_ALLOWED_CONTENT_LEN) ZIO.fail(new Exception("ContentLenTooBig"))
      else ZIO.unit

    stream <-
      if (isChunked)
        ZIO.attempt(body_stream.via(Chunked11.chunkedDecode).flattenChunks)
      else
        ZIO.attempt(
          body_stream
            .take(contentLenL)
        )

    _ <- ZIO.debug("*************************************************")

  } yield ()
}
