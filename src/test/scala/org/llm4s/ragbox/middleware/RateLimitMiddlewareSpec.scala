package org.llm4s.ragbox.middleware

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.comcast.ip4s._
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIStringSyntax

import scala.concurrent.duration._

class RateLimitMiddlewareSpec extends AnyFlatSpec with Matchers {

  /** A request from TCP peer `peer`. */
  private def from(peer: String, req: Request[IO] = Request[IO](Method.GET, uri"/api/v1/stats")): Request[IO] =
    req.withAttribute(
      Request.Keys.ConnectionInfo,
      Request.Connection(
        local = SocketAddress(ipv4"127.0.0.1", port"8080"),
        remote = SocketAddress(IpAddress.fromString(peer).get, port"50000"),
        secure = false
      )
    )

  private def xff(value: String)(req: Request[IO]): Request[IO] = req.putHeaders(Header.Raw(ci"X-Forwarded-For", value))

  // ---------------------------------------------------------------- client IP

  "ClientIp" should "ignore forwarding headers from peers that are not trusted proxies" in {
    val ip = ClientIp(Seq.empty)
    ip(xff("1.2.3.4")(from("203.0.113.9"))) shouldBe "203.0.113.9"
    ip(from("203.0.113.9").putHeaders(Header.Raw(ci"X-Real-IP", "1.2.3.4"))) shouldBe "203.0.113.9"
    ip(Request[IO]()) shouldBe ClientIp.Unknown
  }

  it should "take the right-most untrusted X-Forwarded-For address behind trusted proxies" in {
    val ip = ClientIp(Seq("10.0.0.0/8", "192.168.1.1"))
    // the client prepended a fake address; the proxy appended the real one
    ip(xff("6.6.6.6, 198.51.100.7")(from("10.1.2.3"))) shouldBe "198.51.100.7"
    // two trusted proxies in a row
    ip(xff("198.51.100.7, 10.9.9.9")(from("192.168.1.1"))) shouldBe "198.51.100.7"
    // X-Real-IP when there is no X-Forwarded-For
    ip(from("10.1.2.3").putHeaders(Header.Raw(ci"X-Real-IP", "198.51.100.8"))) shouldBe "198.51.100.8"
    // no header: the proxy itself
    ip(from("10.1.2.3")) shouldBe "10.1.2.3"
    // only trusted hops: the left-most
    ip(xff("10.0.0.5, 10.0.0.6")(from("10.1.2.3"))) shouldBe "10.0.0.5"
    // garbage left of the proxies' entries is not believed
    ip(xff("not-an-ip, 198.51.100.7")(from("10.1.2.3"))) shouldBe "198.51.100.7"
    ip(xff("not-an-ip")(from("10.1.2.3"))) shouldBe "10.1.2.3"
  }

  it should "treat IPv4-mapped IPv6 peers as their IPv4 address" in {
    ClientIp(Seq("10.0.0.0/8"))(xff("198.51.100.7")(from("::ffff:10.0.0.1"))) shouldBe "198.51.100.7"
  }

  // ---------------------------------------------------------------- bounded counter

  "WindowCounter" should "never hold more than maxKeys keys" in {
    val (size, thirdAllowed) = (for {
      counter <- WindowCounter.create(1.minute, maxKeys = 100)
      _ <- (1 to 1000).toList.traverse_(i => counter.tryAcquire(s"client-$i", 5))
      size <- counter.size
      // the newest key is still tracked: its 2nd request is allowed, its 3rd is not
      _ <- counter.tryAcquire("client-1000", 2)
      third <- counter.tryAcquire("client-1000", 2)
    } yield (size, third)).unsafeRunSync()
    size shouldBe 100
    thirdAllowed shouldBe false
  }

  it should "start a new window once the old one has passed" in {
    // Virtual time (TestControl), so the window boundary is exact rather than wall-clock dependent
    val result = TestControl.executeEmbed(for {
      counter <- WindowCounter.create(1.minute, maxKeys = 10)
      a <- counter.tryAcquire("k", 1)
      _ <- IO.sleep(1.minute - 1.milli)
      b <- counter.tryAcquire("k", 1)
      _ <- IO.sleep(1.milli)
      c <- counter.tryAcquire("k", 1)
    } yield (a, b, c)).unsafeRunSync()
    result shouldBe ((true, false, true))
  }

  // ---------------------------------------------------------------- middleware

