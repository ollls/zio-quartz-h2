package io.quartz.util

import zio.ZIO
import java.io.FileInputStream
import zio.stream.ZStream
import io.quartz.http2.model.{Headers, Method, StatusCode, ContentType, Response, Request}

/**
 * Utility for handling HTTP Range requests according to RFC 7233.
 * Provides functionality to create appropriate HTTP responses for regular and range requests
 * for file resources.
 */
object HttpRangeRequest {
  
  /**
   * Creates an HTTP response for a file resource, handling range requests if present.
   *
   * @param req The HTTP request that may contain Range header
   * @param file The file to be served
   * @param rangedType The content type that should support range requests
   * @param BLOCK_SIZE The chunk size for streaming the file content (in bytes)
   * @return An HTTP Response object configured based on the request and range header
   */
  def makeResponse(req: Request, file: java.io.File, rangedType: ContentType, BLOCK_SIZE: Int = 32000): Response = {
    val Hdr_Range: Option[Array[String]] =
      req.headers.get("range").map(range => (range.split("=")(1))).map(_.split("-"))
    val jstream = new java.io.FileInputStream(file)
    val fileContentType = ContentType.contentTypeFromFileName(file.getName)

    Hdr_Range match {
      case None =>
        // No range header, return the entire file
        if (fileContentType != rangedType)
          Response
            .Ok()
            .asStream(ZStream.fromInputStream(jstream, chunkSize = BLOCK_SIZE))
            .contentType(fileContentType)
        else
          Response
            .Ok()
            .hdr("Accept-Ranges", "bytes")
            .contentType(fileContentType)

      case Some(rangeValues) =>
        // Parse range values: bytes=start-end
        val (start, end) = if (rangeValues.length >= 2 && rangeValues(1).nonEmpty) {
          (rangeValues(0).toLong, rangeValues(1).toLong)
        } else {
          (rangeValues(0).toLong, file.length() - 1)
        }
        
        // Position the stream at the start of the requested range
        jstream.getChannel().position(start)
        
        // Calculate the number of bytes to read
        val bytesToRead = end - start + 1
        
        Response
          .Error(StatusCode.PartialContent)
          .asStream(ZStream.fromInputStream(jstream, chunkSize = BLOCK_SIZE).take(bytesToRead))
          .hdr("Content-Range", s"bytes ${start}-${end}/${file.length()}")
          .contentType(fileContentType)
    }
  }
}
