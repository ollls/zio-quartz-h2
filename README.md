<img src="quartz-h2.jpeg" width="84" title="quartz-h2"/>

[![Generic badge](https://img.shields.io/badge/zio--quartz--h2-0.5.4-blue)](https://repo1.maven.org/maven2/io/github/ollls/zio-quartz-h2_3/0.5.4)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ollls_zio-quartz-h2&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ollls_zio-quartz-h2)

# Asyncronous Java NIO **http/2 TLS** packet streaming server/client.
It's now with HTTP/1.1

ZIO2 native,asynchronous,Java NIO based implementation of http/2 packet streaming server with TLS encryption implemented as scala ZIO2 effect with ALPN h2 tag. Direct native translation of ZIO ZStream chunks into http2 packets (inbound and outbound). Tested and optimized to produce highest possible TPS. Server supports http multipart with ZStream interface along with automatic file saving for file based multipart uploads.

``` 
libraryDependencies += "io.github.ollls" %% "zio-quartz-h2" % "0.5.4"
```

to run example from zio-quartz-h2 code base directly

```
sbt IO/run
```

* 0.5.4 template example: client/server (quartz-h2 HTTP/2 client only supports TLS with ALPN H2 HTTP/2 hosts)<br>
https://github.com/ollls/zio-quartz-demo
* Template project with use cases, `sbt run`:<br>https://github.com/ollls/zio-qh2-examples
* Use cases:<br> https://github.com/ollls/zio-quartz-h2/blob/master/examples/IO/src/main/scala/Run.scala, to run: `sbt IO/run`
* To debug: switch to "debug" or 'trace" in logback-test.xml, **use "off" or "error" for performace tests with wrk and h2load**. 
* You may look at the quartz-h2 CATS port https://github.com/ollls/quartz-h2
<br>

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
* HTTP Multipart file upload.

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
Finished in 2.1959 seconds
94 tests, 94 passed, 0 skipped, 0 failed<br>
```
* Performance test with h2load.

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


```




