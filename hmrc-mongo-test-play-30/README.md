
# hmc-mongo-test

Test is a library to help write tests for hmrc-mongo.


## Installing

Include the following dependency in your SBT build

```scala
resolvers += "HMRC-open-artefacts-maven2" at "https://open.artefacts.tax.service.gov.uk/maven2"

libraryDependencies += "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-xx" % "[INSERT-VERSION]"
```

Where `play-xx` is your version of Play (e.g. `play-30`)

## Example construction

Mixing in the trait `DefaultPlayMongoRepositorySupport` to your tests should give you appropriate test setup and tear down.

As long as mongo is running, it will ensure that a temporary database is used for your tests. It will also check that queries use indices are repositories have a TTL index.

```scala
class UserRepositorySpec
  extends AnyWordSpec
     with DefaultPlayMongoRepositorySupport[User]
     with Matchers
     with ScalaFutures {

  override lazy val repository = new UserRepository(mongoComponent)

  "user.findAll" should "find none initially" in {
    repository.findAll().futureValue shouldBe Seq.empty
  }
}
```

It combines the traits
- `PlayMongoRepositorySupport[A]`
As long as you provide a definition of `repository` which can be created with the provided `mongoComponent`, you will get a collection to test against.
See `MongoSupport.databaseName` and `MongoSupport.mongoUri` for the default settings, which expect a local mongo instance, and will create a database specific for the test.

- `CleanMongoCollectionSupport`
This ensures that the database is dropped before each test

- `IndexedMongoQueriesSupport`
This sets the mongo server setting `notablescan` to `1`, which ensures that any scan without an index will fail.
Note, this is a global admin setting, which can cause issues running tests in parallel with different settings. If you require a mix of tests with different settings, consider turning off parallel test executions in your build.sbt.

  A query without an index can have a detrimental effect on Mongo and affect other services using the same mongo. If however, you really need to use a query without an index, and have confirmed that it will not affect other services, then you may disable this check by overriding `checkIndexedQueries`. It applies to the whole test suite, so you should break the test suite up so that only the queries intended to be run with indices are affected.

- `TtlIndexedMongoSupport`
This checks that the repository has a TTL index defined, and correctly uses the `Date` type.
The repository may opt out of this by defining `requiresTtlIndex`. See [README](../README.md#ttl-indexes) for details.


### Helpers

`DefaultPlayMongoRepositorySupport` provides a number of helpers for interogating or updating the collection, such as

- find
- findAll
- insert
- createCollection
- dropCollection
- count

See [PlayMongoRepositorySupport](src/main/scala/uk/gov/hmrc/mongo/test/PlayMongoRepositorySupport.scala) for the full list.

You can also access the underlying collection directly via `repository.collection`.

### MongoUri

As mentioned above, the tests by default will be provided a `mongoComponent` which has been initialised with a `mongoUri` pointing to it's own database.

If you are running against an application set up with `Guice`, then you should ensure that the `mongoUri` used by Guice and the test suite is the same. It is recommended to configure Guice to use the `mongoUri` provided by the test since this will be pointing to a clean temporary database.

e.g.

```scala
class UserRepositorySpec
  extends AnyWordSpec
     with DefaultPlayMongoRepositorySupport[User]
     with GuiceOneAppPerSuite
     with Matchers
     with ScalaFutures {

  // Using the test's mongoUri ensures the tests will not conflict, or affect the db as defined in application.conf.
  override lazy val app: Application = new GuiceApplicationBuilder()
    .configure("mongodb.uri" -> mongoUri)
    .build()

  // Now it doesn't matter if the repository is looked up from `app` or instantiated with `mongoComponent`
  // they will point to the same db.
  override lazy val repository = new UserRepository(mongoComponent)
  override lazy val repository = app.injector.instanceOf[UserRepository]
}
```

Alternatively, the following example reuses the mongoComponent provided by Guice. This is not recommended since this may be pointing at a non-isolated database with data.

```scala
class UserRepositorySpec
  extends AnyWordSpec
     with DefaultPlayMongoRepositorySupport[User]
     with GuiceOneAppPerSuite
     with Matchers
     with ScalaFutures {

  override lazy val app: Application = ...

  // either reuse the same instances:
  override lazy val mongoComponent = app.injector.instanceOf[MongoComponent]
  // or just point to the same uri:
  override lazy val mongoUri = "mongodb://localhost:27017/..."

  // Now it doesn't matter if the repository is looked up from `app` or instantiated with `mongoComponent`
  // they will point to the same db.
  override lazy val repository = new UserRepository(mongoComponent)
  override lazy val repository = app.injector.instanceOf[UserRepository]
}
```

If you are running against an external system, you will need to update `mongoUri` accordingly. Although it is probably more appropriate to interrogate the data by asking the external system rather than accessing the underlying db (e.g. by adding a testOnly endpoint).


### Schemas

Even if you haven't defined a [schema](https://docs.mongodb.com/realm/mongodb/document-schemas/) in your Repository object, you may want to provide a schema to your tests to ensure that the data is stored in the format you expect. This can be especially useful for catching Dates stored as String.

```scala
class UserRepositorySpec
  extends AnyWordSpec
     with DefaultPlayMongoRepositorySupport[User]
     with Matchers
     with ScalaFutures {

  override protected lazy val optSchema: Option[BsonDocument] =
    Some(BsonDocument("""
      { bsonType: "object"
      , required: [ "_id", "created", "name" ]
      , properties:
        { _id     : { bsonType: "objectId" }
        , created : { bsonType: "date" }
        , name    : { bsonType: "string" }
        }
      }
    """))
}
```

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
