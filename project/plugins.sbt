// Assembly plugin for fat JAR creation
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.2.0")

// Revolver for development hot-reloading
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

// Derive the project version from git tags (v1.2.3 -> 1.2.3)
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")

// Code coverage with scoverage
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.12")
