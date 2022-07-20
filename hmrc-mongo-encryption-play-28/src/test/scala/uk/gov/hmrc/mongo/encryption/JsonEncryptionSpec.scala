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
import uk.gov.hmrc.crypto.{Crypted, CryptoGCMWithKeysFromConfig, Protected, PlainText}
import uk.gov.hmrc.crypto.json.CryptoFormats


import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import java.security.SecureRandom
import java.util.Base64

// same as EncrypterAsStringSpec, but storing the encrypted value as a tagged object
class JsonEncryptionSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach
     with OptionValues {
  import JsonEncryptionSpec._

  val databaseName: String =
    "test-" + this.getClass.getSimpleName

  val mongoComponent =
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")

  val myObjectSchema =
    // schema doesn't catch if the string is actually encrypted
    BsonDocument(
      """
      { bsonType: "object"
      , required: [ "_id", "protectedString", "protectedBoolean", "protectedLong", "protectedNested" ]
      , properties:
        { _id              : { bsonType: "objectId" }
        , protectedString  : { bsonType: "string" }
        , protectedBoolean : { bsonType: "string" }
        , protectedLong    : { bsonType: "string" }
        , protectedNested  : { bsonType: "string" }
        , protectedOptional: { bsonType: "string" }
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
    extraCodecs      = (
                         Codecs.playFormatCodec(MyObject.ssFormat),
                         Codecs.playFormatCodec(MyObject.snFormat)
                       )
  )

  "Encryption" should {
    def assertEncrypted[T](encryptedValue: String, expectedValue: Protected[T])(implicit rds: Reads[T]): Unit =
      Json.parse(MyObject.crypto.decrypt(Crypted(encryptedValue)).value).as[T] shouldBe expectedValue.decryptedValue

    "encrypt and decrypt model" in {
      val unencryptedString  = Protected[String]("123456789")
      val unencryptedBoolean = Protected[Boolean](true)
      val unencryptedLong    = Protected[Long](123456789L)
      val unencryptedNested  = Protected[Nested](Nested("n1", 2))

      (for {
         _   <- playMongoRepository.collection.insertOne(MyObject(
                  id                = ObjectId.get(),
                  protectedString   = unencryptedString,
                  protectedBoolean  = unencryptedBoolean,
                  protectedLong     = unencryptedLong,
                  protectedNested   = unencryptedNested,
                  protectedOptional = None
                )).headOption()
         res <- playMongoRepository.collection.find().headOption().map(_.value)
         _   =  res.protectedString  shouldBe unencryptedString
         _   =  res.protectedBoolean shouldBe unencryptedBoolean
         _   =  res.protectedLong    shouldBe unencryptedLong
         // and confirm it is stored as an EncryptedValue
         // (schema alone doesn't catch if there are extra fields - e.g. if unencrypted object is merged with encrypted fields)
         raw <- mongoComponent.database.getCollection[BsonDocument]("myobject").find().headOption().map(_.value)

         _   =  assertEncrypted(raw.get("protectedString"  ).asString.getValue, unencryptedString )
         _   =  assertEncrypted(raw.get("protectedBoolean" ).asString.getValue, unencryptedBoolean)
         _   =  assertEncrypted(raw.get("protectedLong"    ).asString.getValue, unencryptedLong   )
         _   =  assertEncrypted(raw.get("protectedNested"  ).asString.getValue, unencryptedNested )(Nested.format)
       } yield ()
      ).futureValue
    }

    "update primitive value" in {
      val unencryptedString  = Protected[String]("123456789")
      val unencryptedBoolean = Protected[Boolean](true)
      val unencryptedLong    = Protected[Long](123456789L)
      val unencryptedNested  = Protected[Nested](Nested("n1", 2))

      val unencryptedString2 = Protected[String]("987654321")

      (for {
         _   <- playMongoRepository.collection.insertOne(MyObject(
                  id                = ObjectId.get(),
                  protectedString   = unencryptedString,
                  protectedBoolean  = unencryptedBoolean,
                  protectedLong     = unencryptedLong,
                  protectedNested   = unencryptedNested,
                  protectedOptional = None
                )).headOption()

         _   <- playMongoRepository.collection.updateMany(BsonDocument(), Updates.set("protectedString", unencryptedString2)).headOption().map(_ => ())

         res <- playMongoRepository.collection.find().headOption().map(_.value)
         _   =  res.protectedString   shouldBe unencryptedString2
         _   =  res.protectedBoolean  shouldBe unencryptedBoolean
         _   =  res.protectedLong     shouldBe unencryptedLong
         _   =  res.protectedNested   shouldBe unencryptedNested
         _   =  res.protectedOptional shouldBe None
         // and confirm it is stored as an EncryptedValue
         raw <- mongoComponent.database.getCollection[BsonDocument]("myobject").find().headOption().map(_.value)
         _   =  assertEncrypted(raw.get("protectedString"  ).asString.getValue, unencryptedString2)
         _   =  assertEncrypted(raw.get("protectedBoolean" ).asString.getValue, unencryptedBoolean)
         _   =  assertEncrypted(raw.get("protectedLong"    ).asString.getValue, unencryptedLong   )
         _   =  assertEncrypted(raw.get("protectedNested"  ).asString.getValue, unencryptedNested )(Nested.format)
       } yield ()
      ).futureValue
    }

    /*"update nested value" in {
      val unencryptedString  = Protected[String]("123456789")
      val unencryptedBoolean = Protected[Boolean](true)
      val unencryptedLong    = Protected[Long](123456789L)
      val unencryptedNested  = Protected[Nested](Nested("n1", 2))

      val unencryptedNested2 = Protected[Nested](Nested("n2", 3))

      (for {
         _   <- playMongoRepository.collection.insertOne(MyObject(
                  id                = ObjectId.get(),
                  protectedString   = unencryptedString,
                  protectedBoolean  = unencryptedBoolean,
                  protectedLong     = unencryptedLong,
                  protectedNested   = unencryptedNested,
                  protectedOptional = None
                )).headOption()

         _   <- playMongoRepository.collection.updateMany(BsonDocument(), Updates.set("protectedNested", unencryptedNested2)).headOption().map(_ => ())

         res <- playMongoRepository.collection.find().headOption().map(_.value)
         _   =  res.protectedString                shouldBe unencryptedString
         _   =  res.protectedBoolean               shouldBe unencryptedBoolean
         _   =  res.protectedLong                  shouldBe unencryptedLong
         _   =  res.protectedNested.decryptedValue shouldBe unencryptedNested2.decryptedValue // error messages when comparing Sensitive are unhelpful since we've suppressed toString
         _   =  res.protectedNested                shouldBe unencryptedNested2
         _   =  res.protectedOptional              shouldBe None

         // and confirm it is stored as an EncryptedValue
         // (schema alone doesn't catch if there are extra fields - e.g. if unencrypted object is merged with encrypted fields)
         raw <- mongoComponent.database.getCollection[BsonDocument]("myobject").find().headOption().map(_.value)
         _   =  shouldBeEncrypted(raw, __ \ "protectedString"  )
         _   =  shouldBeEncrypted(raw, __ \ "protectedBoolean" )
         _   =  shouldBeEncrypted(raw, __ \ "protectedLong"    )
         _   =  shouldBeEncrypted(raw, __ \ "protectedNested"  )
         _   =  shouldBeEncrypted(raw, __ \ "protectedOptional", required = false)
       } yield ()
      ).futureValue
    }*/
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

object JsonEncryptionSpec {
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

  //case class SensitiveNested(value: Nested) extends Sensitive[Nested]

  case class MyObject(
    id               : ObjectId,
    protectedString  : Protected[String],
    protectedBoolean : Protected[Boolean],
    protectedLong    : Protected[Long],
    protectedNested  : Protected[Nested],
    protectedOptional: Option[Protected[String]]
  )

  object MyObject {
    implicit val crypto = {
      val aesKey = {
        val aesKey = new Array[Byte](32)
        new SecureRandom().nextBytes(aesKey)
        Base64.getEncoder.encodeToString(aesKey)
      }
      val config = Configuration("crypto.key" -> aesKey)
      new CryptoGCMWithKeysFromConfig("crypto", config.underlying)
    }

    private implicit val oif = MongoFormats.Implicits.objectIdFormat
    private implicit val nf  = Nested.format

    private implicit val psf = CryptoFormats.protectedEncryptorDecryptor[String]
    private implicit val pbf = CryptoFormats.protectedEncryptorDecryptor[Boolean]
    private implicit val plf = CryptoFormats.protectedEncryptorDecryptor[Long]
    private implicit val pnf = CryptoFormats.protectedEncryptorDecryptor[Nested]
    /*
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
*/
    val format: Format[MyObject] =
      ( (__ \ "_id"              ).format[ObjectId]
      ~ (__ \ "protectedString"  ).format[Protected[String]](psf)
      ~ (__ \ "protectedBoolean" ).format[Protected[Boolean]]
      ~ (__ \ "protectedLong"    ).format[Protected[Long]]
      ~ (__ \ "protectedNested"  ).format[Protected[Nested]]
      ~ (__ \ "protectedOptional").formatNullable[Protected[String]]
      )(MyObject.apply, unlift(MyObject.unapply))
  }
}
