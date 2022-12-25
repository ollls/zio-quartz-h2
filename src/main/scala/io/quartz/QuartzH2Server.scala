package io.quartz

import zio.{ZIO, Task, Chunk, Promise, ExitCode}
import zio.stream.ZStream

import io.quartz.http2.Http2Connection
import io.quartz.netio._

import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.channels.Channel
import java.nio.channels.{
  AsynchronousChannelGroup,
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import io.quartz.http2.Constants._
import io.quartz.http2.Frames
import io.quartz.http2.Http2Settings

import javax.net.ssl.SSLContext
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory

import scala.concurrent.duration.FiniteDuration
import concurrent.duration.DurationInt

import java.nio.file.Files
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2._
import io.quartz.http2.routes.HttpRoute
import io.quartz.http2.routes.Routes
import io.quartz.http2.routes.HttpRouteIO

import java.net._
import java.io._
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket
import scala.jdk.CollectionConverters.ListHasAsScala
import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ForkJoinPool._
import java.util.concurrent.ForkJoinPool

case class HeaderSizeLimitExceeded(msg: String) extends Exception(msg)
case class BadProtocol(ch: IOChannel, msg: String) extends Exception(msg)

object QuartzH2Server {
  def buildSSLContext(
      protocol: String,
      JKSkeystore: String,
      password: String
  ): Task[SSLContext] = {

    val ctx = ZIO.attemptBlocking {
      val sslContext: SSLContext = SSLContext.getInstance(protocol)
      val keyStore: KeyStore = KeyStore.getInstance("JKS")
      val ks = new java.io.FileInputStream(JKSkeystore)
      if (ks == null)
        ZIO.fail(
          new java.io.FileNotFoundException(
            JKSkeystore + " keystore file not found."
          )
        )
      keyStore.load(ks, password.toCharArray())
      val tmf: TrustManagerFactory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm()
      )
      tmf.init(keyStore)
      val kmf =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      kmf.init(keyStore, password.toCharArray())
      sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
      sslContext
    }
    ctx
  }
}

class QuartzH2Server(HOST: String, PORT: Int, h2IdleTimeOutMs: Int, sslCtx: SSLContext) {

  // def this(HOST: String) = this(HOST, 8080, 20000, null)

  val MAX_HTTP_HEADER_SZ = 16384
  val HTTP1_KEEP_ALIVE_MS = 20000

  // val HOST = "localhost"
  // val PORT = 8443
  // val SERVER = "127.0.0.1"

  val default_server_settings = new Http2Settings()

  val header_pair = raw"(.{2,100}):\s+(.+)".r
  val http_line = raw"([A-Z]{3,8})\s+(.+)\s+(HTTP/.+)".r

  private def parseHeaderLine(line: String, hdrs: Headers): Headers =
    line match {
      case http_line(method, path, _) =>
        hdrs ++ Headers(
          ":method" -> method,
          ":path" -> path,
          ":scheme" -> "http"
        ) // FIX TBD - no schema for now, ":scheme" -> prot)
      case header_pair(attr, value) => hdrs + (attr.toLowerCase -> value)
      case _                        => hdrs
    }

  def protoSwitch() = {
    val CRLF = "\r\n"
    val r = new StringBuilder
    r ++= "HTTP/1.1 101 Switching Protocols" + CRLF
    r ++= "Connection: Upgrade" + CRLF
    r ++= "Upgrade: h2c" + CRLF + CRLF
    r.toString()
  }

  def responseString() = {
    val contLen = 0
    val CRLF = "\r\n"
    val TAG = "quartz"
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    val r = new StringBuilder
    r ++= "HTTP/1.1 200" + CRLF // + code.value.toString + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: " + contLen.toString() + CRLF

    r ++= CRLF

    r.toString()

  }

  def responseStringNo11() = {
    val contLen = 0
    val CRLF = "\r\n"
    val TAG = "quartz"
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    val r = new StringBuilder
    r ++= "HTTP/1.1 505" + CRLF // + code.value.toString + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: " + contLen.toString() + CRLF

    r ++= CRLF

    r.toString()

  }

