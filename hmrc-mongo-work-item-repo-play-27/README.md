
# work-item-repo

[ ![Download](https://api.bintray.com/packages/hmrc/releases/work-item-repo/images/download.svg) ](https://bintray.com/hmrc/releases/work-item-repo/_latestVersion)

Enables a microservice to distribute work across it's instances.
It can be used as a simplified alternative to SQS, using mongo-repository as the queue.

## Installing

Include the following dependency in your SBT build

``` scala
resolvers += Resolver.bintrayRepo("hmrc", "releases")

libraryDependencies += "uk.gov.hmrc" %% "work-item-repo" % "[INSERT-VERSION]"
```

## How to Use

See [How to Use](../master/HOW_TO_USE.md)

## Compatibility

|Library Version|Scala Version|Play Version|
|--------|-------------|---------------|
|>=7.2.x | 2.12        | 2.7           |
|7.x.x   | 2.11, 2.12  | 2.6           |
|6.x.x   | 2.11        | 2.6, 2.5      |

From 7.x.x onwards support for Play 2.5 has been dropped. If you use Play 2.5 continue to use the 6.x.x branch and consider upgrading.
work-item-repo since version 6.0.0 uses the latest ReactiveMongo (https://github.com/ReactiveMongo/ReactiveMongo) instead of HMRC fork of it (https://github.com/hmrc/ReactiveMongo). Please review your dependencies if you upgrade. In particular you should no longer use https://github.com/hmrc/Play-ReactiveMongo/ in your microservice.

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
