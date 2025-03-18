package example

import zio.{ZIO, Task, Chunk, Promise, ExitCode, ZIOApp, ZLayer}
import zio.ZIOAppDefault
import zio.stream.{ZStream, ZPipeline, ZSink}
import io.quartz.QuartzH2Server
import io.quartz.http2._
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2.routes.Routes
import io.quartz.http2.model.Cookie
import io.quartz.http2.routes.HttpRouteIO
import io.quartz.http2.routes.WebFilter
import ch.qos.logback.classic.Level

import zio.logging.LogFormat
import zio.logging.backend.SLF4J
import zio.LogLevel

import io.quartz.util.MultiPart
import io.quartz.http2.model.StatusCode

object param1 extends QueryParam("param1")
object param2 extends QueryParam("param2")

object MyApp extends ZIOAppDefault {

  override val bootstrap = zio.Runtime.removeDefaultLoggers ++ SLF4J.slf4j ++ zio.Runtime.enableWorkStealing

  val filter: WebFilter[Any] = (request: Request) =>
    ZIO.attempt(
      Either.cond(
        !request.uri.getPath().endsWith("na.txt"),
        request.hdr("test_tid" -> "ABC123Z9292827"),
        Response.Error(StatusCode.Forbidden).asText("Denied: " + request.uri.getPath())
      )
    )

  val R: HttpRouteIO[String] = {
    case req @ GET -> Root / "ldt" =>
      for {
        time <- ZIO.succeed(java.time.LocalDateTime.now())
      } yield (Response.Ok().asText(time.toString()))

    case req @ GET -> "pub" /: remainig_path =>
      ZIO.succeed(Response.Ok().asText(remainig_path.toString()))

    // GET with two parameters
    case req @ GET -> Root / "hello" / "1" / "2" / "user2" :? param1(test) :? param2(test2) =>
      ZIO.succeed(Response.Ok().asText("param1=" + test + "  " + "param2=" + test2))

    // GET with paameter, cookies and custom headers
    case GET -> Root / "hello" / "user" / StringVar(userId) :? param1(par) =>
      val headers = Headers("procid" -> "header_value_from_server", "content-type" -> ContentType.Plain.toString)
      val c1 = Cookie("testCookie1", "ABCD", secure = true)
      val c2 = Cookie("testCookie2", "ABCDEFG", secure = false)
      val c3 =
        Cookie("testCookie3", "1A8BD0FC645E0", secure = false, expires = Some(java.time.ZonedDateTime.now.plusHours(5)))

      ZIO.succeed(
        Response
          .Ok()
          .hdr(headers)
          .cookie(c1)
          .cookie(c2)
          .cookie(c3)
          .asText(s"$userId with para1 $par")
      )

    // GET with simple ZIO environment made as just a String
    case GET -> Root / "envstr" =>
      for {
        text <- ZIO.environmentWith[String](str => str.get)
      } yield (Response.Ok().asText(s"$text"))

    // automatic multi-part upload, file names preserved
    case req @ POST -> Root / "mpart" =>
      MultiPart.writeAll(req, "web_root/") *> ZIO.succeed(Response.Ok())

    case req @ POST -> Root / "upload" / StringVar(file) =>
      val FOLDER_PATH = "web_root/"
      val FILE = s"$file"
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
        u <- req.stream.run(ZSink.fromFile(jpath))
      } yield (Response.Ok().asText("OK"))

    // best path for h2spec
    case req @ GET -> Root =>
      for {
        x <- req.stream.runCount
      } yield (Response.Ok().asText(s"OK bytes received: $x"))

    case req @ POST -> Root =>
      for {
        u <- req.stream.runCollect
      } yield (Response.Ok().asText("OK:" + String(u.toArray)))

    // perf tests
    case req @ GET -> Root / "test2" =>
      ZIO.debug(s"connection id = ${req.connId}/${req.streamId}") *> ZIO.attempt(Response.Ok())

    case req @ GET -> Root / "test" => ZIO.attempt(Response.Ok())

    case req @ GET -> Root / "snihost" =>
      for {
        _ <-
          req.stream.runDrain // properly ignore incoming data, we must flush it, generaly if you sure there will be no data, you can ignore.
        result_text <- ZIO.attempt(req.sniServerNames match {
          case Some(hosts) => s"Host names in TLS SNI extension: ${hosts.mkString(",")}"
          case None        => "No TLS SNI host names provided or unsecure connection"
        })
      } yield (Response.Ok().asText(result_text))

    case "localhost" ! GET -> Root / "example" =>
      // how to send data in separate H2 packets of various size.
      val ts = ZStream.fromChunks(Chunk.fromArray("Block1\n".getBytes()), Chunk.fromArray("Block22\n".getBytes()))
      ZIO.attempt(Response.Ok().asStream(ts))

    case GET -> "doc" /: remainingPath =>
      val FOLDER_PATH = "web_root/doc/"
      val FILE = if (remainingPath.toString.isEmpty) "index.html" else remainingPath.toString
      val BLOCK_SIZE = 1024 * 14
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
        present <- ZIO.attempt(jpath.exists())
        _ <- ZIO.fail(new java.io.FileNotFoundException(jpath.toString())).when(present == false)
      } yield (Response
        .Ok()
        .asStream(ZStream.fromFile(jpath, BLOCK_SIZE))
        .contentType(ContentType.contentTypeFromFileName(FILE)))

    case GET -> Root / StringVar(file) =>
      val FOLDER_PATH = "web_root/"
      val FILE = s"$file"
      val BLOCK_SIZE = 1024 * 14
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
        present <- ZIO.attempt(jpath.exists())
        _ <- ZIO.fail(new java.io.FileNotFoundException(jpath.toString())).when(present == false)
      } yield (Response
        .Ok()
        .asStream(ZStream.fromFile(jpath, BLOCK_SIZE))
        .contentType(ContentType.contentTypeFromFileName(FILE)))
  }

  def onConnect(id: Long) = {
    ZIO.logTrace(s"connected - $id")
  }

  def onDisconnect(id: Long) = {
    ZIO.logTrace(s"disconnected - $id")
  }

  def run = {
    val env = ZLayer.fromZIO(ZIO.succeed("Hello ZIO World!"))
    (for {
      _ <- zio.Console.printLine("****************************************************************************************")
      _ <- zio.Console.printLine("\u001B[31mUse https://localhost:8443/doc/index.html to read the index.html file\u001B[0m")
      _ <- zio.Console.printLine("****************************************************************************************")

      args <- this.getArgs
      _ <- ZIO.when(args.find(_ == "--debug").isDefined)(ZIO.attempt(QuartzH2Server.setLoggingLevel(Level.DEBUG)))
      _ <- ZIO.when(args.find(_ == "--error").isDefined)(ZIO.attempt(QuartzH2Server.setLoggingLevel(Level.ERROR)))
      _ <- ZIO.when(args.find(_ == "--trace").isDefined)(ZIO.attempt(QuartzH2Server.setLoggingLevel(Level.TRACE)))
      _ <- ZIO.when(args.find(_ == "--off").isDefined)(ZIO.attempt(QuartzH2Server.setLoggingLevel(Level.OFF)))

      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server(
        "localhost",
        8443,
        16000,
        ctx, /*2097152,*/
        onConnect = onConnect,
        onDisconnect = onDisconnect
      ).startIO(R, filter, sync = false)

    } yield (exitCode)).provideSomeLayer(env)
  }

}
