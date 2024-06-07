/*
 * Copyright 2017-2024 Lenses.io Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lenses.streamreactor.connect.http.sink.client
import cats.effect.IO
import cats.implicits.none
import com.typesafe.scalalogging.LazyLogging
import io.lenses.streamreactor.connect.http.sink.tpl.ProcessedTemplate
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
class HttpRequestSender(
  sinkName:       String,
  authentication: Option[Authentication], // ssl, basic, oauth2, proxy
  method:         Method,
  client:         Client[IO],
) extends LazyLogging {

  private case class HeaderInfo(contentType: Option[`Content-Type`], headers: Headers)

  private def buildHeaders(headers: Seq[(String, String)]): Either[Throwable, HeaderInfo] = {

    val (contentTypeHeaders, otherHeaders) = headers
      .partition(_._1.equalsIgnoreCase("Content-Type"))

    for {
      contentTypeSingle <- Either.cond(
        contentTypeHeaders.size <= 1,
        contentTypeHeaders.headOption.map {
          case (_, ct) => ct
        },
        new IllegalArgumentException("Excessive content types"),
      )
      contentTypeParsed: Option[`Content-Type`] <- contentTypeSingle.map(`Content-Type`.parse) match {
        case Some(Left(ex)) => Left(ex)
        case Some(Right(r: `Content-Type`)) => Right(Some(r))
        case None => Right(none)
      }
    } yield {
      HeaderInfo(
        contentTypeParsed,
        Headers(otherHeaders.map {
          case (name, value) =>
            Header.ToRaw.rawToRaw(new Header.Raw(CIString(name), value))
        }: _*),
      )
    }
  }

  def sendHttpRequest(
    processedTemplate: ProcessedTemplate,
  ): IO[Unit] =
    for {
      tpl: ProcessedTemplate <- IO.pure(processedTemplate)

      uri <- IO.pure(Uri.unsafeFromString(processedTemplate.endpoint))
      _   <- IO.delay(logger.debug(s"[$sinkName] sending a http request to url $uri"))

      clientHeaders: HeaderInfo <- IO.fromEither(buildHeaders(tpl.headers))

      request <- IO {
        Request[IO](
          method  = method,
          uri     = uri,
          headers = clientHeaders.headers,
        )
          .withEntity(processedTemplate.content)
      }
      requestWithContentType = clientHeaders.contentType.fold(request)(request.withContentType)
      // Add authentication if present
      authenticatedRequest <- IO {
        authentication.fold(requestWithContentType) {
          case BasicAuthentication(username, password) =>
            requestWithContentType.putHeaders(Authorization(BasicCredentials(username, password)))
        }
      }
      _ <- IO.delay(logger.debug(s"[$sinkName] Auth: $authenticatedRequest"))
      response <- client.expect[String](authenticatedRequest).onError(e =>
        IO {
          logger.error(s"[$sinkName] error writing to HTTP endpoint", e.getMessage)
        } *> IO.raiseError(e),
      )
      _ <- IO.delay(logger.trace(s"[$sinkName] Response: $response"))
    } yield ()

}
