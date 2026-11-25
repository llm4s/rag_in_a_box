package org.llm4s.ragbox.ingestion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import java.net.{InetAddress, UnknownHostException}

class UrlPolicySpec extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  /** A resolver that never touches the network. */
  private val fakeDns: Map[String, Seq[String]] = Map(
    "public.example" -> Seq("93.184.215.14"),
    "docs.example.com" -> Seq("93.184.215.14"),
    "intranet.example" -> Seq("10.1.2.3"),
    "mixed.example" -> Seq("93.184.215.14", "192.168.1.10"),
    "metadata.example" -> Seq("169.254.169.254")
  )

  private def resolve(host: String): Seq[InetAddress] =
    fakeDns.get(host).map(_.map(InetAddress.getByName)).getOrElse(throw new UnknownHostException(host))

  private def policy(config: UrlPolicyConfig = UrlPolicyConfig()) = new UrlPolicy(config, resolve)

  private val blocked = Table(
    "url",
    // loopback, in every spelling
    "http://127.0.0.1/",
    "http://127.1/",
    "http://2130706433/",
    "http://0x7f.1/",
    "http://0x7f000001/",
    "http://0177.0.0.1/",
    "http://017700000001/",
    "http://[::1]/",
    "http://[::ffff:127.0.0.1]/",
    "http://[::ffff:7f00:1]/",
    "http://localhost/",
    "http://LOCALHOST./",
    "http://foo.localhost/",
    // cloud metadata and link-local
    "http://169.254.169.254/latest/meta-data/",
    "http://[fd00:ec2::254]/",
    "http://[fe80::1]/",
    "http://[::ffff:169.254.169.254]/",
    "http://metadata.google.internal/",
    "http://metadata.example/",
    // private
    "http://10.0.0.1/",
    "http://172.16.5.4/",
    "http://192.168.0.1/",
    "http://[fc00::1]/",
    "http://intranet.example/",
    "http://mixed.example/",
    // unspecified, multicast, CGNAT
    "http://0.0.0.0/",
    "http://0/",
    "http://[::]/",
    "http://224.0.0.1/",
    "http://[ff02::1]/",
    "http://100.64.0.1/",
    "http://100.127.255.254/"
  )

  "UrlPolicy" should "refuse non-public addresses in any form" in {
    forAll(blocked) { url =>
      withClue(url) { policy().check(url).isLeft shouldBe true }
    }
  }

  it should "allow public hosts and addresses" in {
    Seq(
      "https://public.example/page",
      "http://93.184.215.14/",
      "https://docs.example.com:8443/a?b=c",
      "http://100.128.0.1/",
      "http://[2606:2800:220:1:248:1893:25c8:1946]/"
    ).foreach { url =>
      withClue(url) { policy().check(url).isRight shouldBe true }
    }
  }

  it should "only allow http and https" in {
    Seq("file:///etc/passwd", "ftp://public.example/", "jar:http://public.example/x.jar!/", "gopher://public.example/", "public.example")
      .foreach { url =>
        withClue(url) { policy().check(url) shouldBe Left("Only http and https URLs are allowed") }
      }
  }

  it should "refuse embedded credentials, unresolvable hosts and malformed URLs" in {
    policy().check("http://user:pw@public.example/") shouldBe Left("URLs with embedded credentials are not allowed")
    policy().check("http://nowhere.example/") shouldBe Left("URL host could not be resolved")
    policy().check("http://").isLeft shouldBe true
    policy().check("http://[::1/").isLeft shouldBe true
    policy().check("").isLeft shouldBe true
  }

  it should "restrict to the allowed hosts when configured" in {
    val p = policy(UrlPolicyConfig(allowedHosts = Seq("public.example", "*.example.com")))
    p.check("https://public.example/").isRight shouldBe true
    p.check("https://docs.example.com/").isRight shouldBe true
    p.check("https://example.com.evil.example/").isLeft shouldBe true
    p.check("https://93.184.215.14/") shouldBe
      Left("Host is not in the allowed hosts list (ingestion.url.allowed-hosts)")
    // An allowed host is still subject to the address check
    policy(UrlPolicyConfig(allowedHosts = Seq("intranet.example"))).check("http://intranet.example/").isLeft shouldBe true
  }

  it should "skip the address check only when private networks are explicitly allowed" in {
    val p = policy(UrlPolicyConfig(allowPrivateNetworks = true))
    p.check("http://intranet.example/").isRight shouldBe true
    p.check("http://10.0.0.1/").isRight shouldBe true
    p.check("file:///etc/passwd").isLeft shouldBe true
  }

  it should "be the llm4s UrlPolicy the loaders enforce, returning the addresses to connect to" in {
    val p: org.llm4s.rag.loader.UrlPolicy = policy(UrlPolicyConfig(allowedHosts = Seq("public.example")))
    p.resolve(new java.net.URI("https://public.example/page")).map(_.map(_.getHostAddress)) shouldBe Right(Seq("93.184.215.14"))

    // A refusal is marked as one, so a crawl skips the page; a host that does not resolve is a failed read
    val refusedPage = p.resolve(new java.net.URI("https://docs.example.com/"))
    refusedPage.left.map(_.endpoint) shouldBe Left(UrlPolicy.RefusedEndpoint)
    val privatePage = policy().resolve(new java.net.URI("http://intranet.example/"))
    privatePage.left.map(_.endpoint) shouldBe Left(UrlPolicy.RefusedEndpoint)
    // The address it resolved to stays out of the reason returned to the caller
    privatePage.left.map(_.message).left.getOrElse("") should not include "10.1.2.3"
    policy().resolve(new java.net.URI("http://nowhere.example/")).left.map(_.endpoint) shouldBe Left("nowhere.example")

    def failure(error: org.llm4s.error.NetworkError) = org.llm4s.rag.loader.LoadResult.failure("u", error)
    UrlPolicy.refused(failure(refusedPage.left.toOption.get)) shouldBe true
    UrlPolicy.refused(failure(policy().resolve(new java.net.URI("http://nowhere.example/")).left.toOption.get)) shouldBe false
  }

  it should "still refuse cloud metadata addresses when private networks are allowed" in {
    val p = policy(UrlPolicyConfig(allowPrivateNetworks = true))
    p.check("http://169.254.169.254/latest/meta-data/").isLeft shouldBe true
    p.check("http://metadata.example/").isLeft shouldBe true
  }
}
