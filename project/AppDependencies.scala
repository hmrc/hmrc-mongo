import sbt._

object AppDependencies {

  val compile = Seq(
  )

  lazy val test: Seq[ModuleID] = Seq(
    "org.pegdown"            % "pegdown"     % "1.6.0" % "test",
    "org.scalatest"          %% "scalatest"  % "3.0.5" % "test"
  )

}