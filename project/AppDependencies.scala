import sbt._

object AppDependencies {

  private val play26Version = "2.6.24"
  private val play27Version = "2.7.3"

  lazy val test: Seq[ModuleID] = Seq(
    "org.pegdown"   % "pegdown"    % "1.6.0" % "test",
    "org.scalatest" %% "scalatest" % "3.0.8" % "test"
  )
  lazy val common: Seq[ModuleID] = Seq("org.mongodb.scala" %% "mongo-scala-driver" % "2.7.0")

  lazy val hmrcMongoPlay26: Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play"       % play26Version,
    "com.typesafe.play" %% "play-guice" % play26Version
  ) ++ common ++ test

  lazy val hmrcMongoPlay27: Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play"       % play27Version,
    "com.typesafe.play" %% "play-guice" % play27Version
  ) ++ common ++ test

  lazy val hmrcMongoTest: Seq[ModuleID] = Seq(
    "org.pegdown"   % "pegdown"    % "1.6.0",
    "org.scalatest" %% "scalatest" % "3.0.8"
  ) ++ common ++ test

  lazy val metrixPlay26: Seq[ModuleID] = Seq(
    "io.dropwizard.metrics" % "metrics-graphite"      % "3.2.6",
    "com.kenshoo"           %% "metrics-play"         % "2.6.19_0.7.0",
    "org.mockito"           % "mockito-all"           % "1.9.5"         % Test
  ) ++ hmrcMongoPlay26

}
