package example

import zio.{ZIO, Task, Chunk, Promise, ExitCode, ZIOApp, ZLayer}
import zio.ZIOAppDefault
import zio.stream.{ZStream, ZPipeline, ZSink}
import io.quartz.QuartzH2Server
import io.quartz.http2._
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2.routes.Routes
import io.quartz.http2.routes.HttpRouteIO
import io.quartz.http2.routes.WebFilter

import zio.logging.LogFormat
import zio.logging.backend.SLF4J
import zio.LogLevel

import io.quartz.util.MultiPart
import io.quartz.http2.model.StatusCode

object MyApp extends ZIOAppDefault {

  override val bootstrap = zio.Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  val filter: WebFilter = (r: Request) =>
    ZIO
      .succeed(Response.Error(StatusCode.Forbidden).asText("Denied: " + r.uri.getPath()))
      .when(r.uri.getPath().endsWith("test70.jpeg"))

  // val HOME_DIR = "/Users/user000/tmp1/"

  val R: HttpRouteIO[String] = {

    case GET -> Root / "envstr" =>
      for {
        text <- ZIO.environmentWith[String]( str => str.get )
      } yield (Response.Ok().asText( s"$text"))

    case req @ POST -> Root / "mpart" =>
      MultiPart.writeAll(req, "/Users/user000/tmp1/") *> ZIO.succeed(Response.Ok())

    case req @ POST -> Root / "upload" / StringVar(file) =>
      val FOLDER_PATH = "/Users/user000/web_root/"
      val FILE = s"$file"
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
        u <- req.stream.run(ZSink.fromFile(jpath))
      } yield (Response.Ok().asText("OK"))

    // best path for h2spec
    case GET -> Root => ZIO.attempt(Response.Ok().asText("OK"))

    case req @ POST -> Root =>
      for {
        u <- req.stream.runCollect
      } yield (Response.Ok().asText("OK:" + String(u.toArray)))

    // perf tests
    case GET -> Root / "test" => ZIO.attempt(Response.Ok())

    case GET -> Root / "example" =>
      // how to send data in separate H2 packets of various size.
      val ts = ZStream.fromChunks(Chunk.fromArray("Block1\n".getBytes()), Chunk.fromArray("Block22\n".getBytes()))
      ZIO.attempt(Response.Ok().asStream(ts))

    case GET -> Root / StringVar(file) =>
      val FOLDER_PATH = "/Users/user000/web_root/"
      val FILE = s"$file"
      val BLOCK_SIZE = 16000
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
        present <- ZIO.attempt(jpath.exists())
        _ <- ZIO.fail(new java.io.FileNotFoundException( jpath.toString())).when(present == false)
      } yield (Response
        .Ok()
        .asStream(ZStream.fromFile(jpath, BLOCK_SIZE))
        .contentType(ContentType.contentTypeFromFileName(FILE)))

  }

  def run =
    val env = ZLayer.fromZIO( ZIO.succeed( "Hello ZIO World!") )
    (for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server("localhost", 8443, 16000, ctx).startIO(R, filter, sync = false)

    } yield (exitCode)).provideSomeLayer( env )

}
