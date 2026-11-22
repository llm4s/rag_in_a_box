package org.llm4s.ragbox.middleware

import cats.effect.IO
import com.comcast.ip4s.{Cidr, IpAddress, Ipv4Address}
import org.http4s.Request
import org.typelevel.ci.CIStringSyntax

/**
 * Works out which client a request comes from, for rate limiting.
 *
 * `X-Forwarded-For` / `X-Real-IP` are only believed when the TCP peer is one of the configured
 * trusted proxies (`RATE_LIMIT_TRUSTED_PROXIES`); anyone else could put any address there to
 * dodge a limit or to fill the limiter with made-up clients. Behind trusted proxies the client
 * is the right-most `X-Forwarded-For` address that is not itself a trusted proxy (the left-most
 * entries are whatever the client chose to send).
 *
 * @param trustedProxies proxy addresses / networks
 */
final class ClientIp(trustedProxies: Seq[Cidr[IpAddress]]) {

  import ClientIp._

  private def trusted(ip: IpAddress): Boolean = trustedProxies.exists(_.contains(ip))

  /** The client address of `req`, or `unknown` when the connection has no peer address. */
  def apply(req: Request[IO]): String =
    req.remoteAddr.map(normalize) match {
      case None => Unknown
      case Some(peer) if !trusted(peer) => peer.toString
      case Some(peer) =>
        val forwarded = req.headers.get(ci"X-Forwarded-For").toList
          .flatMap(_.toList)
          .flatMap(_.value.split(","))
          .map(_.trim)
          .filter(_.nonEmpty)
        if (forwarded.nonEmpty) fromForwardedFor(forwarded).getOrElse(peer.toString)
        else
          req.headers.get(ci"X-Real-IP")
            .flatMap(h => parseIp(h.head.value))
            .map(_.toString)
            .getOrElse(peer.toString)
    }

  /** Right to left: skip trusted proxies; the first other address is the client. */
  private def fromForwardedFor(entries: List[String]): Option[String] = {
    val parsed = entries.map(parseIp)
    if (parsed.exists(_.isEmpty)) {
      // A malformed hop: only trust what our own proxies appended after it
      parsed.reverse.takeWhile(_.isDefined).flatten.find(ip => !trusted(ip)).map(_.toString)
    } else {
      val ips = parsed.flatten
      ips.reverse.find(ip => !trusted(ip)).orElse(ips.headOption).map(_.toString)
    }
  }
}

object ClientIp {

  val Unknown = "unknown"

  /** An IP address (`10.0.0.1`, `::1`) or a CIDR (`10.0.0.0/8`). */
  def parseCidr(value: String): Option[Cidr[IpAddress]] = {
    val v = value.trim
    Cidr.fromString(v).orElse(IpAddress.fromString(v).map(ip => Cidr(ip, if (ip.isInstanceOf[Ipv4Address]) 32 else 128)))
  }

  /** Parse every entry, dropping ones that do not parse (config validation reports those). */
  def apply(trustedProxies: Seq[String]): ClientIp = new ClientIp(trustedProxies.flatMap(parseCidr))

  private def parseIp(value: String): Option[IpAddress] = IpAddress.fromString(value.trim).map(normalize)

  /** IPv4-mapped IPv6 addresses (`::ffff:10.0.0.1`) count as the IPv4 address. */
  private def normalize(ip: IpAddress): IpAddress = ip.collapseMappedV4
}
