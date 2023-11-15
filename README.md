
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
- [transactions](#transactions)
- [test support](https://github.com/hmrc/hmrc-mongo/tree/main/hmrc-mongo-test-play-30)
- [metrix](https://github.com/hmrc/hmrc-mongo/tree/main/hmrc-mongo-metrix-play-30)
- [work-item-repo](https://github.com/hmrc/hmrc-mongo/tree/main/hmrc-mongo-work-item-repo-play-30)

## Migration

See [MIGRATION](https://github.com/hmrc/hmrc-mongo/blob/main/MIGRATION.md) for migrating from Simple-reactivemongo.

## Adding to your build

In your SBT build add:

```scala
resolvers += "HMRC-open-artefacts-maven2" at "https://open.artefacts.tax.service.gov.uk/maven2"

libraryDependencies ++= Seq(
  "uk.gov.hmrc.mongo" %% "hmrc-mongo-play-xx" % "[INSERT_VERSION]"
)
```

Where `play-xx` is your version of Play (e.g. `play-30`).

## PlayMongoRepository

Create a case class to represent the data model to be serialised/deserialised to `MongoDB`

Create [JSON Format](https://www.playframework.com/documentation/2.8.x/ScalaJsonCombinators) to map the data model to JSON.

Extend [PlayMongoRepository](https://github.com/hmrc/hmrc-mongo/blob/main/hmrc-mongo-play-30/src/main/scala/uk/gov/hmrc/mongo/play/PlayMongoRepository.scala), providing the collectionName, the mongoComponent, and domainFormat.

The mongoComponent can be injected if you register the PlayMongoModule with play. In `application.conf`:
```scala
play.modules.enabled += "uk.gov.hmrc.mongo.play.PlayMongoModule"
```

Ensure the repository is annotated with `javax.inject.Singleton` so indexes are only initialised on startup.

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
                     IndexModel(Indexes.ascending("name"), IndexOptions().name("nameIdx").unique(true))
                   ),
  extraCodecs    = Seq(
                     new UuidCodec(UuidRepresentation.STANDARD),
                     Codecs.playFormatCodec(AdminUser.mongoFormat),
                     Codecs.playFormatCodec(StandardUser.mongoFormat)
                   )
) {
  // queries and updates can now be implemented with the available `collection: org.mongodb.scala.MongoCollection`
  def findAll(): Future[Seq[User]] =
    collection.find().toFuture()
}
```

Other parameters:
- `indexes` - in the above example, the indices were also provided to the constructor. They will be ensured to be created before the Repository is available, by blocking. Mark Indices as `background` as appropriate.
- `optSchema` - you may provide a `BSONDocument` to represent the schema. If provided, all inserts will be validated against this. This may be useful when migrating from simple-reactivemongo to ensure the domainFormat has not changed, if relying on library provided formats (e.g. dates).
- `replaceIndexes` - by default, the indices defined by `indexes` parameter will be created in addition to any previously defined indices. If an index definition changes, you will get a `MongoCommandException` for the conflict. If an index definition has been removed, it will still be left in the database. By setting `replaceIndexes` to `true`, it will remove any previously but no longer defined indices, and replace any indices with changed definition. **Please check how reindexing affects your application before turning on**
- `extraCodecs` - you may provide extra `Codec`s in order to support the types you wish to use in your repository. Some examples are:
  - you may want to provide a `UuidCodec` in order to work with UUIDs as binary values - see [UUIDs](./MIGRATION.md#UUIDs) for more information.
  - Since the Mongo driver uses the runtime class of objects to look up the codec, if you are working with a `sealed trait` you may want to use `Codecs.playFormatSumCodecs` to create a codec for each subtype detected. See [ADTs](./MIGRATION.md#atds) for more information.

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

This is a utility to cache generic JSON data in Mongo DB.

The variants are:
  - `MongoCacheRepository` for storing json data into a composite object which expires as a unit.
  - `SessionCacheRepository` ties the composite object to the session.
  - `EntityCache` which makes it easier to work with a single data type.


### `MongoCacheRepository`

e.g.

```scala
@Singleton
class MyCacheRepository @Inject()(
  mongoComponent  : MongoComponent,
  configuration   : Configuration,
  timestampSupport: TimestampSupport
)(implicit ec: ExecutionContext
) extends MongoCacheRepository(
  mongoComponent   = mongoComponent,
  collectionName   = "mycache",
  ttl              = configuration.get[FiniteDuration]("cache.expiry")
  timestampSupport = timestampSupport, // Provide a different one for testing
  cacheIdType      = CacheIdType.SimpleCacheId // Here, CacheId to be represented with `String`
)
```

The functions exposed by this class are:

- `put[A: Writes](cacheId: CacheId)(dataKey: DataKey[A], data: A): Future[CacheItem]`

  This upserts data into the cache.

  Data inserted using this method has a time-to-live (TTL) that applies per CacheId. The amount of time is configured by the `ttl` parameter when creating the class. Any modifications of data for an CacheId will reset the TTL.

  Calling `put[String](cacheId)(DataKey("key1"), "value1)` and `put[String](cacheId)(DataKey("key2"), "value2)` with the same cacheId will create the following JSON structure in Mongo:

  ```json
  {
    "_id": "cacheId",
    "data": {
      "key1": "value1",
      "key2": "value2"
    }
  }
  ```

  This structure allows caching multiple keys against a CacheId. As cached values expire per CacheId, this provides a way to expire related data at the same time.

  See `EntityCache` for a simpler use-case, where key is hardcoded to a constant, to provide a cache of CacheId to value.

- `get[A: Reads](cacheId: CacheId)(dataKey: DataKey[A]): Future[Option[A]]`

  This retrieves the data stored under the dataKey.

  The `DataKey` has a phantom type to indicate the type of data stored. e.g.

  ```scala
  implicit val stepOneDataFormat: Format[StepOneData] = ...
  val stepOneDataKey = DataKey[StepOneData]("stepOne")

  for {
    _       <- cacheRepository.put(cacheId)(stepOneDataKey, StepOneData(..))
    optData <- cacheRepository.get(cacheId)(stepOneDataKey) // inferred as Option[StepOneData]
  } yield println(s"Found $optData")
  ```

- `delete[A](cacheId: CacheId)(dataKey: DataKey[A]): Future[Unit]`

  Deletes the data stored under the dataKey.

- `deleteEntity(cacheId: CacheId): Future[Unit]`

  Deletes the whole entity (rather than waiting for the ttl).

- `findById(cacheId: CacheId): Future[Option[CacheItem]]`

  Returns the whole entity.


### SessionCacheRepository

  A variant of MongoCacheRepository which uses the sessionId for cacheId. This helps, for example, store data from different steps of a flow against the same session.

  It exposes the functions `putSession`, `getFromSession` and `deleteFromSession` which require the `Request` rather than a CacheId.

### EntityCache

 A variant which makes it easier to work with a single data type, which all expire independently.

## Transactions

The trait `uk.gov.hmrc.mongo.transaction.Transactions` fills the gap of providing `withTransaction` (as available for the java sync driver) for mongo-scala. It may be removed when this is supported by the official driver.

First confirm whether you actually need to use transactions, they will incur a performance cost.

If you don't need to use an existing Session, it is preferrable to use `withSessionAndTransaction` which will provide you a session to use.

You will need to provide an implicit `TransactionConfiguration`. We recommend using `TransactionConfiguration.strict` for causal consistency, but you can provide your own if you do not need such a strict configuration.

It is also recommended to use the `Future` rather than the `Observable` abstraction, since we have noticed a few gotchas with the `Observable` - e.g. some db functions return `Publisher[Void]` which silently ignore any further monadic steps.

e.g.

```scala
@Singleton
class ModelRepository @Inject() (val mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
  extends PlayMongoRepository[Model](...)
     with Transactions {

  private implicit val tc = TransactionConfiguration.strict

  def replaceAll(seq: Seq[Model]): Future[Unit] =
    withSessionAndTransaction(session =>
      for {
        _ <- collection.deleteMany(session, Document()).toFuture()
        _ <- collection.insertMany(session, seq).toFuture()
      } yield ()
    )
```

You may see `com.mongodb.MongoCommandException, with message: Command failed with error 263 (OperationNotSupportedInTransaction): 'Cannot create namespace ... in multi-document transaction.'` if collections are created implicitly on insert/upsert from a transaction, which is not supported until Mongo 4.4. You will need to ensure that the collection is created before the transaction runs. This especially applies to tests.

## Encryption

`hmrc-mongo` doesn't itself provide any encryption support. It is recommend to use `crypto-json` as provided by [crypto](https://github.com/hmrc/crypto).

Tests should ensure the data is stored in the expected encrypted format.
You may want to refer to [these examples](/hmrc-mongo-play-30/src/test/scala/uk/gov/hmrc/mongo/encryption)

## TTL Indexes

TTL Indexes are generally expected. `PlayMongoRepository` will log warnings on startup if there is no TTL Index. `DefaultPlayMongoRepositorySupport` will by default fail the test if there isn't a TTL Index or it points to a non-Date field.

In the exceptional case that a TTL Index is not required, this can be indicated by overriding `requiresTtlIndex` with `false` in `PlayMongoRepository`.

## Changes

### Version 1.4.0
- Supports Play 2.9.
  Note, joda time is no longer supported with Play 2.9 build.

### Version 1.3.0
- Restore target JVM 8.
### Version 1.2.0
- Made awaits on initialisation configurable. Use `hmrc.mongo.init.timeout` - default is `5 seconds`.
### Version 1.0.0
- `java.time.LocalDateTime` support has been removed.

  It is preferrable to use `java.time.Instant` for storing DateTime in mongo. Use your own formats if `LocalDateTime` is required.

- Added checks for TTL Indexes.

  See [TTL Indexes](#ttl-indexes)

- Deployments will no longer fail if adding a new index takes a long time to apply. Failures to create an index (e.g. conflicting index) will still fail a deployment if they occur immediately (which they typically do).

  If you still need to know when `ensureIndexes` has finished, you can access `PlayMongoRepository#initialised`

### Version 0.72.0
MongoCacheRepository.get previously returned `None` for both an empty/expired cache as well as if it failed to deserialise the data. Failure to deserialise will now result in a failed Future.

### Version 0.60.0

Drops support for Play 2.7. Only Play 2.8 is supported.

## License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
