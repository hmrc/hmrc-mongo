import sbt._

object LibDependencies {

  def test(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.scalatest"          %% "scalatest"                  % "3.2.17"       % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"            % "3.2.17.0"     % Test,
    "com.vladsch.flexmark"   %  "flexmark-all"               % "0.64.8"       % Test,
    "ch.qos.logback"         %  "logback-classic"            % "1.5.18"       % Test,
    "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4"        % Test
  )

  def mongoCommon(scalaVersion: String): Seq[ModuleID] = Seq(
    "org.mongodb.scala" %% "mongo-scala-driver" % "5.5.1" cross CrossVersion.for3Use2_13,
    "uk.gov.hmrc"       %% "mdc"                % "0.1.0",
    "org.slf4j"         %  "slf4j-api"          % "1.7.30"
  ) ++ test(scalaVersion)

  lazy val metrixCommon: Seq[ModuleID] =
    Seq("io.dropwizard.metrics" % "metrics-graphite" % "3.2.6")

  def hmrcMongoPlay(playSuffix: String, scalaVersion: String): Seq[ModuleID] = Seq(
    playOrg(playSuffix) %% "play"                     % playVersion(playSuffix),
    playOrg(playSuffix) %% "play-guice"               % playVersion(playSuffix),
    "uk.gov.hmrc"       %% s"crypto-json-$playSuffix" % "8.2.0" % Test
  ) ++ test(scalaVersion)

  def hmrcMongoTestPlay(playSuffix: String, scalaVersion: String): Seq[ModuleID] = Seq(
    "org.scalatest"         %% "scalatest"       % scalatestVersion(playSuffix),
    "com.vladsch.flexmark"  %  "flexmark-all"    % flexmarkAllVersion(playSuffix)
  ) ++ test(scalaVersion)

  def hmrcMongoMetrixPlay(playSuffix: String): Seq[ModuleID] = Seq(
    "io.dropwizard.metrics" %  "metrics-core"    % "4.2.22", // version chosen for compatibility with bootstrap-play
    "org.scalatestplus"     %% "mockito-4-11"    % "3.2.17.0" % Test
  )

  def hmrcMongoWorkItemRepoPlay(playSuffix: String): Seq[ModuleID] =
    Seq.empty

  private def playVersion(playSuffix: String) =
    playSuffix match {
      case "play-29" => "2.9.6"
      case "play-30" => "3.0.6"
    }

  private def playOrg(playSuffix: String) =
    playSuffix match {
      case "play-29" => "com.typesafe.play"
      case "play-30" => "org.playframework"
    }

  private def scalatestVersion(playSuffix: String) =
    // version chosen for compatibility with scalatestplus-play
    playSuffix match {
      case "play-29"
         | "play-30" => "3.2.17"
    }

  private def flexmarkAllVersion(playSuffix: String) =
    playSuffix match {
      case "play-29"
         | "play-30" => "0.64.8"
    }
}
