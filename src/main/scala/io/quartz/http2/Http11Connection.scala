package io.quartz.http2
import io.quartz.http2.model.{Headers, Method, ContentType, StatusCode, Request, Response}
import io.quartz.netio.IOChannel
import io.quartz.util.ResponseWriters11
import io.quartz.util.Chunked11
import io.quartz.http2.Constants.ErrorGen
import io.quartz.http2.Constants.Error
import io.quartz.http2.routes.HttpRoute
import io.quartz.util.Utils

import zio.{ZIO, Task, Chunk, Promise, Ref}
import zio.stream.ZStream

case class HeaderSizeLimitExceeded(msg: String) extends Exception(msg)
case class BadIncomingData(msg: String) extends Exception(msg)

object Http11Connection {

  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100 // 104 MB

  def make[Env](ch: IOChannel, id: Long, keepAliveMs: Int, httpRoute: HttpRoute[Env]) = for {
    streamIdRef <- Ref.make(1)
    _ <- ZIO.succeed(ch.timeOutMs(keepAliveMs))
    c <- ZIO.attempt(new Http11Connection(ch, id, streamIdRef, httpRoute))
  } yield (c)
}

class Http11Connection[Env](ch: IOChannel, val id: Long, streamIdRef: Ref[Int], httpRoute: HttpRoute[Env]) {

  def shutdown: Task[Unit] =
    ZIO.logDebug("Http11Connection.shutdown")

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
            r0 <- Utils.splitHeadersAndBody(ch, Chunk.empty[Byte])
            headers_bytes = r0._1
            leftover_bytes = r0._2
            v1 <- Utils.getHttpHeaderAndLeftover(headers_bytes, ch.secure())
          } yield (v1._1, leftover_bytes)

      headers_received = v._1
      leftover = v._2

      headers <- ZIO.attempt(translateHeadersFrom11to2(headers_received))

      _ <- refStart.set(false)

      validate <- ZIO.when(headers.validatePseudoHeaders == false)(
        ZIO.fail(new BadIncomingData("bad inbound data or invalid request"))
      )

      isWebSocket <- ZIO.succeed(
        headers
          .get("upgrade")
          .map(_.equalsIgnoreCase("websocket"))
          .collect { case true => true }
          .getOrElse(false)
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
        else if (isWebSocket) ZIO.succeed(body_stream)
        else
          ZIO.attempt(
            body_stream
              .take(contentLenL)
          )

      emptyTH <- Promise.make[Throwable, Headers] // no trailing headers for 1.1
      _ <- emptyTH.succeed(Headers()) // complete with empty

      http11request <- ZIO.attempt(
        Request(
          id,
          streamId,
          headers,
          stream,
          ch.secure(),
          ch.sniServerNames(),
          if (isWebSocket) Some(ch) else None,
          emptyTH
        )
      )
      // _ <- ZIO.when(isWebSocket == false)(route2(streamId, http11request, isChunked))
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

    _ <- ZIO.when(request.isWebSocket == true)(ZIO.logInfo( s"HTTP/1.1 connId=$id streamId=$streamId Upgraded WebSocket Connection has ended"))
    _ <- ZIO.when(request.isWebSocket == false)(response_o match {
      case Some(response) =>
        for {
          _ <- ResponseWriters11.writeFullResponseFromStreamChunked(
            ch,
            response.hdr(("Transfer-Encoding" -> "chunked"))
          )
          _ <- ZIO.logInfo(
            s"HTTP/1.1 connId=$id streamId=$streamId ${request.method.name} ${request.path} chunked=$requestChunked ${response.code.toString()}"
          )
        } yield ()
      case None =>
        ZIO.logError(
          s"HTTP/1.1 connId=$id streamId=$streamId ${request.method.name} ${request.path} ${StatusCode.NotFound.toString()}"
        ) *> ResponseWriters11.writeNoBodyResponse(ch, StatusCode.NotFound, false)
    })

  } yield ()

}
