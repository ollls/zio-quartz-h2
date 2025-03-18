package io.quartz.netio

import java.util.function.Consumer
import java.nio.ByteBuffer
import zio.{ZIO, Task}
import zio.Queue
import zio.Ref
import scala.collection.immutable.List
import io.quartz.iouring.{IoUring, IoUringSocket}
import io.quartz.QuartzH2Server
import java.util.concurrent.atomic.AtomicInteger
//import org.typelevel.log4cats.Logger
//import io.quartz.MyLogger._

/** IoUringEntry represents an entry in the IoUringTbl. Each entry contains a Mutex for synchronization, a Ref counter
  * to track usage, and the IoUring instance itself.
  *
  * @param q
  *
  * @param counter
  *   Reference counter to track usage
  * @param ring
  *   IoUring instance
  */

case class IoUringEntry(
    q: Queue[Task[Unit]], // Task[Unit] IO wraped op to execute later.
    cntr: AtomicInteger,
    ring: IoUring
) {

  def close = for {
    //_ <- ZIO.succeed(ring.close())
    _ <- q.shutdown
  } yield ()

  /** Synchronized wrapper for IoUring's queueRead method.
    *
    * @param entry
    *   The IoUringEntry containing the IoUring instance
    * @param channel
    *   The IoUringSocket to read from
    * @param buffer
    *   The ByteBuffer to read into
    * @return
    *   IO[Unit]
    */
  def queueRead(consumer: Consumer[ByteBuffer], channel: IoUringSocket, buffer: java.nio.ByteBuffer): Task[Unit] =
    // rwqMutex.lock.use(_ =>
    for {
      _ <- ZIO.succeed(channel.onRead(consumer))
      queueReadIO <- ZIO.succeed(ZIO.succeed(ring.queueRead(channel, buffer)))
      _ <- q.offer(queueReadIO.unit)
    } yield ()
  // )

  /** Synchronized wrapper for IoUring's queueWrite method.
    *
    * @param entry
    *   The IoUringEntry containing the IoUring instance
    * @param channel
    *   The IoUringSocket to write to
    * @param buffer
    *   The ByteBuffer to write from
    * @return
    *   IO[Unit]
    */
  def queueWrite(consumer: Consumer[ByteBuffer], channel: IoUringSocket, buffer: java.nio.ByteBuffer): Task[Unit] =
    for {
      _ <- ZIO.succeed(channel.onWrite(consumer))
      queueWriteIO <- ZIO.succeed(ZIO.succeed(ring.queueWrite(channel, buffer)))
      _ <- q.offer(queueWriteIO.unit)
    } yield ()
}

/** IoUringTbl manages a collection of IoUring instances. It provides a method to get the least used IoUring instance
  * based on reference counters.
  *
  * @param entries
  *   List of IoUringEntry instances
  */
class IoUringTbl(entries: List[IoUringEntry]) {

  /** Get the IoUringEntry with the lowest reference counter value. This helps distribute the load across multiple
    * IoUring instances.
    *
    * @return
    *   IO containing the IoUringEntry with the lowest counter value
    */
  def get: Task[IoUringEntry] = {
    for {
      T <- ZIO.foreach(entries)(rec => ZIO.succeed(rec, rec.cntr.get))

      c <- ZIO.succeed(T.minBy(_._2))
      (entry, count) = c

      b <- ZIO
        .attempt {
          val current = entry.cntr.get()
          val updated = current + 1
          val success = entry.cntr.compareAndSet(current, updated)
          (success, entry)
        }
        .flatMap {
          case (true, e)  => ZIO.succeed(e) // Update was successful
          case (false, _) => get // Update failed due to collision, retry with get
        }
    } yield (b)
  }

  /** Release an IoUringEntry by decrementing its counter.
    *
    * @param entry
    *   The IoUringEntry to release
    * @return
    *   IO[Unit]
    */
  def release(entry: IoUringEntry): Task[Unit] = {
    if (entry.cntr.get <= 0) {
      ZIO.fail(new IllegalStateException("Cannot release IoUringEntry: counter is already at 0"))
    } else {
      ZIO
        .attempt {
          val current = entry.cntr.get()
          val updated = current - 1
          entry.cntr.compareAndSet(current, updated)
        }
        .flatMap {
          case true  => ZIO.unit
          case false => release(entry)
        }
    }
  }

