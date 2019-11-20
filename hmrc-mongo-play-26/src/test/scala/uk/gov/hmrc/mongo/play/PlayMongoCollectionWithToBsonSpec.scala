/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.mongo.play

import org.joda.{time => jot}
import java.{time => jat}
import org.bson.codecs.configuration.CodecRegistries
import org.bson.types.ObjectId
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalatest.WordSpecLike
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.compatible.Assertion
import org.scalatest.Matchers._
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.mongodb.scala.Completed
import org.mongodb.scala.model.{Filters, Updates}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.component.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, MongoFormats, MongoJavatimeFormats, MongoJodaFormats}
import uk.gov.hmrc.mongo.play.json.Codecs.toBson

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

import ExecutionContext.Implicits.global

/** An alternative to [[PlayMongoCollectionSpec]] which uses toBson rather than registering extra codecs
  * with optRegistry.
  */
class PlayMongoCollectionWithToBsonSpec extends WordSpecLike with ScalaFutures with ScalaCheckDrivenPropertyChecks {

  import PlayMongoCollectionSpec._

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds)

  val mongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")
  }

  val playMongoCollection = new PlayMongoCollection[MyObject](
    mongoComponent = mongoComponent,
    collectionName = "myobject",
    domainFormat   = MongoFormats.mongoEntity(myObjectFormat),
    indexes        = Seq.empty
  )

  import Implicits._
  import MongoFormats.Implicits._
  import MongoJodaFormats.Implicits._
  // Note without the following import, it will compile, but use plays Javatime formats, and fail in runtime
  import MongoJavatimeFormats.Implicits._

  "PlayMongoCollection.collection" should {

    "read and write object with fields" in {
      forAll(myObjectGen) { myObj =>
        dropDatabase()

        val result = playMongoCollection.collection.insertOne(myObj).toFuture
        result.futureValue shouldBe Completed()

        val writtenObj = playMongoCollection.collection.find().toFuture
        writtenObj.futureValue shouldBe List(myObj)
      }
    }

    "filter by fields" in {
      forAll(myObjectGen) { myObj =>
        dropDatabase()

        val result = playMongoCollection.collection.insertOne(myObj).toFuture
        result.futureValue shouldBe Completed()

        def checkFind[A : Writes](key: String, value: A): Assertion =
          playMongoCollection.collection
            .find(filter = Filters.equal(key, toBson(value)))
            .toFuture
            .futureValue shouldBe List(myObj)

        checkFind("_id"              , myObj.id               ) // Note, even with mongoEntity, we have to use internal key
        checkFind("string"           , myObj.string           )
        checkFind("boolean"          , myObj.boolean          )
        checkFind("int"              , myObj.int              )
        checkFind("long"             , myObj.long             )
        checkFind("double"           , myObj.double           )
        checkFind("bigDecimal"       , myObj.bigDecimal       )
        checkFind("jodaDateTime"     , myObj.jodaDateTime     )
        checkFind("jodaLocalDate"    , myObj.jodaLocalDate    )
        checkFind("jodaLocalDateTime", myObj.jodaLocalDateTime)
        checkFind("javaInstant"      , myObj.javaInstant      )
        checkFind("javaLocalDate"    , myObj.javaLocalDate    )
        checkFind("javaLocalDateTime", myObj.javaLocalDateTime)
        checkFind("sum"              , myObj.sum              )
        checkFind("objectId"         , myObj.objectId         )
      }
    }

    "update fields" in {
      forAll(myObjectGen) { originalObj =>
        forAll(myObjectGen suchThat (_ != originalObj)) { targetObj =>
          dropDatabase()

          val result = playMongoCollection.collection.insertOne(originalObj).toFuture
          result.futureValue shouldBe Completed()

          def checkUpdate[A : Writes](key: String, value: A): Assertion =
            playMongoCollection.collection
              .updateOne( filter = new com.mongodb.BasicDBObject()
                        , update = Updates.set(key, toBson(value))
                        )
              .toFuture
              .futureValue
              .wasAcknowledged shouldBe true

          // Note, not checking update of `_id` since immutable
          checkUpdate("string"           , targetObj.string           )
          checkUpdate("boolean"          , targetObj.boolean          )
          checkUpdate("int"              , targetObj.int              )
          checkUpdate("long"             , targetObj.long             )
          checkUpdate("double"           , targetObj.double           )
          checkUpdate("bigDecimal"       , targetObj.bigDecimal       )
          checkUpdate("jodaDateTime"     , targetObj.jodaDateTime     )
          checkUpdate("jodaLocalDate"    , targetObj.jodaLocalDate    )
          checkUpdate("jodaLocalDateTime", targetObj.jodaLocalDateTime)
          checkUpdate("javaInstant"      , targetObj.javaInstant      )
          checkUpdate("javaLocalDate"    , targetObj.javaLocalDate    )
          checkUpdate("javaLocalDateTime", targetObj.javaLocalDateTime)
          checkUpdate("sum"              , targetObj.sum              )
          checkUpdate("objectId"         , targetObj.objectId         )

          val writtenObj = playMongoCollection.collection.find().toFuture
          writtenObj.futureValue shouldBe List(targetObj.copy(id = originalObj.id))
        }
      }
    }
  }

  def dropDatabase() =
    mongoComponent.database
      .drop()
      .toFuture
      .futureValue
}
