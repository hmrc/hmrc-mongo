/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.encryption

import org.bson.types.ObjectId
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Updates
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.Configuration
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.play.json.formats.MongoFormats
import uk.gov.hmrc.crypto.{Crypted, CryptoGCMWithKeysFromConfig, PlainText }


import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import java.security.SecureRandom
import java.util.Base64
import Sensitive._

// same as EncrypterAsStringSpec, but storing the encrypted value as a tagged object
class EncrypterSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach
     with OptionValues {
  import EncrypterSpec._

  val databaseName: String =
    "test-" + this.getClass.getSimpleName

  val mongoComponent =
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")

  val myObjectSchema =
    // schema doesn't catch if there are extra fields (e.g. if encryption has merged with unencrypted object)
    BsonDocument(
      """
      { bsonType: "object"
      , required: [ "_id", "sensitiveString", "sensitiveBoolean", "sensitiveLong", "sensitiveNested" ]
      , properties:
        { _id              : { bsonType: "string" }
        , sensitiveString  : { bsonType: "object", required: [ "encrypted", "value" ], properties: { "encrypted": { bsonType: "bool"}, value: { bsonType: "string" } } }
        , sensitiveBoolean : { bsonType: "object", required: [ "encrypted", "value" ], properties: { "encrypted": { bsonType: "bool"}, value: { bsonType: "string" } } }
        , sensitiveLong    : { bsonType: "object", required: [ "encrypted", "value" ], properties: { "encrypted": { bsonType: "bool"}, value: { bsonType: "string" } } }
        , sensitiveNested  : { bsonType: "object", required: [ "encrypted", "value" ], properties: { "encrypted": { bsonType: "bool"}, value: { bsonType: "string" } } }
        , sensitiveOptional: { bsonType: "object", required: [ "encrypted", "value" ], properties: { "encrypted": { bsonType: "bool"}, value: { bsonType: "string" } } }
        }
      }
      """
    )

  val playMongoRepository = new PlayMongoRepository[MyObject](
    mongoComponent   = mongoComponent,
    collectionName   = "myobject",
    domainFormat     = MyObject.format,
    indexes          = Seq.empty,
    optSchema        = Some(myObjectSchema),
    extraCodecs      = Seq(
                         Codecs.playFormatCodec(MyObject.ssFormat),
                         Codecs.playFormatCodec(MyObject.snFormat)
                       )
  )

  "Encryption" should {
    def shouldBeEncrypted(raw: BsonDocument, path: JsPath, required: Boolean = true): Unit =
      path(Json.parse(raw.toJson)) match {
        case Seq(x)        => x.as[JsObject].keys shouldBe Set("encrypted", "value")
        case x :: _        => throw new AssertionError(s"$path resolved to more than one field in $raw")
        case _ if required => throw new AssertionError(s"$path did not exist in $raw")
        case _             => ()
      }

    "encrypt and decrypt model" in {
      val unencryptedString  = SensitiveString("123456789")
      val unencryptedBoolean = SensitiveBoolean(true)
      val unencryptedLong    = SensitiveLong(123456789L)
      val unencryptedNested  = SensitiveNested(Nested("n1", 2))

      (for {
         _   <- playMongoRepository.collection.insertOne(MyObject(
                  id                = ObjectId.get().toString,
                  sensitiveString   = unencryptedString,
                  sensitiveBoolean  = unencryptedBoolean,
                  sensitiveLong     = unencryptedLong,
                  sensitiveNested   = unencryptedNested,
                  sensitiveOptional = None
                )).headOption()
         res <- playMongoRepository.collection.find().headOption().map(_.value)
         _   =  res.sensitiveString  shouldBe unencryptedString
         _   =  res.sensitiveBoolean shouldBe unencryptedBoolean
         _   =  res.sensitiveLong    shouldBe unencryptedLong
         // and confirm it is stored as an EncryptedValue
         // (schema alone doesn't catch if there are extra fields - e.g. if unencrypted object is merged with encrypted fields)
         raw <- mongoComponent.database.getCollection[BsonDocument]("myobject").find().headOption().map(_.value)
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveString"  )
                _ = println(s"6")
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveBoolean" )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveLong"    )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveNested"  )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveOptional", required = false)
       } yield ()
      ).futureValue
    }

    "update primitive value" in {
      val unencryptedString  = SensitiveString("123456789")
      val unencryptedBoolean = SensitiveBoolean(true)
      val unencryptedLong    = SensitiveLong(123456789L)
      val unencryptedNested  = SensitiveNested(Nested("n1", 2))

      val unencryptedString2 = SensitiveString("987654321")

      (for {
         _   <- playMongoRepository.collection.insertOne(MyObject(
                  id                = ObjectId.get().toString,
                  sensitiveString   = unencryptedString,
                  sensitiveBoolean  = unencryptedBoolean,
                  sensitiveLong     = unencryptedLong,
                  sensitiveNested   = unencryptedNested,
                  sensitiveOptional = None
                )).headOption()

         _   <- playMongoRepository.collection.updateMany(BsonDocument(), Updates.set("sensitiveString", unencryptedString2)).headOption().map(_ => ())

         res <- playMongoRepository.collection.find().headOption().map(_.value)
         _   =  res.sensitiveString   shouldBe unencryptedString2
         _   =  res.sensitiveBoolean  shouldBe unencryptedBoolean
         _   =  res.sensitiveLong     shouldBe unencryptedLong
         _   =  res.sensitiveNested   shouldBe unencryptedNested
         _   =  res.sensitiveOptional shouldBe None
         // and confirm it is stored as an EncryptedValue
         // (schema alone doesn't catch if there are extra fields - e.g. if unencrypted object is merged with encrypted fields)
         raw <- mongoComponent.database.getCollection[BsonDocument]("myobject").find().headOption().map(_.value)
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveString"  )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveBoolean" )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveLong"    )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveNested"  )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveOptional", required = false)
       } yield ()
      ).futureValue
    }

    "update nested value" in {
      val unencryptedString  = SensitiveString("123456789")
      val unencryptedBoolean = SensitiveBoolean(true)
      val unencryptedLong    = SensitiveLong(123456789L)
      val unencryptedNested  = SensitiveNested(Nested("n1", 2))

      val unencryptedNested2 = SensitiveNested(Nested("n2", 3))

      (for {
         _   <- playMongoRepository.collection.insertOne(MyObject(
                  id                = ObjectId.get().toString,
                  sensitiveString   = unencryptedString,
                  sensitiveBoolean  = unencryptedBoolean,
                  sensitiveLong     = unencryptedLong,
                  sensitiveNested   = unencryptedNested,
                  sensitiveOptional = None
                )).headOption()

         _   <- playMongoRepository.collection.updateMany(BsonDocument(), Updates.set("sensitiveNested", unencryptedNested2)).headOption().map(_ => ())

         res <- playMongoRepository.collection.find().headOption().map(_.value)
         _   =  res.sensitiveString   shouldBe unencryptedString
         _   =  res.sensitiveBoolean  shouldBe unencryptedBoolean
         _   =  res.sensitiveLong     shouldBe unencryptedLong
         _   =  res.sensitiveNested.value   shouldBe unencryptedNested2.value // error messages when comparing Sensitive are unhelpful since we've suppressed toString
         _   =  res.sensitiveNested   shouldBe unencryptedNested2
         _   =  res.sensitiveOptional shouldBe None

         // and confirm it is stored as an EncryptedValue
         // (schema alone doesn't catch if there are extra fields - e.g. if unencrypted object is merged with encrypted fields)
         raw <- mongoComponent.database.getCollection[BsonDocument]("myobject").find().headOption().map(_.value)
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveString"  )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveBoolean" )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveLong"    )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveNested"  )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveOptional", required = false)
       } yield ()
      ).futureValue
    }
  }

  def prepareDatabase(): Unit =
    (for {
      exists <- MongoUtils.existsCollection(mongoComponent, playMongoRepository.collection)
      _      <- if (exists) playMongoRepository.collection.deleteMany(BsonDocument()).toFuture()
                else Future.unit
     } yield ()
    ).futureValue

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    prepareDatabase()
  }
}

