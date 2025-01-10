
# hmc-mongo-test

Test is a library to help write tests for hmrc-mongo.

## Example construction

Mixing in the trait `DefaultPlayMongoRepositorySupport` to your tests should give you appropriate test setup and tear down.

It combines the traits
- `PlayMongoRepositorySupport[A]`
As long as you provide a definition of `repository` which can be created with the provided `mongoComponent`, you will get a collection to test against.
See `MongoSupport.databaseName` and `MongoSupport.mongoUri` for the default settings, which expect a local mongo instance, and will create a database specific for the test.

- `CleanMongoCollectionSupport`
This ensures that the database is dropped before each test

- `IndexedMongoQueriesSupport`
This sets the mongo server setting `notablescan` to `1`, which ensures that any scan without an index will fail.
Note, this is a global admin setting, which can cause issues running tests in parallel with different settings. If you require a mix of tests with different settings, consider turning off parallel test executions in your build.sbt.


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

### MongoUri

As mentioned above, the tests by default will be provided a `mongoComponent` which has been initialised with a `mongoUri` pointing to it's own database.

If you are running against an application set up with `Guice`, then you may have another instance of the repository, which uses the `mongoUri` as defined in the app's configuration.

To ensure they are using the same, you can either override the test's `mongoUri` to ensure it uses the same database, or alternatively configure Guice to use the same mongoUri as defined by the test.

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

or

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

Even if you haven't defined a schema in your Repository object, you may want to provide a schema to your tests to ensure that the data is stored in the format you expect - especially useful for catching Dates stored as String.

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


## Installing

Test is now part of and versioned with the `hmrc-mongo` library, which provides helper utilities for building systems with mongo.

Include the following dependency in your SBT build

```scala
resolvers += "HMRC-open-artefacts-maven2" at "https://open.artefacts.tax.service.gov.uk/maven2"

libraryDependencies += "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-xx" % "[INSERT-VERSION]"
```

Where `play-xx` is your version of Play (e.g. `play-28`)

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
