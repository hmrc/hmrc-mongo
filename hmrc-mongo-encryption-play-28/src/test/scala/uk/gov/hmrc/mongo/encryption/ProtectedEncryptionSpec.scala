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
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.Configuration
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoFormats
import uk.gov.hmrc.crypto.{Crypted, CryptoGCMWithKeysFromConfig, Protected}
import uk.gov.hmrc.crypto.json.CryptoFormats


import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import java.security.SecureRandom
import java.util.Base64

/**
  * Protected defined as `case class Protected[T](t: T)` i.e. generic - we can't look up a codec for this
  * So we can't update (or filter by) a protected leaf field.
  *
  */
class ProtectedEncryptionSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach
     with OptionValues {
  import ProtectedEncryptionSpec._

  val databaseName: String =
    "test-" + this.getClass.getSimpleName

  val mongoComponent =
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")

  val myObjectSchema =
    // schema doesn't catch if the string is actually encrypted
    // this should be tested for additionally
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
    optSchema        = Some(myObjectSchema)
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

object ProtectedEncryptionSpec {
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

    private implicit val psf = CryptoFormats.protectedEncrypterDecrypter[String]
    private implicit val pbf = CryptoFormats.protectedEncrypterDecrypter[Boolean]
    private implicit val plf = CryptoFormats.protectedEncrypterDecrypter[Long]
    private implicit val pnf = CryptoFormats.protectedEncrypterDecrypter[Nested]

    val format: Format[MyObject] =
      ( (__ \ "_id"              ).format[ObjectId]
      ~ (__ \ "protectedString"  ).format[Protected[String]]
      ~ (__ \ "protectedBoolean" ).format[Protected[Boolean]]
      ~ (__ \ "protectedLong"    ).format[Protected[Long]]
      ~ (__ \ "protectedNested"  ).format[Protected[Nested]]
      ~ (__ \ "protectedOptional").formatNullable[Protected[String]]
      )(MyObject.apply, unlift(MyObject.unapply))
  }
}
