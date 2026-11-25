package org.llm4s.ragbox.ingestion

import org.llm4s.error.NetworkError
import org.llm4s.rag.loader.{DocumentLoader, LoadResult}
import org.llm4s.rag.loader.{UrlPolicy => Llm4sUrlPolicy}
import org.slf4j.LoggerFactory

import java.net.{InetAddress, URI}
import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.util.Try

/**
 * Restrictions on where ingestion may read from.
 *
 * @param allowedRoots directories that directory ingestion may read under. `None` means the
 *                     default: the paths of the configured directory sources (so `INGEST_DIR`,
 *                     when set). An empty list disables directory ingestion through the API.
 * @param url          restrictions on URL and web-crawler ingestion
 */
final case class IngestionPolicyConfig(
  allowedRoots: Option[Seq[String]] = None,
  url: UrlPolicyConfig = UrlPolicyConfig()
)

/**
 * Restrictions on URL and web-crawler ingestion.
 *
 * @param allowedHosts         when non-empty, the only hosts that may be fetched: an entry
 *                             matches the host exactly, and `*.example.com` matches any subdomain
 *                             of example.com. Allowed hosts are still subject to the address check.
 * @param allowPrivateNetworks allow hosts that resolve to loopback, private and other non-public
 *                             addresses (to crawl an intranet). Cloud metadata addresses stay
 *                             refused.
 */
final case class UrlPolicyConfig(
  allowedHosts: Seq[String] = Seq.empty,
  allowPrivateNetworks: Boolean = false
)

/**
 * An ingestion request refused by the ingestion policy. The routes map it to 400 Bad Request;
 * its message is safe to return to the caller.
 */
final case class IngestionRequestRejected(reason: String) extends RuntimeException(reason)

/**
 * Which directories may be ingested: only those under an allowed root, compared after resolving
 * symlinks, so neither `..` nor a symlink can reach outside the roots.
 */
final class PathPolicy(roots: Seq[String]) {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Each root as configured (absolute, normalized) and resolved, when it exists. */
  private def rootForms: Seq[(Path, Option[Path])] =
    roots.map(_.trim).filter(_.nonEmpty).flatMap { root =>
      Try(Paths.get(root).toAbsolutePath.normalize).toOption match {
        case Some(lexical) => Some(lexical -> Try(lexical.toRealPath()).toOption)
        case None =>
          logger.warn(s"Ignoring invalid ingestion root '$root'")
          None
      }
    }

  /**
   * Check that `raw` names a directory under an allowed root.
   *
   * @return the path to walk (as given, so document ids stay as before) and its resolved form,
   *         which llm4s's `DirectoryLoader` stays inside
   */
  def checkDirectory(raw: String): Either[String, PathPolicy.Allowed] = {
    val outside = "Path is outside the allowed ingestion roots"
    for {
      _ <- Either.cond(raw != null && raw.trim.nonEmpty, (), "Path is required")
      _ <- Either.cond(!raw.contains('\u0000'), (), "Path contains invalid characters")
      path <- Try(Paths.get(raw)).toEither.left.map(_ => "Path is not a valid path")
      _ <- Either.cond(
        (0 until path.getNameCount).forall(i => path.getName(i).toString != ".."),
        (),
        "Path must not contain '..'"
      )
      forms = rootForms
      _ <- Either.cond(
        forms.exists(_._2.isDefined),
        (),
        "Directory ingestion is disabled: no allowed roots are configured (ingestion.allowed-roots / INGEST_ALLOWED_ROOTS)"
      )
      absolute <- Try(path.toAbsolutePath.normalize).toEither.left.map(_ => "Path is not a valid path")
      // Lexical check first, so the existence of paths outside the roots is never revealed
      _ <- Either.cond(
        forms.exists { case (lexical, real) => absolute.startsWith(lexical) || real.exists(absolute.startsWith) },
        (),
        outside
      )
      real <- Try(path.toRealPath()).toEither.left.map(_ => "Directory not found or not readable")
      root <- forms.flatMap(_._2).find(real.startsWith).toRight(outside)
      _ <- Either.cond(Files.isDirectory(real), (), "Path is not a directory")
    } yield PathPolicy.Allowed(path, real, root)
  }
}