object EncrypterSpec {
  case class Nested(
    n1: String,
    n2: Int
  )
  object Nested {
    val format =
      ( (__ \ "n1").format[String]
      ~ (__ \ "n2").format[Int]
      )(Nested.apply, unlift(Nested.unapply))
  }

  case class SensitiveNested(value: Nested) extends Sensitive[Nested]

  case class MyObject(
    id               : String,
    sensitiveString  : SensitiveString,
    sensitiveBoolean : SensitiveBoolean,
    sensitiveLong    : SensitiveLong,
    sensitiveNested  : SensitiveNested,
    sensitiveOptional: Option[SensitiveString]
  )

  object MyObject {

    private val crypto = {
      val aesKey = {
        val aesKey = new Array[Byte](32)
        new SecureRandom().nextBytes(aesKey)
        Base64.getEncoder.encodeToString(aesKey)
      }
      val config = Configuration("crypto.key" -> aesKey)
      new CryptoGCMWithKeysFromConfig("crypto", config.underlying)
    }


    implicit val oif = MongoFormats.Implicits.objectIdFormat

    val cryptoFormat: OFormat[Crypted] =
      ( (__ \ "encrypted").format[Boolean]
      ~ (__ \ "value"    ).format[String]
      )((_, v) => Crypted.apply(v), unlift(Crypted.unapply).andThen(v => (true, v)))

    // The required Crypto could be an implicit to simplify constructing the Formats
    def sensitiveFormat[A: Format, B <: Sensitive[A]](apply: A => B, unapply: B => A): OFormat[B] =
      cryptoFormat
        .inmap[B](
          c  => apply(Json.parse(crypto.decrypt(c).value).as[A]),
          sn => crypto.encrypt(PlainText(implicitly[Format[A]].writes(unapply(sn)).toString))
        )

    implicit val ssFormat = sensitiveFormat[String, SensitiveString](SensitiveString.apply, unlift(SensitiveString.unapply))
    implicit val sbFormat = sensitiveFormat[Boolean, SensitiveBoolean](SensitiveBoolean.apply, unlift(SensitiveBoolean.unapply))
    implicit val slFormat = sensitiveFormat[Long, SensitiveLong](SensitiveLong.apply, unlift(SensitiveLong.unapply))
    implicit val snFormat = sensitiveFormat(SensitiveNested.apply, unlift(SensitiveNested.unapply))(Nested.format)

    val format =
      ( (__ \ "_id"              ).format[String]
      ~ (__ \ "sensitiveString"  ).format[SensitiveString]
      ~ (__ \ "sensitiveBoolean" ).format[SensitiveBoolean]
      ~ (__ \ "sensitiveLong"    ).format[SensitiveLong]
      ~ (__ \ "sensitiveNested"  ).format[SensitiveNested]
      ~ (__ \ "sensitiveOptional").formatNullable[SensitiveString]
      )(MyObject.apply, unlift(MyObject.unapply))
  }
}
