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
import uk.gov.hmrc.crypto.{Crypted, Decrypter, Encrypter, Sensitive, SymmetricCryptoFactory}
import uk.gov.hmrc.crypto.Sensitive._
import uk.gov.hmrc.crypto.json.JsonEncryption


import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import java.security.SecureRandom
import java.util.Base64

class SensitiveEncryptionSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach
     with OptionValues {
  import SensitiveEncryptionSpec._

  val databaseName: String =
    "test-" + this.getClass.getSimpleName

  val mongoComponent =
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")

  val myObjectSchema =
    // schema doesn't catch if the string is actually encrypted
    BsonDocument(
      """
      { bsonType: "object"
      , required: [ "_id", "sensitiveString", "sensitiveBoolean", "sensitiveLong", "sensitiveNested" ]
      , properties:
        { _id              : { bsonType: "objectId" }
        , sensitiveString  : { bsonType: "string" }
        , sensitiveBoolean : { bsonType: "string" }
        , sensitiveLong    : { bsonType: "string" }
        , sensitiveNested  : { bsonType: "string" }
        , sensitiveOptional: { bsonType: "string" }
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
                         Codecs.playFormatCodec(MyObject.sensitiveStringFormat),
                         Codecs.playFormatCodec(MyObject.sensitiveNestedFormat)
                       )
  )

  "Encryption" should {
    def assertEncrypted[A: Reads](encryptedValue: String, expectedValue: A): Unit =
      Json.parse(MyObject.crypto.decrypt(Crypted(encryptedValue)).value).as[A] shouldBe expectedValue

    "encrypt and decrypt model" in {
      val unencryptedString  = SensitiveString("123456789")
      val unencryptedBoolean = SensitiveBoolean(true)
      val unencryptedLong    = SensitiveLong(123456789L)
      val unencryptedNested  = SensitiveNested(Nested("n1", 2))

      (for {
         _   <- playMongoRepository.collection.insertOne(MyObject(
                  id                = ObjectId.get(),
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

         // and confirm it is stored as an encrypted value
         raw <- mongoComponent.database.getCollection[BsonDocument]("myobject").find().headOption().map(_.value)
         _   =  assertEncrypted(raw.get("sensitiveString"  ).asString.getValue, unencryptedString .decryptedValue)
         _   =  assertEncrypted(raw.get("sensitiveBoolean" ).asString.getValue, unencryptedBoolean.decryptedValue)
         _   =  assertEncrypted(raw.get("sensitiveLong"    ).asString.getValue, unencryptedLong   .decryptedValue)
         _   =  assertEncrypted(raw.get("sensitiveNested"  ).asString.getValue, unencryptedNested .decryptedValue)(Nested.format)
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
                  id                = ObjectId.get(),
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

         // and confirm it is stored as an encrypted value
         raw <- mongoComponent.database.getCollection[BsonDocument]("myobject").find().headOption().map(_.value)
         _   =  assertEncrypted(raw.get("sensitiveString"  ).asString.getValue, unencryptedString2.decryptedValue)
         _   =  assertEncrypted(raw.get("sensitiveBoolean" ).asString.getValue, unencryptedBoolean.decryptedValue)
         _   =  assertEncrypted(raw.get("sensitiveLong"    ).asString.getValue, unencryptedLong   .decryptedValue)
         _   =  assertEncrypted(raw.get("sensitiveNested"  ).asString.getValue, unencryptedNested .decryptedValue)(Nested.format)
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
                  id                = ObjectId.get(),
                  sensitiveString   = unencryptedString,
                  sensitiveBoolean  = unencryptedBoolean,
                  sensitiveLong     = unencryptedLong,
                  sensitiveNested   = unencryptedNested,
                  sensitiveOptional = None
                )).headOption()

         _   <- playMongoRepository.collection.updateMany(BsonDocument(), Updates.set("sensitiveNested", unencryptedNested2)).headOption().map(_ => ())

         res <- playMongoRepository.collection.find().headOption().map(_.value)
         _   =  res.sensitiveString                shouldBe unencryptedString
         _   =  res.sensitiveBoolean               shouldBe unencryptedBoolean
         _   =  res.sensitiveLong                  shouldBe unencryptedLong
         _   =  res.sensitiveNested.decryptedValue shouldBe unencryptedNested2.decryptedValue // error messages when comparing Sensitive are unhelpful since we've suppressed toString
         _   =  res.sensitiveNested                shouldBe unencryptedNested2
         _   =  res.sensitiveOptional              shouldBe None

         // and confirm it is stored as an EncryptedValue
         // (schema alone doesn't catch if there are extra fields - e.g. if unencrypted object is merged with encrypted fields)
         raw <- mongoComponent.database.getCollection[BsonDocument]("myobject").find().headOption().map(_.value)
         _   =  assertEncrypted(raw.get("sensitiveString"  ).asString.getValue, unencryptedString .decryptedValue)
         _   =  assertEncrypted(raw.get("sensitiveBoolean" ).asString.getValue, unencryptedBoolean.decryptedValue)
         _   =  assertEncrypted(raw.get("sensitiveLong"    ).asString.getValue, unencryptedLong   .decryptedValue)
         _   =  assertEncrypted(raw.get("sensitiveNested"  ).asString.getValue, unencryptedNested2.decryptedValue)(Nested.format)
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

object SensitiveEncryptionSpec {
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

  case class SensitiveNested(override val decryptedValue: Nested) extends Sensitive[Nested]

  case class MyObject(
    id               : ObjectId,
    sensitiveString  : SensitiveString,
    sensitiveBoolean : SensitiveBoolean,
    sensitiveLong    : SensitiveLong,
    sensitiveNested  : SensitiveNested,
    sensitiveOptional: Option[SensitiveString]
  )

  object MyObject {
    implicit val crypto: Encrypter with Decrypter = {
      val aesKey = {
        val aesKey = new Array[Byte](32)
        new SecureRandom().nextBytes(aesKey)
        Base64.getEncoder.encodeToString(aesKey)
      }
      val config = Configuration("crypto.key" -> aesKey)
      SymmetricCryptoFactory.aesGcmCryptoFromConfig("crypto", config.underlying)
    }

    val sensitiveStringFormat  = JsonEncryption.sensitiveEncrypterDecrypter(SensitiveString.apply)
    val sensitiveBooleanFormat = JsonEncryption.sensitiveEncrypterDecrypter(SensitiveBoolean.apply)
    val sensitiveLongFormat    = JsonEncryption.sensitiveEncrypterDecrypter(SensitiveLong.apply)
    val sensitiveNestedFormat: Format[SensitiveNested]   = {
      implicit val nf  = Nested.format
      JsonEncryption.sensitiveEncrypterDecrypter(SensitiveNested.apply)
    }


    val format: Format[MyObject] = {
      implicit val oif = MongoFormats.Implicits.objectIdFormat
      implicit val psf = sensitiveStringFormat
      implicit val pbf = sensitiveBooleanFormat
      implicit val plf = sensitiveLongFormat
      implicit val pnf = sensitiveNestedFormat
      ( (__ \ "_id"              ).format[ObjectId]
      ~ (__ \ "sensitiveString"  ).format[SensitiveString]
      ~ (__ \ "sensitiveBoolean" ).format[SensitiveBoolean]
      ~ (__ \ "sensitiveLong"    ).format[SensitiveLong]
      ~ (__ \ "sensitiveNested"  ).format[SensitiveNested]
      ~ (__ \ "sensitiveOptional").formatNullable[SensitiveString]
      )(MyObject.apply, unlift(MyObject.unapply))
    }
  }
}
