package io.quartz.netio

import java.util.concurrent.locks.ReentrantLock
import java.net.SocketAddress
import java.util.function.{Consumer, BiConsumer}
import java.nio.ByteBuffer
import zio.{ZIO, Chunk, Task}
import io.quartz.iouring.{IoUringServerSocket, IoUringSocket, IoUring}
import scala.concurrent.ExecutionContextExecutorService

object IOURingChannel {

  private def ioUringAccept(
      ring: IoUring,
      serverSocket: IoUringServerSocket,
      cb: (ring: IoUring, socket: IoUringSocket) => Unit
  ) = {

    val bconsumer = new BiConsumer[IoUring, IoUringSocket] {
      override def accept(ring: IoUring, socket: IoUringSocket): Unit = {
        cb(ring, socket)
      }
    }
    serverSocket.onAccept(bconsumer)
    ring.queueAccept(serverSocket)
    ring.execute()
  }

  def accept(ring: IoUring, serverSocket: IoUringServerSocket): Task[(IoUring, IoUringSocket)] = {
    for {
      result <- ZIO.async[Any, Throwable, (IoUring, IoUringSocket)](cb =>
        for {
          f1 <- ZIO.succeed((ring: IoUring, socket: IoUringSocket) => cb(ZIO.succeed(ring, socket)))
          _ <- ZIO.attempt(ioUringAccept(ring, serverSocket, f1))
        } yield (Some(ZIO.unit))
      )
    } yield (result)
  }

}

class IOURingChannel(val ring: IoUringEntry, val ch1: IoUringSocket, var timeOutMs0: Int) extends IOChannel {

  var f_putBack: ByteBuffer = null

  def put(bb: ByteBuffer): Task[Unit] = ZIO.succeed { f_putBack = bb }

  val lock = new ReentrantLock()

  def toDirectBuffer(buffer: ByteBuffer): ByteBuffer = {
    if (buffer.isDirect()) {
      return buffer; // Already a direct buffer
    }
    // Create a new direct buffer with the same capacity
    val directBuffer = ByteBuffer.allocateDirect(buffer.capacity());
    // Save the original position and limit
    val position = buffer.position();
    val limit = buffer.limit();
    // Copy the data
    buffer.rewind();
    directBuffer.put(buffer);
    // Restore the original position and limit for both buffers
    buffer.position(position);
    buffer.limit(limit);
    directBuffer.position(position);
    directBuffer.limit(limit);

    directBuffer;
  }

  def effectAsyncChannelIO[A](ring: IoUringEntry, ch: IoUringSocket)(
      op: (ring: IoUringEntry, ch: IoUringSocket) => (A => Unit) => Task[Any]
  ) = {
    ZIO.asyncZIO[Any, Throwable, A](cb =>
      ZIO
        .attempt(op(ring, ch))
        .flatMap(handler => {
          val f1: A => Unit = bb => { cb(ZIO.succeed(bb)) }
          // todo: investigate how to catch error
          handler(f1)
        })
    )
  }

  private def ioUringReadIO(
      ring: IoUringEntry,
      ch: IoUringSocket,
      bufferDirect: ByteBuffer,
      cb: ByteBuffer => Unit
  ): Task[Unit] = {
    for {
      consumer <-
        ZIO.succeed(new Consumer[ByteBuffer] {
          override def accept(buffer: ByteBuffer): Unit = {
            cb(buffer)
          }
        })
      _ <- ring.queueRead(consumer, ch, bufferDirect)
    } yield ()
  }

  private def ioUringWriteIO(
      ring: IoUringEntry,
      ch: IoUringSocket,
      bufferDirect: ByteBuffer,
      cb: ByteBuffer => Unit
  ): Task[Unit] = {
    for {
      consumer <- ZIO.succeed(new Consumer[ByteBuffer] {
        override def accept(buffer: ByteBuffer): Unit = {
          cb(buffer)
        }
      })
      _ <- ring.queueWrite(consumer, ch, /*toDirectBuffer(*/ bufferDirect)
    } yield ()
  }

  private def submit(ring: IoUring) = {
    this.synchronized {
      ring.submit()
    }
  }

  def readBuffer(
      dst: ByteBuffer,
      timeOut: Int
  ): Task[Int] = {
    for {
      _ <-
        if (f_putBack != null) {
          ZIO.succeed(dst.put(f_putBack)) *> ZIO.succeed { f_putBack = null }
        } else ZIO.unit
      b1 <- effectAsyncChannelIO[ByteBuffer](ring, ch1)((ring, ch1) => ioUringReadIO(ring, ch1, dst, _))
      n <- ZIO.succeed(b1.position())
      _ <- ZIO.fail(new java.nio.channels.ClosedChannelException).when(n <= 0)

    } yield (n)
  }

  def read(timeOutMs: Int): Task[Chunk[Byte]] = {
    for {
      _ <- ZIO.succeed(this.timeOutMs(timeOutMs0))
      bb <- ZIO.succeed(ByteBuffer.allocateDirect(TCPChannel.HTTP_READ_PACKET))
      b1 <- effectAsyncChannelIO[ByteBuffer](ring, ch1)((ring, ch1) => ioUringReadIO(ring, ch1, bb, _))
      // _ <- IO.raiseError(new java.nio.channels.ClosedChannelException).whenA(b1.position == 0)
    } yield (Chunk.fromByteBuffer(b1.flip))
  }

  def write(buffer: ByteBuffer): Task[Int] = {
    for {
      b1 <- effectAsyncChannelIO[ByteBuffer](ring, ch1)((ring, ch1) => ioUringWriteIO(ring, ch1, buffer, _))
    } yield (b1.position())
  }

  def close(): Task[Unit] = ZIO.succeed(ch1.close())
  def secure() = false
  // used in TLS mode to pass parameter from SNI tls extension
  def remoteAddress(): Task[SocketAddress] = ???

}
