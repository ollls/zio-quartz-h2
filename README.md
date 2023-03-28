<img src="quartz-h2.jpeg" width="84" title="quartz-h2"/>

[![Generic badge](https://img.shields.io/badge/zio--quartz--h2-0.4.3-blue)](https://repo1.maven.org/maven2/io/github/ollls/zio-quartz-h2_3/0.4.3)

# Asyncronous Java NIO **http/2 TLS** packet streaming server/client.

ZIO2 native, asyncronous, Java NIO based implementation of http/2 packet streaming server with TLS encryption implemented as scala ZIO2 effect with ALPN h2 tag. Direct native translation of ZIO ZStream chunks into http2 packets (inbound and outbound). Tested and optimized to produce highest possible TPS. Server supports http multipart with ZStream and automatic saving of files for file based multi parts.
```scala
MultiPart.stream(req: Request): ZIO[Any, Throwable, ZStream[Any, Throwable, Headers | Chunk[Byte]]] 
MultiPart.writeAll(req: Request, folderPath: String): Task[Unit]
```

``` 
libraryDependencies += "io.github.ollls" %% "zio-quartz-h2" % "0.4.3"
```
* 0.4.3 template example: client/server (quartz-h2 HTTP/2 client only supports TLS with ALPN H2 HTTP/2 hosts), `sbt run`<br>
https://github.com/ollls/zio-quartz-demo
* Template project with use cases, `sbt run`:<br>https://github.com/ollls/zio-qh2-examples
* Use cases:<br> https://github.com/ollls/zio-quartz-h2/blob/master/examples/IO/src/main/scala/Run.scala, to run: `sbt IO/run`
* To debug: switch to "debug" or 'trace" in logback-test.xml
* You may look at the quartz-h2 CATS port https://github.com/ollls/quartz-h2
* Standard support for ZIO Environment.

```scala

def run =
    val env = ZLayer.fromZIO( ZIO.succeed( "Hello ZIO World!") )
    (for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server("localhost", 8443, 16000, ctx).startIO(R, filter, sync = false)

    } yield (exitCode)).provideSomeLayer( env )

```

* Web filter.

```scala

val filter: WebFilter = (r: Request) =>
    ZIO
      .succeed(Response.Error(StatusCode.Forbidden).asText("Denied: " + r.uri.getPath()))
      .when(r.uri.getPath().endsWith("test70.jpeg"))
   
...

exitCode <- new QuartzH2Server("localhost", 8443, 16000, ctx).startIO(R, filter, sync = false)

```

* File retrieval.

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

* File upload ( smart http2 flow control implemented if disk saving speed cannot keep up with inbound network data.) 

```scala 

 case req @ POST -> Root / "upload" / StringVar(file) =>
      val FOLDER_PATH = "/Users/user000/web_root/"
      val FILE = s"$file"
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
        u <- req.stream.run(ZSink.fromFile(jpath))
      } yield (Response.Ok().asText("OK"))
        
```        
* HTTP Multipart file retrieval.

```scala

 case req @ POST -> Root / "mpart" =>
      MultiPart.writeAll(req, "/Users/user000/tmp1/" ) *> ZIO.succeed(Response.Ok())

```

* How to send data in separate H2 packets of various size

```scala 

    case GET -> Root / "example" =>
      val ts = ZStream.fromChunks(Chunk.fromArray("Block1\n".getBytes()), Chunk.fromArray("Block22\n".getBytes()))
      ZIO.attempt(Response.Ok().asStream(ts))
      
````      

* How to run h2spec.

1. Start server with "sbt IO/run"<br>
2. ./h2spec http2 -h localhost -p 8443 -t -k<br>

You should get:
```
Finished in 3.7611 seconds
94 tests, 92 passed, 1 skipped, 1 failed<br>
```
* Performance test with h2load.

```
h2load -D10 -c32 -m20 https://localhost:8444/test

...

finished in 10.01s, 60531.00 req/s, 591.29KB/s
requests: 605310 total, 605950 started, 605310 done, 605310 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 605310 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 5.77MB (6054828) total, 591.12KB (605310) headers (space savings 90.00%), 0B (0) data
                     min         max         mean         sd        +/- sd
time for request:      229us    117.26ms      8.35ms      3.84ms    68.19%
time for connect:     6.12ms    330.56ms    149.18ms    101.51ms    56.25%
time to 1st byte:     7.19ms    344.09ms    154.27ms    104.43ms    56.25%
req/s           :    1833.28     1959.44     1890.35       37.16    65.63%

```




