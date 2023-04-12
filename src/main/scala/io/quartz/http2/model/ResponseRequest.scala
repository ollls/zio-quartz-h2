package io.quartz.http2.model

import zio.{ZIO, Task, Promise, Chunk}
import zio.stream.ZStream
import java.net.URI

sealed case class Request(
    connId: Long,
    streamId: Int,
    headers: Headers,
    stream: ZStream[Any, Throwable, Byte],
    secure: Boolean,
    sniServerNames: Option[Array[String]],
    trailingHeaders: Promise[Throwable, Headers]
) {
  def path: String = headers.get(":path").getOrElse("")
  def method: Method = Method(headers.get(":method").getOrElse(""))
  def contentLen: String = headers.get("content-length").getOrElse("0") // keep it string
  def uri: URI = new URI(path)
  def contentType: ContentType = ContentType(headers.get("content-type").getOrElse(""))
  def isJSONBody: Boolean = contentType == ContentType.JSON

  def transferEncoding = headers.getMval("transfer-encoding")

  def body = stream.runCollect.map(chunk => chunk.toArray) // .compile.toVector.map( _.toArray  )
}

object Response {

  val EmptyStream = ZStream.empty

  def Ok(): Response = {
    val h = Headers() + (":status", StatusCode.OK.toString)
    new Response(StatusCode.OK, h)
  }

  def Error(code: StatusCode): Response = {
    new Response(code, Headers() + (":status", code.toString))
  }

}

//Response ///////////////////////////////////////////////////////////////////////////
sealed case class Response(
    code: StatusCode,
    headers: Headers,
    stream: ZStream[Any, Throwable, Byte] = EmptyStream
) {

  def hdr(hdr: Headers): Response = new Response(this.code, this.headers ++ hdr, this.stream)

  def hdr(pair: (String, String)) = new Response(this.code, this.headers + pair, this.stream)

  def cookie(cookie: Cookie) = {
    val pair = ("Set-Cookie" -> cookie.toString())
    new Response(this.code, this.headers + pair, this.stream)
  }

  def asStream(s0: ZStream[Any, Throwable, Byte]) =
    new Response(this.code, this.headers, s0)

  def asText(text: String) = new Response(this.code, this.headers, ZStream.fromChunk(Chunk.fromArray(text.getBytes())))

  /*
  def asTextBody(text: String): Response = {
    val s0 = ZStream(Chunk.fromArray(text.getBytes))
    new Response(this.code, this.headers, s0).contentType(ContentType.Plain)
  }*/

  def contentType(type0: ContentType): Response =
    new Response(this.code, this.headers + ("content-type" -> type0.toString()), this.stream)

  def isChunked: Boolean = transferEncoding().exists(_.equalsIgnoreCase("chunked"))

  def transferEncoding(): List[String] = headers.getMval("transfer-encoding")

  def transferEncoding(vals0: String*): Response =
    new Response(this.code, vals0.foldLeft(this.headers)((h, v) => h + ("transfer-encoding" -> v)), this.stream)

}
