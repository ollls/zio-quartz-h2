import zio.test._
import zio.Console
import zio.{ZIO, Clock}
import zio.durationInt
import zio.stream.{ZStream, ZSink}
import zio.test.TestAspect._
import zio.test.Assertion._
import zio.logging.backend.SLF4J

import io.quartz.QuartzH2Server
import io.quartz.QuartzH2Client
import io.quartz.http2.routes.HttpRouteIO
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2._

object HelloWorldSpec extends ZIOSpecDefault {

  val PORT = 11443
  val FOLDER_PATH = "/Users/ostrygun/web_root/"
  val BIG_FILE = "img_0278.jpeg"
  val BLOCK_SIZE = 1024 * 14

  val R: HttpRouteIO[Any] = {
    case req @ GET -> Root =>
      for {
        x <- req.stream.runCount
      } yield (Response.Ok().asText(s"OK bytes received: $x"))

    case GET -> Root / StringVar(file) =>
      val FILE = s"$file"
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
        present <- ZIO.attempt(jpath.exists())
        _ <- ZIO.fail(new java.io.FileNotFoundException(jpath.toString())).when(present == false)
      } yield (Response
        .Ok()
        .asStream(ZStream.fromFile(jpath, BLOCK_SIZE))
        .contentType(ContentType.contentTypeFromFileName(FILE)))
  }
  ////////////////////////////////////////////////////////////////////
  def spec =
    suite("ZIO-QUARTZ-H2 tests")(
      test("Parallel retrieval with GET: client<-server") {
        for {
          ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
          server <- ZIO.attempt(new QuartzH2Server("localhost", PORT.toInt, 16000, ctx))
          fib <- (server.startIO(R, sync = false)).fork
          _ <- live(Clock.sleep(2000.milli))
          c <- QuartzH2Client.open(s"https://localhost:$PORT", 1000, ctx)
          program = c.doGet("/" + BIG_FILE).flatMap(_.stream.runCount)
          list <- ZIO.attempt(Array.fill(30)(program))
          r <- ZIO.collectAllPar(list)
          _ <- ZIO.foreach(r)(totalBytes => ZIO.logInfo(s" $totalBytes received"))
          _ <- server.shutdown
          _ <- fib.join
        } yield (assertTrue(r.length == 30))
      }
    ).provide(zio.Runtime.removeDefaultLoggers ++ SLF4J.slf4j)
}