  def getHttpHeaderAndLeftover(chunk: Chunk[Byte]): (Headers, Chunk[Byte]) = {
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
        hdrs = parseHeaderLine(new String(line.toArray), hdrs)
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

  def doConnect(
      ch: IOChannel,
      maxStreams: Int,
      keepAliveMs: Int,
      route: Request => Task[Option[Response]],
      leftOver: Chunk[Byte] = Chunk.empty[Byte]
  ): Task[Unit] = {
    for {
      buf <-
        if (leftOver.size > 0) ZIO.succeed(leftOver) else ch.read(HTTP1_KEEP_ALIVE_MS)

      test <- ZIO.attempt(buf.take(PrefaceString.length))

      testbb <- ZIO.attempt(ByteBuffer.wrap(test.toArray))
      isOK <- ZIO.attempt(Frames.checkPreface(testbb))
      _ <- ZIO.logTrace("doConnect() - Preface result: " + isOK)
      _ <-
        if (isOK == false) {
          doConnectUpgrade(ch, maxStreams, keepAliveMs, route, buf)
        } else
          ZIO.scoped {
            ZIO
              .acquireRelease(Http2Connection.make(ch, maxStreams, keepAliveMs, route, None))(
                _.shutdown.catchAll(_ => ZIO.unit)
              )
              .flatMap(_.processIncoming(buf.drop(PrefaceString.length)))
          }

    } yield ()

  }

  def doConnectUpgrade(
      ch: IOChannel,
      maxStreams: Int,
      keepAliveMs: Int,
      route: Request => Task[Option[Response]],
      buf: Chunk[Byte]
  ): Task[Unit] = {
    val R = for {
      _ <- ZIO.logTrace("doConnectUpgrade()")
      hb <- ZIO.attempt(getHttpHeaderAndLeftover(buf))
      leftover = hb._2
      headers11 = hb._1
      contentLen = headers11.get("Content-Length").getOrElse("0").toLong

      s1 <- ZIO.attempt(
        ZStream[Chunk[Byte]](leftover).flatMap(c0 => ZStream.fromChunk(c0))
      )
      s2 <- ZIO.attempt(
        ZStream.repeatZIO(ch.read(HTTP1_KEEP_ALIVE_MS)).flatMap(c0 => ZStream.fromChunk(c0))
      )
      res <- ZIO.attempt((s1 ++ s2).take(contentLen))

      emptyTH <- Promise.make[Throwable, Headers] // no trailing headers for 1.1
      _ <- emptyTH.succeed(Headers()) // complete with empty
      http11request <- ZIO.attempt(Some(Request(headers11, res, emptyTH)))
      upd = headers11.get("upgrade").getOrElse("")
      _ <- ZIO.logTrace("doConnectUpgrade() - Upgrade = " + upd)
      clientPreface <-
        if (upd == "h2c") {
          ZIO.logTrace("doConnectUpgrade() - h2c upgrade requested") *>
            ch.write(ByteBuffer.wrap(protoSwitch().getBytes)) *>
            ch.read(
              HTTP1_KEEP_ALIVE_MS
            ) // clent preface and remote peer/client setting array  !!!!FIX NEDED
        } else
          ZIO.fail(new BadProtocol(ch, "HTTP2 Upgrade Request Denied"))
      bbuf <- ZIO.attempt(ByteBuffer.wrap(clientPreface.toArray))
      isOK <- ZIO.attempt(Frames.checkPreface(bbuf))
      c <-
        if (isOK) Http2Connection.make(ch, maxStreams, keepAliveMs, route, http11request)
        else
          ZIO.fail(
            new BadProtocol(ch, "Cannot see HTTP2 Preface, bad protocol")
          )
      _ <- ZIO.scoped {
        ZIO
          .acquireRelease(ZIO.succeed(c))(_.shutdown.catchAll(_ => ZIO.unit))
          .flatMap(_.processIncoming(clientPreface.drop(PrefaceString.length)))
      }
    } yield ()
    R
  }

  ///////////////////////////////////
  def errorHandler(e: Throwable) = {
    e match {
      case BadProtocol(ch, e) =>
        ch.write(ByteBuffer.wrap(responseStringNo11().getBytes)) *> ZIO.logError(
          e.toString
        )
      case e: java.nio.channels.InterruptedByTimeoutException =>
        ZIO.logInfo("Remote peer disconnected on timeout")
      case _ => ZIO.logError("errorHandler: " + e.toString)
      /*>> IO(e.printStackTrace)*/
    }
  }

  def hostName(address: SocketAddress) = {
    val ia = address.asInstanceOf[InetSocketAddress]
    ia.getHostString()
  }

  def startIO(pf: HttpRouteIO, sync: Boolean): Task[ExitCode] = {
    start(Routes.of(pf), sync)
  }

  def start(R: HttpRoute, sync: Boolean): Task[ExitCode] = {

    val cores = Runtime.getRuntime().availableProcessors()
    val h2streams = cores * 2 // optimal setting tested with h2load

    if (sync == false) {
      /*
      val fjj = new ForkJoinWorkerThreadFactory {
        val num = new AtomicInteger();
        def newThread(pool: ForkJoinPool) = {
          val thread = defaultForkJoinWorkerThreadFactory.newThread(pool);
          thread.setDaemon(true);
          thread.setName("qh2-pool" + "-" + num.getAndIncrement());
          thread;
        }
      }*/
      //val e = new java.util.concurrent.ForkJoinPool(cores) //.ForkJoinPool(cores, fjj, (t, e) => System.exit(0), false)
      //val e0 = Executors.newFixedThreadPool(cores);
      //val ec = ExecutionContext.fromExecutor(e)
      ZIO.executor.map(_.asExecutionContextExecutorService).flatMap(run0(_, R, cores, h2streams, h2IdleTimeOutMs))
      //val ee = zio.Executor.fromJavaExecutor( e )
      //ZIO.onExecutor( ee )( run0( e, R, cores, h2streams, h2IdleTimeOutMs))

    } else {
      // Loom test commented out, just FYI
      // val e = Executors.newVirtualThreadPerTaskExecutor()
      // val ec = ExecutionContext.fromExecutor(e)
      run1(R, cores, h2streams, h2IdleTimeOutMs)
      //val e = new java.util.concurrent.ForkJoinPool(cores)
      //val ee = zio.Executor.fromJavaExecutor( e )
      //ZIO.onExecutor( ee )( run1( R, cores, h2streams, h2IdleTimeOutMs))
    }
  }

  def run0(e: ExecutorService, R: HttpRoute, maxThreadNum: Int, maxStreams: Int, keepAliveMs: Int): Task[ExitCode] = {
    for {
      addr <- ZIO.attempt(new InetSocketAddress(HOST, PORT))
      _ <- ZIO.logInfo("HTTP/2 TLS Service: QuartzH2 (async - Java NIO)")
      _ <- ZIO.logInfo(s"Concurrency level(max threads): $maxThreadNum, max streams per conection: $maxStreams")
      _ <- ZIO.logInfo(s"H2 Idle Timeout: $keepAliveMs Ms")
      _ <- ZIO.logInfo(
        s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}"
      )
      group <- ZIO.attempt(
        AsynchronousChannelGroup.withThreadPool(e)
      )
      server_ch <- ZIO.attempt(
        group.provider().openAsynchronousServerSocketChannel(group).bind(addr)
      )
      accept = ZIO.logDebug("Wait on accept") *> TCPChannel
        .accept(server_ch)
        .tap(c =>
          ZIO.logInfo(
            s"Connect from remote peer: ${hostName(c.ch.getRemoteAddress())}"
          )
        )
        .flatMap(ch => ZIO.attempt(TLSChannel(sslCtx, ch)))
        .flatMap(c => c.ssl_init_h2().map((c, _)))

      ch0 <- accept
        .flatMap(ch1 =>
          ZIO.scoped {
            ZIO
              .acquireRelease(ZIO.succeed(ch1))(  t => t._1.close().catchAll(e => errorHandler(e).ignore  )) // handleError!!!!
              .flatMap( t => doConnect( t._1, maxStreams, keepAliveMs, R, t._2) )
          }.fork
        )
        .forever

    } yield (ExitCode.success)
  }

