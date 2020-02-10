/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.play.json

import java.{time => jat}

import com.mongodb.MongoWriteException
import org.bson.types.ObjectId
import org.joda.{time => jot}
import org.mongodb.scala.bson.{BsonDocument, BsonString}
import org.mongodb.scala.Completed
import org.mongodb.scala.model.{Filters, Updates}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.json._
import play.api.libs.functional.syntax._
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}
import uk.gov.hmrc.mongo.play.json.formats.{MongoFormats, MongoJavatimeFormats, MongoJodaFormats}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import ExecutionContext.Implicits.global

class PlayMongoRepositorySpec
    extends AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with ScalaCheckDrivenPropertyChecks
    with BeforeAndAfterAll {

  import Codecs.toBson
  import PlayMongoRepositorySpec._

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds)

  val mongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")
  }

  val playMongoRepository = new PlayMongoRepository[MyObject](
    mongoComponent = mongoComponent,
    collectionName = "myobject",
    domainFormat   = myObjectFormat,
    indexes        = Seq.empty,
    optSchema      = Some(myObjectSchema)
  )

  import Implicits._
  import MongoFormats.Implicits._
  import MongoJodaFormats.Implicits._
  // Note without the following import, it will compile, but use plays Javatime formats.
  // Applying `myObjectSchema` will check that dates are being stored as dates
  import MongoJavatimeFormats.Implicits._

  "PlayMongoRepository.collection" should {

    "read and write object with fields" in {
      forAll(myObjectGen) { myObj =>
        prepareDatabase()

        val result = playMongoRepository.collection.insertOne(myObj).toFuture
        result.futureValue shouldBe Completed()

        val writtenObj = playMongoRepository.collection.find().toFuture
        writtenObj.futureValue shouldBe List(myObj)
      }
    }

    "filter by fields" in {
      forAll(myObjectGen) { myObj =>
        prepareDatabase()

        val result = playMongoRepository.collection.insertOne(myObj).toFuture
        result.futureValue shouldBe Completed()

        def checkFind[A: Writes](key: String, value: A): Assertion =
          playMongoRepository.collection
            .find(filter = Filters.equal(key, toBson(value)))
            .toFuture
            .futureValue shouldBe List(myObj)

        checkFind("_id", myObj.id)
        checkFind("string", myObj.string)
        checkFind("boolean", myObj.boolean)
        checkFind("int", myObj.int)
        checkFind("long", myObj.long)
        checkFind("double", myObj.double)
        checkFind("bigDecimal", myObj.bigDecimal)
        checkFind("jodaDateTime", myObj.jodaDateTime)
        checkFind("jodaLocalDate", myObj.jodaLocalDate)
        checkFind("jodaLocalDateTime", myObj.jodaLocalDateTime)
        checkFind("javaInstant", myObj.javaInstant)
        checkFind("javaLocalDate", myObj.javaLocalDate)
        checkFind("javaLocalDateTime", myObj.javaLocalDateTime)
        checkFind("sum", myObj.sum)
        checkFind("objectId", myObj.objectId)
      }
    }

    "update fields" in {
      forAll(myObjectGen) { originalObj =>
        forAll(myObjectGen suchThat (_ != originalObj)) { targetObj =>
          prepareDatabase()

          val result = playMongoRepository.collection.insertOne(originalObj).toFuture
          result.futureValue shouldBe Completed()

          def checkUpdate[A: Writes](key: String, value: A): Assertion =
            playMongoRepository.collection
              .updateOne(filter = BsonDocument(), update = Updates.set(key, toBson(value)))
              .toFuture
              .futureValue
              .wasAcknowledged shouldBe true

          // Note, not checking update of `_id` since immutable
          checkUpdate("string", targetObj.string)
          checkUpdate("boolean", targetObj.boolean)
          checkUpdate("int", targetObj.int)
          checkUpdate("long", targetObj.long)
          checkUpdate("double", targetObj.double)
          checkUpdate("bigDecimal", targetObj.bigDecimal)
          checkUpdate("jodaDateTime", targetObj.jodaDateTime)
          checkUpdate("jodaLocalDate", targetObj.jodaLocalDate)
          checkUpdate("jodaLocalDateTime", targetObj.jodaLocalDateTime)
          checkUpdate("javaInstant", targetObj.javaInstant)
          checkUpdate("javaLocalDate", targetObj.javaLocalDate)
          checkUpdate("javaLocalDateTime", targetObj.javaLocalDateTime)
          checkUpdate("sum", targetObj.sum)
          checkUpdate("objectId", targetObj.objectId)

          val writtenObj = playMongoRepository.collection.find().toFuture
          writtenObj.futureValue shouldBe List(targetObj.copy(id = originalObj.id))
        }
      }
    }

    "validate against jsonSchema" in {
      forAll(myObjectGen) { originalObj =>
        forAll(myObjectGen suchThat (_ != originalObj)) { targetObj =>
          prepareDatabase()

          val result = playMongoRepository.collection.insertOne(originalObj).toFuture
          result.futureValue shouldBe Completed()

          def checkUpdateFails[A](key: String, value: A)(implicit ev: Writes[A]): Assertion =
            whenReady {
              playMongoRepository.collection
                .updateOne(filter = BsonDocument(), update = Updates.set(key, toBson(value)))
                .toFuture
                .failed
            } { e =>
              e            shouldBe a[MongoWriteException]
              e.getMessage should include("Document failed validation")
           }

          // updates should fail with the wrong Writers
          checkUpdateFails("javaInstant", targetObj.javaInstant)(Writes.DefaultInstantWrites)
          checkUpdateFails("javaLocalDate", targetObj.javaLocalDate)(Writes.DefaultLocalDateWrites)
          checkUpdateFails("javaLocalDateTime", targetObj.javaLocalDateTime)(Writes.DefaultLocalDateTimeWrites)
        }
      }
    }
  }

  def prepareDatabase(): Unit =
    (for {
      exists <- MongoUtils.existsCollection(mongoComponent, playMongoRepository.collection)
      _      <- if (exists) playMongoRepository.collection.deleteMany(BsonDocument()).toFuture
                else Future.successful(())
     } yield ()
    ).futureValue

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    // ensure jsonSchema is defined as expected
    (for {
       collections <- mongoComponent.database.listCollections.toFuture
       collection  =  collections.find(_.get("name") == Some(BsonString(playMongoRepository.collection.namespace.getCollectionName)))
       _           =  collection.isDefined shouldBe true
       options     =  collection.flatMap(_.get[BsonDocument]("options"))
       _           =  options.exists(_.containsKey("validator")) shouldBe true
       validator   =  options.get.getDocument("validator")
       _           =  Option(validator.get(f"$$jsonSchema")) shouldBe playMongoRepository.optSchema
     } yield ()
    ).futureValue
  }
}

