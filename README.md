<img src="quartz-h2.jpeg" width="84" title="quartz-h2"/>

[![Generic badge](https://img.shields.io/badge/zio--quartz--h2-0.6.0-blue)](https://repo1.maven.org/maven2/io/github/ollls/zio-quartz-h2_3/0.6.0)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ollls_zio-quartz-h2&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ollls_zio-quartz-h2)

# Asynchronous HTTP/2 TLS packet streaming server/client with multiple I/O options
It now supports HTTP/1.1 and IO-Uring on Linux platforms

ZIO2 native, asynchronous implementation of HTTP/2 packet streaming server with TLS encryption implemented as scala ZIO2 effect with ALPN h2 tag. Direct native translation of ZIO ZStream chunks into HTTP/2 packets (inbound and outbound). Tested and optimized to produce highest possible TPS. Server supports HTTP multipart with ZStream interface along with automatic file saving for file based multipart uploads.

## I/O Implementation Options

- **Java NIO**: Cross-platform compatibility with high performance
- **Linux IO-Uring**: Native Linux kernel I/O for maximum performance (new in v0.6.0)
- **Synchronous Mode**: Traditional blocking Java sockets for specific use cases

``` 
libraryDependencies += "io.github.ollls" %% "zio-quartz-h2" % "0.6.0"
```

to run example from zio-quartz-h2 code base directly

```
sbt IO/run
```

## Using IO-Uring on Linux

To utilize the new IO-Uring implementation on Linux platforms:

```scala
// Configure the number of IO-Uring instances
val NUMBER_OF_RING_INSTANCES = 1

// Recommended ZIO Runtime configuration for IO-Uring
override val bootstrap = zio.Runtime.removeDefaultLoggers ++ SLF4J.slf4j ++ zio.Runtime.setExecutor(
  zio.Executor.fromJavaExecutor(Executors.newWorkStealingPool(Runtime.getRuntime().availableProcessors() - NUMBER_OF_RING_INSTANCES))
) ++ zio.Runtime.setBlockingExecutor(zio.Executor.fromJavaExecutor(Executors.newCachedThreadPool()))

// Start server with IO-Uring
exitCode <- new QuartzH2Server(
  "localhost",
  8443,
  16000,
  ctx,
  onConnect = onConnect,
  onDisconnect = onDisconnect
).startIO_linuxOnly(NUMBER_OF_RING_INSTANCES, R, filter)
```

For a complete working example of IO-Uring implementation, refer to the [examples/IOU/src/main/scala/Run.scala](https://github.com/ollls/zio-quartz-h2/blob/master/examples/IOU/src/main/scala/Run.scala) file in the repository.

For a complete working example of Java NIO implementation, refer to the [examples/IO/src/main/scala/Run.scala](https://github.com/ollls/zio-quartz-h2/blob/master/examples/IO/src/main/scala/Run.scala) file in the repository.


### Logging.

Use .../src/main/resources/logback-test.xml to tailor to your specific requirements.

Also you may use options to control logging level.
```
sbt "run --debug"
sbt "run --error"
sbt "run --off"
```

## Version Information

- **Current Version**: 0.6.0
- **Major Changes**: Added Linux IO-Uring support, upgraded to Scala 3.3, updated logback-classic to 1.5.17

### Template Examples
* Template project with use cases, `sbt run`:<br>https://github.com/ollls/zio-qh2-examples
* Use cases:<br> https://github.com/ollls/zio-quartz-h2/blob/master/examples/IO/src/main/scala/Run.scala, to run: `sbt IO/run`
* To debug: switch to "debug" or 'trace" in logback-test.xml, **use "off" or "error" for performace tests with wrk and h2load**. 
* You may look at the quartz-h2 CATS port https://github.com/ollls/quartz-h2
<br>

## Features

- HTTP/2 with TLS encryption and ALPN h2 tag
- HTTP/1.1 support
- Multiple I/O implementations (Java NIO and Linux IO-Uring)
- ZIO2 native implementation
- High-performance packet streaming
- HTTP multipart support with ZStream interface
- Automatic file saving for multipart uploads
- Web filter support
- Configurable logging
- **Reactive Flow Control**: Advanced backpressure mechanisms that integrate with ZStream
- **End-to-End Backpressure**: Complete backpressure chain from network socket to application logic
- **Threshold-Based Window Updates**: Optimized HTTP/2 flow control with intelligent window management

### Standard support for ZIO Environment.

```scala

def run =
    val env = ZLayer.fromZIO( ZIO.succeed( "Hello ZIO World!") )
    (for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server("localhost", 8443, 16000, ctx).startIO(R, filter, sync = false)

    } yield (exitCode)).provideSomeLayer( env )

```

### Webfilter support with Either[Response, Request]. Provide filter as a parameter QuartzH2Server()

```scala

val filter: WebFilter = (request: Request) =>
  ZIO.attempt(
    Either.cond(
     !request.uri.getPath().startsWith("/private"),
     request.hdr("test_tid" -> "ABC123Z9292827"),
     Response.Error(StatusCode.Forbidden).asText("Denied: " + request.uri.getPath())
    )
 )    
```

```scala

exitCode <- new QuartzH2Server("localhost", 8443, 16000, ctx).startIO(R, filter, sync = false)

```

### File retrieval.

```scala 

case GET -> Root / StringVar(file) =>
      val FOLDER_PATH = "/Users/user000/web_root/"
      val FILE = s"$file"
      val BLOCK_SIZE = 16000
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
        present <- ZIO.attempt(jpath.exists())
        _ <- ZIO.fail(new java.io.FileNotFoundException).when(present == false)

      } yield (Response
        .Ok()
        .asStream(ZStream.fromFile( jpath, BLOCK_SIZE ))
        .contentType(ContentType.contentTypeFromFileName(FILE)))

```

### File upload ( smart http2 flow control implemented if disk saving speed cannot keep up with inbound network data.) 

```scala 

 case req @ POST -> Root / "upload" / StringVar(file) =>
      val FOLDER_PATH = "/Users/user000/web_root/"
      val FILE = s"$file"
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
        u <- req.stream.run(ZSink.fromFile(jpath))
      } yield (Response.Ok().asText("OK"))
        
```        
### HTTP Multipart file upload.

```scala

 case req @ POST -> Root / "mpart" =>
      MultiPart.writeAll(req, "/Users/user000/tmp1/" ) *> ZIO.succeed(Response.Ok())

```

### How to send data in separate H2 packets of various size

```scala 

    case GET -> Root / "example" =>
      val ts = ZStream.fromChunks(Chunk.fromArray("Block1\n".getBytes()), Chunk.fromArray("Block22\n".getBytes()))
      ZIO.attempt(Response.Ok().asStream(ts))
      
````      

### How to run h2spec.

1. Start server with "sbt IO/run"<br>
2. ./h2spec http2 -h localhost -p 8443 -t -k<br>

You should get:
```
Finished in 2.1959 seconds
94 tests, 94 passed, 0 skipped, 0 failed<br>
```
### Performance test with h2load.

```
h2load -D10 -c68 -m30 -t2 https://localhost:8443/test

...

finished in 10.01s, 65292.20 req/s, 637.89KB/s
requests: 652922 total, 654452 started, 652922 done, 652922 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 652922 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 6.23MB (6531974) total, 637.62KB (652922) headers (space savings 90.00%), 0B (0) data
                     min         max         mean         sd        +/- sd
time for request:      231us    144.82ms     19.31ms     10.02ms    73.06%
time for connect:     7.39ms    860.22ms    385.69ms    278.36ms    54.90%
time to 1st byte:     9.10ms    875.77ms    398.01ms    282.96ms    54.90%
req/s           :       0.00     1489.99      959.99      563.28    75.00%
