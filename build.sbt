import Dependencies._

ThisBuild / scalaVersion := "3.2.1"
ThisBuild / version := "0.2.0"
ThisBuild / organization := "io.github.ollls"
ThisBuild / organizationName := "ollls"

ThisBuild / developers := List(
  Developer(
    id    = "ostrygun",
    name  = "Oleg Strygun",
    email = "ostrygun@gmail.com",
    url   = url("https://github.com/ollls/")
  )
)

ThisBuild / licenses := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage := Some(url("https://github.com/ollls/quartz-h2"))

ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
ThisBuild / publishMavenStyle := true

ThisBuild / scmInfo := Some(
ScmInfo(
    url("https://github.com/ollls/quartz-h2"),
    "scm:git@github.com:ollls/quartz-h2"
  )
)


Runtime / unmanagedClasspath += baseDirectory.value / "src" / "main" / "resources"

lazy val root = (project in file("."))
  .settings(
    organization := "io.github.ollls",
    name := "quartz-h2",
    libraryDependencies += scalaTest % Test,
    libraryDependencies += "dev.zio" %% "zio"  % "2.0.5",
    libraryDependencies += "com.twitter" % "hpack" % "1.0.2",
    //libraryDependencies += "dev.zio" %% "zio-logging" % "2.1.5",
    libraryDependencies += "dev.zio" %% "zio-logging-slf4j" % "2.1.5",
    libraryDependencies += "org.slf4j" % "slf4j-api" % "2.0.4",
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.3.5",
  )

  // JPL integration
  // libraryDependencies += "dev.zio" %% "zio-logging-jpl" % "2.1.5"
  // SLF4j integration  
  // libraryDependencies += "dev.zio" %% "zio-logging-slf4j" % "2.1.5"
  // Using ZIO Logging for SLF4j loggers, usually third-party non-ZIO libraries

//libraryDependencies += "dev.zio" %% "zio-logging-slf4j-bridge" % "2.1.5"


  lazy val RIO = (project in file("examples/RIO")).dependsOn(root)
  .settings(
    name := "example",
  )

    lazy val IO = (project in file("examples/IO")).dependsOn(root)
  .settings(
    name := "example",
  )

  scalacOptions ++= Seq(
  //"-Wunused:imports",
  //"-Xfatal-warnings",
  "-deprecation", 
  "-feature",
)

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
