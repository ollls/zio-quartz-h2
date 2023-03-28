package io.quartz.http2
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.netio.IOChannel

import zio.{ZIO, Task, Chunk}
import zio.stream.ZStream

object Http11Connection {
  def make[Env](ch: IOChannel, id: Long) = for {
    c <- ZIO.attempt(new Http11Connection(ch, id))
  } yield (c)
}

class Http11Connection[Env](ch: IOChannel, val id: Long) {
  def shutdown: Task[Unit] =
    ZIO.logInfo("Http11Connection.shutdown")

  def processIncoming(headers: Headers, leftOver: Chunk[Byte]): ZIO[Env, Throwable, Unit] = for {

    _ <- ZIO.debug("*************************************************")
    _ <- ZIO.debug(headers.printHeaders)
    _ <- ZIO.debug(s"leftover size = " + leftOver)

    body_stream = (ZStream(leftOver) ++ ZStream.repeatZIO(ch.read())).flatMap(ZStream.fromChunk(_))

    isChunked <- ZIO.succeed(headers.getMval("transfer-encoding").exists(_.equalsIgnoreCase("chunked")))
    isContinue <- ZIO.succeed(headers.get("Expect").getOrElse("").equalsIgnoreCase("100-continue"))



    _ <- ZIO.debug("*************************************************")

  } yield ()
}
