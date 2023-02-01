/*
 * Copyright 2023 HM Revenue & Customs
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

import akka.util.ByteString
import com.mongodb.MongoWriteException
import org.bson.UuidRepresentation
import org.bson.codecs.UuidCodec
import org.bson.types.ObjectId
import org.joda.{time => jot}
import org.mongodb.scala.{Document, ReadPreference}
import org.mongodb.scala.bson.{BsonDocument, BsonString}
import org.mongodb.scala.model.{Filters, Updates}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}
import uk.gov.hmrc.mongo.play.json.formats.{MongoBinaryFormats, MongoFormats, MongoJavatimeFormats, MongoJodaFormats, MongoUuidFormats}

import java.util.UUID
import java.{time => jat}
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

  import org.scalacheck.Shrink.shrinkAny // disable shrinking here - will just generate invalid inputs

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
    optSchema      = Some(myObjectSchema),
    extraCodecs    = Seq(new UuidCodec(UuidRepresentation.STANDARD))
  )

  import Implicits._
  import MongoFormats.Implicits._
  import MongoBinaryFormats.Implicits._
  import MongoJodaFormats.Implicits._
  // Note without the following import, it will compile, but use plays Javatime formats.
  // Applying `myObjectSchema` will check that dates are being stored as dates
  import MongoJavatimeFormats.Implicits._
  // Note without the following import, it will compile, but use play's UUID formats.
  import MongoUuidFormats.Implicits._

  "PlayMongoRepository.collection" should {
    "read and write object with fields" in {
      forAll(myObjectGen) { myObj =>
        prepareDatabase()

        val result = playMongoRepository.collection.insertOne(myObj).toFuture()
        result.futureValue.wasAcknowledged shouldBe true

        val writtenObj = playMongoRepository.collection.find().toFuture()
        writtenObj.futureValue shouldBe List(myObj)
      }
    }

    "filter by fields" in {
      forAll(myObjectGen) { myObj =>
        prepareDatabase()

        val result = playMongoRepository.collection.insertOne(myObj).toFuture()
        result.futureValue.wasAcknowledged shouldBe true

        def checkFind[A: Writes](key: String, value: A): Assertion =
          playMongoRepository.collection
            .find(filter = Filters.equal(key, toBson(value)))
            .toFuture()
            .futureValue shouldBe List(myObj)

        checkFind("_id"              , myObj.id)
        checkFind("string"           , myObj.string)
        checkFind("boolean"          , myObj.boolean)
        checkFind("int"              , myObj.int)
        checkFind("long"             , myObj.long)
        checkFind("double"           , myObj.double)
        checkFind("bigDecimal"       , myObj.bigDecimal)
        checkFind("jodaDateTime"     , myObj.jodaDateTime)
        checkFind("jodaLocalDate"    , myObj.jodaLocalDate)
        checkFind("jodaLocalDateTime", myObj.jodaLocalDateTime)
        checkFind("javaInstant"      , myObj.javaInstant)
        checkFind("javaLocalDate"    , myObj.javaLocalDate)
        checkFind("sum"              , myObj.sum)
        checkFind("objectId"         , myObj.objectId)
        checkFind("uuid"             , myObj.uuid)
        checkFind("uuidWrapper"      , myObj.uuidWrapper)
        checkFind("binary"           , myObj.binary)
        checkFind("binaryWrapper"    , myObj.binaryWrapper)
      }
    }

    "filter by fields with native Mongo Java codecs" in {
      forAll(myObjectGen) { myObj =>
        prepareDatabase()

        val result = playMongoRepository.collection.insertOne(myObj).toFuture()
        result.futureValue.wasAcknowledged shouldBe true

        def checkFind[A: Writes](key: String, value: A): Assertion =
          playMongoRepository.collection
            .find(filter = Filters.equal(key, value))
            .toFuture()
            .futureValue shouldBe List(myObj)

        checkFind("_id"              , myObj.id)
        checkFind("javaInstant"      , myObj.javaInstant)
        checkFind("javaLocalDate"    , myObj.javaLocalDate)
        checkFind("uuid"             , myObj.uuid)
      }
    }

    "update fields" in {
      forAll(myObjectGen) { originalObj =>
        forAll(myObjectGen suchThat (_ != originalObj)) { targetObj =>
          prepareDatabase()

          val result = playMongoRepository.collection.insertOne(originalObj).toFuture()
          result.futureValue.wasAcknowledged shouldBe true

          def checkUpdate[A: Writes](key: String, value: A): Assertion =
            playMongoRepository.collection
              .updateOne(filter = BsonDocument(), update = Updates.set(key, toBson(value)))
              .toFuture()
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
          checkUpdate("sum"              , targetObj.sum              )
          checkUpdate("objectId"         , targetObj.objectId         )
          checkUpdate("listString"       , targetObj.listString       )
          checkUpdate("listLong"         , targetObj.listLong         )
          checkUpdate("uuid"             , targetObj.uuid             )
          checkUpdate("uuidWrapper"      , targetObj.uuidWrapper      )
          checkUpdate("binary"           , targetObj.binary           )
          checkUpdate("binaryWrapper"    , targetObj.binaryWrapper    )

          val writtenObj = playMongoRepository.collection.find().toFuture()
          writtenObj.futureValue shouldBe List(targetObj.copy(id = originalObj.id))
        }
      }
    }

    "update fields with native Mongo Java codecs" in {
      forAll(myObjectGen) { originalObj =>
        forAll(myObjectGen suchThat (_ != originalObj)) { targetObj =>
          prepareDatabase()

          val result = playMongoRepository.collection.insertOne(originalObj).toFuture()
          result.futureValue.wasAcknowledged shouldBe true

          def checkUpdate[A: Writes](key: String, value: A): Assertion =
            playMongoRepository.collection
              .updateOne(filter = BsonDocument(), update = Updates.set(key, value))
              .toFuture()
              .futureValue
              .wasAcknowledged shouldBe true

          // Note, not checking update of `_id` since immutable
          checkUpdate("javaInstant"      , targetObj.javaInstant      )
          checkUpdate("javaLocalDate"    , targetObj.javaLocalDate    )
          checkUpdate("uuid"             , targetObj.uuid             )

          val writtenObj = playMongoRepository.collection.find().toFuture()

          writtenObj.futureValue shouldBe List(originalObj.copy(
            javaInstant       = targetObj.javaInstant,
            javaLocalDate     = targetObj.javaLocalDate,
            uuid              = targetObj.uuid
          ))
        }
      }
    }

    // We make a best attempt, for clients which unfortunately relied on the order of keys in JSON objects (which isn't required by the spec),
    // which simple-reactivemongo implementation preserved.
    // Clients should really be using BSON directly (which does guarantee order) or modelling in JSON appropriately (e.g. Array or order labelled objects).
    // However, in the spirit of making the migration from simple-reactive mongo as easy as possible, we attempt to keep the order. This comes with caveats, such as
    // the "_id" field will always bubble up to the first entry, and any mutation of the JSON object (adding/removing entries) will loose the ordering.
   "preserve order in json keys" in {
      val repo =
        new PlayMongoRepository[JsObject](
          mongoComponent = mongoComponent,
          collectionName = "rawjson",
          domainFormat   = Format[JsObject](jsv => JsSuccess(jsv.asInstanceOf[JsObject]), Writes[JsObject](js => js)),
          indexes        = Seq.empty
        )

      forAll(flatJsonObjectGen) { json =>
        def keys(json: JsObject) =
          json.fields.map(_._1).toList

        (for {
           _            <- repo.collection.deleteMany(BsonDocument()).toFuture()
           _            <- repo.collection.insertOne(json).toFuture()
           returnedJson <- repo.collection.find().headOption().map {
                             case Some(res) => res
                             case other     => fail(new RuntimeException(s"Failed to read back JsObject - was $other"))
                           }
           // The `drop(1)` removes the generated "_id".
           // Note, if we were to store "_id" ourselves, it would always be returned as the first entry, regardless of where it was set in the original Json.
           // We cannot remove with `returnedJson - "_id"` since this will loose the ordering (play's implementation delegates to Scala).
         } yield keys(json) shouldBe keys(returnedJson).drop(1)
        ).futureValue
      }
    }

    "validate against jsonSchema" in {
      forAll(myObjectGen) { originalObj =>
        forAll(myObjectGen suchThat (_ != originalObj)) { targetObj =>
          prepareDatabase()

          val result = playMongoRepository.collection.insertOne(originalObj).toFuture()
          result.futureValue.wasAcknowledged shouldBe true

          def checkUpdateFails[A](key: String, value: A)(implicit ev: Writes[A]): Assertion =
            whenReady {
              playMongoRepository.collection
                .updateOne(filter = BsonDocument(), update = Updates.set(key, toBson(value)))
                .toFuture()
                .failed
            } { e =>
              e            shouldBe a[MongoWriteException]
              e.getMessage should include("Document failed validation")
           }

          // updates should fail with the wrong Writers
          checkUpdateFails("javaInstant"      , targetObj.javaInstant      )(Writes.DefaultInstantWrites)
          checkUpdateFails("javaLocalDate"    , targetObj.javaLocalDate    )(Writes.DefaultLocalDateWrites)
          checkUpdateFails("uuid"             , targetObj.uuid             )(Writes.UuidWrites)
          checkUpdateFails("uuidWrapper"      , targetObj.uuidWrapper      )(Writes.UuidWrites.contramap(_.unwrap))
          checkUpdateFails("binary"           , targetObj.binary           )(Writes.arrayWrites[Byte].contramap(_.toArray[Byte]))
          checkUpdateFails("binaryWrapper"    , targetObj.binaryWrapper    )(Writes.arrayWrites[Byte].contramap(_.unwrap.toArray[Byte]))
        }
      }
    }
  }

  def prepareDatabase(): Unit =
    (for {
      exists <- MongoUtils.existsCollection(mongoComponent, playMongoRepository.collection)
      _      <- if (exists) playMongoRepository.collection.deleteMany(BsonDocument()).toFuture()
                else Future.unit
     } yield ()
    ).futureValue

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    // ensure jsonSchema is defined as expected
    (for {
       _           <- updateIndexPreference(requireIndexedQuery = false)
       collections <- mongoComponent.database.listCollections().toFuture()
       collection  =  collections.find(_.get("name") == Some(BsonString(playMongoRepository.collection.namespace.getCollectionName)))
       _           =  collection.isDefined shouldBe true
       options     =  collection.flatMap(_.get[BsonDocument]("options"))
       _           =  options.exists(_.containsKey("validator")) shouldBe true
       validator   =  options.get.getDocument("validator")
       _           =  Option(validator.get(f"$$jsonSchema")) shouldBe playMongoRepository.optSchema
     } yield ()
    ).futureValue
  }

  protected def updateIndexPreference(requireIndexedQuery: Boolean): Future[Boolean] = {
    val notablescan = if (requireIndexedQuery) 1 else 0

    mongoComponent.client
      .getDatabase("admin")
      .withReadPreference(ReadPreference.primaryPreferred())
      .runCommand(Document(
        "setParameter" -> 1,
        "notablescan" -> notablescan
      ))
      .toFuture()
      .map(_.getBoolean("was"))
  }
}

object PlayMongoRepositorySpec {

  case class StringWrapper    (unwrap: String     ) extends AnyVal
  case class BooleanWrapper   (unwrap: Boolean    ) extends AnyVal
  case class IntWrapper       (unwrap: Int        ) extends AnyVal
  case class LongWrapper      (unwrap: Long       ) extends AnyVal
  case class DoubleWrapper    (unwrap: Double     ) extends AnyVal
  case class BigDecimalWrapper(unwrap: BigDecimal ) extends AnyVal
  case class UUIDWrapper      (unwrap: UUID       ) extends AnyVal
  case class BinaryWrapper    (unwrap: ByteString ) extends AnyVal

  sealed trait Sum
  object Sum {
    case object Sum1 extends Sum
    case object Sum2 extends Sum
  }

  case class MyObject(
    id               : ObjectId,
    // Wrappers
    string           : StringWrapper,
    boolean          : BooleanWrapper,
    int              : IntWrapper,
    long             : LongWrapper,
    double           : DoubleWrapper,
    bigDecimal       : BigDecimalWrapper,
    // Sum type (WIP)
    sum              : Sum,
    // Joda time
    jodaDateTime     : jot.DateTime,
    jodaLocalDate    : jot.LocalDate,
    jodaLocalDateTime: jot.LocalDateTime,
    // Java time
    javaInstant      : jat.Instant,
    javaLocalDate    : jat.LocalDate,
    objectId         : ObjectId,
    // Arrays
    listString       : List[String],
    listLong         : List[Long],
    // UUID
    uuid             : UUID,
    uuidWrapper      : UUIDWrapper,
    // Binary data - we use ByteString for this test because case class equality
    // uses reference equality for arrays so it will never compare equal
    binary           : ByteString,
    binaryWrapper    : BinaryWrapper
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

  // Note without the following import, it will compile, but use play's UUID formats.
  import MongoUuidFormats.Implicits._

  val uuidWrapperFormat: Format[UUIDWrapper] =
    implicitly[Format[UUID]].inmap(UUIDWrapper.apply, unlift(UUIDWrapper.unapply))

  import MongoBinaryFormats.Implicits._

  val binaryWrapperFormat: Format[BinaryWrapper] =
    implicitly[Format[ByteString]].inmap(BinaryWrapper.apply, unlift(BinaryWrapper.unapply))

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
    implicit val swf   = stringWrapperFormat
    implicit val bwf   = booleanWrapperFormat
    implicit val iwf   = intWrapperFormat
    implicit val lwf   = longWrapperFormat
    implicit val dwf   = doubleWrapperFormat
    implicit val bdwf  = bigDecimalWrapperFormat
    implicit val uwf   = uuidWrapperFormat
    implicit val binwf = binaryWrapperFormat
    implicit val sf    = sumFormat
  }

  val myObjectFormat = {
    import Implicits._
    import MongoFormats.Implicits._
    import MongoJodaFormats.Implicits._
    // Note without the following import, it will compile, but use plays Javatime formats.
    // Applying `myObjectSchema` will check that dates are being stored as dates
    import MongoJavatimeFormats.Implicits._
    ( (__ \ "_id"              ).format[ObjectId         ]
    ~ (__ \ "string"           ).format[StringWrapper    ]
    ~ (__ \ "boolean"          ).format[BooleanWrapper   ]
    ~ (__ \ "int"              ).format[IntWrapper       ]
    ~ (__ \ "long"             ).format[LongWrapper      ]
    ~ (__ \ "double"           ).format[DoubleWrapper    ]
    ~ (__ \ "bigDecimal"       ).format[BigDecimalWrapper]
    ~ (__ \ "sum"              ).format[Sum              ]
    ~ (__ \ "jodaDateTime"     ).format[jot.DateTime     ]
    ~ (__ \ "jodaLocalDate"    ).format[jot.LocalDate    ]
    ~ (__ \ "jodaLocalDateTime").format[jot.LocalDateTime]
    ~ (__ \ "javaInstant"      ).format[jat.Instant      ]
    ~ (__ \ "javaLocalDate"    ).format[jat.LocalDate    ]
    ~ (__ \ "objectId"         ).format[ObjectId         ]
    ~ (__ \ "listString"       ).format[List[String]     ]
    ~ (__ \ "listLong"         ).format[List[Long]       ]
    ~ (__ \ "uuid"             ).format[UUID             ]
    ~ (__ \ "uuidWrapper"      ).format[UUIDWrapper      ]
    ~ (__ \ "binary"           ).format[ByteString       ]
    ~ (__ \ "binaryWrapper"    ).format[BinaryWrapper    ]
    )(MyObject.apply, unlift(MyObject.unapply))
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
        , listString       : { bsonType: "array"    }
        , listLong         : { bsonType: "array"    }
        , listLong         : { bsonType: "array"    }
        , uuid             : { bsonType: "binData"  }
        , uuidWrapper      : { bsonType: "binData"  }
        , binary           : { bsonType: "binData"  }
        , binaryWrapper    : { bsonType: "binData"  }
        }
      }
      """
    )

  def myObjectGen =
    for {
      s   <- Arbitrary.arbitrary[String      ]
      b   <- Arbitrary.arbitrary[Boolean     ]
      i   <- Arbitrary.arbitrary[Int         ]
      l   <- Arbitrary.arbitrary[Long        ]
      d   <- Arbitrary.arbitrary[Double      ]
      ls  <- Arbitrary.arbitrary[List[String]]
      ll  <- Arbitrary.arbitrary[List[Long]  ]
      u   <- Arbitrary.arbitrary[UUID        ]
      bin <- Arbitrary.arbitrary[Array[Byte] ]
      bd  <- Arbitrary.arbitrary[BigDecimal  ]
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
      javaLocalDate     = jat.Instant.ofEpochMilli(epochMillis).atZone(jat.ZoneOffset.UTC).toLocalDate,
      objectId          = new org.bson.types.ObjectId(new java.util.Date(epochMillis)),
      listString        = ls,
      listLong          = ll,
      uuid              = u,
      uuidWrapper       = UUIDWrapper(u),
      binary            = ByteString(bin),
      binaryWrapper     = BinaryWrapper(ByteString(bin))
    )

  val flatJsonObjectGen: Gen[JsObject] = {
    // Not all Strings are valid bson field named (e.g. with `.`)
    case class BsonFieldName(s: String)
    implicit val bsonFieldNameGen: Arbitrary[BsonFieldName] =
      Arbitrary(
        Gen.alphaNumStr
        .suchThat(s => scala.util.Try(BsonDocument(s -> "")).isSuccess)
        .map(BsonFieldName)
      )
    Arbitrary.arbContainer2[Map,BsonFieldName,String]
      .arbitrary.map { m =>
        Json.obj(m.map { case (k, v) => k.s -> Json.toJsFieldJsValueWrapper[String](v) }.toSeq :_ *)
      }
  }
}
