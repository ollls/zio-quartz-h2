package io.quartz.util

import zio.ZIO
import java.io.FileInputStream
import zio.stream.ZStream
import io.quartz.http2.model.{Headers, Method, StatusCode, ContentType, Response, Request}

object HttpRangeRequest {
  def makeResponse(req: Request, file: java.io.File, rangedType: ContentType, BLOCK_SIZE: Int = 32000): Response = {
    val Hdr_Range: Option[Array[String]] =
      req.headers.get("range").map(range => (range.split("=")(1))).map(_.split("-"))
    val jstream = new java.io.FileInputStream(file)

    Hdr_Range match {
      case None =>
        val fileContentType = ContentType.contentTypeFromFileName(file.getName)
        if (fileContentType != rangedType)
          Response
            .Ok()
            .asStream(ZStream.fromInputStream(jstream, chunkSize = BLOCK_SIZE))
            .contentType(ContentType.contentTypeFromFileName(file.getName))
        else
          Response
            .Ok()
            .hdr("Accept-Ranges", "bytes")
            .contentType(ContentType.contentTypeFromFileName(file.getName))

      case Some(minmax: Array[String]) =>
        val minmax =
          if (Hdr_Range.get.length > 1) Hdr_Range.map(m => (m(0).toLong, m(1).toLong)).get
          else Hdr_Range.map(m => (m(0).toLong, file.length() - 1)).get
        jstream.getChannel().position(minmax._1.toLong)
        Response
          .Error(StatusCode.PartialContent)
          .asStream(ZStream.fromInputStream(jstream, chunkSize = BLOCK_SIZE).take(minmax._2))
          .hdr("Content-Range", s"bytes ${minmax._1}-${minmax._2}/${file.length()}")
          .contentType(ContentType.contentTypeFromFileName(file.getName))
    }
  }
}
