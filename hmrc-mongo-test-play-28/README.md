
# hmc-mongo-test

Test is a library to help write tests for hmrc-mongo.

## Example construction

Mixing in the trait `DefaultPlayMongoRepositorySupport` to your tests should give you appropriate test setup and tear down.

It combines the traits
- `PlayMongoRepositorySupport[A]`
As long as you provide a definition of `repository` which can be created with the proided `mongoComponent`, you will get a collection to test against.
See `MongoSupport.databaseName` and `MongoSupport.mongoUri` for the default settings, which expect a local mongo instance, and will create a database specific for the test.

- `CleanMongoCollectionSupport`
This ensures that the database is dropped before each test

- `IndexedMongoQueriesSupport`
This sets the mongo server setting `notablescan` to `1`, which ensures that any scan without an index will fail.
Note, this is a global admin setting, which can cause issues running tests in parallel with different settings. If you require a mix of tests with different settings, consider turning off parallel test executions in your build.sbt.


``` scala
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

## Installing

Test is now part of and versioned with the `hmrc-mongo` library, which provides helper utilities for building systems with mongo.

Include the following dependency in your SBT build

``` scala
resolvers += "HMRC-open-artefacts-maven2" at "https://open.artefacts.tax.service.gov.uk/maven2"

libraryDependencies += "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-xx" % "[INSERT-VERSION]"
```

Where `play-xx` is your version of Play (e.g. `play-28`)

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
