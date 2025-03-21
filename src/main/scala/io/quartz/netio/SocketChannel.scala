package io.quartz.netio

import java.net._;
import java.io._;

import java.nio.ByteBuffer
import zio.{ZIO, Task}
import zio.Chunk

object SocketChannel {

  val HTTP_READ_PACKET = 16384

  def accept(ch: ServerSocket) =
    ZIO.attemptBlocking(new SocketChannel(ch.accept()))
}

class SocketChannel(val socket: Socket) extends IOChannel {

  def readBuffer( dst: ByteBuffer,timeOut: Int ): Task[Int] = ???
  def put(bb: ByteBuffer): Task[Unit] = ???

  def read(timeOut: Int): Task[Chunk[Byte]] =
    for {
      _ <- ZIO.attempt(socket.setSoTimeout(timeOut))
      buffer <- ZIO.attempt(Array.ofDim[Byte](SocketChannel.HTTP_READ_PACKET))
      nb <- ZIO.attemptBlocking(socket.getInputStream().read(buffer))
      chunk <- ZIO.attempt(Chunk.fromArray(buffer))
    } yield (chunk.take(nb))

  def write(buffer: ByteBuffer): Task[Int] =
    for {
      size <- ZIO.attempt(buffer.remaining())
      array <- ZIO.attempt(Array.ofDim[Byte](size))
      _ <- ZIO.attempt(buffer.get(array))
      _ <- ZIO.attemptBlocking(socket.getOutputStream().write(array))
    } yield (size)

  def close(): Task[Unit] = {
    ZIO.attempt {
      socket.close();
    }
  }

  def remoteAddress(): Task[SocketAddress] = ZIO.attempt(socket.getRemoteSocketAddress())

  def secure() = true // it is only true for zio-quartz-h2, we don't use it open.

}
