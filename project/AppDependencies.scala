import sbt._

object AppDependencies {

  private val play26Version = "2.6.20"

  lazy val test: Seq[ModuleID] = Seq(
    "org.pegdown"   % "pegdown"    % "1.6.0" % "test",
    "org.scalatest" %% "scalatest" % "3.0.5" % "test"
  )
  lazy val common: Seq[ModuleID] = Seq("org.mongodb.scala" %% "mongo-scala-driver" % "2.7.0")

  lazy val hmrcMongoPlay26: Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play"       % play26Version,
    "com.typesafe.play" %% "play-guice" % play26Version
  ) ++ common ++ test

  lazy val hmrcMongoTest: Seq[ModuleID] = Seq(
    "org.pegdown"   % "pegdown"    % "1.6.0",
    "org.scalatest" %% "scalatest" % "3.0.5"
  ) ++ common ++ test
}
