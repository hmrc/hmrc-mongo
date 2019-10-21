import sbt._

val name = "hmrc-mongo"

lazy val library = Project(name, file("."))
  .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning, SbtArtifactory)
  .settings(
    majorVersion := 0,
    scalaVersion := "2.11.12",
    crossScalaVersions := Seq("2.11.12", "2.12.8")
  )
  .aggregate(hmrcMongoPlay26, hmrcMongoTest)

lazy val hmrcMongoPlay26 = Project("hmrc-mongo-play-26", file("hmrc-mongo-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    majorVersion := 0,
    libraryDependencies ++= AppDependencies.hmrcMongoPlay26,
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  )

lazy val hmrcMongoTest = Project("hmrc-mongo-test", file("hmrc-mongo-test"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    majorVersion := 0,
    libraryDependencies ++= AppDependencies.hmrcMongoTest,
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  )
