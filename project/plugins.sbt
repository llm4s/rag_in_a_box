// Assembly plugin for fat JAR creation
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.2.0")

// Revolver for development hot-reloading
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

// Native packager for Docker builds
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")

// Code coverage with scoverage
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.12")
