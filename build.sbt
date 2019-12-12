import sbt._

val name = "hmrc-mongo"

val scala2_11 = "2.11.12"
val scala2_12 = "2.12.10"

// Disable multiple project tests running at the same time: https://stackoverflow.com/questions/11899723/how-to-turn-off-parallel-execution-of-tests-for-multi-project-builds
// TODO: restrict parallelExecution to tests only (the obvious way to do this using Test scope does not seem to work correctly)
parallelExecution in Global := false

lazy val commonSettings = Seq(
  organization := "uk.gov.hmrc.mongo",
  majorVersion := 0
)

lazy val commonResolvers = Seq(
  resolvers := Seq(
    Resolver.bintrayRepo("hmrc", "releases"),
    Resolver.typesafeRepo("releases")
  )
)

lazy val library = Project(name, file("."))
  .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning, SbtArtifactory)
  .settings(
    commonSettings,
    publish := {},
    publishAndDistribute := {},
    scalaVersion := scala2_12,
    crossScalaVersions := Seq.empty // by default, this is Seq(scalaVersion), which doesn't play well with sbt-cross and will cause sbt `+` commands to build multiple times
  )
  .aggregate(
    hmrcMongoCommon_2_11, hmrcMongoCommon_2_12,
    hmrcMongoPlay26_2_11, hmrcMongoPlay26_2_12,
    hmrcMongoPlay27_2_11, hmrcMongoPlay27_2_12,
    metrixPlay26_2_11   , metrixPlay26_2_12
    /*    N/A        */ , metrixPlay27_2_12, // metrics-play for 2.7 only exists for 2.12+
    hmrcMongoTest_2_11  , hmrcMongoTest_2_12
  )

lazy val hmrcMongoCommon = Project("hmrc-mongo-common", file("hmrc-mongo-common"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    commonResolvers,
    libraryDependencies ++= AppDependencies.hmrcMongoPlay26,
    makePublicallyAvailableOnBintray := true
  ).cross

lazy val hmrcMongoCommon_2_11 = hmrcMongoCommon(scala2_11)
lazy val hmrcMongoCommon_2_12 = hmrcMongoCommon(scala2_12)

lazy val hmrcMongoPlay26 = Project("hmrc-mongo-play-26", file("hmrc-mongo-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    commonResolvers,
    libraryDependencies ++= AppDependencies.hmrcMongoPlay26,
    makePublicallyAvailableOnBintray := true
  ).cross

lazy val hmrcMongoPlay26_2_11 = hmrcMongoPlay26(scala2_11).dependsOn(hmrcMongoCommon_2_11, hmrcMongoTest_2_11)
lazy val hmrcMongoPlay26_2_12 = hmrcMongoPlay26(scala2_12).dependsOn(hmrcMongoCommon_2_12, hmrcMongoTest_2_12)

lazy val hmrcMongoPlay27 = Project("hmrc-mongo-play-27", file("hmrc-mongo-play-27"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    commonResolvers,
    unmanagedSourceDirectories in Compile += baseDirectory.value / "../hmrc-mongo-play-26/src/main/scala",
    libraryDependencies ++= AppDependencies.hmrcMongoPlay27,
    makePublicallyAvailableOnBintray := true
  )
  .cross

lazy val hmrcMongoPlay27_2_11 = hmrcMongoPlay27(scala2_11).dependsOn(hmrcMongoCommon_2_11, hmrcMongoTest_2_11)
lazy val hmrcMongoPlay27_2_12 = hmrcMongoPlay27(scala2_12).dependsOn(hmrcMongoCommon_2_12, hmrcMongoTest_2_12)

lazy val hmrcMongoTest = Project("hmrc-mongo-test", file("hmrc-mongo-test"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    commonResolvers,
    libraryDependencies ++= AppDependencies.hmrcMongoTest,
    makePublicallyAvailableOnBintray := true
  ).cross

lazy val hmrcMongoTest_2_11 = hmrcMongoTest(scala2_11).dependsOn(hmrcMongoCommon_2_11)
lazy val hmrcMongoTest_2_12 = hmrcMongoTest(scala2_12).dependsOn(hmrcMongoCommon_2_12)

lazy val metrixPlay26 = Project("hmrc-mongo-metrix-play-26", file("hmrc-mongo-metrix-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    commonResolvers,
    libraryDependencies ++= AppDependencies.hmrcMongoMetrixPlay26,
    makePublicallyAvailableOnBintray := true
  ).cross

lazy val metrixPlay26_2_11 = metrixPlay26(scala2_11).dependsOn(hmrcMongoPlay26_2_11, hmrcMongoTest_2_11)
lazy val metrixPlay26_2_12 = metrixPlay26(scala2_12).dependsOn(hmrcMongoPlay26_2_12, hmrcMongoTest_2_12)

lazy val metrixPlay27 = Project("hmrc-mongo-metrix-play-27", file("hmrc-mongo-metrix-play-27"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    commonResolvers,
    libraryDependencies ++= AppDependencies.hmrcMongoMetrixPlay27,
    makePublicallyAvailableOnBintray := true
  ).cross

lazy val metrixPlay27_2_12 = metrixPlay27(scala2_12).dependsOn(hmrcMongoPlay27_2_12, hmrcMongoTest_2_12)
