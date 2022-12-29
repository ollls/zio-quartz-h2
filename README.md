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
      } yield (Response
        .Ok()
        .asStream(ZStream.fromFile( jpath, BLOCK_SIZE ))
        .contentType(ContentType.contentTypeFromFileName(FILE)))
        
```        
