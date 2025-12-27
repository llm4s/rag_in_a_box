import sbt._

// Reference local llm4s checkout (not yet released as JAR)
lazy val llm4s = ProjectRef(file("../llm4s"), "core")

lazy val root = (project in file("."))
  .dependsOn(llm4s)
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

      // Testing
      "org.scalatest" %% "scalatest" % "3.2.18" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test
    ),

    // Assembly settings for fat JAR
    assembly / assemblyJarName := "ragbox-assembly.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) => MergeStrategy.first
      case PathList("reference.conf") => MergeStrategy.concat
      case PathList("module-info.class") => MergeStrategy.discard
      case x if x.endsWith(".proto") => MergeStrategy.first
      case x if x.contains("io.netty.versions.properties") => MergeStrategy.first
      case x => MergeStrategy.first
    }
  )
