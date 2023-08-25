import sbt._

object LibDependencies {

  private val play28Version = "2.8.20"
  private val play29Version = "2.9.0-M7"

  def test(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.scalatest"          %% "scalatest"                  % "3.2.15"       % Test,
    "org.scalacheck"         %% "scalacheck"                 % "1.14.3"       % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"            % "3.2.16.0"     % Test,
    "com.vladsch.flexmark"   %  "flexmark-all"               % "0.62.2"       % Test,
    "ch.qos.logback"         %  "logback-classic"            % "1.2.3"        % Test
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
    "uk.gov.hmrc"       %% "crypto-json-play-28" % "7.1.0-SNAPSHOT" % Test
  ) ++ test(scalaVersion)

  def hmrcMongoPlay29(scalaVersion: String): Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play"                % play29Version,
    "joda-time"         %  "joda-time"           % "2.10.5", // not provided transitively by play-28 (can we drop joda?)
    "com.typesafe.play" %% "play-guice"          % play29Version,
    "uk.gov.hmrc"       %% "crypto-json-play-29" % "7.1.0-SNAPSHOT" % Test
  ) ++ test(scalaVersion)

  def hmrcMongoTestPlay28(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.scalatest"         %% "scalatest"       % "3.1.0", // version chosen for compatibility with scalatestplus-play
    "com.vladsch.flexmark"  %  "flexmark-all"    % "0.35.10",
    "org.mockito"           %% "mockito-scala"   % "1.17.14" % Test
  ) ++ test(scalaVersion)

  def hmrcMongoTestPlay29(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.scalatest"         %% "scalatest"       % "3.2.15", // version chosen for compatibility with scalatestplus-play
    "com.vladsch.flexmark"  %  "flexmark-all"    % "0.62.2",
    "org.mockito"           %% "mockito-scala"   % "1.17.14" % Test
  ) ++ test(scalaVersion)

  lazy val hmrcMongoMetrixPlay28: Seq[ModuleID] = Seq(
    "com.kenshoo"           %% "metrics-play"    % "2.7.3_0.8.1", // no Play 2.8 build, but is compatible.
    "org.mockito"           %% "mockito-scala"   % "1.17.14" % Test
  )

  lazy val hmrcMongoMetrixPlay29: Seq[ModuleID] = Seq(
    "com.kenshoo"           %% "metrics-play"    % "2.7.3_0.8.1", // no Play 2.9 build, but is compatible.
    "org.mockito"           %% "mockito-scala"   % "1.17.14" % Test
  )

  lazy val hmrcMongoWorkItemRepoPlay28: Seq[ModuleID] = Seq()

  lazy val hmrcMongoWorkItemRepoPlay29: Seq[ModuleID] = Seq()
}