  /** Get the total number of entries in the table.
    *
    * @return
    *   The number of IoUringEntry instances
    */
  def size: Int = entries.size

  def closeIoURings = {
    ZIO.succeed( IoUringTbl.shutdown = true) *> 
    ZIO.foreach( entries )( _.close)
  }
}

object IoUringTbl {

  @volatile
  var shutdown = false

  var server: QuartzH2Server[Any] = null

  private def setServer[Env](srv: QuartzH2Server[Env]) = {

    server = srv.asInstanceOf[QuartzH2Server[Any]]

  }

  def getCqesProcessor(entry: IoUringEntry): Task[Unit] = {
    val processCqes = ZIO.attemptBlocking(entry.ring.getCqes(server.timeout)).catchAll { case _: Throwable =>
      ZIO.logError("IoUring: ring shutdown") *> ZIO.succeed(IoUringTbl.shutdown = true) *> server.shutdown
    }
    // Continue until shutdown becomes true
    processCqes
      .repeatUntil(_ => IoUringTbl.shutdown || server.shutdownFlag)
      .unit
  }

  /** Processes I/O events for a specific IoUringEntry.
    *
    * This method creates a continuous processing loop that waits for signals from the queue when read/write operations
    * are enqueued, executes the IoUring event loop to process completion events, and continues the loop to handle
    * subsequent events.
    *
    * The processor terminates gracefully when the queue is shut down or an error occurs. Each IoUringEntry should have
    * its own processor running to handle its events.
    *
    * @param entry
    *   The IoUringEntry whose events will be processed
    * @return
    *   An IO that runs continuously until the queue is shut down
    */

  def submitProcessor(entry: IoUringEntry): Task[Unit] = {
    val processSubmit = for {
      fiber <- ZIO.descriptor
      queueOpIO <- entry.q.take
      _ <- queueOpIO *> ZIO.succeed(entry.ring.submit())
    } yield ()

    processSubmit
      .catchAll { case e: Throwable =>
        ZIO.logError(s"${e.toString()} - IoUring: submission queue shutdown") *>
          ZIO.succeed(IoUringTbl.shutdown = true) *> server.shutdown
      }
      .catchAllDefect { deffect =>
        ZIO.logError(s"Server halted, cannot exit from callback, critical error") *>
          ZIO.succeed(deffect.printStackTrace()) *>
          ZIO.succeed(IoUringTbl.shutdown = true) *> server.shutdown

      }
      .repeatUntil(_ => IoUringTbl.shutdown || server.shutdownFlag)
  }

  /** Create a new IoUringTbl with the specified number of IoUring instances.
    *
    * @param count
    *   Number of IoUring instances to create
    * @param ringSize
    *   Size of each IoUring instance
    * @return
    *   IO containing a new IoUringTbl
    */
  def apply[Env](server: QuartzH2Server[Env], count: Int, ringSize: Int = 1024): Task[IoUringTbl] = {
    IoUringTbl.shutdown = false //important for tests
    ZIO.succeed(setServer(server)) *>
      ZIO
        .foreach(0 until count)(_ =>
          for {
            q <- Queue.bounded[Task[Unit]](1024)
            counter <- ZIO.succeed(new AtomicInteger)
            ring <- ZIO.succeed(new IoUring(ringSize))
          } yield IoUringEntry(q, /*mutex,*/ counter, ring)
        )
        .flatMap { entries =>
          val tbl = new IoUringTbl(entries.toList)
          // Start a processor for each IoUringEntry
          ZIO
            .foreach(entries) { entry =>
              submitProcessor(entry).fork *> ZIO.blocking{getCqesProcessor(entry)}.fork
              //ZIO.blocking(submitProcessor(entry)).fork *> ZIO.blocking{getCqesProcessor(entry)}.fork
            }
            .as(tbl)
        }
  }
}
