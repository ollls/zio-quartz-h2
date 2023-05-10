package io.quartz.websocket

import zio.{Chunk, ZIO}

import java.security.MessageDigest
import java.util.Base64
import java.nio.ByteBuffer
import zio.stream.ZStream

import io.quartz.http2.model.{Response, Request, Headers}

object Websocket {

  val WS_PACKET_SZ = 32768

  private val magicString =
    "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes("US-ASCII")
  def apply(isClient: Boolean = false, idleTimeout: Int = 0) = new Websocket(isClient, idleTimeout)

}

class Websocket(isClient: Boolean, idleTimeout: Int) {

  final val CRLF = "\r\n"
  var isClosed = true

  // private val IN_J_BUFFER = java.nio.ByteBuffer.allocate(0xffff * 2) // 64KB * 2

  private val frames = new FrameTranscoder(isClient)

  def closeReply(req: Request) = {
    val T = frames.frameToBuffer(WebSocketFrame.Close())
    val chunks = Chunk.fromArray(T(0).array()) ++ Chunk.fromArray(T(1).array())
    req.channel.get.write(ByteBuffer.wrap(chunks.toArray))
  }

  def pongReply(req: Request, data: Chunk[Byte] = Chunk.empty) = {
    val T = frames.frameToBuffer(WebSocketFrame.Pong(data))
    req.channel.get.write(ByteBuffer.wrap(T(0).array()))
  }

  def pingReply(req: Request, data: Chunk[Byte] = Chunk.empty) = {
    val T = frames.frameToBuffer(WebSocketFrame.Ping(data))
    req.channel.get.write(ByteBuffer.wrap(T(0).array()))
  }

  def writeBinary(req: Request, data: Chunk[Byte], last: Boolean = true) = {
    val frame = WebSocketFrame.Binary(data, last)
    writeFrame(req, frame)
  }

  def writeText(req: Request, str: String, last: Boolean = true) = {
    val frame = WebSocketFrame.Text(str, last)
    writeFrame(req, frame)
  }

  /////////////////////////////////////////////////////////////////////
  private def genWsResponse(resp: Response): String = {
    val r = new StringBuilder
    r ++= "HTTP/1.1 101 Switching Protocols" + CRLF
    resp.headers.foreach { case (key, value) => r ++= key + ": " + value + CRLF }
    r ++= CRLF
    val T = r.toString()
    T
  }

  private def genAcceptKey(str: String): String = {
    val crypt = MessageDigest.getInstance("SHA-1")
    crypt.reset()
    crypt.update(str.getBytes("US-ASCII"))
    crypt.update(Websocket.magicString)
    val bytes = crypt.digest()
    Base64.getEncoder.encodeToString(bytes)
  }

  // Not used, websocket client support not implemented yet
  def startClientHadshake(host: String) = {
    val key = {
      val bytes = new Array[Byte](16)
      scala.util.Random.nextBytes(bytes)
      Base64.getEncoder.encodeToString(bytes)
    }

    Response
      .Ok()
      .hdr("host" -> host)
      .hdr("upgrade" -> "websocket")
      .hdr("connection" -> "Upgrade")
      .hdr("sec-websocket-version" -> "13")
      .hdr("sec-websocket-key" -> key)

  }

  private def serverHandshake(req: Request) = {
    val result = for {
      uval <- req.headers.get("upgrade")
      cval <- req.headers.get("connection")
      aval <- req.headers.get("sec-websocket-version")
      kval <- req.headers.get("sec-websocket-key")
      _ <- Option(
        uval.equalsIgnoreCase("websocket") &&
          cval.toLowerCase().contains("upgrade") &&
          aval.equalsIgnoreCase("13") &&
          Base64.getDecoder.decode(kval).length == 16
      ).collect { case true => true } // convert false to None
      rspKey <- Some(genAcceptKey(kval))

    } yield (rspKey)

    val zresp = result.map(key => {
      Response.Ok().hdr("upgrade" -> "websocket").hdr("connection" -> "upgrade").hdr("sec-websocket-accept" -> key)
    }) match {
      case None =>
        Left(
          new Exception(
            "Invalid websocket upgrade request: " +
              "websocket headers or version is invalid"
          )
        )
      case Some(v) => Right(v)
    }
    zresp
  }

  def writeFrame(req: Request, frame: WebSocketFrame) = {
    def processArray(ab: Array[ByteBuffer], i: Int): ZIO[Any, Throwable, Int] =
      if (i < ab.length)
        req.channel.get.write(ByteBuffer.wrap(ab(i).array)) *> processArray(ab, i + 1)
      else ZIO.succeed(0)

    for {
      array <- ZIO.attempt(frames.frameToBuffer(frame))
      _ <- processArray(array, 0)

    } yield ()
  }

