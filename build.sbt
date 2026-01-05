import sbt._

lazy val root = (project in file("."))
  .settings(
    name := "rag-in-a-box",
    organization := "org.llm4s",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.7.1",

    // Compiler options for Scala 3
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding", "UTF-8",
      "-feature",
      "-unchecked"
    ),

    // Dependencies
    libraryDependencies ++= Seq(
      // LLM4s - RAG framework (0.2.7 adds WebCrawlerLoader)
      // TODO: Update to 0.2.8 when available for PgSearchIndex persistence fix
      "org.llm4s" %% "core" % "0.2.7",

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
      "com.github.pureconfig" %% "pureconfig-core" % "0.17.8",

      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      "org.typelevel" %% "log4cats-slf4j" % "2.7.0",
      // JSON structured logging
      "ch.qos.logback.contrib" % "logback-json-classic" % "0.1.5",
      "ch.qos.logback.contrib" % "logback-jackson" % "0.1.5",
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.17.0",
      // Conditional processing in logback.xml
      "org.codehaus.janino" % "janino" % "3.1.12",

      // Testing
      "org.scalatest" %% "scalatest" % "3.2.18" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test
    ),

    // Assembly settings for fat JAR
    assembly / assemblyJarName := "ragbox-assembly.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      // Discard signature files from signed JARs (BouncyCastle, etc.)
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF")) => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".DSA")) => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".RSA")) => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) => MergeStrategy.first
      case PathList("reference.conf") => MergeStrategy.concat
      case PathList("module-info.class") => MergeStrategy.discard
      case x if x.endsWith(".proto") => MergeStrategy.first
      case x if x.contains("io.netty.versions.properties") => MergeStrategy.first
      case x => MergeStrategy.first
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
    }.taskValue
  )
