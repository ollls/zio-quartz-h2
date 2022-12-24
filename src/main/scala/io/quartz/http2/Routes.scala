package io.quartz.http2.routes

import zio.{ZIO, Task}
import zio.stream.ZStream
import io.quartz.http2.model.{Request, Response, Headers, StatusCode, Method}


type HttpRoute = Request => Task[Option[Response]]
type HttpRouteIO = PartialFunction[Request, Task[Response]]


object Routes {
  //route withot environment, gives direct HttpRoute
  def of[Env](pf: HttpRouteIO): HttpRoute = {
    val T1: Request => Task[Option[Response]] = (request: Request) =>
      pf.lift(request) match {
        case Some(c) => c.flatMap(r => (ZIO.succeed(Option(r))))
        case None    => (ZIO.succeed(None))
      }
    T1
  }
}
