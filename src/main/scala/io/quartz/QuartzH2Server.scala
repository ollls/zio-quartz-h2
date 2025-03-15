package io.quartz

import zio.durationInt
import zio.{ZIO, UIO, Task, Chunk, Promise, Ref, ExitCode, ZIOApp}
import zio.stream.ZStream
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
import io.quartz.http2.routes.WebFilter
import io.quartz.http2.routes.Routes
import io.quartz.http2.routes.HttpRouteIO
import io.quartz.util.Utils

import java.net._
import java.io._
import io.quartz.iouring.{IoUringServerSocket, IoUring}
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket
import scala.jdk.CollectionConverters.ListHasAsScala
import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ForkJoinPool._
import java.util.concurrent.ForkJoinPool
import ch.qos.logback.classic.Level

case class HeaderSizeLimitExceeded(msg: String) extends Exception(msg)
case class BadProtocol(ch: IOChannel, msg: String) extends Exception(msg)

object QuartzH2Server {

  def setLoggingLevel(level: Level) = {
    val root = org.slf4j.LoggerFactory.getLogger("ROOT").asInstanceOf[ch.qos.logback.classic.Logger]
    root.setLevel(level)
  }

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

/** Quartz HTTP/2 server.
  * @param HOST
  *   the host address of the server
  * @param PORT
  *   the port number to bind to
  * @param h2IdleTimeOutMs
  *   the maximum idle time in milliseconds before a connection is closed
  * @param sslCtx
  *   the SSL context to use for secure connections, can be null for non-secure connections
  * @param incomingWinSize
  *   the initial window size for incoming flow control
  * @param onConnect
  *   callback function that is called when a connection is established, provides connectionId : Long as an argument
  * @param onDisconnect
  *   callback function that is called when a connection is terminated, provides connectionId : Long as an argument
  */
class QuartzH2Server[Env](
    HOST: String,
    PORT: Int,
    h2IdleTimeOutMs: Int,
    sslCtx: SSLContext,
    incomingWinSize: Int = 65535,
    onConnect: Long => ZIO[Env, Throwable, Unit] = _ => ZIO.unit,
    onDisconnect: Long => ZIO[Env, Nothing, Unit] = _ => ZIO.unit
) {
  val MAX_HTTP_HEADER_SZ = 16384
  val HTTP1_KEEP_ALIVE_MS = 20000

  var shutdownFlag = false

  def timeout = h2IdleTimeOutMs

  def shutdown = (for {
    _ <- ZIO.debug("Shutting down...")
    _ <- ZIO.succeed { shutdownFlag = true }
    c <- TCPChannel.connect(HOST, PORT)
    _ <- c.close()
  } yield ()).catchAll(_ => ZIO.unit)

  def ctrlC_handlerZIO(group: AsynchronousChannelGroup, s0: AsynchronousServerSocketChannel) = ZIO.attempt(
    java.lang.Runtime
      .getRuntime()
      .addShutdownHook(new Thread {
        override def run = {
          println("abort")
          // for async wait this is all we need
          shutdownFlag = true
        }
      })
  )

  def ctrlC_handlerZIOsync(s0: SSLServerSocket) = ZIO.attempt(
    java.lang.Runtime
      .getRuntime()
      .addShutdownHook(new Thread {
        override def run = {
          println("abort2")
          shutdownFlag = true
          /* blockig socket will need one
           * last connection to process a shutdown flag */
          /* ZIO context not available here hard, we just halt the jvm for now*/
          Runtime.getRuntime().halt(0);
        }
      })
  )

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

  def doConnect(
      ch: IOChannel,
      idRef: Ref[Long],
      maxStreams: Int,
      keepAliveMs: Int,
      route: HttpRoute[Env],
      leftOver: Chunk[Byte] = Chunk.empty[Byte]
  ): ZIO[Env, Throwable, Unit] = {
    for {
      id <- idRef.get
      buf <-
        if (leftOver.size > 0) ZIO.succeed(leftOver) else ch.read(HTTP1_KEEP_ALIVE_MS)

      test <- ZIO.attempt(buf.take(PrefaceString.length))

      testbb <- ZIO.attempt(ByteBuffer.wrap(test.toArray))
      isOK <- ZIO.attempt(Frames.checkPreface(testbb))
      _ <- ZIO.logTrace(s"doConnect() - Preface result: $isOK")
      _ <-
        if (isOK == false) {
          doConnectUpgrade(ch, id, maxStreams, keepAliveMs, route, buf)
        } else
          ZIO.scoped {
            ZIO
              .acquireRelease(Http2Connection.make(ch, id, maxStreams, keepAliveMs, route, incomingWinSize, None))(c =>
                onDisconnect(c.id) *> c.shutdown.catchAll(_ => ZIO.unit)
              )
              .tap(c => onConnect(c.id))
              .flatMap(_.processIncoming(buf.drop(PrefaceString.length)))
          }

    } yield ()
  }

  def doConnectUpgrade(
      ch: IOChannel,
      id: Long,
      maxStreams: Int,
      keepAliveMs: Int,
      route: HttpRoute[Env],
      buf: Chunk[Byte]
  ): ZIO[Env, Throwable, Unit] = for {
    _ <- ZIO.logTrace("doConnectUpgrade()")

    hdrb_body <- Utils.splitHeadersAndBody(ch, buf)
    hdr_body <- Utils.getHttpHeaderAndLeftover(hdrb_body._1, ch.secure())

    leftover = hdrb_body._2
    headers11 = hdr_body._1
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
    http11request <- ZIO.attempt(Some(Request(id, 1, headers11, res, ch.secure(), ch.sniServerNames(), emptyTH)))
    upd = headers11.get("upgrade").getOrElse("<N/A>")
    _ <- ZIO.logTrace("doConnectUpgrade() - Upgrade = " + upd)
    _ <-
      if (upd != "h2c") for {
        c <- Http11Connection.make(ch, id, keepAliveMs, route)
        refStart <- Ref.make(true)
        _ <- ZIO.scoped {
          ZIO
            .acquireRelease(ZIO.succeed(c))(c => onDisconnect(c.id) *> c.shutdown.catchAll(_ => ZIO.unit))
            .tap(c => onConnect(c.id))
            .flatMap(_.processIncoming(headers11, leftover, refStart).forever)
        }
        // _ <- ZIO.fail(new BadProtocol(ch, "HTTP2 Upgrade Request Denied"))
      } yield ()
      else
        for {
          _ <- ZIO.logTrace("doConnectUpgrade() - h2c upgrade requested")
          _ <- ch.write(ByteBuffer.wrap(protoSwitch().getBytes))
          clientPreface <- ch.read(HTTP1_KEEP_ALIVE_MS)
          bbuf <- ZIO.attempt(ByteBuffer.wrap(clientPreface.toArray))
          isOK <- ZIO.attempt(Frames.checkPreface(bbuf))
          c <-
            if (isOK) Http2Connection.make(ch, id, maxStreams, keepAliveMs, route, incomingWinSize, http11request)
            else
              ZIO.fail(
                new BadProtocol(ch, "Cannot see HTTP2 Preface, bad protocol")
              )
          _ <- ZIO.scoped {
            ZIO
              .acquireRelease(ZIO.succeed(c))(c => onDisconnect(c.id) *> c.shutdown.catchAll(_ => ZIO.unit))
              .tap(c => onConnect(c.id))
              .flatMap(_.processIncoming(clientPreface.drop(PrefaceString.length)))
          }
        } yield ()
  } yield ()

  ///////////////////////////////////
  def errorHandler(e: Throwable) = {
    if (shutdownFlag == false) {
      e match {
        case BadProtocol(ch, e) =>
          ch.write(Frames.mkGoAwayFrame(0, Error.PROTOCOL_ERROR, e.getBytes))
          /*ch.write(ByteBuffer.wrap(responseStringNo11().getBytes))*/ *> ZIO.logError(
            e.toString
          )
        case e: java.nio.channels.InterruptedByTimeoutException =>
          ZIO.logInfo("Remote peer disconnected on timeout")
        case e: java.nio.channels.ClosedChannelException =>
          ZIO.logInfo("Remote peer disconnected")
        case _ => ZIO.logError("errorHandler: " + e.toString)
      }
    } else ZIO.unit
  }

  def hostName(address: SocketAddress) = {
    val ia = address.asInstanceOf[InetSocketAddress]
    ia.getHostString()
  }

  private def printSniName(names: Option[Array[String]]) = {
    names match {
      case Some(value) => value(0)
      case None        => "not provided"
    }
  }

  private def tlsPrint(c: TLSChannel) = {
    c.f_SSL.engine.getSession().getCipherSuite()
  }

  def startIO(
      pf: HttpRouteIO[Env],
      filter: WebFilter[Env] = (r0: Request) => ZIO.succeed(Right(r0)),
      sync: Boolean
  ): ZIO[Env, Throwable, ExitCode] = {
    start(Routes.of[Env](pf, filter), sync)
  }

  def startIO_linuxOnly(
      rings: Int,
      pf: HttpRouteIO[Env],
      filter: WebFilter[Env] = (r0: Request) => ZIO.succeed(Right(r0))
  ): ZIO[Env, Throwable, ExitCode] = {
    start_withIOURing(rings, Routes.of[Env](pf, filter), false)
  }

  def start_withIOURing(rings: Int, R: HttpRoute[Env], sync: Boolean): ZIO[Env, Throwable, ExitCode] = {
    val cores = Runtime.getRuntime().availableProcessors()
    val h2streams = cores * 2
    if (sslCtx != null) {
      run4(rings, R, cores, h2streams, h2IdleTimeOutMs)
    } else {
      ???
    }

  }

  def start(R: HttpRoute[Env], sync: Boolean): ZIO[Env, Throwable, ExitCode] = {

    val cores = Runtime.getRuntime().availableProcessors()
    val h2streams = cores * 4 //max, not always good

    if (sync == false) {

      if (sslCtx != null)
        ZIO.executor.map(_.asExecutionContextExecutorService).flatMap(run0(_, R, cores, h2streams, h2IdleTimeOutMs))
      else
        ZIO.executor.map(_.asExecutionContextExecutorService).flatMap(run3(_, R, cores, h2streams, h2IdleTimeOutMs))
    } else {
      run1(R, cores, h2streams, h2IdleTimeOutMs)
    }
  }

  def run0(
      e: ExecutorService,
      R: HttpRoute[Env],
      maxThreadNum: Int,
      maxStreams: Int,
      keepAliveMs: Int
  ): ZIO[Env, Throwable, ExitCode] = {
    for {
      addr <- ZIO.attempt(new InetSocketAddress(HOST, PORT))
      _ <- ZIO.logInfo("HTTP/2 TLS Service: QuartzH2 (async - Java NIO)")
      _ <- ZIO.logInfo(s"Concurrency level(max threads): $maxThreadNum, max streams per conection: $maxStreams")
      _ <- ZIO.logInfo(s"H2 Idle Timeout: $keepAliveMs Ms")
      _ <- ZIO.logInfo(
        s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}"
      )

      conId <- Ref.make(0L)

      group <- ZIO.attempt(
        AsynchronousChannelGroup.withThreadPool(e)
      )
      server_ch <- ZIO.attempt(
        group.provider().openAsynchronousServerSocketChannel(group).bind(addr)
      )

      _ <- ctrlC_handlerZIO(group, server_ch)

      accept = ZIO.logDebug("Wait on accept") *> TCPChannel
        .accept(server_ch)
        .tap(c =>
          ZIO.logInfo(
            s"Connect from remote peer: ${hostName(c.ch.getRemoteAddress())}"
          )
        )
        .flatMap(ch => ZIO.attempt(TLSChannel(sslCtx, ch)))

      ch0 <- accept
        .flatMap((c => c.ssl_init_h2().map((c, _)).catchAll(e => c.rch.close().ignore *> ZIO.fail(e))))
        .tap(c =>
          ZIO.logInfo(
            s"${c._1.ctx.getProtocol()} ${tlsPrint(c._1)} ${c._1.f_SSL.engine
                .getApplicationProtocol()} tls-sni: ${printSniName(c._1.sniServerNames())}"
          )
        )
        .tap(_ => conId.update(_ + 1))
        .flatMap(ch1 =>
          ZIO.scoped {
            ZIO
              .acquireRelease(ZIO.succeed(ch1))(t => t._1.close().ignore)
              .flatMap(t =>
                doConnect(t._1, conId, maxStreams, keepAliveMs, R, t._2).catchAll(e => errorHandler(e).ignore)
              )
          }.fork
        )
        .catchAll(e => errorHandler(e).ignore)
        .repeatUntil(_ => shutdownFlag)

      _ <- ZIO.attempt(server_ch.close())
      _ <- ZIO.when(shutdownFlag)(ZIO.logInfo("Shutdown request, server stoped gracefully"))

    } yield (ExitCode.success)
  }

  def run1(
      R: HttpRoute[Env],
      maxThreadNum: Int,
      maxStreams: Int,
      keepAliveMs: Int
  ): ZIO[Env, Throwable, ExitCode] = {
    for {
      addr <- ZIO.succeed(new InetSocketAddress(HOST, PORT))

      _ <- ZIO.logInfo("HTTP/2 TLS Service: QuartzH2 ( sync - Java Socket )")
      _ <- ZIO.logInfo(s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}")

      conId <- Ref.make(0L)

      server_ch: SSLServerSocket <- ZIO.attempt(
        sslCtx.getServerSocketFactory().createServerSocket(PORT, 0, addr.getAddress()).asInstanceOf[SSLServerSocket]
      )

      _ <- ctrlC_handlerZIOsync(server_ch)

      accept: ZIO[Any, Throwable, SocketChannel] = ZIO
        .attemptBlocking[SSLSocket] { val R: SSLSocket = server_ch.accept().asInstanceOf[SSLSocket]; R }
        .tap((c: SSLSocket) =>
          ZIO.attempt {
            c.setUseClientMode(false);
            c.setHandshakeApplicationProtocolSelector((eng, list) => {
              if (list.asScala.find(_ == "h2").isDefined) "h2"
              else ""
            })
          }
        )
        .tap(_ => conId.update(_ + 1))
        .flatMap((c: SSLSocket) => ZIO.attempt(new SocketChannel(c)))
        .tap(c => ZIO.logInfo(s"Connect from remote peer: ${c.socket.getInetAddress().toString()}"))

      ch0 <- accept
        .flatMap(ch1 =>
          ZIO.scoped {
            ZIO
              .acquireRelease(ZIO.succeed(ch1))(_.close().ignore)
              .flatMap(ch => doConnect(ch, conId, maxStreams, keepAliveMs, R, Chunk.empty[Byte]))
              .catchAll(e => errorHandler(e).ignore)
          }.fork
        )
        .catchAll(e => errorHandler(e).ignore)
        .repeatUntil(_ => shutdownFlag)

      _ <- ZIO.attempt(server_ch.close())
      _ <- ZIO.when(shutdownFlag)(ZIO.logInfo("Shutdown request, server stoped gracefully"))

    } yield (ExitCode.success)
  }

  def run3(
      e: ExecutorService,
      R: HttpRoute[Env],
      maxThreadNum: Int,
      maxStreams: Int,
      keepAliveMs: Int
  ): ZIO[Env, Throwable, ExitCode] = {
    for {
      addr <- ZIO.attempt(new InetSocketAddress(HOST, PORT))
      _ <- ZIO.logInfo("HTTP/2 h2c: QuartzH2 (async - Java NIO)")
      _ <- ZIO.logInfo(s"Concurrency level(max threads): $maxThreadNum, max streams per conection: $maxStreams")
      _ <- ZIO.logInfo(s"h2c idle timeout: $keepAliveMs Ms")
      _ <- ZIO.logInfo(
        s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}"
      )
      conId <- Ref.make(0L)

      group <- ZIO.attempt(
        AsynchronousChannelGroup.withThreadPool(e)
      )
      server_ch <- ZIO.attempt(
        group.provider().openAsynchronousServerSocketChannel(group).bind(addr)
      )

      _ <- ctrlC_handlerZIO(group, server_ch)

      accept = ZIO.logDebug("Wait on accept") *> TCPChannel
        .accept(server_ch)
        .tap(c =>
          ZIO.logInfo(
            s"Connect from remote peer: ${hostName(c.ch.getRemoteAddress())}"
          )
        )

      ch0 <- accept
        .tap(_ => conId.update(_ + 1))
        .flatMap(ch1 =>
          ZIO.scoped {
            ZIO
              .acquireRelease(ZIO.succeed(ch1))(t => t.close().ignore)
              .flatMap(t =>
                doConnect(t, conId, maxStreams, keepAliveMs, R, Chunk.empty[Byte]).catchAll(e => errorHandler(e).ignore)
              )
          }.fork
        )
        .catchAll(e => errorHandler(e).ignore)
        .repeatUntil(_ => shutdownFlag)

      _ <- ZIO.attempt(server_ch.close())
      _ <- ZIO.when(shutdownFlag)(ZIO.logInfo("Shutdown request, server stoped gracefully"))

    } yield (ExitCode.success)
  }

  def ctrlC_handlerZIO_withConnect(rings: IoUringTbl) = ZIO.attempt(
    java.lang.Runtime
      .getRuntime()
      .addShutdownHook(new Thread {
        override def run = {
          Runtime.getRuntime.halt(0)
          ()
        }
      })
  )

  def run4(
      rings: Int,
      R: HttpRoute[Env],
      maxThreadNum: Int,
      maxStreams: Int,
      keepAliveMs: Int
  ): ZIO[Env, Throwable, ExitCode] = {
    for {

      addr <- ZIO.attempt(new InetSocketAddress(HOST, PORT))
      _ <- ZIO.logInfo(s"HTTP/2 TLS Service: QuartzH2 (async - linux io-uring with $rings ring instance(s))")
      _ <- ZIO.logInfo(s"Concurrency level(max threads): $maxThreadNum, max streams per conection: $maxStreams")
      _ <- ZIO.logInfo(s"H2 Idle Timeout: $keepAliveMs Ms")
      _ <- ZIO.logInfo(
        s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}"
      )

      conId <- Ref.make(0L)

      // with default ZIO main thread pool, one ring is the best which means one daemon service thread on a single iouring descriptor.
      // would be nice to limit parallelism on ZIO.default.executor to get more rings - but this is only possible
      // with custom ThreadPool, and not sure why, but defaut executior is better anyway.
      rings <- IoUringTbl(this, rings)
      _ <- ctrlC_handlerZIO_withConnect(rings)

      serverSocket <- ZIO.succeed(new IoUringServerSocket(PORT))
      acceptURing <- ZIO.succeed(new IoUring(512))
      loop = for {
        _ <- ZIO.logDebug("Wait on accept")
        a <- IOURingChannel.accept(acceptURing, serverSocket)
        (ring_srv, socket) = a
        _ <- ZIO.logInfo(s"Connect from remote peer: ${socket.ipAddress()}")

        ring <- rings.get

        ch <- ZIO.succeed(IOURingChannel(ring, socket, keepAliveMs))
        c <- ZIO
          .succeed(TLSChannel(sslCtx, ch))
          .flatMap(c => c.ssl_init_h2().map((c, _)))
          .catchAll(e => ch.close().ignore *> ZIO.succeed(rings.release(ring)) *> ZIO.fail(e))

        _ <- ZIO.logInfo(
          s"${c._1.ctx.getProtocol()} ${tlsPrint(c._1)} ${c._1.f_SSL.engine
              .getApplicationProtocol()} tls-sni: ${printSniName(c._1.sniServerNames())}"
        )

        _ <- conId.update(_ + 1)

        _ <- ZIO.scoped {
          ZIO
            .acquireRelease(ZIO.succeed(c))(t => t._1.close().ignore *> ZIO.succeed(rings.release(ring)))
            .flatMap(t =>
              doConnect(t._1, conId, maxStreams, keepAliveMs, R, t._2).catchAll(e => errorHandler(e).ignore)
            )
        }.fork
      } yield ()

      _ <- loop
        .catchAll(e => errorHandler(e).ignore)
        .catchAllDefect(error => ZIO.logError(error.toString()))
        .repeatUntil((_ => shutdownFlag))
      _ <- rings.closeIoURings
      _ <- ZIO.succeed(serverSocket.close())
      _ <- ZIO.succeed(acceptURing.close())

      _ <- ZIO.when(shutdownFlag)(ZIO.logInfo("Shutdown request, server stoped gracefully"))

    } yield (ExitCode.success)
  }

}
