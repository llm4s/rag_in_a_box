package org.llm4s.ragbox.auth

import cats.effect.IO
import org.llm4s.ragbox.db.ConnectionPool

import java.sql.{ResultSet, Timestamp}
import java.time.Instant
import javax.sql.DataSource

/**
 * User role.
 */
sealed trait UserRole {
  def name: String
}

object UserRole {
  case object Admin extends UserRole { val name = "admin" }
  case object User extends UserRole { val name = "user" }

  def fromString(s: String): UserRole = s.toLowerCase match {
    case "admin" => Admin
    case "user" => User
    case other => throw new IllegalArgumentException(s"Unknown role: $other")
  }
}

/**
 * A user in the system.
 */
final case class User(
  id: Int,
  username: String,
  passwordHash: String,
  role: UserRole,
  createdAt: Instant
)

/**
 * PostgreSQL-backed user registry.
 */
class UserRegistry(dataSource: DataSource) {

  /**
   * Create a new user.
   */
  def create(username: String, passwordHash: String, role: UserRole): IO[User] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql =
      """INSERT INTO users (username, password_hash, role)
        |VALUES (?, ?, ?)
        |RETURNING id, username, password_hash, role, created_at""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, username)
      stmt.setString(2, passwordHash)
      stmt.setString(3, role.name)
      val rs = stmt.executeQuery()
      if (rs.next()) rowToUser(rs)
      else throw new RuntimeException("Failed to create user")
    } finally {
      stmt.close()
    }
  }

  /**
   * Get user by username.
   */
  def getByUsername(username: String): IO[Option[User]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "SELECT id, username, password_hash, role, created_at FROM users WHERE username = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, username)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rowToUser(rs)) else None
    } finally {
      stmt.close()
    }
  }

  /**
   * Get user by ID.
   */
  def getById(id: Int): IO[Option[User]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "SELECT id, username, password_hash, role, created_at FROM users WHERE id = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setInt(1, id)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rowToUser(rs)) else None
    } finally {
      stmt.close()
    }
  }

  /**
   * List all users.
   */
  def list(): IO[Seq[User]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "SELECT id, username, password_hash, role, created_at FROM users ORDER BY created_at"
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      val users = scala.collection.mutable.ArrayBuffer.empty[User]
      while (rs.next()) {
        users += rowToUser(rs)
      }
      users.toSeq
    } finally {
      stmt.close()
    }
  }

  /**
   * Update user password.
   */
  def updatePassword(id: Int, passwordHash: String): IO[Boolean] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "UPDATE users SET password_hash = ? WHERE id = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, passwordHash)
      stmt.setInt(2, id)
      stmt.executeUpdate() > 0
    } finally {
      stmt.close()
    }
  }

  /**
   * Update user role.
   */
  def updateRole(id: Int, role: UserRole): IO[Boolean] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "UPDATE users SET role = ? WHERE id = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, role.name)
      stmt.setInt(2, id)
      stmt.executeUpdate() > 0
    } finally {
      stmt.close()
    }
  }

  /**
   * Delete user.
   */
  def delete(id: Int): IO[Boolean] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "DELETE FROM users WHERE id = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setInt(1, id)
      stmt.executeUpdate() > 0
    } finally {
      stmt.close()
    }
  }

  /**
   * Check if username exists.
   */
  def exists(username: String): IO[Boolean] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "SELECT 1 FROM users WHERE username = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, username)
      val rs = stmt.executeQuery()
      rs.next()
    } finally {
      stmt.close()
    }
  }

  /**
   * Count users.
   */
  def count(): IO[Int] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "SELECT COUNT(*) FROM users"
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      stmt.close()
    }
  }

  /**
   * Nothing to close: the connection pool is owned by the caller.
   */
  def close(): IO[Unit] = IO.unit

  private def rowToUser(rs: ResultSet): User = {
    User(
      id = rs.getInt("id"),
      username = rs.getString("username"),
      passwordHash = rs.getString("password_hash"),
      role = UserRole.fromString(rs.getString("role")),
      createdAt = rs.getTimestamp("created_at").toInstant
    )
  }
}

object UserRegistry {

  /**
   * A user registry. The schema is managed by Flyway ([[org.llm4s.ragbox.db.Migrations]]); this does no I/O.
   */
  def apply(dataSource: DataSource): IO[UserRegistry] = IO.pure(new UserRegistry(dataSource))
}