object PlayMongoRepositorySpec {

  case class StringWrapper(unwrap: String) extends AnyVal
  case class BooleanWrapper(unwrap: Boolean) extends AnyVal
  case class IntWrapper(unwrap: Int) extends AnyVal
  case class LongWrapper(unwrap: Long) extends AnyVal
  case class DoubleWrapper(unwrap: Double) extends AnyVal
  case class BigDecimalWrapper(unwrap: BigDecimal) extends AnyVal

  sealed trait Sum
  object Sum {
    case object Sum1 extends Sum
    case object Sum2 extends Sum
  }

  case class MyObject(
    id: ObjectId,
    // Wrappers
    string: StringWrapper,
    boolean: BooleanWrapper,
    int: IntWrapper,
    long: LongWrapper,
    double: DoubleWrapper,
    bigDecimal: BigDecimalWrapper,
    // Sum type (WIP)
    sum: Sum,
    // Joda time
    jodaDateTime: jot.DateTime,
    jodaLocalDate: jot.LocalDate,
    jodaLocalDateTime: jot.LocalDateTime,
    // Java time
    javaInstant: jat.Instant,
    javaLocalDate: jat.LocalDate,
    javaLocalDateTime: jat.LocalDateTime,
    objectId: ObjectId
  )

  val stringWrapperFormat: Format[StringWrapper] =
    implicitly[Format[String]].inmap(StringWrapper.apply, unlift(StringWrapper.unapply))

  val booleanWrapperFormat: Format[BooleanWrapper] =
    implicitly[Format[Boolean]].inmap(BooleanWrapper.apply, unlift(BooleanWrapper.unapply))

  val intWrapperFormat: Format[IntWrapper] =
    implicitly[Format[Int]].inmap(IntWrapper.apply, unlift(IntWrapper.unapply))

  val longWrapperFormat: Format[LongWrapper] =
    implicitly[Format[Long]].inmap(LongWrapper.apply, unlift(LongWrapper.unapply))

  val doubleWrapperFormat: Format[DoubleWrapper] =
    implicitly[Format[Double]].inmap(DoubleWrapper.apply, unlift(DoubleWrapper.unapply))

  val bigDecimalWrapperFormat: Format[BigDecimalWrapper] =
    implicitly[Format[BigDecimal]].inmap(BigDecimalWrapper.apply, unlift(BigDecimalWrapper.unapply))

  val sumFormat: Format[Sum] = new Format[Sum] {
    override def reads(js: JsValue) =
      js.validate[String]
        .flatMap {
          case "Sum1" => JsSuccess(Sum.Sum1)
          case "Sum2" => JsSuccess(Sum.Sum2)
          case other  => JsError(__, s"Unexpected Sum value $other")
        }

    override def writes(sum: Sum) =
      sum match {
        case Sum.Sum1 => JsString("Sum1")
        case Sum.Sum2 => JsString("Sum2")
      }
  }

