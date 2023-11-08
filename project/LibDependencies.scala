import sbt._

object LibDependencies {

  private val play28Version = "2.8.20"
  private val play29Version = "2.9.0"
  private val play30Version = "3.0.0"

  def test(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.scalatest"          %% "scalatest"                  % "3.2.17"       % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"            % "3.2.17.0"     % Test,
    "com.vladsch.flexmark"   %  "flexmark-all"               % "0.64.8"       % Test,
    "ch.qos.logback"         %  "logback-classic"            % "1.2.12"       % Test
  ) ++
    (CrossVersion.partialVersion(scalaVersion) match {
      case Some((2, 12)) => Seq.empty
      case _             => // spilt out into it's own jar in 2.13
                            Seq("org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4" % Test)
    })

  def mongoCommon(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.mongodb.scala" %% "mongo-scala-driver" % "4.10.2",
    "org.slf4j"         %  "slf4j-api"          % "1.7.30"
  ) ++ test(scalaVersion)

  lazy val metrixCommon: Seq[ModuleID] =
    Seq("io.dropwizard.metrics" % "metrics-graphite" % "3.2.6")

  def hmrcMongoPlay28(scalaVersion: String): Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play"                % play28Version,
    "com.typesafe.play" %% "play-guice"          % play28Version,
    "uk.gov.hmrc"       %% "crypto-json-play-28" % "7.5.0" % Test
  ) ++ test(scalaVersion)

  def hmrcMongoPlay29(scalaVersion: String): Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play"                % play29Version,
    "com.typesafe.play" %% "play-guice"          % play29Version,
    "uk.gov.hmrc"       %% "crypto-json-play-29" % "7.5.0" % Test
  ) ++ test(scalaVersion)

  def hmrcMongoPlay30(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.playframework" %% "play"                % play30Version,
    "org.playframework" %% "play-guice"          % play30Version,
    "uk.gov.hmrc"       %% "crypto-json-play-30" % "7.5.0" % Test
  ) ++ test(scalaVersion)

  def hmrcMongoTestPlay28(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.scalatest"         %% "scalatest"       % "3.1.1", // version chosen for compatibility with scalatestplus-play
    "com.vladsch.flexmark"  %  "flexmark-all"    % "0.35.10",
    "org.mockito"           %% "mockito-scala"   % "1.17.14" % Test
  ) ++ test(scalaVersion)

  def hmrcMongoTestPlay29(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.scalatest"         %% "scalatest"       % "3.2.17", // version chosen for compatibility with scalatestplus-play
    "com.vladsch.flexmark"  %  "flexmark-all"    % "0.62.2", // to go beyond requires Java 11 https://github.com/scalatest/scalatest/issues/2276
    "org.mockito"           %% "mockito-scala"   % "1.17.14" % Test
  ) ++ test(scalaVersion)

  def hmrcMongoTestPlay30(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.scalatest"         %% "scalatest"       % "3.2.17", // version chosen for compatibility with scalatestplus-play
    "com.vladsch.flexmark"  %  "flexmark-all"    % "0.62.2", // to go beyond requires Java 11 https://github.com/scalatest/scalatest/issues/2276
    "org.mockito"           %% "mockito-scala"   % "1.17.14" % Test
  ) ++ test(scalaVersion)

  lazy val hmrcMongoMetrixPlay28: Seq[ModuleID] = Seq(
    "io.dropwizard.metrics" %  "metrics-core"    % "4.0.5", // version chosen for compatibility with bootstrap-play
    "org.mockito"           %% "mockito-scala"   % "1.17.14" % Test
  )

  lazy val hmrcMongoMetrixPlay29: Seq[ModuleID] = Seq(
    "io.dropwizard.metrics" %  "metrics-core"    % "4.0.5", // version chosen for compatibility with bootstrap-play
    "org.mockito"           %% "mockito-scala"   % "1.17.14" % Test
  )

  lazy val hmrcMongoMetrixPlay30: Seq[ModuleID] = Seq(
    "io.dropwizard.metrics" %  "metrics-core"    % "4.0.5", // version chosen for compatibility with bootstrap-play
    "org.mockito"           %% "mockito-scala"   % "1.17.14" % Test
  )

  lazy val hmrcMongoWorkItemRepoPlay28: Seq[ModuleID] = Seq.empty

  lazy val hmrcMongoWorkItemRepoPlay29: Seq[ModuleID] = Seq.empty

  lazy val hmrcMongoWorkItemRepoPlay30: Seq[ModuleID] = Seq.empty
}
