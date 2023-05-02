package io.quartz.services

import javax.net.ssl.SSLContext
import io.quartz.QuartzH2Client
import io.quartz.http2.Http2ClientConnection
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.ConcurrentMapHasAsScala

import zio.{ZIO, Task, UIO, ZLayer}

object H2Client {
  val layer = ZLayer(ZIO.attempt(new H2Client))
}

class H2Client {

  private val connectionTbl = new ConcurrentHashMap[Long, Array[Http2ClientConnection]](100).asScala

  /** Opens an HTTP/2 connection to the specified URL host using the given SSL context.
    *
    * @param connId
    *   the ID of the server Http2 connection
    * @param urlHost
    *   the URL host to connect to, e.g., "https://example.com:8443"
    * @param timeoutMs
    *   the timeout for the connection in milliseconds
    * @param ctx
    *   the SSL context to use for the connection
    * @param incomingWindowSize
    *   the size of the incoming flow control window for the connection
    * @return
    *   a ZIO Task that completes when the connection is established
    */
  def openMany(
      connId: Long,
      urlHosts: Array[String],
      timeoutMs: Int,
      ctx: SSLContext,
      incomingWindowSize: Int = 65535
  ): Task[Unit] = for {
    connections <- ZIO.foreach(urlHosts)(url =>
      for {
        _ <- ZIO.logInfo(s"ZIO Service H2Client: $url open for connection Id = $connId")
        c <- QuartzH2Client.open(url, timeoutMs, ctx = ctx, incomingWindowSize)
      } yield (c)
    )
    _ <- ZIO.attempt(connectionTbl.put(connId, connections))

  } yield ()

  def open(
      connId: Long,
      urlHost: String,
      timeoutMs: Int,
      ctx: SSLContext,
      incomingWindowSize: Int = 65535
  ): Task[Unit] = openMany(connId, Array(urlHost), timeoutMs, ctx, incomingWindowSize)

  def getConnections(connId: Long): ZIO[Any, Throwable, Array[Http2ClientConnection]] =
    ZIO.attempt(connectionTbl.get(connId).get)

  def getConnection(connId: Long): ZIO[Any, Throwable, Http2ClientConnection] =
    ZIO.attempt(connectionTbl.get(connId).get(0))

  /** Closes HTTPClient which belongs to specific server connId 
   *  @param connId the ID of the server Http2 connection
  */
  def close(connId: Long): UIO[Unit] =
    (for {
        _ <- ZIO.logInfo(s"ZIO Service: H2Client: close request for connection Id = $connId")
      hosts <- ZIO.attempt(connectionTbl.get(connId).map(c => c.map(_.close())))
      _ <- ZIO.attempt(connectionTbl.remove(connId))
    } yield ()).catchAll(_ => ZIO.unit)

}
