import sbt._

object AppDependencies {

  private val play26Version = "2.6.20"
  private val play27Version = "2.7.3"

  lazy val test: Seq[ModuleID] = Seq(
    "org.scalatest"        %% "scalatest"                % "3.1.0-M2" % Test,
    "org.scalacheck"       %% "scalacheck"               % "1.14.0"   % Test,
    "org.scalatestplus"    %% "scalatestplus-scalacheck" % "1.0.0-M2" % Test,
    "com.vladsch.flexmark" %  "flexmark-all"             % "0.35.10"  % Test // replaces pegdown for newer scalatest
  )

  lazy val common: Seq[ModuleID] =
    Seq("org.mongodb.scala" %% "mongo-scala-driver" % "2.7.0")

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
    "org.scalatest" %% "scalatest" % "3.0.5"
  ) ++ common ++ test
}
