import sbt._

val name = "hmrc-mongo"

val scala2_11 = "2.11.12"
val scala2_12 = "2.12.10"

// Disable multiple project tests running at the same time: https://stackoverflow.com/questions/11899723/how-to-turn-off-parallel-execution-of-tests-for-multi-project-builds
parallelExecution in Global := false

lazy val commonSettings = Seq(
  organization := "uk.gov.hmrc.mongo",
  majorVersion := 0
)

lazy val library = Project(name, file("."))
  .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning, SbtArtifactory)
  .settings(
    commonSettings,
    publish := {},
    publishAndDistribute := {},
    scalaVersion := "2.11.12"
  )
  .aggregate(
    hmrcMongoCommon_2_11, hmrcMongoCommon_2_12,
    hmrcMongoPlay26_2_11, hmrcMongoPlay26_2_12,
    hmrcMongoPlay27_2_11, hmrcMongoPlay27_2_12,
    metrixPlay26_2_11   , metrixPlay26_2_12,
    metrixPlay27_2_12,
    hmrcMongoTest_2_11  , hmrcMongoTest_2_12
  )

lazy val hmrcMongoCommon = Project("hmrc-mongo-common", file("hmrc-mongo-common"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoPlay26,
    makePublicallyAvailableOnBintray := true,
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  ).cross

lazy val hmrcMongoCommon_2_11 = hmrcMongoCommon(scala2_11)
lazy val hmrcMongoCommon_2_12 = hmrcMongoCommon(scala2_12)

lazy val hmrcMongoPlay26 = Project("hmrc-mongo-play-26", file("hmrc-mongo-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoPlay26,
    makePublicallyAvailableOnBintray := true,
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  ).cross

lazy val hmrcMongoPlay26_2_11 = hmrcMongoPlay26(scala2_11).dependsOn(hmrcMongoCommon_2_11, hmrcMongoTest_2_11)
lazy val hmrcMongoPlay26_2_12 = hmrcMongoPlay26(scala2_12).dependsOn(hmrcMongoCommon_2_12, hmrcMongoTest_2_12)

lazy val hmrcMongoPlay27 = Project("hmrc-mongo-play-27", file("hmrc-mongo-play-27"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    unmanagedSourceDirectories in Compile += baseDirectory.value / "../hmrc-mongo-play-26/src/main/scala",
    libraryDependencies ++= AppDependencies.hmrcMongoPlay27,
    makePublicallyAvailableOnBintray := true,
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  )
  .cross

lazy val hmrcMongoPlay27_2_11 = hmrcMongoPlay27(scala2_11).dependsOn(hmrcMongoCommon_2_11, hmrcMongoTest_2_11)
lazy val hmrcMongoPlay27_2_12 = hmrcMongoPlay27(scala2_12).dependsOn(hmrcMongoCommon_2_12, hmrcMongoTest_2_12)

lazy val hmrcMongoTest = Project("hmrc-mongo-test", file("hmrc-mongo-test"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoTest,
    makePublicallyAvailableOnBintray := true,
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  ).cross

lazy val hmrcMongoTest_2_11 = hmrcMongoTest(scala2_11).dependsOn(hmrcMongoCommon_2_11)
lazy val hmrcMongoTest_2_12 = hmrcMongoTest(scala2_12).dependsOn(hmrcMongoCommon_2_12)

lazy val metrixPlay26 = Project("hmrc-mongo-metrix-play-26", file("hmrc-mongo-metrix-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.metrixPlay26,
    makePublicallyAvailableOnBintray := true,
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  ).cross

lazy val metrixPlay26_2_11 = metrixPlay26(scala2_11).dependsOn(hmrcMongoPlay26_2_11, hmrcMongoTest_2_11)
lazy val metrixPlay26_2_12 = metrixPlay26(scala2_12).dependsOn(hmrcMongoPlay26_2_12, hmrcMongoTest_2_12)

lazy val metrixPlay27 = Project("hmrc-mongo-metrix-play-27", file("hmrc-mongo-metrix-play-27"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.metrixPlay27,
    makePublicallyAvailableOnBintray := true,
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  ).cross

lazy val metrixPlay27_2_12 = metrixPlay27(scala2_12).dependsOn(hmrcMongoPlay27_2_12, hmrcMongoTest_2_12)
