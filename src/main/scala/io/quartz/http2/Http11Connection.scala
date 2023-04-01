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

case class HeaderSizeLimitExceeded(msg: String) extends Exception(msg)
case class BadIncomingData(msg: String) extends Exception(msg)

object Http11Connection {

  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100 // 104 MB

  def make[Env](ch: IOChannel, id: Long, keepAliveMs: Int, httpRoute: HttpRoute[Env]) = for {
    streamIdRef <- Ref.make(0)
    _ <- ZIO.succeed(ch.timeOutMs(keepAliveMs))
    c <- ZIO.attempt(new Http11Connection(ch, id, streamIdRef, httpRoute))
  } yield (c)
}

class Http11Connection[Env](ch: IOChannel, val id: Long, streamIdRef: Ref[Int], httpRoute: HttpRoute[Env]) {

  val header_pair = raw"(.{2,100}):\s+(.+)".r
  val http_line = raw"([A-Z]{3,8})\s+(.+)\s+(HTTP/.+)".r

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

  private def parseHeaderLine(line: String, hdrs: Headers, secure: Boolean): Headers =
    line match {
      case http_line(method, path, _) =>
        hdrs ++ Headers(
          ":method" -> method,
          ":path" -> path,
          ":scheme" -> (if (secure) "https" else "http")
        ) // FIX TBD - no schema for now, ":scheme" -> prot)
      case header_pair(attr, value) => hdrs + (attr.toLowerCase -> value)
      case _                        => hdrs
    }

  private def getHttpHeaderAndLeftover(chunk: Chunk[Byte], secure: Boolean): Task[(Headers, Chunk[Byte])] =
    ZIO.attempt {
      var cur = chunk
      var stop = false
      var complete = false
      var hdrs = Headers()

      while (stop == false) {
        val i = cur.indexWhere(_ == 0x0d)
        if (i < 0) {
          stop = true
        } else {
          val line = cur.take(i)
          hdrs = parseHeaderLine(new String(line.toArray), hdrs, secure)
          cur = cur.drop(i + 2)
          if (line.size == 0) {
            complete = true;
            stop = true;
          }
        }
      }
      // won't use stream to fetch all headers, must be present at once in one bufer read ops.
      if (complete == false)
        ZIO.fail(new HeaderSizeLimitExceeded(""))
      (hdrs, cur)
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

  def processIncoming(
      headers0: Headers,
      leftOver_tls: Chunk[Byte],
      refStart: Ref[Boolean]
  ): ZIO[Env, Throwable, Unit] = {
    for {
      streamId <- streamIdRef.getAndUpdate(_ + 2)
      start <- refStart.get
      v <-
        if (start) ZIO.attempt((headers0, leftOver_tls))
        else
          for {
            r0 <- splitHeadersAndBody(ch, Chunk.empty[Byte])
            headers_bytes = r0._1
            leftover_bytes = r0._2
            v1 <- getHttpHeaderAndLeftover(headers_bytes, ch.secure)
          } yield (v1._1, leftover_bytes)

      headers_received = v._1
      leftover = v._2

      headers <- ZIO.attempt(translateHeadersFrom11to2(headers_received))

      _ <- refStart.set(false)


      validate <- ZIO.when(headers.validatePseudoHeaders == false)(
        ZIO.fail(new BadIncomingData("bad inbound data or invalid request"))
      )

      body_stream = (ZStream(leftover) ++ ZStream.repeatZIO(ch.read())).flatMap(ZStream.fromChunk(_))

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

      http11request <- ZIO.attempt(Request(id, streamId, headers, stream, emptyTH))

      _ <- route2(streamId, http11request, isChunked)

    } yield ()
  }

  def route2(streamId: Int, request: Request, requestChunked: Boolean): ZIO[Env, Throwable, Unit] = for {
    _ <- ZIO.logTrace(
      s"HTTP/1.1 chunked streamId = $streamId ${request.method.name} ${request.path} "
    )
    _ <- ZIO.logDebug("request.headers: " + request.headers.printHeaders(" | "))

    _ <- ZIO.when(request.headers.tbl.size == 0)(
      ZIO.fail(ErrorGen(streamId, Error.COMPRESSION_ERROR, "http11 empty headers"))
    )

    _ <- ZIO.when(request.headers.ensureLowerCase == false)(
      ZIO.fail(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Upercase letters in the header keys"))
    )

    response_o <- (httpRoute(request)).catchAll {
      case e: (java.io.FileNotFoundException | java.nio.file.NoSuchFileException) =>
        ZIO.logError(e.toString) *> ZIO.succeed(None)
      case e =>
        ZIO.logError(e.toString) *>
          ZIO.succeed(Some(Response.Error(StatusCode.InternalServerError)))
    }

    _ <- response_o match {
      case Some(response) =>
        for {
          _ <- ResponseWriters11.writeFullResponseFromStreamChunked(
            ch,
            response.hdr(("Transfer-Encoding" -> "chunked"))
          )
          _ <- ZIO.logInfo(
            s"HTTP/1.1 streamId = $streamId ${request.method.name} ${request.path} chunked=$requestChunked ${response.code.toString()}"
          )
        } yield ()
      case None =>
        ZIO.logInfo(
          s"HTTP/1.1 streamId = $streamId ${request.method.name} ${request.path} ${StatusCode.NotFound.toString()}"
        ) *> ResponseWriters11.writeNoBodyResponse(ch, StatusCode.NotFound, false)
    }

  } yield ()

}
