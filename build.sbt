import Dependencies._

ThisBuild / scalaVersion := "3.3.3"
ThisBuild / version := "0.6.0"
ThisBuild / organization := "io.github.ollls"
ThisBuild / organizationName := "ollls"
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / developers := List(
  Developer(
    id = "ostrygun",
    name = "Oleg Strygun",
    email = "ostrygun@gmail.com",
    url = url("https://github.com/ollls/")
  )
)

ThisBuild / licenses := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage := Some(url("https://github.com/ollls/zio-quartz-h2"))
ThisBuild / credentials += Credentials(Path.userHome / ".sbt" / ".credentials")
ThisBuild / credentials += Credentials(
  "GnuPG Key ID",
  "gpg",
  "F85809244447DB9FA35A3C9B1EB44A5FC60F4104", // key identifier
  "ignored" // this field is ignored; passwords are supplied by pinentry
)

ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
ThisBuild / publishMavenStyle := true

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/ollls/zio-quartz-h2"),
    "scm:git@github.com:ollls/zio-quartz-h2"
  )
)

Runtime / unmanagedClasspath += baseDirectory.value / "src" / "main" / "resources"

lazy val root = (project in file("."))
  .settings(
    organization := "io.github.ollls",
    name := "zio-quartz-h2",
    libraryDependencies += scalaTest % Test,
    libraryDependencies += "io.github.ollls" %% "iouring-quartz-h2" % "0.9-RC3",
    libraryDependencies += "dev.zio" %% "zio" % "2.1.16",
    libraryDependencies += "dev.zio" %% "zio-streams" % "2.1.16",
    libraryDependencies += "com.twitter" % "hpack" % "1.0.2",
    libraryDependencies += "dev.zio" %% "zio-logging-slf4j" % "2.1.14",
    libraryDependencies += "org.slf4j" % "slf4j-api" % "2.0.17",
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.17",
    libraryDependencies += "dev.zio" %% "zio-test"          % "2.1.16" % Test,
    libraryDependencies += "dev.zio" %% "zio-test-sbt"      % "2.1.16" % Test
  )

lazy val IO = (project in file("examples/IO"))
  .dependsOn(root)
  .settings(
    name := "example"
  )


lazy val IOU = (project in file("examples/IOU"))
  .dependsOn(root)
  .settings(
    name := "iouring example"
  )  

scalacOptions ++= Seq(
  // "-Wunused:imports",
  // "-Xfatal-warnings",
  "-deprecation",
  "-feature",
  "-no-indent"
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
