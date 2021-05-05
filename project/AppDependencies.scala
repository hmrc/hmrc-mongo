import sbt._

object AppDependencies {

  private val play27Version = "2.7.5"
  private val play28Version = "2.8.7"

  lazy val test: Seq[ModuleID] = Seq(
    "org.scalatest"        %% "scalatest"                % "3.1.0"        % Test,
    "org.scalacheck"       %% "scalacheck"               % "1.14.3"       % Test,
    "org.scalatestplus"    %% "scalatestplus-scalacheck" % "3.1.0.0-RC2"  % Test,
    "com.vladsch.flexmark" %  "flexmark-all"             % "0.35.10"      % Test,
    "ch.qos.logback"       %  "logback-classic"          % "1.2.3"        % Test
  )

  def mongoCommon(scalaBinaryVersionValue: String): Seq[ModuleID] = Seq(
    "org.mongodb.scala" %% "mongo-scala-driver" % "4.1.1",
    "org.slf4j"         %  "slf4j-api"          % "1.7.30"
  )

  lazy val metrixCommon: Seq[ModuleID] =
    Seq("io.dropwizard.metrics" % "metrics-graphite" % "3.2.6")

  lazy val hmrcMongoPlay27: Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play"       % play27Version,
    "com.typesafe.play" %% "play-guice" % play27Version
  ) ++ test

  lazy val hmrcMongoPlay28: Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play"       % play28Version,
    "com.typesafe.play" %% "play-guice" % play28Version
  ) ++ test

  lazy val hmrcMongoTestPlay27: Seq[ModuleID] = Seq(
    "org.scalatest"         %% "scalatest"       % "3.0.8", // version chosen for compatibility with scalatestplus-play
    "com.vladsch.flexmark"  %  "flexmark-all"    % "0.35.10",
    "org.mockito"           %% "mockito-scala"   % "1.10.1" % Test
  ) ++ test

  lazy val hmrcMongoTestPlay28: Seq[ModuleID] = Seq(
    "org.scalatest"         %% "scalatest"       % "3.1.0",
    "com.vladsch.flexmark"  %  "flexmark-all"    % "0.35.10",
    "org.mockito"           %% "mockito-scala"   % "1.10.1" % Test
  ) ++ test

  lazy val hmrcMongoMetrixPlay27: Seq[ModuleID] = Seq(
    "com.kenshoo"           %% "metrics-play"    % "2.7.3_0.8.1",
    "org.scalatest"         %% "scalatest"       % "3.1.0"  % Test,
    "org.mockito"           %% "mockito-scala"   % "1.10.1" % Test
  )

  lazy val hmrcMongoMetrixPlay28: Seq[ModuleID] = Seq(
    "com.kenshoo"           %% "metrics-play"    % "2.7.3_0.8.1", // no Play 2.8 build, but is compatible.
    "org.mockito"           %% "mockito-scala"   % "1.10.1" % Test
  )

  lazy val hmrcMongoWorkItemRepoPlay27: Seq[ModuleID] = Seq(
    "org.scalatest"         %% "scalatest"       % "3.1.0"  % Test
  )

  lazy val hmrcMongoWorkItemRepoPlay28: Seq[ModuleID] = Seq(
    "org.scalatest"         %% "scalatest"       % "3.1.0"  % Test
  )
}
