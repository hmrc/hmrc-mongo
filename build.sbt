// Disable multiple project tests running at the same time, since notablescan flag is a global setting.
// https://www.scala-sbt.org/1.x/docs/Parallel-Execution.html
Global / concurrentRestrictions += Tags.limitSum(1, Tags.Test, Tags.Untagged)


lazy val commonSettings = Seq(
  organization := "uk.gov.hmrc.mongo",
  majorVersion := 0,
  scalaVersion := "2.12.14",
  isPublicArtefact := true
)

lazy val library = Project("hmrc-mongo", file("."))
  .settings(
    commonSettings,
    publish / skip := true,

    // by default this is Seq(scalaVersion) which doesn't play well and causes sbt
    // to try an invalid cross-build for hmrcMongoMetrixPlay27
    crossScalaVersions := Seq.empty
  )
  .aggregate(
    hmrcMongoCommon,
    hmrcMongoPlay27            , hmrcMongoPlay28,
    hmrcMongoTestPlay27        , hmrcMongoTestPlay28,
    hmrcMongoMetrixPlay27      , hmrcMongoMetrixPlay28,
    hmrcMongoWorkItemRepoPlay27, hmrcMongoWorkItemRepoPlay28
  )

lazy val hmrcMongoCommon = Project("hmrc-mongo-common", file("hmrc-mongo-common"))
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.mongoCommon(scalaBinaryVersion.value)
  )

lazy val hmrcMongoPlay27 = Project("hmrc-mongo-play-27", file("hmrc-mongo-play-27"))
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoPlay27
  ).dependsOn(hmrcMongoCommon)

lazy val hmrcMongoPlay28 = Project("hmrc-mongo-play-28", file("hmrc-mongo-play-28"))
  .settings(
    commonSettings,
    unmanagedSourceDirectories in Compile += baseDirectory.value / "../hmrc-mongo-play-27/src/main/scala",
    libraryDependencies ++= AppDependencies.hmrcMongoPlay28
  ).dependsOn(hmrcMongoCommon)

lazy val hmrcMongoTestPlay27 = Project("hmrc-mongo-test-play-27", file("hmrc-mongo-test-play-27"))
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoTestPlay27
  ).dependsOn(hmrcMongoPlay27)

lazy val hmrcMongoTestPlay28 = Project("hmrc-mongo-test-play-28", file("hmrc-mongo-test-play-28"))
  .settings(
    commonSettings,
    unmanagedSourceDirectories in Compile += baseDirectory.value / "../hmrc-mongo-test-play-27/src/main/scala",
    libraryDependencies ++= AppDependencies.hmrcMongoTestPlay28
  ).dependsOn(hmrcMongoPlay28)

lazy val hmrcMongoMetrixPlay27 = Project("hmrc-mongo-metrix-play-27", file("hmrc-mongo-metrix-play-27"))
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoMetrixPlay27
  ).dependsOn(hmrcMongoPlay27, hmrcMongoTestPlay27 % "compile->test")

lazy val hmrcMongoMetrixPlay28 = Project("hmrc-mongo-metrix-play-28", file("hmrc-mongo-metrix-play-28"))
  .settings(
    commonSettings,
    unmanagedSourceDirectories in Compile += baseDirectory.value / "../hmrc-mongo-metrix-play-27/src/main/scala",
    libraryDependencies ++= AppDependencies.hmrcMongoMetrixPlay28
  ).dependsOn(hmrcMongoPlay28, hmrcMongoTestPlay28 % "compile->test")

lazy val hmrcMongoWorkItemRepoPlay27 = Project("hmrc-mongo-work-item-repo-play-27", file("hmrc-mongo-work-item-repo-play-27"))
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoWorkItemRepoPlay27
  ).dependsOn(hmrcMongoMetrixPlay27)

lazy val hmrcMongoWorkItemRepoPlay28 = Project("hmrc-mongo-work-item-repo-play-28", file("hmrc-mongo-work-item-repo-play-28"))
  .settings(
    commonSettings,
    unmanagedSourceDirectories in Compile += baseDirectory.value / "../hmrc-mongo-work-item-repo-play-27/src/main/scala",
    libraryDependencies ++= AppDependencies.hmrcMongoWorkItemRepoPlay28
  ).dependsOn(hmrcMongoMetrixPlay28)
