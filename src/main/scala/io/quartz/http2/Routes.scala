package io.quartz.http2.routes

import zio.{ZIO, Task}
import zio.stream.ZStream
import io.quartz.http2.model.{Request, Response, Headers, StatusCode, Method}


type HttpRoute[Env] = Request => ZIO[Env, Throwable, Option[Response]]
type WebFilter[Env] = Request => ZIO[Env, Throwable, Either[Response, Request]]
type HttpRouteIO[Env] = PartialFunction[Request, ZIO[Env, Throwable, Response]]

object Routes {
  // route withot environment, gives direct HttpRoute
  def of[Env](pf: HttpRouteIO[Env], filter: WebFilter[Env]): HttpRoute[Env] = {
    val route = (request: Request) =>
      pf.lift(request) match {
        case Some(c) => c.flatMap(r => (ZIO.succeed(Option(r))))
        case None    => (ZIO.succeed(None))
      }
    (r0: Request) =>
      filter(r0).flatMap {
        case Right(request) => route(request)
        case Left(response) =>
          ZIO.logWarning(s"Web filter denied acess with response code ${response.code}") *> ZIO.succeed(Some(response))
      }
  }

}
