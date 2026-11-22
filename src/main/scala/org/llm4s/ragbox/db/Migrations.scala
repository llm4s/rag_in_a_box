package org.llm4s.ragbox.db

import cats.effect.IO
import org.flywaydb.core.Flyway

import javax.sql.DataSource
import scala.jdk.CollectionConverters._

/**
 * ragbox's database schema, managed by Flyway.
 *
 * The migrations are the SQL scripts in `src/main/resources/db/migration`
 * (`V<n>__<description>.sql`); Flyway records the ones it has applied in `flyway_schema_history`.
 * Components never create or alter tables themselves: the schema must be migrated before they
 * are used (at startup in `Main`, unless `database.migrate-on-start` is off, or with
 * `--migrate-only`).
 *
 * '''Databases created before Flyway.''' Those have ragbox's tables, possibly at any of the
 * historic states the old startup DDL produced, but no history table. Flyway baselines such a
 * database at version 0 ([[BaselineVersion]]), so V1 still runs on it; V1 is written to be
 * idempotent (`CREATE ... IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, guarded data fixes) and
 * brings any historic state, or an empty database, to the same schema.
 *
 * '''Several replicas.''' Flyway takes a PostgreSQL advisory lock for the duration of a
 * migration, so replicas starting together apply each migration once: the others wait, then
 * find nothing pending.
 *
 * Tables owned by llm4s (the vector/keyword table, `llm4s_collections`, `llm4s_principals`) are
 * not managed here; llm4s creates them when the search index or vector store starts, after the
 * migrations have run.
 */
object Migrations {

  /** Where the migration scripts live on the classpath. */
  val Location: String = "classpath:db/migration"

  /**
   * The version an existing pre-Flyway database is baselined at: 0, below V1, so that V1 brings
   * whatever historic schema it has up to date.
   */
  val BaselineVersion: String = "0"

  /** What a migration run did. */
  final case class Result(applied: Seq[String], schemaVersion: Option[String])

  /** The Flyway instance for `dataSource`. */
  def flyway(dataSource: DataSource): Flyway =
    Flyway
      .configure()
      .dataSource(dataSource)
      .locations(Location)
      .baselineOnMigrate(true)
      .baselineVersion(BaselineVersion)
      .baselineDescription("Pre-Flyway schema (adopted; V1 upgrades it in place)")
      .load()

  /** Apply all pending migrations to `dataSource`. A no-op when the schema is up to date. */
  def migrate(dataSource: DataSource): IO[Result] = IO.blocking {
    val result = flyway(dataSource).migrate()
    val applied = Option(result.migrations).map(_.asScala.toSeq).getOrElse(Seq.empty)
      .map(m => s"V${m.version} ${m.description}")
    Result(
      applied = applied,
      schemaVersion = Option(result.targetSchemaVersion).orElse(Option(result.initialSchemaVersion))
    )
  }
}
