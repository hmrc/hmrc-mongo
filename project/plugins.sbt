resolvers += Resolver.url("hmrc-sbt-plugin-releases", url("https://dl.bintray.com/hmrc/sbt-plugin-releases"))(
  Resolver.ivyStylePatterns
)

resolvers += "HMRC Releases" at "https://dl.bintray.com/hmrc/releases"

//addSbtPlugin("uk.gov.hmrc" % "sbt-auto-build" % "1.16.0")

//addSbtPlugin("uk.gov.hmrc" % "sbt-git-versioning" % "1.19.0")

//addSbtPlugin("uk.gov.hmrc" % "sbt-artifactory" % "0.21.0")

addSbtPlugin("com.lucidchart" % "sbt-cross" % "4.0")
