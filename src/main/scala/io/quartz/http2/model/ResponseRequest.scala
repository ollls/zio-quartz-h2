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

  /** Adds the specified headers to the request headers.
    *
    * @param hdr
    *   the headers to add
    * @return
    *   a new request with the additional headers
    */
  def hdr(hdr: Headers): Request =
    new Request(connId, streamId, headers ++ hdr, this.stream, secure, sniServerNames, this.trailingHeaders)

  /** Adds the specified header pair to the request headers.
    *
    * @param pair
    *   a tuple containing the header name and value
    * @return
    *   a new request with the additional header pair
    */
  def hdr(pair: (String, String)): Request =
    new Request(connId, streamId, headers + pair, this.stream, secure, sniServerNames, this.trailingHeaders)

    /** Returns the path component of the request URI.
      *
      * @return
      *   the path component of the request URI
      */
  def path: String = headers.get(":path").getOrElse("")

  /** Returns the HTTP method used in the request.
    *
    * @return
    *   the HTTP method used in the request
    */
  def method: Method = Method(headers.get(":method").getOrElse(""))

  /** Returns the content length of the request body as a string.
    *
    * @return
    *   the content length of the request body as a string
    */
  def contentLen: String = headers.get("content-length").getOrElse("0")

  /** Returns the URI of the request.
    *
    * @return
    *   the URI of the request
    */
  def uri: URI = new URI(path)

  /** Returns the content type of the request body.
    *
    * @return
    *   the content type of the request body
    */
  def contentType: ContentType = ContentType(headers.get("content-type").getOrElse(""))

  /** Returns `true` if the content type of the request body is JSON.
    *
    * @return
    *   `true` if the content type of the request body is JSON
    */
  def isJSONBody: Boolean = contentType == ContentType.JSON

  /** Returns the transfer encoding used in the request.
    *
    * @return
    *   the transfer encoding used in the request
    */
  def transferEncoding = headers.getMval("transfer-encoding")

  /** Returns the request body as a byte array.
    *
    * @return
    *   the request body as a byte array
    */

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
    stream: ZStream[Any, Throwable, Byte] = Response.EmptyStream
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
