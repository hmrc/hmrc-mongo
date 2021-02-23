
# hmrc-mongo

![](https://img.shields.io/github/v/release/hmrc/hmrc-mongo)

Provides support to use the [Official Scala driver for MongoDB](https://docs.mongodb.com/drivers/scala)

It is designed to make the transition from [Simple Reactivemongo](https://github.com/hmrc/simple-reactivemongo) as easy as possible.

## Main features

It provides a `PlayMongoRepository` class to help set up a `org.mongodb.scala.MongoCollection`, registering the domain model codecs, and creating the indices. Then all queries/updates can be carried out on the `MongoCollection` with the official scala driver directly.

The model objects are mapped to json with [Play json](https://github.com/playframework/play-json). This library will then map the json to mongo BSON.

Other included features are:
- [lock](#lock)
- [cache](#cache)
- [test support](https://github.com/hmrc/hmrc-mongo/tree/master/hmrc-mongo-test-play-27)
- [metrix](https://github.com/hmrc/hmrc-mongo/tree/master/hmrc-mongo-metrix-play-27)

## Migration

See [MIGRATION](https://github.com/hmrc/hmrc-mongo/blob/master/MIRATION.md) for migrating from Simple-reactivemongo.

## Adding to your build

In your SBT build add:

```scala
resolvers += Resolver.bintrayRepo("hmrc", "releases")

libraryDependencies ++= Seq(
  "uk.gov.hmrc.mongo" %% "hmrc-mongo-play-xx" % "[INSERT_VERSION]"
)
```

Where play-xx is play-26, play-27 or play-28 depending on your version of Play.

## PlayMongoRepository

Create a case class to represent the data model to be serialised/deserialised to `MongoDB`

Create [JSON Format](https://www.playframework.com/documentation/2.8.x/ScalaJsonCombinators) to map the data model to JSON.

Extend [PlayMongoRepository](https://github.com/hmrc/hmrc-mongo/blob/master/hmrc-mongo-play-27/src/main/scala/uk/gov/hmrc/mongo/play/PlayMongoComponent.scala), providing the collectionName, the mongoComponent, and domainFormat.

The mongoComponent can be injected if you register the PlayMongoModule with play. In `application.conf`:
```scala
play.modules.enabled += "uk.gov.hmrc.mongo.play.PlayMongoModule"
```

```scala
@Singleton
class UserRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository[User](
  collectionName = "user",
  mongoComponent = mongoComponent,
  domainFormat   = User.mongoFormat,
  indexes        = Seq(
                     IndexModel(ascending("name"), IndexOptions.name("nameIdx").unique(true))
                   )))
) {
  // queries and updates can now be implemented with the available `collection: org.mongodb.scala.MongoCollection`
  def findAll(): Future[User] =
    collection.find().toFuture
}
```

Other parameters:
- `indexes` - in the above example, the indices were also provided to the constructor. They will be ensured to be created before the Repository is available, by blocking. Mark Indices as `background` as appropriate.
- `optSchema` - you may provide a `BSONDocument` to represent the schema. If provided, all inserts will be validated against this. This may be useful when migrating from simple-reactivemongo to ensure the domainFormat has not changed, if relying on library provided formats (e.g. dates).
- `replaceIndexes` - by default, the indices defined by `indexes` parameter will be created in addition to any previously defined indices. If an index definition changes, you will get a `MongoCommandException` for the conflict. If an index definition has been removed, it will still be left in the database. By setting `replaceIndexes` to `true`, it will remove any previously but no longer defined indices, and replace any indices with changed definition. **Please check how reindexing affects your application before turning on**


## Lock

This is a utility that prevents multiple instances of the same application from performing an operation at the same time. This can be useful for example when a REST api has to be called at a scheduled time. Without this utility every instance of the application would call the REST api.

There are 2 variants that can be used to instigate a lock, `LockService` for locking for a particular task and `ExclusiveTimePeriodLock` to lock exclusively for a given time period (i.e. stop other instances executing the task until it stops renewing the lock).

### LockService

Inject `MongoLockRepository` and create an instance of `LockService`.

The `ttl` timeout allows other apps to release and get the lock if it was stuck for some reason.

`withLock[T](body: => Future[T]): Future[Option[T]]` accepts anything that returns a Future[T] and will return the result in an Option. If it was not possible to acquire the lock, None is returned.

It will execute the body only if the lock can be obtained, and the lock is released when the action has finished (both successfully or in failure).

e.g.

```scala
@Singleton
class LockClient @Inject()(mongoLockRepository: MongoLockRepository) {
  val lockService = LockService(mongoLockRepository, lockId = "my-lock", ttl = 1.hour)

  // now use the lock
  lockService.withLock {
    Future { /* do something */ }
  }.map {
    case Some(res) => logger.debug(s"Finished with $res. Lock has been released.")
    case None      => logger.debug("Failed to take lock")
  }
}
```

### TimePeriodLockService

The `ttl` timeout allows other apps to claim the lock if it is not renewed for this period.

`withRenewedLock[T](body: => Future[T]): Future[Option[T]]` accepts anything that returns a Future[T] and will return the result in an Option. If it was not possible to acquire the lock, None is returned.

It will execute the body only if no lock is already taken, or the lock is already owned by this service instance. It is not released when the action has finished (unless it ends in failure), but is held onto until it expires.


```scala
@Singleton
class LockClient @Inject()(mongoLockRepository: MongoLockRepository) {
  val lockService = TimePeriodLockService(mongoLockRepository, lockId = "my-lock", ttl = 1.hour)

  // now use the lock
  lockService.withRenewedLock {
    Future { /* do something */ }
  }.map {
    case Some(res) => logger.debug(s"Finished with $res. Lock has been renewed.")
    case None      => logger.debug("Failed to take lock")
  }
}
```

## Cache

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
