package org.llm4s.ragbox.routes

import org.http4s.Uri.Path

/**
 * Path matching for the per-collection endpoints, `/api/v1/collections/{collection}[/{suffix}]`.
 *
 * Collection paths are hierarchical (`eng/backend`), and every per-collection endpoint accepts
 * them in both spellings:
 *  - raw slashes: `/api/v1/collections/eng/backend/stats`
 *  - encoded slashes: `/api/v1/collections/eng%2Fbackend/stats`
 *
 * The routes match the path after `/api/v1/collections` (the `rest` of
 * `"api" /: "v1" /: "collections" /: rest`) with the extractors below. Each one knows the
 * trailing literal segments of its endpoint (`permissions`, `stats`, `config`,
 * `config/preview`); the segments before them, decoded and joined with `/`, are the collection.
 *
 * '''Ambiguity.''' A trailing `permissions`, `stats`, `config` or `config/preview` written as
 * raw path segments always names the sub-resource: `GET /collections/eng/stats` is the
 * statistics of `eng`. To address a collection whose last segment is one of those words, encode
 * the slash before it: `GET /collections/eng%2Fstats` is the collection `eng/stats` itself (a
 * segment containing `%2F` is never a suffix). At the first level, `accessible` and `configs`
 * are list endpoints, not collections.
 */
object CollectionRoute {

  /** Trailing segments that name a sub-resource of a collection, longest first. */
  private val Suffixes: Seq[Vector[String]] =
    Seq(Vector("config", "preview"), Vector("permissions"), Vector("stats"), Vector("config"))

  /** First-level names that are list endpoints, not collections. */
  private val Reserved: Set[String] = Set("accessible", "configs")

  /** The collection `segments` name: decoded and joined with `/`, with no empty level. */
  private def collection(segments: Vector[Path.Segment]): Option[String] =
    Option(segments.map(_.decoded()).mkString("/"))
      .filter(c => segments.nonEmpty && c.split("/", -1).forall(_.nonEmpty))

  /** The sub-resource suffix `segments` end with, if any, with at least one segment before it. */
  private def suffixOf(segments: Vector[Path.Segment]): Option[Vector[String]] =
    Suffixes.find(s => segments.size > s.size && segments.takeRight(s.size).map(_.encoded) == s)

  /** `{collection}/<suffix>`: the collection before the endpoint's trailing literal segments. */
  final class Suffixed private[CollectionRoute] (suffix: Vector[String]) {
    def unapply(rest: Path): Option[String] = {
      val segments = rest.segments
      if (suffixOf(segments).contains(suffix)) collection(segments.dropRight(suffix.size)) else None
    }
  }

  /** `{collection}`: the collection itself (no sub-resource suffix, not a list endpoint). */
  object Bare {
    def unapply(rest: Path): Option[String] = {
      val segments = rest.segments
      if (suffixOf(segments).isDefined) None
      else collection(segments).filterNot(Reserved)
    }
  }

  val Permissions: Suffixed = new Suffixed(Vector("permissions"))
  val Stats: Suffixed = new Suffixed(Vector("stats"))
  val Config: Suffixed = new Suffixed(Vector("config"))
  val ConfigPreview: Suffixed = new Suffixed(Vector("config", "preview"))

  /** Whether `path` (a request's `pathInfo`) is a `/collections/{c}/config` endpoint (not the preview). */
  def isConfig(path: Path): Boolean = below(path).exists(Config.unapply(_).isDefined)

  /** Whether `path` is the `/collections/{c}/config/preview` endpoint. */
  def isConfigPreview(path: Path): Boolean = below(path).exists(ConfigPreview.unapply(_).isDefined)

  /** The part of `path` after `/api/v1/collections`, if `path` is below it. */
  private def below(path: Path): Option[Path] = {
    val segments = path.segments
    Option.when(segments.size > 3 && segments.take(3).map(_.encoded) == Vector("api", "v1", "collections"))(
      Path(segments.drop(3))
    )
  }
}
