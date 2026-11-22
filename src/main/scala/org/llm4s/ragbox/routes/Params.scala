package org.llm4s.ragbox.routes

import cats.data.{Validated, ValidatedNel}
import cats.effect.IO
import cats.syntax.all._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.dsl.impl.OptionalValidatingQueryParamDecoderMatcher
import org.llm4s.ragbox.model.ErrorResponse

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import scala.util.Try

/**
 * Validation shared by the routes: query parameters, paging and ids.
 *
 * Query parameters are matched with `OptionalValidatingQueryParamDecoderMatcher`, so a value that
 * does not parse (`?page=abc`) is a 400 naming the parameter, rather than the route not matching
 * (404). Use [[withValid]] to unpack them:
 * {{{
 * case GET -> Root / "x" :? Params.Page(page) +& Params.PageSize(size) as _ =>
 *   Params.withValid((Params.opt(page), Params.opt(size)).tupled) { case (page, size) => ... }
 * }}}
 */
object Params {

  /** ISO-8601 instants (`2024-01-01T00:00:00Z`). */
  given instantQueryParamDecoder: QueryParamDecoder[Instant] =
    QueryParamDecoder[String].emap { str =>
      try Right(Instant.parse(str))
      catch {
        case _: DateTimeParseException =>
          Left(ParseFailure(s"Invalid timestamp: $str", "Expected ISO-8601, like 2024-01-01T00:00:00Z"))
      }
    }

  /** `A`'s decoder, with failures that name the parameter (`Invalid 'page': abc`). */
  def named[A](name: String)(using decoder: QueryParamDecoder[A]): QueryParamDecoder[A] =
    (value: QueryParameterValue) =>
      decoder.decode(value).leftMap(_.map(f => ParseFailure(s"Invalid '$name': ${value.value}", f.details)))

  /** An optional parameter whose invalid values are reported by name (see [[withValid]]). */
  class Validating[A](name: String)(using QueryParamDecoder[A])
      extends OptionalValidatingQueryParamDecoderMatcher[A](name)(using named[A](name))

  object Page extends Validating[Int]("page")
  object PageSize extends Validating[Int]("pageSize")
  object Limit extends Validating[Int]("limit")
  object Offset extends Validating[Int]("offset")
  object From extends Validating[Instant]("from")
  object To extends Validating[Instant]("to")
  object Since extends Validating[Instant]("since")

  /** An optional validating parameter, as its matcher extracts it. */
  type Param[A] = Option[ValidatedNel[ParseFailure, A]]

  /** The parameter's value, if given; invalid if it was given but did not parse. */
  def opt[A](param: Param[A]): ValidatedNel[ParseFailure, Option[A]] =
    param.fold[ValidatedNel[ParseFailure, Option[A]]](Validated.validNel(None))(_.map(Some(_)))

  /** Run `f` with the parameters, or answer 400 listing the ones that did not parse. */
  def withValid[A](params: ValidatedNel[ParseFailure, A])(f: A => IO[Response[IO]]): IO[Response[IO]] =
    params.fold(
      errors => BadRequest(ErrorResponse.badRequest(
        "Invalid query parameter",
        Some(errors.toList.map(_.sanitized).mkString("; "))
      ).asJson),
      f
    )

  /**
   * A page: `page` from 1, `pageSize` from 1 to a route's maximum. Out-of-range values are
   * clamped rather than rejected.
   */
  final case class Paging(page: Int, pageSize: Int) {
    def offset: Int = (page - 1) * pageSize
  }

  object Paging {
    def apply(page: Option[Int], pageSize: Option[Int], defaultSize: Int, maxSize: Int): Paging =
      Paging(page.getOrElse(1).max(1), pageSize.getOrElse(defaultSize).max(1).min(maxSize))
  }

  /** A `limit` clamped to [1, max] (`default` when absent). */
  def limit(value: Option[Int], default: Int, max: Int): Int = value.getOrElse(default).max(1).min(max)

  /** An `offset` of at least 0. */
  def offset(value: Option[Int]): Int = value.getOrElse(0).max(0)

  /** `id` as a UUID, if it is one. */
  def uuid(id: String): Option[UUID] = Try(UUID.fromString(id)).toOption.filter(_.toString.equalsIgnoreCase(id))

  /**
   * Run `f` with `id` when it is a UUID; otherwise 404 with `notFound`, as no such resource can
   * exist (rather than passing it to the database, which would fail).
   */
  def withUuid(id: String, notFound: => String)(f: UUID => IO[Response[IO]]): IO[Response[IO]] =
    uuid(id).fold(NotFound(ErrorResponse.notFound(notFound).asJson))(f)
}