  object Implicits {
    implicit val swf  = stringWrapperFormat
    implicit val bwf  = booleanWrapperFormat
    implicit val iwf  = intWrapperFormat
    implicit val lwf  = longWrapperFormat
    implicit val dwf  = doubleWrapperFormat
    implicit val bdwf = bigDecimalWrapperFormat
    implicit val sf   = sumFormat
  }

  val myObjectFormat = {
    import Implicits._
    import MongoFormats.Implicits._
    import MongoJodaFormats.Implicits._
    // Note without the following import, it will compile, but use plays Javatime formats.
    // Applying `myObjectSchema` will check that dates are being stored as dates
    import MongoJavatimeFormats.Implicits._
    ( (__ \ "_id").format[ObjectId]
    ~ (__ \ "string").format[StringWrapper]
    ~ (__ \ "boolean").format[BooleanWrapper]
    ~ (__ \ "int").format[IntWrapper]
    ~ (__ \ "long").format[LongWrapper]
    ~ (__ \ "double").format[DoubleWrapper]
    ~ (__ \ "bigDecimal").format[BigDecimalWrapper]
    ~ (__ \ "sum").format[Sum]
    ~ (__ \ "jodaDateTime").format[jot.DateTime]
    ~ (__ \ "jodaLocalDate").format[jot.LocalDate]
    ~ (__ \ "jodaLocalDateTime").format[jot.LocalDateTime]
    ~ (__ \ "javaInstant").format[jat.Instant]
    ~ (__ \ "javaLocalDate").format[jat.LocalDate]
    ~ (__ \ "javaLocalDateTime").format[jat.LocalDateTime]
    ~ (__ \ "objectId").format[ObjectId])(MyObject.apply, unlift(MyObject.unapply))
  }

  val myObjectSchema =
    // Note, we can't assert specific bsonTypes for numbers (long, double, decimal), since we go via Json, and loose specific number types.
    BsonDocument(
      """
      { bsonType: "object"
      , properties:
        { _id              : { bsonType: "objectId" }
        , string           : { bsonType: "string"   }
        , boolean          : { bsonType: "bool"     }
        , int              : { bsonType: "int"      }
        , long             : { bsonType: "number"   }
        , double           : { bsonType: "number"   }
        , bigDecimal       : { bsonType: "number"   }
        , sum              : { enum: [ "Sum1", "Sum2" ] }
        , jodaDateTime     : { bsonType: "date"     }
        , jodaLocalDate    : { bsonType: "date"     }
        , jodaLocalDateTime: { bsonType: "date"     }
        , javaInstant      : { bsonType: "date"     }
        , javaLocalDate    : { bsonType: "date"     }
        , javaLocalDateTime: { bsonType: "date"     }
        }
      }
      """
    )

  def myObjectGen =
    for {
      s <- Arbitrary.arbitrary[String]
      b <- Arbitrary.arbitrary[Boolean]
      i <- Arbitrary.arbitrary[Int]
      l <- Arbitrary.arbitrary[Long]
      d <- Arbitrary.arbitrary[Double]
      bd <- Arbitrary
             .arbitrary[BigDecimal]
             // Only BigDecimal within Decimal128 range is supported.
             .suchThat(bd => scala.util.Try(new org.bson.types.Decimal128(bd.bigDecimal)).isSuccess)
      epochMillis <- Gen.choose(0L, System.currentTimeMillis * 2) // Keep Dates within range (ArithmeticException for any Long.MAX_VALUE)
    } yield MyObject(
      id                = new org.bson.types.ObjectId(new java.util.Date(epochMillis)),
      string            = StringWrapper(s),
      boolean           = BooleanWrapper(b),
      int               = IntWrapper(i),
      long              = LongWrapper(l),
      double            = DoubleWrapper(d),
      bigDecimal        = BigDecimalWrapper(bd),
      sum               = Sum.Sum1,
      jodaDateTime      = new jot.DateTime(epochMillis, jot.DateTimeZone.UTC), // Mongo db assumes UTC (timezone is not stored in db - when read back, it will represent the same instant, but with timezone UTC)
      jodaLocalDate     = new jot.LocalDate(epochMillis),
      jodaLocalDateTime = new jot.LocalDateTime(epochMillis),
      javaInstant       = jat.Instant.ofEpochMilli(epochMillis),
      javaLocalDate     = jat.LocalDate.ofEpochDay(epochMillis / (24 * 60 * 60 * 1000)),
      javaLocalDateTime = jat.LocalDateTime.ofInstant(jat.Instant.ofEpochMilli(epochMillis), jat.ZoneId.of("Z")),
      objectId          = new org.bson.types.ObjectId(new java.util.Date(epochMillis))
    )
}
