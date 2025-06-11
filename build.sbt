import uk.gov.hmrc.DefaultBuildSettings

// Disable multiple project tests running at the same time, since notablescan flag is a global setting.
// https://www.scala-sbt.org/1.x/docs/Parallel-Execution.html
Global / concurrentRestrictions += Tags.limitSum(1, Tags.Test, Tags.Untagged)

val scala2_13 = "2.13.16"
val scala3    = "3.3.6"

ThisBuild / majorVersion     := 2
ThisBuild / scalaVersion     := scala2_13
ThisBuild / isPublicArtefact := true
ThisBuild / scalacOptions    ++= Seq("-feature")
ThisBuild / organization     := "uk.gov.hmrc.mongo"

lazy val library = Project("hmrc-mongo", file("."))
  .settings(publish / skip := true)
  .aggregate(
    hmrcMongoCommon,
    hmrcMongoPlay29, hmrcMongoTestPlay29, hmrcMongoMetrixPlay29, hmrcMongoWorkItemRepoPlay29,
    hmrcMongoPlay30, hmrcMongoTestPlay30, hmrcMongoMetrixPlay30, hmrcMongoWorkItemRepoPlay30
  )

lazy val hmrcMongoCommon = Project("hmrc-mongo-common", file("hmrc-mongo-common"))
  .settings(
    crossScalaVersions := Seq(scala2_13, scala3),
    libraryDependencies ++= LibDependencies.mongoCommon(scalaVersion.value)
  )

def copyPlay30Sources(module: Project) =
  CopySources.copySources(
    module,
    transformSource   = _.replace("org.apache.pekko", "akka"),
    transformResource = _.replace("pekko", "akka")
  )

lazy val hmrcMongoPlay29 = Project("hmrc-mongo-play-29", file("hmrc-mongo-play-29"))
  .settings(
    crossScalaVersions := Seq(scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoPlay("play-29", scalaVersion.value),
    copyPlay30Sources(hmrcMongoPlay30)
  ).dependsOn(hmrcMongoCommon)

lazy val hmrcMongoPlay30 = Project("hmrc-mongo-play-30", file("hmrc-mongo-play-30"))
  .settings(
    crossScalaVersions := Seq(scala2_13, scala3),
    libraryDependencies ++= LibDependencies.hmrcMongoPlay("play-30", scalaVersion.value)
  ).dependsOn(hmrcMongoCommon)

lazy val hmrcMongoTestPlay29 = Project("hmrc-mongo-test-play-29", file("hmrc-mongo-test-play-29"))
  .settings(
    crossScalaVersions := Seq(scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoTestPlay("play-29", scalaVersion.value),
    copyPlay30Sources(hmrcMongoTestPlay30)
  ).dependsOn(hmrcMongoPlay29)

lazy val hmrcMongoTestPlay30 = Project("hmrc-mongo-test-play-30", file("hmrc-mongo-test-play-30"))
  .settings(
    crossScalaVersions := Seq(scala2_13, scala3),
    libraryDependencies ++= LibDependencies.hmrcMongoTestPlay("play-30", scalaVersion.value)
  ).dependsOn(hmrcMongoPlay30)

lazy val hmrcMongoMetrixPlay29 = Project("hmrc-mongo-metrix-play-29", file("hmrc-mongo-metrix-play-29"))
  .settings(
    crossScalaVersions := Seq(scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoMetrixPlay("play-29"),
    copyPlay30Sources(hmrcMongoMetrixPlay30)
  ).dependsOn(hmrcMongoPlay29, hmrcMongoTestPlay29 % Test)

lazy val hmrcMongoMetrixPlay30 = Project("hmrc-mongo-metrix-play-30", file("hmrc-mongo-metrix-play-30"))
  .settings(
    crossScalaVersions := Seq(scala2_13, scala3),
    libraryDependencies ++= LibDependencies.hmrcMongoMetrixPlay("play-30")
  ).dependsOn(hmrcMongoPlay30, hmrcMongoTestPlay30 % Test)

lazy val hmrcMongoWorkItemRepoPlay29 = Project("hmrc-mongo-work-item-repo-play-29", file("hmrc-mongo-work-item-repo-play-29"))
  .settings(
    crossScalaVersions := Seq(scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoWorkItemRepoPlay("play-29"),
    copyPlay30Sources(hmrcMongoWorkItemRepoPlay30)
  ).dependsOn(hmrcMongoMetrixPlay29, hmrcMongoTestPlay29 % Test)

lazy val hmrcMongoWorkItemRepoPlay30 = Project("hmrc-mongo-work-item-repo-play-30", file("hmrc-mongo-work-item-repo-play-30"))
  .settings(
    crossScalaVersions := Seq(scala2_13, scala3),
    libraryDependencies ++= LibDependencies.hmrcMongoWorkItemRepoPlay("play-30")
  ).dependsOn(hmrcMongoMetrixPlay30, hmrcMongoTestPlay30 % Test)
