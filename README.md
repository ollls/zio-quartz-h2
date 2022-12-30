ZIO2 native, 100% asyncronous Java NIO based implementation of http/2 packet streaming server with TLS encryption implemented as scala ZIO2 effect. Direct native translation of ZIO ZStream chunks into http2 packets (inbound and outbound).

* To run:  sbt IO/run
* To debug: switch to "debug" or 'trace" in logback-test.xml
* To access from browser: https://127.0.0.1:8443/IMG_0278.jpeg. ( read log messages and make sure your local file path is OK, also edit path in example/Run scala  )

```scala 

case GET -> Root / StringVar(file) =>
      val FOLDER_PATH = "/Users/user000/web_root/"
      val FILE = s"$file"
      val BLOCK_SIZE = 16000
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
      } yield (Response
        .Ok()
        .asStream(ZStream.fromFile( jpath, BLOCK_SIZE ))
        .contentType(ContentType.contentTypeFromFileName(FILE)))

```

* File upload ( smart http2 flow control implemented if disk saving speed cannot keep up with inbound network data.) 

```scala 

 case GET -> Root / StringVar(file) =>
      val FOLDER_PATH = "/Users/user000/web_root/"
      val FILE = s"$file"
      val BLOCK_SIZE = 16000
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
        //check for presence, ZStream.fromFile() can be created with wrong path
        present <- ZIO.attempt(jpath.exists())
        _ <- ZIO.fail(new java.io.FileNotFoundException).when(present == false)
      } yield (Response
        .Ok()
        .asStream(ZStream.fromFile(jpath, BLOCK_SIZE))
        .contentType(ContentType.contentTypeFromFileName(FILE)))
      }
        
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

You should get:<br>
```
Finished in 3.7611 seconds<br>
94 tests, 92 passed, 1 skipped, 1 failed<br>
```




