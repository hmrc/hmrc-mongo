import sbt._

val name = "hmrc-mongo"

lazy val commonSettings = Seq(
  organization := "uk.gov.hmrc",
  majorVersion := 0
)

lazy val library = Project(name, file("."))
  .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning, SbtArtifactory)
  .settings(
    commonSettings,
    publish := {},
    publishAndDistribute := {},
    scalaVersion := "2.11.12",
    crossScalaVersions := Seq("2.11.12", "2.12.8")
  )
  .aggregate(hmrcMongoCommon, hmrcMongoPlay26, hmrcMongoPlay27, metrixPlay26, hmrcMongoTest)

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
  )

lazy val hmrcMongoPlay26 = Project("hmrc-mongo-play-26", file("hmrc-mongo-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .dependsOn(hmrcMongoCommon, hmrcMongoTest % Test)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoPlay26,
    makePublicallyAvailableOnBintray := true,
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  )

lazy val hmrcMongoPlay27 = Project("hmrc-mongo-play-27", file("hmrc-mongo-play-27"))
  .dependsOn(hmrcMongoCommon, hmrcMongoTest % Test)
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

lazy val hmrcMongoTest = Project("hmrc-mongo-test", file("hmrc-mongo-test"))
  .dependsOn(hmrcMongoCommon)
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.hmrcMongoTest,
    makePublicallyAvailableOnBintray := true,
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  )

lazy val metrixPlay26 = Project("hmrc-mongo-metrix-play-26", file("hmrc-mongo-metrix-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .dependsOn(hmrcMongoPlay26 % Compile, hmrcMongoTest % Compile)
  .settings(
    commonSettings,
    libraryDependencies ++= AppDependencies.metrixPlay26,
    makePublicallyAvailableOnBintray := true,
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  )
