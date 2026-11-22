package org.llm4s.ragbox.config

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import scala.util.Try

/**
 * A parsed `DATABASE_URL`.
 *
 * Accepts the libpq / Heroku style `postgres://` and `postgresql://` forms as well as
 * `jdbc:postgresql://`, each with optional userinfo, port, database and query string:
 *
 * {{{
 *   postgresql://user:pass@host:5432/db?sslmode=require
 *   postgres://host/db
 *   jdbc:postgresql://user:pass@host/db
 * }}}
 *
 * pgjdbc does not accept userinfo in the URL, so credentials are split out and the
 * remaining parts are reassembled into a plain `jdbc:postgresql://host:port/db?query` URL.
 *
 * @param host       host name, or a bracketed IPv6 literal (e.g. `[::1]`)
 * @param port       port, if given in the URL
 * @param database   database name (percent-decoded), if given in the URL
 * @param user       user name (percent-decoded), if given in the URL
 * @param password   password (percent-decoded), if given in the URL
 * @param query      raw query string without the leading `?`, passed through to pgjdbc
 */
final case class DatabaseUrl(
  host: String,
  port: Option[Int],
  database: Option[String],
  user: Option[String],
  password: Option[String],
  query: Option[String]
) {

  /**
   * Build the JDBC URL. Credentials are never included.
   *
   * @param defaultPort      port to use when the URL has none
   * @param defaultDatabase  database to use when the URL has none
   */
  def jdbcUrl(defaultPort: Int, defaultDatabase: String): String = {
    val db = DatabaseUrl.encodePathSegment(database.getOrElse(defaultDatabase))
    val q = query.map("?" + _).getOrElse("")
    s"jdbc:postgresql://$host:${port.getOrElse(defaultPort)}/$db$q"
  }

  override def toString: String =
    s"DatabaseUrl(host=$host, port=$port, database=$database, user=$user, password=${password.map(_ => "****")}, query=$query)"
}

object DatabaseUrl {

  /** Default PostgreSQL port, used when a DATABASE_URL omits the port. */
  val DefaultPort: Int = 5432

  private val Schemes = Set("postgres", "postgresql")

  /**
   * Parse a DATABASE_URL.
   *
   * @return the parsed URL, or a human-readable error (never containing the password)
   */
  def parse(raw: String): Either[String, DatabaseUrl] = {
    val trimmed = raw.trim
    val withoutJdbc =
      if (trimmed.regionMatches(true, 0, "jdbc:", 0, 5)) trimmed.substring(5) else trimmed

    val schemeSep = withoutJdbc.indexOf("://")
    if (schemeSep < 0) {
      return Left(
        "DATABASE_URL must look like postgresql://[user[:password]@]host[:port][/database][?params] " +
          "(postgres:// and jdbc:postgresql:// are also accepted)"
      )
    }

    val scheme = withoutJdbc.substring(0, schemeSep).toLowerCase
    if (!Schemes.contains(scheme)) {
      return Left(s"DATABASE_URL has unsupported scheme '$scheme' (expected postgres, postgresql or jdbc:postgresql)")
    }

    val rest = withoutJdbc.substring(schemeSep + 3)

    // Split off the query string. Everything before it is [userinfo@]hostport[/database].
    val (beforeQuery, query) = rest.indexOf('?') match {
      case -1 => (rest, None)
      case i => (rest.substring(0, i), Some(rest.substring(i + 1)).filter(_.nonEmpty))
    }

    // The last '@' separates userinfo from the host. Using the last one tolerates an
    // unencoded '@' or '/' in the password.
    val (userInfo, hostAndPath) = beforeQuery.lastIndexOf('@') match {
      case -1 => (None, beforeQuery)
      case i => (Some(beforeQuery.substring(0, i)), beforeQuery.substring(i + 1))
    }

    val (hostPort, path) = hostAndPath.indexOf('/') match {
      case -1 => (hostAndPath, "")
      case i => (hostAndPath.substring(0, i), hostAndPath.substring(i + 1))
    }

    for {
      hp <- parseHostPort(hostPort)
      (host, port) = hp
      database <- parseDatabase(path)
      creds <- parseUserInfo(userInfo)
      (user, password) = creds
    } yield DatabaseUrl(host, port, database, user, password, query)
  }

  private def parseHostPort(hostPort: String): Either[String, (String, Option[Int])] = {
    if (hostPort.isEmpty) {
      Left("DATABASE_URL is missing a host")
    } else if (hostPort.contains(',')) {
      Left("DATABASE_URL with multiple hosts is not supported")
    } else if (hostPort.startsWith("[")) {
      // IPv6 literal: [addr] or [addr]:port
      hostPort.indexOf(']') match {
        case -1 => Left("DATABASE_URL has an unterminated IPv6 host literal")
        case close =>
          val host = hostPort.substring(0, close + 1)
          val after = hostPort.substring(close + 1)
          if (host.length <= 2) Left("DATABASE_URL is missing a host")
          else if (after.isEmpty) Right((host, None))
          else if (after.startsWith(":")) parsePort(after.substring(1)).map(p => (host, Some(p)))
          else Left(s"DATABASE_URL has unexpected characters after IPv6 host: '$after'")
      }
    } else {
      hostPort.split(":", -1) match {
        case Array(host) => Right((host, None))
        case Array("", _) => Left("DATABASE_URL is missing a host")
        case Array(host, port) => parsePort(port).map(p => (host, Some(p)))
        case _ => Left("DATABASE_URL host contains more than one ':' (wrap IPv6 addresses in [brackets])")
      }
    }
  }

  private def parsePort(port: String): Either[String, Int] =
    port.toIntOption.filter(p => p > 0 && p <= 65535) match {
      case Some(p) if port.forall(_.isDigit) => Right(p)
      case _ => Left(s"DATABASE_URL has an invalid port: '$port'")
    }

  private def parseDatabase(path: String): Either[String, Option[String]] = {
    val trimmed = path.stripSuffix("/")
    if (trimmed.isEmpty) Right(None)
    else if (trimmed.contains('/')) Left(s"DATABASE_URL has an invalid database path: '$path'")
    else decode(trimmed, "database name").map(Some(_))
  }

  private def parseUserInfo(userInfo: Option[String]): Either[String, (Option[String], Option[String])] =
    userInfo match {
      case None => Right((None, None))
      case Some(info) =>
        val (rawUser, rawPassword) = info.indexOf(':') match {
          case -1 => (info, None)
          case i => (info.substring(0, i), Some(info.substring(i + 1)))
        }
        for {
          user <- decode(rawUser, "user name")
          password <- rawPassword match {
            case Some(p) => decode(p, "password").map(Some(_))
            case None => Right(None)
          }
        } yield (Some(user).filter(_.nonEmpty), password)
    }

  /**
   * Percent-decode a URL component. Unlike form decoding, '+' is kept literally.
   */
  private def decode(value: String, what: String): Either[String, String] =
    Try(URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)).toEither
      .left.map(_ => s"DATABASE_URL has an invalid percent-encoding in the $what")

  /**
   * Percent-encode a database name for use as a JDBC URL path segment.
   * pgjdbc percent-decodes the database name, so anything outside the unreserved set is encoded.
   */
  private[config] def encodePathSegment(value: String): String = {
    val sb = new StringBuilder
    value.getBytes(StandardCharsets.UTF_8).foreach { b =>
      val c = (b & 0xff).toChar
      if (c.isLetterOrDigit && c < 128 || c == '-' || c == '_' || c == '.' || c == '~') sb.append(c)
      else sb.append(f"%%${b & 0xff}%02X")
    }
    sb.toString
  }
}