  def run1(R: HttpRoute, maxThreadNum: Int, maxStreams: Int, keepAliveMs: Int): Task[ExitCode] = {
    for {
      addr <- ZIO.succeed(new InetSocketAddress(HOST, PORT))

      _ <- ZIO.logInfo("HTTP/2 TLS Service: QuartzH2 ( sync - Java Socket )")
      _ <- ZIO.logInfo(s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}")

      server_ch: SSLServerSocket <- ZIO.attempt(
        sslCtx.getServerSocketFactory().createServerSocket(PORT, 0, addr.getAddress()).asInstanceOf[SSLServerSocket]
      )

      accept: ZIO[Any, Throwable, SocketChannel] = ZIO
        .attemptBlocking[SSLSocket] { val R: SSLSocket = server_ch.accept().asInstanceOf[SSLSocket]; R }
        .tap((c: SSLSocket) =>
          ZIO.attempt {
            c.setUseClientMode(false);
            c.setHandshakeApplicationProtocolSelector((eng, list) => {
              if (list.asScala.find(_ == "h2").isDefined) "h2"
              else null
            })
          }
        )
        .flatMap((c: SSLSocket) => ZIO.attempt(new SocketChannel(c)))
        .tap(c => ZIO.logInfo(s"Connect from remote peer: ${c.socket.getInetAddress().toString()}"))

      ch0 <- accept
        .flatMap(ch1 =>
          ZIO.scoped {
            ZIO
              .acquireRelease(ZIO.succeed(ch1))(_.close().catchAll(e => errorHandler(e).ignore )) // handleError!!!!
              .flatMap(ch => doConnect(ch, maxStreams, keepAliveMs, R, Chunk.empty[Byte]))
          }.fork
        )
        .forever
    } yield (ExitCode.success)
  }

}
