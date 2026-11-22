package org.llm4s.ragbox.ingestion

import org.llm4s.core.safety.NetworkSecurity
import org.llm4s.error.ProcessingError
import org.llm4s.rag.loader.{DocumentLoader, FileLoader, LoadResult}
import org.slf4j.LoggerFactory

import java.io.IOException
import java.net.{InetAddress, URI}
import java.nio.file.{FileVisitOption, FileVisitResult, Files, Path, Paths, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import scala.collection.mutable
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
 * @param allowPrivateNetworks skip the check that a URL's host resolves only to public addresses.
 *                             Note that the llm4s 0.6.0 loaders apply their own, non-configurable
 *                             check when they fetch, so private addresses are still refused there.
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
   *         which the walk must stay inside
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
   * @param real the directory with symlinks resolved; the walk never leaves it
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
 * Loads the files under a directory, like llm4s's `DirectoryLoader`, but never leaves `confineTo`.
 *
 * `DirectoryLoader` follows symlinks wherever they point. This walk follows a symlink only when
 * its target is inside `confineTo`, loads only regular files, and checks each file again just
 * before reading it. Document ids and metadata are the same as `DirectoryLoader`'s.
 *
 * @param maxDepth maximum recursion depth (0 = the directory itself only)
 */
final case class ConfinedDirectoryLoader(
  path: Path,
  confineTo: Path,
  extensions: Set[String],
  recursive: Boolean = true,
  metadata: Map[String, String] = Map.empty,
  maxDepth: Int = 10
) extends DocumentLoader {

  private def inside(p: Path): Boolean = Try(p.toRealPath()).toOption.exists(_.startsWith(confineTo))

  private def matches(file: Path): Boolean = {
    val name = file.getFileName.toString.toLowerCase(Locale.ROOT)
    extensions.exists(ext => name.endsWith(s".${ext.toLowerCase(Locale.ROOT)}"))
  }

  def load(): Iterator[LoadResult] = {
    val dir = path.toFile
    if (!dir.isDirectory || !inside(path))
      Iterator(LoadResult.listingFailure(path.toString, ProcessingError("load", s"Not a readable directory: $path")))
    else {
      val files = mutable.ArrayBuffer.empty[Path]
      val visitor = new SimpleFileVisitor[Path] {
        override def preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult =
          if (inside(d)) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE

        override def visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (attrs.isRegularFile && matches(f) && inside(f)) files += f
          FileVisitResult.CONTINUE
        }

        // Unreadable entries and symlink loops are skipped, as DirectoryLoader skips unreadable ones
        override def visitFileFailed(f: Path, e: IOException): FileVisitResult = FileVisitResult.CONTINUE
      }
      val depth = if (recursive) maxDepth + 1 else 1
      Files.walkFileTree(path, java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS), depth, visitor)

      val fileMetadata = metadata + ("directory" -> dir.getName)
      files.iterator.flatMap { file =>
        // The tree can change after the walk: check again just before reading
        if (inside(file) && Files.isRegularFile(file)) FileLoader(file, fileMetadata).load()
        else Iterator(LoadResult.skipped(file.toString, "Outside the ingestion directory"))
      }
    }
  }

  def description: String = s"ConfinedDirectoryLoader($path, ${extensions.mkString(",")})"
}

/**
 * Which URLs may be fetched: http and https only, optionally only from allow-listed hosts, and
 * (unless private networks are allowed) only hosts whose every address is public.
 *
 * Hosts are resolved here, but the loader resolves them again when it connects, so a host whose
 * DNS answer changes in between (DNS rebinding) is not covered by this check alone.
 *
 * @param resolve name resolution, replaceable in tests
 */
final class UrlPolicy(
  config: UrlPolicyConfig,
  resolve: String => Seq[InetAddress] = host => InetAddress.getAllByName(host).toSeq
) {
  import UrlPolicy._

  private val allowedHosts: Seq[String] =
    config.allowedHosts.map(normalizeHost).filter(_.nonEmpty)

  /** Whether `host` is allowed by the host allow-list (always, when the list is empty). */
  def hostAllowed(host: String): Boolean = {
    val h = normalizeHost(host)
    allowedHosts.isEmpty || allowedHosts.exists { entry =>
      if (entry.startsWith("*.")) h.endsWith(entry.drop(1))
      else h == entry
    }
  }

  /** Whether the host of `url` is allowed by the host allow-list. */
  def urlHostAllowed(url: String): Boolean =
    hostOf(url).exists(hostAllowed)

  /** Check `raw`; this may resolve its host, so it can block. */
  def check(raw: String): Either[String, URI] =
    for {
      _ <- Either.cond(raw != null && raw.trim.nonEmpty, (), "URL is required")
      _ <- Either.cond(raw.length <= MaxUrlLength, (), s"URL is longer than $MaxUrlLength characters")
      uri <- Try(new URI(raw.trim)).toEither.left.map(_ => "URL is not valid")
      scheme = Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
      _ <- Either.cond(scheme == "http" || scheme == "https", (), "Only http and https URLs are allowed")
      _ <- Either.cond(uri.getRawUserInfo == null, (), "URLs with embedded credentials are not allowed")
      host <- hostOf(uri).toRight("URL has no valid host")
      _ <- Either.cond(hostAllowed(host), (), "Host is not in the allowed hosts list (ingestion.url.allowed-hosts)")
      _ <- if (config.allowPrivateNetworks) Right(()) else checkAddresses(host)
    } yield uri

  private def checkAddresses(host: String): Either[String, Unit] = {
    val blocked = "URL host is a private, loopback, link-local or otherwise non-public address"
    val addresses: Either[String, Seq[InetAddress]] =
      if (NetworkSecurity.isBlockedHostname(host) || host == "localhost" || host.endsWith(".localhost"))
        Left(blocked)
      else if (host.contains(':'))
        // IPv6 literal: parsed, never looked up
        Try(Seq(InetAddress.getByName(host))).toEither.left.map(_ => "URL host is not a valid IPv6 address")
      else if (looksNumeric(host))
        // Every numeric form a resolver may accept (2130706433, 0x7f.1, 0177.0.0.1, 127.1), parsed here
        parseNumericIPv4(host).map(Seq(_)).toRight("URL host is not a valid IPv4 address")
      else
        Try(resolve(host)).toEither.left.map(_ => "URL host could not be resolved")
          .filterOrElse(_.nonEmpty, "URL host could not be resolved")
    addresses.flatMap(as => Either.cond(!as.exists(isBlockedAddress), (), blocked))
  }
}

