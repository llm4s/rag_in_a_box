package ragbox.auth

import cats.effect.IO
import ragbox.config.DatabaseConfig

import java.sql.{Connection, DriverManager, ResultSet, Timestamp}
import java.time.Instant
import java.util.Properties
import scala.compiletime.uninitialized

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
class UserRegistry(dbConfig: DatabaseConfig) {

  private var connection: Connection = uninitialized

  /**
   * Initialize the registry and ensure tables exist.
   */
  def initialize(): IO[Unit] = IO {
    Class.forName("org.postgresql.Driver")

    val props = new Properties()
    props.setProperty("user", dbConfig.effectiveUser)
    props.setProperty("password", dbConfig.effectivePassword)

    connection = DriverManager.getConnection(dbConfig.connectionString, props)

    val stmt = connection.createStatement()
    try {
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS users (
          |    id SERIAL PRIMARY KEY,
          |    username TEXT UNIQUE NOT NULL,
          |    password_hash TEXT NOT NULL,
          |    role TEXT NOT NULL DEFAULT 'user',
          |    created_at TIMESTAMPTZ DEFAULT NOW()
          |)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_users_username
          |    ON users(username)""".stripMargin
      )
    } finally {
      stmt.close()
    }
  }

  /**
   * Create a new user.
   */
  def create(username: String, passwordHash: String, role: UserRole): IO[User] = IO {
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
  def getByUsername(username: String): IO[Option[User]] = IO {
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
  def getById(id: Int): IO[Option[User]] = IO {
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
  def list(): IO[Seq[User]] = IO {
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
  def updatePassword(id: Int, passwordHash: String): IO[Boolean] = IO {
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
  def updateRole(id: Int, role: UserRole): IO[Boolean] = IO {
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
  def delete(id: Int): IO[Boolean] = IO {
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
  def exists(username: String): IO[Boolean] = IO {
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
  def count(): IO[Int] = IO {
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
   * Close the connection.
   */
  def close(): IO[Unit] = IO {
    if (connection != null && !connection.isClosed) {
      connection.close()
    }
  }

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
   * Create and initialize a user registry.
   */
  def apply(dbConfig: DatabaseConfig): IO[UserRegistry] = {
    val registry = new UserRegistry(dbConfig)
    registry.initialize().as(registry)
  }
}