object PathPolicy {

  /**
   * A directory that may be ingested.
   *
   * @param path the path as requested, to walk from
   * @param real the directory with symlinks resolved
   * @param root the allowed root it is under, resolved
   */
  final case class Allowed(path: Path, real: Path, root: Path)

  private val MaxPatterns = 50
  private val Extension = "[A-Za-z0-9_+-][A-Za-z0-9_.+-]{0,31}".r

  /**
   * Check file patterns (`*.md`, `.md` or `md`) and return their extensions. A pattern may not
   * contain a path separator, so it cannot point the walk somewhere else.
   */
  def checkPatterns(patterns: Set[String]): Either[String, Set[String]] =
    if (patterns.isEmpty) Left("At least one file pattern is required")
    else if (patterns.size > MaxPatterns) Left(s"At most $MaxPatterns file patterns are allowed")
    else
      patterns.toList
        .map { pattern =>
          val ext = pattern.trim.stripPrefix("*.").stripPrefix(".")
          if (pattern.exists(c => c == '/' || c == '\\' || c == '\u0000') || pattern.contains(".."))
            Left(s"Invalid file pattern '${sanitize(pattern)}': patterns must not contain path separators or '..'")
          else if (!Extension.matches(ext))
            Left(s"Invalid file pattern '${sanitize(pattern)}': expected a file extension such as '*.md'")
          else Right(ext.toLowerCase(Locale.ROOT))
        }
        .partitionMap(identity) match {
        case (Nil, exts) => Right(exts.toSet)
        case (error :: _, _) => Left(error)
      }

  private def sanitize(s: String): String = s.take(64).filter(c => !c.isControl)
}

/**
 * Which URLs ingestion may fetch, as the llm4s [[Llm4sUrlPolicy UrlPolicy]] its loaders enforce:
 * http and https only, no embedded credentials, optionally only allow-listed hosts, and (unless
 * private networks are allowed) only hosts whose every address is public.
 *
 * The loaders ask it for every URL they fetch - each configured URL, each crawled page, each
 * redirect hop and each robots.txt - and connect to one of the addresses it returns, so a host
 * cannot pass the check and then resolve somewhere else (DNS rebinding). ragbox also asks it
 * before a run ([[check]]), to refuse a request with a reason instead of failing the fetch.
 *
 * The address rules are llm4s's (`UrlPolicy.Standard`); the allow-list is ragbox's, as its entries
 * match exactly unless they start with `*.`.
 *
 * @param lookup name resolution, replaceable in tests
 */