object UrlPolicy {

  val MaxUrlLength = 2048

  /**
   * Whether `address` is not a public unicast address: loopback, private (RFC 1918, ULA
   * fc00::/7), link-local (including cloud metadata 169.254.169.254 and fd00:ec2::254),
   * unspecified, multicast, CGNAT 100.64.0.0/10, reserved and documentation ranges, and IPv6
   * forms that embed a blocked IPv4 address (IPv4-mapped, NAT64, 6to4).
   */
  def isBlockedAddress(address: InetAddress): Boolean = NetworkSecurity.isBlockedIP(address)

  /** The host of `url`, lower case, without IPv6 brackets or a trailing dot. */
  def hostOf(url: String): Option[String] = Try(new URI(url.trim)).toOption.flatMap(hostOf)

  private def hostOf(uri: URI): Option[String] =
    Option(uri.getHost)
      // java.net.URI rejects some hosts that java.net.URL (which the loaders connect with) accepts
      .orElse(Try(uri.toURL.getHost).toOption)
      .map(normalizeHost)
      .filter(_.nonEmpty)

  private def normalizeHost(host: String): String =
    host.trim.toLowerCase(Locale.ROOT).stripPrefix("[").stripSuffix("]").stripSuffix(".")

  private val DecimalPart = "[0-9]+".r
  private val HexPart = "0x[0-9a-f]*".r

  private def looksNumeric(host: String): Boolean =
    host.split("\\.", -1).forall(p => DecimalPart.matches(p) || HexPart.matches(p))

  /**
   * Parse the legacy `inet_aton` IPv4 forms: one to four parts, each decimal, octal (leading 0)
   * or hex (0x), the last part filling the remaining bytes.
   */
  private[ingestion] def parseNumericIPv4(host: String): Option[InetAddress] = {
    def part(p: String): Option[Long] =
      if (HexPart.matches(p)) {
        val digits = p.drop(2)
        if (digits.isEmpty) Some(0L) else Try(java.lang.Long.parseLong(digits, 16)).toOption
      } else if (p.length > 1 && p.startsWith("0")) Try(java.lang.Long.parseLong(p.drop(1), 8)).toOption
      else Try(java.lang.Long.parseLong(p)).toOption

    val parts = host.split("\\.", -1).toSeq
    if (parts.isEmpty || parts.size > 4 || parts.exists(_.isEmpty) || parts.exists(_.length > 20)) None
    else {
      val values = parts.map(part)
      if (values.exists(_.isEmpty)) None
      else {
        val vs = values.flatten
        val leading = vs.init
        val last = vs.last
        val lastBytes = 4 - leading.size
        if (leading.exists(v => v < 0 || v > 255) || last < 0 || last >= (1L << (8 * lastBytes))) None
        else {
          val value = leading.foldLeft(0L)((acc, v) => (acc << 8) | v) << (8 * lastBytes) | last
          val bytes = Array.tabulate[Byte](4)(i => ((value >> (8 * (3 - i))) & 0xff).toByte)
          Try(InetAddress.getByAddress(bytes)).toOption
        }
      }
    }
  }
}

/** A loader that yields fixed results: here, the refusals for URLs the policy rejected. */
private[ingestion] final case class FixedResultsLoader(results: Seq[LoadResult]) extends DocumentLoader {
  def load(): Iterator[LoadResult] = results.iterator
  override def estimatedCount: Option[Int] = Some(results.size)
  def description: String = s"FixedResultsLoader(${results.size})"
}

/** `underlying`'s results, with each successful document passed through `filter`. */
private[ingestion] final case class FilteredLoader(
  underlying: DocumentLoader,
  filter: LoadResult.Success => LoadResult
) extends DocumentLoader {
  def load(): Iterator[LoadResult] = underlying.load().map {
    case success: LoadResult.Success => filter(success)
    case other => other
  }
  def description: String = underlying.description
}
