import sbt._

object AppDependencies {

  private val play28Version = "2.8.8"

  def test(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.scalatest"          %% "scalatest"                  % "3.1.0"        % Test,
    "org.scalacheck"         %% "scalacheck"                 % "1.14.3"       % Test,
    "org.scalatestplus"      %% "scalatestplus-scalacheck"   % "3.1.0.0-RC2"  % Test,
    "com.vladsch.flexmark"   %  "flexmark-all"               % "0.35.10"      % Test,
    "ch.qos.logback"         %  "logback-classic"            % "1.2.3"        % Test

  ) ++
    (CrossVersion.partialVersion(scalaVersion) match {
      case Some((2, n)) if n >= 13 => // spilt out into it's own jar in 2.13
                                      Seq("org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4" % Test)
      case _                       => Seq.empty
    })

  def mongoCommon(scalaBinaryVersionValue: String): Seq[ModuleID] = Seq(
    "org.mongodb.scala" %% "mongo-scala-driver" % "4.5.0",
    "org.slf4j"         %  "slf4j-api"          % "1.7.30"
  )

  lazy val metrixCommon: Seq[ModuleID] =
    Seq("io.dropwizard.metrics" % "metrics-graphite" % "3.2.6")

  def hmrcMongoPlay28(scalaVersion: String): Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play"       % play28Version,
    "com.typesafe.play" %% "play-guice" % play28Version
  ) ++ test(scalaVersion)

  def hmrcMongoTestPlay28(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.scalatest"         %% "scalatest"       % "3.1.0", // version chosen for compatibility with scalatestplus-play
    "com.vladsch.flexmark"  %  "flexmark-all"    % "0.35.10",
    "org.mockito"           %% "mockito-scala"   % "1.10.1" % Test
  ) ++ test(scalaVersion)

  lazy val hmrcMongoMetrixPlay28: Seq[ModuleID] = Seq(
    "com.kenshoo"           %% "metrics-play"    % "2.7.3_0.8.1" // no Play 2.8 build, but is compatible.
  )

  lazy val hmrcMongoWorkItemRepoPlay28: Seq[ModuleID] = Seq()
}