final class UrlPolicy(
  config: UrlPolicyConfig,
  lookup: String => Seq[InetAddress] = host => InetAddress.getAllByName(host).toSeq
) extends Llm4sUrlPolicy {
  import UrlPolicy._

  private val allowedHosts: Seq[String] =
    config.allowedHosts.map(normalizeHost).filter(_.nonEmpty)

  /** Whether `host` is allowed by the host allow-list (always, when the list is empty). */
  private def hostAllowed(host: String): Boolean = {
    val h = normalizeHost(host)
    allowedHosts.isEmpty || allowedHosts.exists { entry =>
      if (entry.startsWith("*.")) h.endsWith(entry.drop(1))
      else h == entry
    }
  }

  /**
   * The addresses of a host: an IP literal is parsed, never looked up, and a host that only looks
   * like a number (`2130706433`, `0x7f.1`, `127.1`: forms some resolvers read as an address) is
   * refused rather than guessed at.
   */
  private def addressesOf(host: String): Either[NetworkError, Seq[InetAddress]] =
    if (host.contains(':') || DottedQuad.matches(host))
      Try(Seq(InetAddress.getByName(host))).toEither.left.map(_ => refusal("URL host is not a valid IP address"))
    else if (NumericLooking.matches(host)) Left(refusal("URL host is not a valid IP address"))
    else
      Try(lookup(host)).toOption.filter(_.nonEmpty)
        .toRight(NetworkError(Unresolved, None, host))

  private val standard: Llm4sUrlPolicy.Standard =
    Llm4sUrlPolicy.Standard(allowPrivateNetworks = config.allowPrivateNetworks, resolver = addressesOf)

  /** What llm4s's loaders ask before each fetch: the addresses `url` may be fetched from. */
  def resolve(url: URI): Either[NetworkError, Seq[InetAddress]] =
    for {
      scheme <- Right(Option(url.getScheme).map(_.toLowerCase(Locale.ROOT)).getOrElse(""))
      _ <- Either.cond(scheme == "http" || scheme == "https", (), refusal("Only http and https URLs are allowed"))
      _ <- Either.cond(url.getRawUserInfo == null, (), refusal("URLs with embedded credentials are not allowed"))
      host <- Option(url.getHost).map(normalizeHost).filter(_.nonEmpty).toRight(refusal("URL has no valid host"))
      _ <- Either.cond(hostAllowed(host), (), refusal("Host is not in the allowed hosts list (ingestion.url.allowed-hosts)"))
      addresses <- standard.resolve(url).left.map {
        // What llm4s refused (a blocked name or address range): the detail stays in the log
        case blocked if blocked.endpoint == Llm4sRefusal =>
          logger.debug(s"URL refused: ${blocked.message}")
          refusal("URL host is a private, loopback, link-local or otherwise non-public address")
        case other => other
      }
    } yield addresses

  /** Check `raw` before a run; this may resolve its host, so it can block. The reason is safe to return to the caller. */
  def check(raw: String): Either[String, URI] =
    for {
      _ <- Either.cond(raw != null && raw.trim.nonEmpty, (), "URL is required")
      _ <- Either.cond(raw.length <= MaxUrlLength, (), s"URL is longer than $MaxUrlLength characters")
      uri <- Try(new URI(raw.trim)).toEither.left.map(_ => "URL is not valid")
      _ <- resolve(uri).left.map(_.message)
    } yield uri
}

object UrlPolicy {

  private val logger = LoggerFactory.getLogger(classOf[UrlPolicy])

  val MaxUrlLength = 2048

  /** The `endpoint` of the `NetworkError` for a URL the policy refuses (not one it could not resolve). */
  val RefusedEndpoint = "ingestion-policy"

  /** The `endpoint` llm4s's `UrlPolicy.Standard` gives its own refusals. */
  private val Llm4sRefusal = "ssrf-protection"

  private val Unresolved = "URL host could not be resolved"

  private def refusal(reason: String): NetworkError = NetworkError(reason, None, RefusedEndpoint)

  /** Whether `result` is a fetch the policy refused, as opposed to one that failed. */
  def refused(result: LoadResult): Boolean = result match {
    case LoadResult.Failure(_, error: NetworkError, _, _) => error.endpoint == RefusedEndpoint
    case _ => false
  }

  /** `host`, lower case, without IPv6 brackets or a trailing dot. */
  private def normalizeHost(host: String): String =
    host.trim.toLowerCase(Locale.ROOT).stripPrefix("[").stripSuffix("]").stripSuffix(".")

  private val DottedQuad = "(?:[0-9]{1,3}\\.){3}[0-9]{1,3}".r
  private val NumericLooking = "(?:(?:0x[0-9a-f]*|[0-9]+)\\.)*(?:0x[0-9a-f]*|[0-9]+)".r
}

/** A loader that yields fixed results: here, the refusals for URLs the policy rejected. */
private[ingestion] final case class FixedResultsLoader(results: Seq[LoadResult]) extends DocumentLoader {
  def load(): Iterator[LoadResult] = results.iterator
  override def estimatedCount: Option[Int] = Some(results.size)
  def description: String = s"FixedResultsLoader(${results.size})"
}

/** `underlying`'s results, each passed through `transform`. */
private[ingestion] final case class MappedLoader(
  underlying: DocumentLoader,
  transform: LoadResult => LoadResult
) extends DocumentLoader {
  def load(): Iterator[LoadResult] = underlying.load().map(transform)
  def description: String = underlying.description
}
