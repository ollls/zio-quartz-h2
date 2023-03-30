package io.quartz.http2
import io.quartz.http2.model.{Headers, Method, ContentType, StatusCode, Request, Response}
import io.quartz.netio.IOChannel
import io.quartz.util.ResponseWriters11
import io.quartz.util.Chunked11
import io.quartz.http2.Constants.ErrorGen
import io.quartz.http2.Constants.Error
import io.quartz.http2.routes.HttpRoute

import zio.{ZIO, Task, Chunk, Promise, Ref}
import zio.stream.ZStream

//TODO streamID increment, while keepalive
//TODO headertranslation 11 -> 2, 2 -> 11
//TODO better fallback loggging

object Http11Connection {

  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100 // 104 MB

  def make[Env](ch: IOChannel, id: Long, keepAliveMs: Int, httpRoute: HttpRoute[Env]) = for {
    _ <- ZIO.succeed(ch.timeOutMs(keepAliveMs))
    c <- ZIO.attempt(new Http11Connection(ch, id, httpRoute))
  } yield (c)
}

class Http11Connection[Env](ch: IOChannel, val id: Long, httpRoute: HttpRoute[Env]) {
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

  def translateHeadersFrom2to11(headers: Headers): Headers = {
      val map = headers.tbl.map((k, v) => {
      val k1 = k.toLowerCase() match {
        case ":authority"    => "host"
        case k: String => k
      }
      (k1, v)
    })
    new Headers(map)

  }

  private[this] def testWithStatePos(byte: Byte, pattern: Array[Byte], statusPos: Int): Int = {
    if (byte == pattern(statusPos)) statusPos + 1
    else 0
  }

  private[this] def chunkSearchFor2CR(chunk: Chunk[Byte]) = {
    val pattern = "\r\n\r\n".getBytes()

    var cntr = 0;
    var stop = false;
    var statusPos = 0; // state shows how many bytes matched in pattern

    while (!stop)
    {
      if (cntr < chunk.size) {
        val b = chunk.byte(cntr)
        cntr += 1
        statusPos = testWithStatePos(b, pattern, statusPos)
        if (statusPos == pattern.length) stop = true

      } else { stop = true; cntr = 0 }
    }

    cntr

  }

  private[this] def splitHeadersAndBody(c: IOChannel, chunk: Chunk[Byte]): Task[(Chunk[Byte], Chunk[Byte])] = {
    val split = chunkSearchFor2CR(chunk)
    if (split > 0) ZIO.attempt(chunk.splitAt(split))
    else
      for {
        next_chunk <- c.read()
        result <- splitHeadersAndBody(c, chunk ++ next_chunk)
      } yield (result)

  }

  def processIncoming(headers0: Headers, leftOver: Chunk[Byte], refStart: Ref[Boolean]): ZIO[Env, Throwable, Unit] = {

    val header_pair = raw"(.{2,100}):\s+(.+)".r
    val http_line = raw"([A-Z]{3,8})\s+(.+)\s+(HTTP/.+)".r

    for {
      start <- refStart.get
      v <- if (start) ZIO.attempt((headers0, leftOver)) else splitHeadersAndBody(ch, Chunk.empty[Byte])
      header_bytes = v._1
      leftover = v._2

      headers <- ZIO.attempt(translateHeadersFrom11to2(headers0))

      _ <- refStart.set(false)

      /* TODO VALIDATION ?*/

      body_stream = (ZStream(leftOver) ++ ZStream.repeatZIO(ch.read())).flatMap(ZStream.fromChunk(_))

      isChunked <- ZIO.succeed(headers.getMval("transfer-encoding").exists(_.equalsIgnoreCase("chunked")))
      isContinue <- ZIO.succeed(headers.get("Expect").getOrElse("").equalsIgnoreCase("100-continue"))
      _ <- ZIO.when(isChunked && isContinue)(
        ResponseWriters11.writeNoBodyResponse(ch, StatusCode.Continue, false)
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

      emptyTH <- Promise.make[Throwable, Headers] // no trailing headers for 1.1
      _ <- emptyTH.succeed(Headers()) // complete with empty

      http11request <- ZIO.attempt(Request(id, 1, headers, stream, emptyTH))

      _ <- route2(1, http11request)

    } yield ()
  }

  def route2(streamId: Int, request: Request): ZIO[Env, Throwable, Unit] = for {
    _ <- ZIO.logTrace(
      s"HTTP/1.1 streamId = $streamId ${request.method.name} ${request.path} "
    )
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
          _ <- ResponseWriters11.writeFullResponseFromStreamChunked(
            ch, response.hdr(("Transfer-Encoding" -> "chunked"))
          )
          _ <- ZIO.logDebug(
            s"HTTP/1.1 chunked streamId = $streamId ${request.method.name} ${request.path} ${response.code.toString()}"
          )
        } yield ()
      case None =>
        ZIO.logDebug(
          s"HTTP/1.1 streamId = $streamId ${request.method.name} ${request.path} ${StatusCode.NotFound.toString()}"
        ) *> ResponseWriters11.writeNoBodyResponse(ch, StatusCode.NotFound, false)
    }

  } yield ()

}
