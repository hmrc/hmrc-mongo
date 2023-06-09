import uk.gov.hmrc.DefaultBuildSettings

// Disable multiple project tests running at the same time, since notablescan flag is a global setting.
// https://www.scala-sbt.org/1.x/docs/Parallel-Execution.html
Global / concurrentRestrictions += Tags.limitSum(1, Tags.Test, Tags.Untagged)

val scala2_12 = "2.12.17"
val scala2_13 = "2.13.10"

lazy val commonSettings = Seq(
  organization       := "uk.gov.hmrc.mongo",
  majorVersion       := 1,
  scalaVersion       := scala2_13,
  crossScalaVersions := Seq(scala2_12, scala2_13),
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
    hmrcMongoPlay28,
    hmrcMongoTestPlay28,
    hmrcMongoMetrixPlay28,
    hmrcMongoWorkItemRepoPlay28
  )

lazy val hmrcMongoCommon = Project("hmrc-mongo-common", file("hmrc-mongo-common"))
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.mongoCommon(scalaVersion.value)
  )

lazy val hmrcMongoPlay28 = Project("hmrc-mongo-play-28", file("hmrc-mongo-play-28"))
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoPlay28(scalaVersion.value)
  ).dependsOn(hmrcMongoCommon)

lazy val hmrcMongoTestPlay28 = Project("hmrc-mongo-test-play-28", file("hmrc-mongo-test-play-28"))
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoTestPlay28(scalaVersion.value)
  ).dependsOn(hmrcMongoPlay28)

lazy val hmrcMongoMetrixPlay28 = Project("hmrc-mongo-metrix-play-28", file("hmrc-mongo-metrix-play-28"))
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoMetrixPlay28
  ).dependsOn(hmrcMongoPlay28, hmrcMongoTestPlay28 % "test")

lazy val hmrcMongoWorkItemRepoPlay28 = Project("hmrc-mongo-work-item-repo-play-28", file("hmrc-mongo-work-item-repo-play-28"))
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoWorkItemRepoPlay28
  ).dependsOn(hmrcMongoMetrixPlay28, hmrcMongoTestPlay28 % "test")