  def readFrame(req: Request): ZIO[Any, Exception, WebSocketFrame] = {
    val T = for {
      // _     <- Channel.readBuffer(req.ch, IN_J_BUFFER)
      chunk <- req.channel.get.read(idleTimeout)
      bbuf <- ZIO.attempt(ByteBuffer.wrap(chunk.toArray))
      frame <- ZIO.attempt(frames.bufferToFrame(bbuf))
      _ <-
        if (frame.opcode == WebSocketFrame.PING) pongReply(req)
        else ZIO.unit
    } yield (frame)

    (T.repeatWhile(_.opcode == WebSocketFrame.PING)).refineToOrDie[Exception]
  }

  def accept(req: Request): ZIO[Any, Exception, Unit] = {
    val T = for {
      res <- ZIO.attempt(serverHandshake(req))
      _ <- res match {
        case Right(response) =>
          req.channel.get
            .remoteAddress()
            .flatMap(adr =>
              ZIO.logDebug(
                "Webocket request initiated from: " + adr.asInstanceOf[java.net.InetSocketAddress].getHostString()
              )
            ) *> req.channel.get.write(ByteBuffer.wrap(genWsResponse(response).getBytes())) *> ZIO.succeed {
            isClosed = false
          }
        case Left(exception) => ZIO.fail(exception)
      }

    } yield ()

    T.refineToOrDie[Exception]
  }

  private def doPingPong(req: Request, f0: WebSocketFrame) =
    f0 match {
      case WebSocketFrame.Ping(data) => pongReply(req, data)
      case _                         => ZIO.succeed(0)
    }

  def receiveTextAsStream(req: Request) = {
    req.channel.get.timeOutMs(idleTimeout)
    val stream = req.stream
    val s0 = stream
      .via(FramePipeline.make)
      .tap(doPingPong(req, _))
      .filter(_.opcode != WebSocketFrame.PING)
      .tap(f => if (f.opcode == WebSocketFrame.CLOSE) ZIO.succeed(this.isClosed = true) else ZIO.unit)
      .takeUntil(_.opcode == WebSocketFrame.CLOSE)
      .filter(_.opcode != WebSocketFrame.CLOSE)

    s0
  }

  def receiveBinaryAsStream(req: Request) = {
    req.channel.get.timeOutMs(idleTimeout)
    val stream = req.stream
    val s0 = stream
      .via(FramePipeline.make)
      .tap(doPingPong(req, _))
      .filter(_.opcode != WebSocketFrame.PING)
      .tap(f => if (f.opcode == WebSocketFrame.CLOSE) ZIO.succeed(this.isClosed = true) else ZIO.unit)
      .takeUntil(_.opcode == WebSocketFrame.CLOSE)
      .filter(_.opcode != WebSocketFrame.CLOSE)
    s0
  }

  def sendOneString(req: Request, data: String): ZIO[Any, Throwable, Unit] = {
    val s0 = ZStream(data).map(WebSocketFrame.Text(_, true)) // one and last packet
    s0.foreach(frame => writeFrame(req, frame))
  }

  def sendOneBinary(req: Request, data: Chunk[Byte]): ZIO[Any, Throwable, Unit] = {
    val s0 = ZStream(data).map(WebSocketFrame.Binary(_, true))
    s0.foreach(frame => writeFrame(req, frame))
  }

  def sendAsTextStream(req: Request, stream: ZStream[Any, Nothing, String]): ZIO[Any, Throwable, Unit] = {
    // val stream0 = stream.grouped(Websocket.WS_PACKET_SZ)
    // here we need to use special empty continuation packed marked as last.
    val last = ZStream("").map(c => WebSocketFrame.Continuation(Chunk.fromArray(c.getBytes()), true))
    val first = stream.take(1).map(WebSocketFrame.Text(_, false))
    val middle = stream.drop(1).map(c => WebSocketFrame.Continuation(Chunk.fromArray(c.getBytes()), false))

    val result: ZStream[Any, Nothing, WebSocketFrame] = first ++ middle ++ last
    result.foreach(frame => writeFrame(req, frame))
  }

  def sendAsBinaryStream(req: Request, stream: ZStream[Any, Nothing, Chunk[Byte]]): ZIO[Any, Throwable, Unit] = {
    val stream0 = stream.grouped(Websocket.WS_PACKET_SZ)
    val last = ZStream(WebSocketFrame.Continuation(Chunk[Byte](), true))
    val first = stream.take(1).map(WebSocketFrame.Binary(_, false))
    val middle = stream.drop(1).map(c => WebSocketFrame.Continuation(c, false))

    val result: ZStream[Any, Nothing, WebSocketFrame] = first ++ middle ++ last
    result.foreach(frame => writeFrame(req, frame))
  }

}
