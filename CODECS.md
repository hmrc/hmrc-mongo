
# BSON, Domain Formats and Codecs

**hmrc-mongo** relies on model to json mapping (with [play-json](https://github.com/playframework/play-json)) for the data serialisation.

The official mongo driver uses Codecs to map data structures to Bson. `PlayMongoRepository` will register a codec for the provided domain format, which will serialise from BSON to [mongodb's extended JSON format](https://docs.mongodb.com/manual/reference/mongodb-extended-json/), and then use the provided domain formats to convert into the entity type.

```scala
class MyDomainRepository
  extends PlayMongoRepository[MyDomain](
    domainFormat = myDomainPlayFormat // play.api.libs.json.Format[MyDomain]
  )
  // A codec is registered for the provided domain format
  // which will allow us to read/write this type

  def write(myDomain: MyDomain): Future[Unit] =
    collection.insertOne(myDomain).toFuture()
```

## Filters/Updates

To use data in filters/updates, a codec must also be present. If missing you will see a runtime exception like

```scala
org.bson.codecs.configuration.CodecConfigurationException("Can't find a codec for class ...")
```

:warning: If a codec is missing, it will result in a runtime exception. Ensure that your tests exercise all your functions sufficiently.

A lot of types already have implicit Codecs, such as `String` and primitive types like `Int` and `Boolean`. So you can just use them. But you can provide any extra ones to the `extraCodecs` parameter:

```scala
class MyDomainRepository
  extends PlayMongoRepository[MyDomain](
    domainFormat = myDomainPlayFormat,
    extraCodecs  = Seq(Codecs.playFormatCodec(
                     subFormat // play.api.libs.json.Format[SubDomain]
                   ))
  )
  // The following require a codec for SubFormat, registered
  // with extraCodecs

  def write(myDomain: MyDomain): Unit =
    collection
      .updateMany(Updates.set("subdomain", subDomain))
      .toFuture()

  def read(subDomain: SubDomain): Future[Seq[MyDomain]] =
    collection
      .find(Filters.equal("subdomain", subDomain))
      .toFuture()
```

Here, `Codecs.playFormatCodec` converts a `Format[SubDomain]` into a `Codec[SubDomain]` which is then registered for use against the `collection`.

## Alternative read formats

The codecs are not limited to subfields. You may want to just read the data out in another format - e.g. by mapping an alternative model to the same collection data, or by applying a projection/aggregation

```scala
class MyDomainRepository
  extends PlayMongoRepository[MyDomain](
    domainFormat = myDomainPlayFormat,
    extraCodecs  = Seq(Codecs.playFormatCodec(
                     myDomain2PlayFormat // play.api.libs.json.Format[MyDomain2]
                   ))
  )

  def read1(): Future[Seq[MyDomain]] =
    collection.find() // this is `MyDomain` by default, as indicated to PlayMongoRepository constructor.

  def read2(): Future[Seq[MyDomain2]] =
    collection.find[MyDomain2]() // provide the return type to the function for a different one
```

## Supported data types

### ObjectId

  There is an implicit codec already registered for `org.bson.types.ObjectId`, so it can be used in filters/updates.

  If your model uses ObjectId, you can use the following `import uk.gov.hmrc.mongo.play.json.formats.MongoFormats` to provide a json format.


### Dates

  It is recommend to use java time, joda time is no longer supported.

  There is an implicit codec already registered for java time, so it can be used in filters/updates.

  To use in your model, you can use the following `uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormat`.

  :warning: Play implicitly provides a Format for java time (e.g. `Format[Instant]`) which stores the date as a String. This is appropriate for json apis, but **not** for storing in Mongo - sorts will not work as expected, and it will break TTL indices. Take extra care to ensure that dates are stored as expected, and consider using schemas, even if just for the tests.


### Numbers

Numbers have special representation in mongoDB extended JSON, but for compatibility with play-json, the numbers will be converted by the registered Codec to JsNumber, as they were in simple-reactivemongo.

### Binary data

Play framework already provides formats for encoding `Array[Byte]`. However, these formats are provided via the generic format for arrays, and therefore encode byte arrays as arrays of numbers.

This is not what most services require when encoding binary data to store in Mongo, so **hmrc-mongo** provides `uk.gov.hmrc.mongo.play.json.formats.MongoBinaryFormats` for encoding `Array[Byte]`, `ByteBuffer` and `akka.util.ByteString` to Mongo's binary representation.


### ADTs

If you have a model defined as

```scala
sealed trait A
case class B() extends A
case class C() extends A

val aFormat: Format[A] = ???
```

or with Scala 3

```scala
enum A:
  case B
  case C

val aFormat: Format[A] = ???
```

You will see that registering a codec for `Format[A]` will still lead to `Can't find a codec for class B` when trying to store instances of `B` or `C`.

This is because the mongo driver looks up the codec by reflection (`b.getClass`) which returns `B` rather than `A`. You will need to register codec for all the data types.

This can be done automatically by using `Codecs.playFormatSumCodecs`, e.g.

```scala
PlayMongoRepository(
  domainFormat = aFormat,
  extraCodecs  = Codecs.playFormatSumCodecs(aFormat)
)
```

Alternatively, you can use the `Codecs.playFormatCodecsBuilder` to state which types to bind the codec to.

e.g.
```scala
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

PlayMongoRepository(
  domainFormat = aFormat,
  extraCodecs  = Codecs.playFormatCodecsBuilder(aFormat).forType[B].forType[C].build
)
```


### Converting to types supported implicitly

An alternative to registering subtype codecs is to just convert the subtype to another type which already has a Codec.

For example, with the following model

```scala
case class MyId(value: ObjectId) extends AnyVal
case class MyAmount(value: Long) extends AnyVal
```

We can just extract the values, since implicit codecs already exist for `ObjectId` and `Long`

```scala
collection.findOneAndUpdate(
  filter = Filters.equal("_id", myId.value),
  update = Updates.set("amount", myAmount.value)
)
```

Or with a more complicated mode, we can convert to `Bson`, which is implicitly supported. Here if we have a Json formatter in scope, we can use `uk.gov.hmrc.mongo.play.json.Codecs.toBson` to convert to Bson.

```scala
collection.findOneAndUpdate(
  filter = Filters.equal("subtype1", Codecs.toBson(subtype1)), // assuming we have a `Format[Subtype1]` in scope
  update = Updates.set("subtype2", Codecs.toBson(subtype2)) // assuming we have a `Format[Subtype1]` in scope
)
```

### Nothing

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

### UUIDs

MongoDB is able to serialize UUIDs as binary values for greater storage efficiency and index performance.

However, it's important to be aware that there are two different formats in which the Mongo Java driver encodes UUIDs as BSON.

The first is the legacy binary subtype `0x03` UUID encoding. Values of this type will appear as `LUUID`, `Legacy UUID` or `BinData(3, "...")` in Mongo database tools like Robo 3T and Mongo Compass.

The second is the new binary subtype `0x04` UUID encoding. Values of this type will appear as `UUID` or `BinData(4, "...")` in Mongo database tools like Robo 3T and Mongo Compass.

There is a new binary subtype because in the past, different Mongo drivers used different binary encodings for UUIDs of subtype `0x03`, which caused problems in multi-language environments. As a result a new standardised binary subtype `0x04` for UUIDs was created, which uses a common encoding in all Mongo drivers.

This means that if your service currently uses binary UUIDs and you are migrating to **hmrc-mongo**, then you must be aware of which encoding your service currently uses.

If you do not configure the UUID encoding to match your existing data, queries which filter by UUID will fail to find any data that was written prior to migrating to **hmrc-mongo**. You may also experience runtime exceptions when reading legacy UUID data from Mongo. If you are forced to back out your migration to **hmrc-mongo**, any data that was written after the deployment of the new code will not be able to be found when filtering by UUID if the changes are backed out.

As a result of these concerns, it might be more practical to migrate any data stored in the old UUID encoding using an aggregation pipeline before proceeding with your migration to **hmrc-mongo**.

Once you know which representation your service uses, you can provide a `new UuidCodec(UuidRepresentation.STANDARD)` or `new UuidCodec(UuidRepresentation.JAVA_LEGACY)` as part of the `extraCodecs` for your repository.

If your service uses the legacy UUID encoding, when declaring Play JSON `Format`s for your types, you must either import `uk.gov.hmrc.mongo.play.json.formats.MongoLegacyUuidFormats.Implicits._` or extend the `MongoLegacyUuidFormats.Implicits` trait.

If your service uses the new UUID encoding, when declaring Play JSON `Format`s for your types, you must either import `uk.gov.hmrc.mongo.play.json.formats.MongoUuidFormats.Implicits._` or extend the `MongoUuidFormats.Implicits` trait.

If your service is a new service with no existing production data, or if the service has never previously used UUID values in its data models, you can safely use `UuidRepresentation.STANDARD` and `MongoUuidFormats.Implicits`.

It's possible that your service writes UUIDs to the database as `String` values, in which case you do not need to use the `MongoUuidFormats` provided by this library at all. The default Play Framework UUID `Format`s already encode UUIDs correctly for your service.
