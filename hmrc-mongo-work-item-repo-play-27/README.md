
# work-item-repo

![](https://img.shields.io/github/v/release/hmrc/hmrc-mongo)

Enables a microservice to distribute work across it's instances.
It can be used as a simplified alternative to SQS, using mongo-repository as the queue.

## Installing

Include the following dependency in your SBT build

``` scala
resolvers += Resolver.bintrayRepo("hmrc", "releases")

libraryDependencies += "uk.gov.hmrc.mongo" %% "hmrc-mongo-work-item-repo-play-xx" % "[INSERT-VERSION]"
```

Where play-xx is play-26, play-27 or play-28 depending on your version of Play.

## How to Use

See [How to Use](../master/HOW_TO_USE.md)

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
