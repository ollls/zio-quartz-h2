ZIO2 native, 100% asyncronous Java NIO based implementation of http/2 packet streaming server with TLS encryption implemented as scala ZIO2 effect. Direct native translation of ZIO ZStream chunks into http2 packets (inbound and outbound).

* Use cases:<br> https://github.com/ollls/zio-quartz-h2/blob/master/examples/IO/src/main/scala/Run.scala
* To run:  sbt IO/run
* To debug: switch to "debug" or 'trace" in logback-test.xml
* To access from browser: https://127.0.0.1:8443/IMG_0278.jpeg. ( read log messages and make sure your local file path is OK, also edit path in example/Run scala  )
* You may look at the quartz-h2 CATS port https://github.com/ollls/quartz-h2

* web filter.

```scala

val filter: WebFilter = (r: Request) =>
    ZIO
      .succeed(Response.Error(StatusCode.Forbidden).asText("Denied: " + r.uri.getPath()))
      .when(r.uri.getPath().endsWith("test70.jpeg"))
   
...
...
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
h2load -D10 -c32 -m20  https://localhost:8443/

...

finished in 10.01s, 34663.90 req/s, 711.06KB/s
requests: 346639 total, 347279 started, 346639 done, 346639 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 346651 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 6.94MB (7281267) total, 338.53KB (346651) headers (space savings 90.00%), 677.03KB (693278) data
                     min         max         mean         sd        +/- sd
time for request:      318us    265.96ms     15.88ms     17.08ms    89.69%
time for connect:     6.51ms    380.04ms    181.23ms    117.08ms    59.38%
time to 1st byte:     8.39ms    391.14ms    188.94ms    120.61ms    59.38%
req/s           :    1012.36     1156.19     1082.72       39.02    65.63%

```




