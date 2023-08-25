import uk.gov.hmrc.DefaultBuildSettings

// Disable multiple project tests running at the same time, since notablescan flag is a global setting.
// https://www.scala-sbt.org/1.x/docs/Parallel-Execution.html
Global / concurrentRestrictions += Tags.limitSum(1, Tags.Test, Tags.Untagged)

val scala2_12 = "2.12.18"
val scala2_13 = "2.13.11"

lazy val commonSettings = Seq(
  organization       := "uk.gov.hmrc.mongo",
  majorVersion       := 1,
  scalaVersion       := scala2_13,
  isPublicArtefact   := true,
  scalacOptions      ++= Seq("-feature")
)

lazy val library = Project("hmrc-mongo", file("."))
  .settings(
    commonSettings,
    publish / skip := true
  )
  .aggregate(
    hmrcMongoCommon,
    hmrcMongoPlay28, hmrcMongoTestPlay28, hmrcMongoMetrixPlay28, hmrcMongoWorkItemRepoPlay28,
    hmrcMongoPlay29, hmrcMongoTestPlay29, hmrcMongoMetrixPlay29, hmrcMongoWorkItemRepoPlay29
  )

lazy val hmrcMongoCommon = Project("hmrc-mongo-common", file("hmrc-mongo-common"))
  .settings(
    commonSettings,
    crossScalaVersions := Seq(scala2_12, scala2_13),
    libraryDependencies ++= LibDependencies.mongoCommon(scalaVersion.value)
  )

def shareSources(location: String) = Seq(
  Compile / unmanagedSourceDirectories   += baseDirectory.value / s"../$location/src/main/scala",
  Compile / unmanagedResourceDirectories += baseDirectory.value / s"../$location/src/main/resources",
  Test    / unmanagedSourceDirectories   += baseDirectory.value / s"../$location/src/test/scala",
  Test    / unmanagedResourceDirectories += baseDirectory.value / s"../$location/src/test/resources"
)

lazy val hmrcMongoPlay28 = Project("hmrc-mongo-play-28", file("hmrc-mongo-play-28"))
  .settings(
    commonSettings,
    crossScalaVersions := Seq(scala2_12, scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoPlay28(scalaVersion.value),
    shareSources("hmrc-mongo")
  ).dependsOn(hmrcMongoCommon)

lazy val hmrcMongoPlay29 = Project("hmrc-mongo-play-29", file("hmrc-mongo-play-29"))
  .settings(
    commonSettings,
    crossScalaVersions := Seq(scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoPlay29(scalaVersion.value),
    shareSources("hmrc-mongo")
  ).dependsOn(hmrcMongoCommon)

lazy val hmrcMongoTestPlay28 = Project("hmrc-mongo-test-play-28", file("hmrc-mongo-test-play-28"))
  .settings(
    commonSettings,
    crossScalaVersions := Seq(scala2_12, scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoTestPlay28(scalaVersion.value),
    shareSources("hmrc-mongo-test")
  ).dependsOn(hmrcMongoPlay28)

lazy val hmrcMongoTestPlay29 = Project("hmrc-mongo-test-play-29", file("hmrc-mongo-test-play-29"))
  .settings(
    commonSettings,
    crossScalaVersions := Seq(scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoTestPlay29(scalaVersion.value),
    shareSources("hmrc-mongo-test")
  ).dependsOn(hmrcMongoPlay29)

lazy val hmrcMongoMetrixPlay28 = Project("hmrc-mongo-metrix-play-28", file("hmrc-mongo-metrix-play-28"))
  .settings(
    commonSettings,
    crossScalaVersions := Seq(scala2_12, scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoMetrixPlay28,
    shareSources("hmrc-mongo-metrix")
  ).dependsOn(hmrcMongoPlay28, hmrcMongoTestPlay28 % Test)

lazy val hmrcMongoMetrixPlay29 = Project("hmrc-mongo-metrix-play-29", file("hmrc-mongo-metrix-play-29"))
  .settings(
    commonSettings,
    crossScalaVersions := Seq(scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoMetrixPlay29,
    shareSources("hmrc-mongo-metrix")
  ).dependsOn(hmrcMongoPlay29, hmrcMongoTestPlay29 % Test)

lazy val hmrcMongoWorkItemRepoPlay28 = Project("hmrc-mongo-work-item-repo-play-28", file("hmrc-mongo-work-item-repo-play-28"))
  .settings(
    commonSettings,
    crossScalaVersions := Seq(scala2_12, scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoWorkItemRepoPlay28,
    shareSources("hmrc-mongo-work-item-repo")
  ).dependsOn(hmrcMongoMetrixPlay28, hmrcMongoTestPlay28 % Test)

lazy val hmrcMongoWorkItemRepoPlay29 = Project("hmrc-mongo-work-item-repo-play-29", file("hmrc-mongo-work-item-repo-play-29"))
  .settings(
    commonSettings,
    crossScalaVersions := Seq(scala2_13),
    libraryDependencies ++= LibDependencies.hmrcMongoWorkItemRepoPlay29,
    shareSources("hmrc-mongo-work-item-repo")
  ).dependsOn(hmrcMongoMetrixPlay29, hmrcMongoTestPlay29 % Test)
