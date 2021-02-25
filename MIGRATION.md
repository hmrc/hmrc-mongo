
# Migration Guide

This guide is for migrating from [simple-reactivemongo](https://github.com/hmrc/simple-reactivemongo) over to hmrc-mongo.

## Table of Contents
- [Replace dependencies](#replace-dependencies)
- [Update Configuration](#update-configuration)
- [Replace ReactiveRepository with PlayMongoRepository](#replace-reactiverepository-with-playmongorepository)
- [Migrate index definitions](#migrate-index-definitions)
- [Update queries to new syntax](#update-queries-to-new-syntax)
- [Error Handling](#error-handling)
- [BSON, Domain Formats and Codecs](#bson-domain-formats-and-codecs)
  - [Entity model](#entity-model)
  - [Filters/Updates](#filtersupdates)
- [Updating Tests](#updating-tests)
  - [DefaultPlayMongoRepositorySupport](#defaultplaymongorepositorysupport)
  - [Mongo Schemas](#mongo-schemas)
- [Deployment](#deployment)
- [Lock](#lock)
- [Cache](#cache)


## Replace dependencies

In `build.sbt/AppDependencies.scala` remove:

```scala
  "uk.gov.hmrc" %% "simple-reactivemongo" % "[version]"
  "uk.gov.hmrc" %% "reactivemongo-test"   % "[version]"
```

and replace with:

```scala
 "uk.gov.hmrc.mongo" %% "hmrc-mongo-play-27"      % "[latest version]"
 "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-27" % "[latest version]"
 ```

Latest versions can be found at: https://github.com/hmrc/hmrc-mongo/releases

If you are pulling in reactivemongo via a feature dependency like cache or lock, replace with the library provided by hmrc-mongo (see [README](https://github.com/hmrc/hmrc-mongo/blob/master/README.md)).

## Update Configuration

In `application.conf` remove

```scala
play.modules.enabled += "play.modules.reactivemongo.ReactiveMongoHmrcModule"
```

and replace with:

```scala
play.modules.enabled += "uk.gov.hmrc.mongo.play.PlayMongoModule"
```

## Replace ReactiveRepository with PlayMongoRepository

Replace `uk.gov.hmrc.mongo.ReactiveRepository` with `uk.gov.hmrc.mongo.play.json.PlayMongoRepository`

```scala
@Singleton
class MyRepo @Inject()(mongo: ReactiveMongoComponent)(implicit ec: ExecutionContext
) extends ReactiveRepository[MyModel, BSONObjectID](
  collectionName = "mycollection",
  mongo          = mongo.mongoConnector.db,
  domainFormat   = MyModel.mongoFormat
)
```

becomes

```scala
@Singleton
class MyRepo @Inject()(mongo: MongoComponent)(implicit ec: ExecutionContext
) extends PlayMongoRepository[MyModel](
  collectionName = "mycollection",
  mongoComponent = mongo,
  domainFormat   = MyModel.mongoFormat
)
```

Note, the scope of the PlayMongoRepository is reduced in comparison to the ReactiveRepository. It is only responsible for setting up a `collection: MongoCollection[Entity]`, and initialising indices and entity codecs. You will use the collection directly for all queries and commands.

For this reason, you do not need to pass the id type any longer.

Also see [scalafix](https://github.com/hmrc/scalafix-rules/tree/master/hmrc-mongo) for some examples.

## Migrate index definitions

The syntax for defining indexes has changed.
See: https://mongodb.github.io/mongo-java-driver/4.0/driver-scala/tutorials/indexes/

:warning: Check that indexes are not unintentionally defined differently, since this may lead to a reindexing (of potentially large data).

Indexes should be set in the PlayMongoRepository’s constructor e.g.

```scala
class MyRepo @Inject()(mongo: MongoComponent)(implicit ec: ExecutionContext
) extends PlayMongoRepository[MyModel](
  collectionName = "mycollection",
  mongoComponent = mongo,
  domainFormat   = MyModel.mongoFormat,
  indexes        = Seq(IndexModel(ascending("myId"),IndexOptions().unique(true)),
  replaceIndexes = false
)
```

If `replaceIndexes` is set to true (default false) PlayMongoRepository will check and update existing indexes at startup. This includes removing existing indexes no longer defined in code.

:warning: The `replaceIndexes` functionality is in the `ensureIndexes()` method. If your repository overrides `ensureIndexes()` then you will not have this functionality. It is recommended to use the declarative `indexes` instead.

:warning: Rebuilding indexes can be an expensive operation on large data sets. Understand the impact of rebuilding the indexes before using in production!


## Update queries to new syntax

HMRC-Mongo’s PlayMongoRepository queries and commands are done using the [underlying API](https://mongodb.github.io/mongo-java-driver/4.0/driver-scala/tutorials/perform-read-operations/) rather than via helpers or wrapped functions.

The underlying API typically returns an `Observable` which can be converted to a scala future using `.toFuture()`, `.toFutureOption()` or `.headOption`.

Examples of ReactiveRepository (SimpleReactiveMongo) vs PlayMongoRepository (HMRC-Mongo)

```scala
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.model.Sorts._
```

### Find

```scala
find( "foo" -> JsString("bar"))
```

becomes

```scala
collection.find( equal("foo", "bar") ).toFuture()
```

### Find (multiple keys)

```scala
find("foo" -> JsString("bar"), "baz" -> 123)
```

becomes

```scala
collection.find( and( equal("foo", "bar"), equal("baz", 123) ) ).toFuture()
```

### Find All

```scala
findAll(ReadPreference.secondaryPreferred)
```

becomes

```scala
collection.withReadPreference(ReadPreference.secondaryPreferred).find().toFuture().map(_.toList)
```

### Insert

```scala
insert( myModel )
```

becomes

```scala
collection.insert(myModel).toFuture()
```

### Update

```scala
findAndUpdate(Json.obj("foo" -> "bar"), Json.obj("$set" -> Json.obj("baz" -> 123)))
```

becomes

```scala
collection.findOneAndUpdate(equal("foo", "bar"), set("baz", 123)).toFutureOption()
```

### Aggregation queries

Some of the building blocks in reactivemongo do not use the `$` in the syntax, adding it for you. The mongo-driver on the other hand needs it explicitly.

```scala
collection.aggregatorContext(List(
  UnwindField("dependencies")
)).collect
```
becomes

```scala
collection.aggregate(List(
  unwind("$dependencies")  // <- the $ is now needed here
)).toFuture()
```

## Error Handling

Non-query operations return result objects similar to reactivemongo (e.g. `InsertOneResult`, `DeleteManyResult`) containing data on how many records were modified etc.

Where it differs is when operations fail, rather than returning an error in the object the future fails.

## BSON, Domain Formats and Codecs

Both simple-reactivemongo and hmrc-mongo rely on model to json mapping (with [play-json](https://github.com/playframework/play-json)) as part of the data serialisation.

The official mongo driver uses Codecs to map data structures to Bson. hmrc-mongo, in order to minimise the transition from simple-reactivemongo to hmrc-mongo, PlayMongoRepository will register a codec for the provided domain format, which will serialise from BSON to [mongodb's extended JSON format](https://docs.mongodb.com/manual/reference/mongodb-extended-json/), and then use the provided domain formats to convert into the entity type.

Values provided in filters and updates are not covered by this codec, see [Filters/Updates](#filtersupdates).

### Entity Model

Json formats previously provided by simple-reactivemongo will need replacing.

:warning: Check that your data is being stored with the same representation as before. Subtle differences may occur, especially with Dates (See [below](#dates)). These changes will break existing data and can be difficult to catch since a test may just verify that data can be serialised and deserialised, but not check that the data is in the same format as previously. It may not be noticed until deployed, when new data could be written in a new format, and old data cannot be read. At this point, it can be tricky to rectify.

Some ideas for verifying the resulting data format are:
- Manual inspection in the database
- Tests could read the data out the database as Bson, and confirm the structure
- Provide a schema to PlayMongoRepository during tests (they would have a performance impact if applied to production code)

#### ObjectId:

`reactivemongo.bson.BSONObjectID` with need replacing with `org.bson.types.ObjectId` in your model. And use `uk.gov.hmrc.mongo.json.ReactiveMongoFormats` to provide a json format.

#### Dates:

simple-reactivemongo only provided support for joda dates. You can use `uk.gov.hmrc.mongo.play.json.formats.MongoJodaFormats` to provide the equivalent json format. However, we strongly recommend replacing joda dates with java time, and use `uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormat`s instead. Java time has official support in filters/updates - See [Filters/Updates](#Filters/Updates).

:warning: Be especially careful with preserving the date format, since if the json format for mongo is not in scope, it will silently use the date formats as provided by play-json, breaking existing data.

It's also worth pointing out that Dates in hmrc-mongo will be represented in json slightly differently to the previous `uk.gov.hmrc.mongo.json.ReactiveMongoFormats`, i.e. with [MongoDB Extended JSON (v2)](https://docs.mongodb.com/manual/reference/mongodb-extended-json/), however this has no impact on the data type at rest (BSON).

#### Numbers:

Numbers have special representation in mongoDB extended JSON, but for compatibility with play-json, the numbers will be converted by the registered Codec to JsNumber, as they were in simple-reactivemongo.

### Filters/Updates

The codec registered for the collection entity from the provided domain format only applies to the whole entity. When using leaf data values in filters/updates, a codec is looked up at runtime for the data values.

:warning: If a codec is missing, it will result in a runtime exception. Ensure that your test coverage is sufficient.

#### Data types with codecs provided by the official driver:

the following query expects there to be a codec available for ObjectId and for the numeric value 1.

```scala
collection.findOneAndUpdate(
  filter = Filters.equal("_id", ObjectId(1)),
  update = Updates.set("amount", 1)
)
```

This is fine, since the official driver provides codecs for these types.

Codecs exist for primatives, BSON, ObjectId and java time.

#### value objects, need extracting

i.e. converting to a type for which a codec does exist

```scala
case class MyId(value: ObjectId) extends AnyVal
case class MyAmount(value: Long) extends AnyVal

collection.findOneAndUpdate(
  filter = Filters.equal("_id", myId.value),
  update = Updates.set("amount", myAmount.value)
)
```

#### Data types with json formatters in scope:

Other types can be converted to Bson, and use the bson codec, as long as there is a Json formatter in scope, using `uk.gov.hmrc.mongo.play.json.Codecs.toBson`

```scala
collection.findOneAndUpdate(
  filter = Filters.equal("_id", Codecs.toBson(myId)),
  update = Updates.set("amount", Codecs.toBson(myAmount))
)
```

#### Dates

Dates are worth a special mention. Codecs are already provided for Java time, allowing their use directly in filters/updates. However, they do not exist for jodatime, you will have to use `Codecs.toBson` with the `uk.gov.hmrc.mongo.play.json.formats.MongoJodaFormats` in scope. We recommend migrating to use java time.

#### Nothing

If you come across:

> Can't find a codec for class scala.runtime.Nothing$.","exception":"org.bson.codecs.configuration.CodecConfigurationException

This arises when `Nothing` is inferred as the result type of a query, and no codec exists for this type. This can be fixed by explicitly stating the expected type.

e.g.

```scala
collection.distinct("name").toFuture()
```

becomes

```scala
collection.distinct[String]("name").toFuture()
```

## Updating Tests

### DefaultPlayMongoRepositorySupport

`uk.gov.hmrc.mongo.MongoSpecSupport` should be replaced with `uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport[T]`.

It expects the repository under test to override `repository`, and provides a `mongoComponent` to create the repository. This will create a database named after the test.

e.g.

```scala
class MyEntityRepositorySpec extends DefaultPlayMongoRepositorySupport[MyEntity] {
  override lazy val repository = new MyEntityRepository(mongoComponent)
  ...
}
```

`DefaultPlayMongoRepositorySupport` will ensure that the database is cleaned and setup (with indexes and schemas) before each test, and turn on `no table scan` to ensure all queries have an index defined. To refine this behaviour, you may use the composed traits directly.

Like MongoSpecSupport, a number of helper functions are provided
- find
- findAll
- insert
- createCollection
- dropCollection
- count

The underlying collection can be accessed via `repository.collection`.

### Mongo Schemas

[Mongo Schemas](https://docs.mongodb.com/realm/mongodb/document-schemas/) can be enabled in the test suite to check data is being written in the correct format. This can help highlight inconsistencies in object serialization (care must be taken with dates and time etc).

If you’re going to use a schema in your test suite we recommend writing it upfront (tooling exists to automate the generation of bson/json-schema from example objects), applying it manually to your local database to confirm it works with the service prior to upgrading it.

## Deployment

Before deploying your upgraded service you must check that the mongodb.uri uses
```scala
ssl=true
```
rather than
```scala
sslEnabled=true
```

The sslEnabled setting is a reactiveMongo specific setting and doesn’t work with the official driver.
If you don’t replace it in your environment config your service will likely fail with a somewhat cryptic error of:

> com.mongodb.MongoSocketReadException: Prematurely reached end of stream

At the time of writing there are still some 200 services using the old sslEnabled flag.

It would be sensible to re-run performance tests after upgrading keeping an eye on memory and cpu usage. Our benchmarking indicates that the official driver is as performant as reactive-mongo and has a smaller memory overhead but it’s worth validating this holds true for your own service.


## Lock

### LockKeeper

`uk.gov.hmrc.lock.LockKeeper#tryLock` replaced by `uk.gov.hmrc.mongo.lock.LockService#withLock`

```scala
class MongoLock(db: () => DB, lockId_ : String) extends LockKeeper {
  override val repo                 : LockRepository = LockMongoRepository(db)
  override val lockId               : String         = lockId_
  override val forceLockReleaseAfter: Duration       = Duration.standardMinutes(60)
}

@Singleton
class LockClient @Inject()(mongo: ReactiveMongoComponent) {
  private val db = mongo.mongoConnector.db

  val myLock = new MongoLock(db, "my-lock")

  // now use the lock
  myLock.tryLock { ... }
}
```

becomes

```scala
@Singleton
class LockClient @Inject()(mongoLockRepository: MongoLockRepository) {
  val myLock = LockService(mongoLockRepository, lockId = "my-lock", ttl = 1.hour)

  // now use the lock
  myLock.withLock { ... }
}
```

### ExclusiveTimePeriodLock

`uk.gov.hmrc.lock.ExclusiveTimePeriodLock#tryToAcquireOrRenewLock` replaced by `uk.gov.hmrc.mongo.lock.TimePeriodLockService#withRenewedLock`

```scala
class MongoLock(db: () => DB, lockId_ : String) extends ExclusiveTimePeriodLock {
  override val repo       : LockRepository = LockMongoRepository(db)
  override val lockId     : String         = lockId_
  override val holdLockFor: Duration       = Duration.standardMinutes(60)
}
@Singleton
class LockClient @Inject()(mongo: ReactiveMongoComponent) {
  private val db = mongo.mongoConnector.db

  val myLock = new MongoLock(db, "my-lock")

  // now use the lock
  myLock.tryToAcquireOrRenewLock { ... }
}
```

becomes

```scala
@Singleton
class LockClient @Inject()(mongoLockRepository: MongoLockRepository) {
  val myLock = TimePeriodLockService(mongoLockRepository, lockId = "my-lock", ttl = 1.hour)

  // now use the lock
  myLock.withRenewedLock { ... }
}
```


## Cache

### CacheRepository

`uk.gov.hmrc.cache.repository.CacheRepository#createOrUpdate` replaced by `uk.gov.hmrc.mongo.cache.MongoCacheRepository#put`

`uk.gov.hmrc.cache.model.Cache` replaced with `uk.gov.hmrc.mongo.cache.CacheItem`.

`uk.gov.hmrc.mongo.cache.SessionCacheRepository` and `uk.gov.hmrc.mongo.cache.EntityCache` are new and may mean you can avoid some boilerplate.