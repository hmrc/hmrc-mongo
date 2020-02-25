import sbt.Keys._
import sbt._

val name = "hmrc-mongo"

val scala2_11 = "2.11.12"
val scala2_12 = "2.12.10"

// Disable multiple project tests running at the same time: https://stackoverflow.com/questions/11899723/how-to-turn-off-parallel-execution-of-tests-for-multi-project-builds
// TODO: restrict parallelExecution to tests only (the obvious way to do this using Test scope does not seem to work correctly)
parallelExecution in Global := false

lazy val commonResolvers = Seq(
    Resolver.bintrayRepo("hmrc", "releases"),
    Resolver.typesafeRepo("releases")
)

lazy val commonSettings = Seq(
  organization := "uk.gov.hmrc.mongo",
  majorVersion := 0,
  scalaVersion := scala2_12,
  crossScalaVersions := Seq(scala2_11, scala2_12),
  makePublicallyAvailableOnBintray := true,
  resolvers := commonResolvers
)

lazy val library = Project(name, file("."))
  .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning, SbtArtifactory)
  .settings(
    commonSettings,
    publish := {},
    publishAndDistribute := {},

    // by default this is Seq(scalaVersion) which doesn't play well and causes sbt
    // to try an invalid cross-build for hmrcMongoMetrixPlay27
    crossScalaVersions := Seq.empty
  )
  .aggregate(
    hmrcMongoCommon,
    hmrcMongoPlay26, hmrcMongoPlay27,
    hmrcMongoTestPlay26, hmrcMongoTestPlay27,
    hmrcMongoMetrixPlay26, hmrcMongoMetrixPlay27
  )

lazy val hmrcMongoCommon = Project("hmrc-mongo-common", file("hmrc-mongo-common"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.mongoCommon(scalaBinaryVersion.value)
  )

lazy val hmrcMongoPlay26 = Project("hmrc-mongo-play-26", file("hmrc-mongo-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoPlay26
  ).dependsOn(hmrcMongoCommon)

lazy val hmrcMongoPlay27 = Project("hmrc-mongo-play-27", file("hmrc-mongo-play-27"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoPlay27
  ).dependsOn(hmrcMongoCommon)

lazy val hmrcMongoTestPlay26 = Project("hmrc-mongo-test-play-26", file("hmrc-mongo-test-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoTestPlay26
  ).dependsOn(hmrcMongoPlay26)

lazy val hmrcMongoTestPlay27 = Project("hmrc-mongo-test-play-27", file("hmrc-mongo-test-play-27"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    unmanagedSourceDirectories in Compile += baseDirectory.value / "../hmrc-mongo-test-play-27/src/main/scala",
    libraryDependencies ++= AppDependencies.hmrcMongoTestPlay27
  ).dependsOn(hmrcMongoPlay27)

lazy val hmrcMongoMetrixPlay26 = Project("hmrc-mongo-metrix-play-26", file("hmrc-mongo-metrix-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoMetrixPlay26
  ).dependsOn(hmrcMongoPlay26, hmrcMongoTestPlay26)

lazy val hmrcMongoMetrixPlay27 = Project("hmrc-mongo-metrix-play-27", file("hmrc-mongo-metrix-play-27"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoMetrixPlay27,
    crossScalaVersions := Seq(scala2_12), // metrics-play for 2.7 only exists for 2.12+
  ).dependsOn(hmrcMongoPlay27, hmrcMongoTestPlay27)
