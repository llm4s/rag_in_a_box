import sbt._

val llm4sVersion = "0.6.0"

def isLicenseOrNotice(name: String): Boolean = {
  val lower = name.toLowerCase
  lower.contains("license") || lower.contains("notice") || lower.startsWith("dependencies") ||
  lower.endsWith(".txt") || lower.endsWith(".md")
}

// Code coverage settings (ThisBuild scope for scoverage)
ThisBuild / coverageEnabled := false
ThisBuild / coverageMinimumStmtTotal := 20  // Minimum 20% statement coverage (baseline)
ThisBuild / coverageFailOnMinimum := true   // Fail build if below minimum
ThisBuild / coverageHighlighting := true
ThisBuild / coverageExcludedPackages := "<empty>;.*Main.*;.*StaticRoutes.*;.*BuildInfo.*"

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "rag-in-a-box",
    organization := "org.llm4s",
    // version is derived from git tags by sbt-dynver (tag v0.2.0 -> 0.2.0)
    scalaVersion := "3.7.1",

    // A build without git metadata (the Docker image build: .git is not in the context) gets its
    // version from RAGBOX_VERSION, which the release workflow passes as a build argument
    version := sys.env.get("RAGBOX_VERSION").map(_.trim).filter(_.nonEmpty).getOrElse(version.value),

    // org.llm4s.ragbox.BuildInfo.version: the version reported by /health and /metrics
    buildInfoKeys := Seq[BuildInfoKey](name, version),
    buildInfoPackage := "org.llm4s.ragbox",

    // Compiler options for Scala 3
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding", "UTF-8",
      "-feature",
      "-unchecked"
    ),

    // Dependencies
    libraryDependencies ++= Seq(
      // LLM4s - RAG framework plus the LLM / embedding providers ragbox can be configured with
      "org.llm4s" %% "llm4s-rag" % llm4sVersion,
      "org.llm4s" %% "llm4s-openai" % llm4sVersion,
      "org.llm4s" %% "llm4s-anthropic" % llm4sVersion,
      "org.llm4s" %% "llm4s-voyage" % llm4sVersion,
      "org.llm4s" %% "llm4s-ollama" % llm4sVersion,

      // HTTP server
      "org.http4s" %% "http4s-ember-server" % "0.23.30",
      "org.http4s" %% "http4s-ember-client" % "0.23.30",
      "org.http4s" %% "http4s-circe" % "0.23.30",
      "org.http4s" %% "http4s-dsl" % "0.23.30",

      // JSON
      "io.circe" %% "circe-core" % "0.14.10",
      "io.circe" %% "circe-generic" % "0.14.10",
      "io.circe" %% "circe-parser" % "0.14.10",

      // Configuration
      "com.typesafe" % "config" % "1.4.3",

      // Logging
      "ch.qos.logback" % "logback-classic" % "1.5.38",
      "org.typelevel" %% "log4cats-slf4j" % "2.7.0",
      // JSON structured logging (LOG_FORMAT=json)
      "net.logstash.logback" % "logstash-logback-encoder" % "9.0",

      // Database connection pooling
      "com.zaxxer" % "HikariCP" % "5.1.0",

      // Schema migrations (src/main/resources/db/migration), applied at startup
      "org.flywaydb" % "flyway-core" % "13.12.2",
      "org.flywaydb" % "flyway-database-postgresql" % "13.12.2",

      // Cron expressions for the ingestion schedule (UNIX cron semantics, Sunday = 0)
      "com.cronutils" % "cron-utils" % "9.2.1",

      // OAuth2/OIDC - JWT validation and JWKS
      "com.auth0" % "java-jwt" % "4.4.0",
      "com.auth0" % "jwks-rsa" % "0.22.1",

      // Testing
      "org.scalatest" %% "scalatest" % "3.2.18" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test,
      // TestControl: a virtual clock for the ingestion scheduler tests
      "org.typelevel" %% "cats-effect-testkit" % "3.5.7" % Test,
      // Testcontainers for Docker-based integration tests
      "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.41.8" % Test,
      "org.testcontainers" % "testcontainers" % "1.21.4" % Test
    ),

    // Assembly settings for fat JAR
    assembly / assemblyJarName := "ragbox-assembly.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      // Discard signature files from signed JARs (BouncyCastle, etc.)
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(n => n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA")) =>
        MergeStrategy.discard
      // ServiceLoader registrations: each llm4s provider module ships its own
      // META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule, so these must be merged
      case PathList("META-INF", "services", _ @ _*) => MergeStrategy.filterDistinctLines
      // Module descriptors are irrelevant on the fat JAR's classpath
      case PathList("module-info.class") => MergeStrategy.discard
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      // Per-library build metadata and license/notice files are not needed at runtime
      case PathList("META-INF", "maven", _ @ _*) => MergeStrategy.discard
      case PathList("META-INF", "INDEX.LIST") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".kotlin_module")) => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(isLicenseOrNotice) => MergeStrategy.discard
      case PathList(name) if isLicenseOrNotice(name) && !name.endsWith(".txt") => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case x if x.endsWith("io.netty.versions.properties") => MergeStrategy.first
      case x if x.endsWith(".proto") => MergeStrategy.first
      // Anything else that conflicts with different content must be handled explicitly above
      case _ => MergeStrategy.deduplicate
    },

    // Copy admin-ui dist to resources during build
    Compile / resourceGenerators += Def.task {
      val adminUiDist = baseDirectory.value / "admin-ui" / "dist"
      val targetDir = (Compile / resourceManaged).value / "public" / "admin"
      if (adminUiDist.exists()) {
        IO.copyDirectory(adminUiDist, targetDir)
        println(s"[info] Copied admin-ui from $adminUiDist to $targetDir")
        // Return all copied files for proper sbt caching
        (targetDir ** "*").get.filter(_.isFile).toSeq
      } else {
        println(s"[warn] Admin UI dist not found at $adminUiDist - skipping copy")
        Seq.empty[File]
      }
    }.taskValue,

  )
