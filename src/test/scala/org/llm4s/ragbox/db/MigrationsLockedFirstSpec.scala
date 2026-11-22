package org.llm4s.ragbox.db

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.postgresql.ds.PGSimpleDataSource

import java.io.{PrintWriter, StringWriter}
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.{Connection, SQLException}
import scala.collection.mutable

/**
 * The DataSource [[Migrations.migrate]] hands Flyway (#52): the lock's connection first, never
 * closed by Flyway, then the real DataSource. No database needed.
 */
class MigrationsLockedFirstSpec extends AnyFlatSpec with Matchers {

  /** A connection that records the methods called on it; `isValid` throws to check unwrapping. */
  private final class Recorder {
    val calls: mutable.Buffer[String] = mutable.Buffer.empty
    val connection: Connection = Proxy.newProxyInstance(
      classOf[Connection].getClassLoader,
      Array(classOf[Connection]),
      new InvocationHandler {
        override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
          calls += method.getName
          method.getName match {
            case "isValid" => throw new SQLException("broken")
            case "getAutoCommit" => java.lang.Boolean.TRUE
            case _ => null
          }
        }
      }
    ).asInstanceOf[Connection]
  }

  "LockedFirst" should "hand out the lock's connection first, with close() a no-op" in {
    val lock = new Recorder
    val ds = new Migrations.LockedFirst(new PGSimpleDataSource(), lock.connection)
    val first = ds.getConnection()
    first.getAutoCommit shouldBe true
    first.close()
    lock.calls.toList shouldBe List("getAutoCommit")
    // Exceptions from the connection surface as themselves, not as reflection wrappers
    an[SQLException] should be thrownBy first.isValid(1)
  }

  it should "delegate everything else to the real DataSource" in {
    val underlying = new PGSimpleDataSource()
    underlying.setServerNames(Array("127.0.0.1"))
    underlying.setPortNumbers(Array(1)) // nothing listens: a second connection is a real attempt
    underlying.setConnectTimeout(1)
    val ds = new Migrations.LockedFirst(underlying, new Recorder().connection)
    ds.getConnection()
    an[SQLException] should be thrownBy ds.getConnection()
    an[SQLException] should be thrownBy ds.getConnection("user", "password")

    val writer = new PrintWriter(new StringWriter())
    ds.setLogWriter(writer)
    ds.getLogWriter shouldBe underlying.getLogWriter
    ds.setLoginTimeout(7)
    ds.getLoginTimeout shouldBe 7
    underlying.getLoginTimeout shouldBe 7
    ds.isWrapperFor(classOf[PGSimpleDataSource]) shouldBe true
    ds.unwrap(classOf[PGSimpleDataSource]) shouldBe underlying
    ds.getParentLogger shouldBe underlying.getParentLogger
  }
}