  private val ok: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "v1" / "stats" => Ok("stats")
    case GET -> Root / "health" => Ok("healthy")
    case req @ POST -> Root / "api" / "v1" / "auth" / "login" =>
      req.as[Json].flatMap { body =>
        if (body.hcursor.get[String]("password").contains("right-password")) Ok("token")
        else Unauthorized(headers.`WWW-Authenticate`(Challenge("Bearer", "ragbox")))
      }
  }

  private def app(config: RateLimitMiddleware.Config): HttpApp[IO] =
    RateLimitMiddleware(config, RateLimitMiddleware.createLimiters(config).unsafeRunSync())(ok).orNotFound

  private def statuses(app: HttpApp[IO], reqs: Seq[Request[IO]]): List[Status] =
    reqs.toList.traverse(r => app.run(r).map(_.status)).unsafeRunSync()

  "RateLimitMiddleware" should "limit each client separately and exempt health checks" in {
    val a = app(RateLimitMiddleware.Config(maxRequests = 2, window = 1.minute, enabled = true))
    statuses(a, Seq.fill(3)(from("203.0.113.1"))) shouldBe List(Status.Ok, Status.Ok, Status.TooManyRequests)
    statuses(a, Seq(from("203.0.113.2"))) shouldBe List(Status.Ok)
    statuses(a, Seq.fill(5)(from("203.0.113.1", Request[IO](Method.GET, uri"/health")))).distinct shouldBe List(Status.Ok)
  }

  it should "not let an untrusted client dodge the limit with X-Forwarded-For" in {
    val a = app(RateLimitMiddleware.Config(maxRequests = 2, window = 1.minute, enabled = true))
    val spoofed = (1 to 3).map(i => xff(s"1.1.1.$i")(from("203.0.113.1")))
    statuses(a, spoofed) shouldBe List(Status.Ok, Status.Ok, Status.TooManyRequests)
  }

  it should "use the forwarded client address behind a trusted proxy" in {
    val a = app(RateLimitMiddleware.Config(maxRequests = 1, window = 1.minute, enabled = true, trustedProxies = Seq("10.0.0.0/8")))
    statuses(a, Seq(xff("198.51.100.1")(from("10.0.0.2")), xff("198.51.100.2")(from("10.0.0.2")))) shouldBe
      List(Status.Ok, Status.Ok)
    statuses(a, Seq(xff("198.51.100.1")(from("10.0.0.3")))) shouldBe List(Status.TooManyRequests)
  }

  private def login(user: String, password: String, peer: String = "203.0.113.50"): Request[IO] =
    from(peer, Request[IO](Method.POST, uri"/api/v1/auth/login")
      .withEntity(Json.obj("username" -> user.asJson, "password" -> password.asJson)))

  "Login throttling" should "block a username after too many failures, even with the right password" in {
    val a = app(RateLimitMiddleware.Config(maxRequests = 100, window = 1.minute, enabled = false, loginMaxAttempts = 3))
    val attempts = (1 to 3).map(i => login("Alice", "wrong", peer = s"203.0.113.$i"))
    statuses(a, attempts) shouldBe List.fill(3)(Status.Unauthorized)
    // a fourth address, the right password: still refused, and the username is case-insensitive
    statuses(a, Seq(login("alice", "right-password", peer = "203.0.113.4"))) shouldBe List(Status.TooManyRequests)
    // another user from a fresh address is unaffected
    statuses(a, Seq(login("bob", "right-password", peer = "203.0.113.5"))) shouldBe List(Status.Ok)
  }

  it should "block an address that tries many usernames" in {
    val a = app(RateLimitMiddleware.Config(maxRequests = 100, window = 1.minute, enabled = false, loginMaxAttempts = 3))
    statuses(a, (1 to 4).map(i => login(s"user$i", "wrong"))) shouldBe
      List(Status.Unauthorized, Status.Unauthorized, Status.Unauthorized, Status.TooManyRequests)
  }

  it should "not count successful logins, and clear a username's failures on success" in {
    val a = app(RateLimitMiddleware.Config(maxRequests = 100, window = 1.minute, enabled = false, loginMaxAttempts = 2))
    statuses(a, Seq(login("carol", "wrong"), login("carol", "right-password"))) shouldBe List(Status.Unauthorized, Status.Ok)
    statuses(a, Seq.fill(5)(login("dave", "right-password", peer = "203.0.113.60"))).distinct shouldBe List(Status.Ok)
    statuses(a, Seq(login("carol", "wrong", peer = "203.0.113.61"), login("carol", "right-password", peer = "203.0.113.61"))) shouldBe
      List(Status.Unauthorized, Status.Ok)
  }

  it should "be off with loginMaxAttempts = 0" in {
    val a = app(RateLimitMiddleware.Config(maxRequests = 100, window = 1.minute, enabled = false, loginMaxAttempts = 0))
    statuses(a, Seq.fill(20)(login("eve", "wrong"))).distinct shouldBe List(Status.Unauthorized)
  }

  "A 429" should "be the standard ErrorResponse with the numbers in details, and keep Retry-After (#54)" in {
    val a = app(RateLimitMiddleware.Config(maxRequests = 1, window = 30.seconds, enabled = true))
    val limited = (a.run(from("203.0.113.9")) *> a.run(from("203.0.113.9"))).unsafeRunSync()
    limited.status shouldBe Status.TooManyRequests
    val body = limited.as[Json].unsafeRunSync().hcursor
    body.get[String]("error") shouldBe Right("rate_limited")
    body.get[String]("message") shouldBe Right("Rate limit exceeded")
    body.get[String]("details") shouldBe Right("limit=1; window=30s; retryAfter=30")
    body.keys.get.toSet shouldBe Set("error", "message", "details")
    limited.headers.get(ci"Retry-After").map(_.head.value) shouldBe Some("30")
    limited.headers.get(ci"X-RateLimit-Limit").map(_.head.value) shouldBe Some("1")
  }
}
