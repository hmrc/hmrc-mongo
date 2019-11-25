import sbt._

object AppDependencies {

  private val play26Version = "2.6.24"
  private val play27Version = "2.7.3"

  lazy val test: Seq[ModuleID] = Seq(
    "org.scalatest"        %% "scalatest"                % "3.1.0-M2" % Test,
    "org.scalacheck"       %% "scalacheck"               % "1.14.0"   % Test,
    "org.scalatestplus"    %% "scalatestplus-scalacheck" % "1.0.0-M2" % Test,
    "com.vladsch.flexmark" % "flexmark-all"              % "0.35.10"  % Test // replaces pegdown for newer scalatest
  )

  lazy val mongoCommon: Seq[ModuleID] =
    Seq("org.mongodb.scala" %% "mongo-scala-driver" % "2.7.0")

  lazy val hmrcMongoPlay26: Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play"       % play26Version,
    "com.typesafe.play" %% "play-guice" % play26Version
  ) ++ mongoCommon ++ test

  lazy val hmrcMongoPlay27: Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play"       % play27Version,
    "com.typesafe.play" %% "play-guice" % play27Version
  ) ++ mongoCommon ++ test

  lazy val hmrcMongoCachePlay26: Seq[ModuleID] = Seq() ++ common ++ test

  lazy val hmrcMongoTest: Seq[ModuleID] = Seq(
    "org.pegdown"   % "pegdown"    % "1.6.0",
    "org.scalatest" %% "scalatest" % "3.0.8"
  ) ++ mongoCommon ++ test

  lazy val metrixPlay26: Seq[ModuleID] = Seq(
    "io.dropwizard.metrics" % "metrics-graphite" % "3.2.6",
    "com.kenshoo"           %% "metrics-play"    % "2.6.19_0.7.0",
    "org.mockito"           %% "mockito-scala"   % "1.7.1" % Test
  ) ++ hmrcMongoPlay26

  lazy val metrixPlay27: Seq[ModuleID] = Seq(
    "io.dropwizard.metrics" % "metrics-graphite" % "3.2.6",
    "com.kenshoo"           %% "metrics-play"    % "2.7.3_0.8.1",
    "org.mockito"           %% "mockito-scala"   % "1.7.1" % Test
  ) ++ hmrcMongoPlay27

}
