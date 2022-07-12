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
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.crypto.SecureGCMCipher2
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoFormats

import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import java.security.SecureRandom
import java.util.Base64
import Sensitive._


class ADEncrypterSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach
     with OptionValues {
  import ADEncrypterSpec._

  val databaseName: String =
    "test-" + this.getClass.getSimpleName

  val mongoComponent =
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")

  val aesKey = {
    val aesKey = new Array[Byte](32)
    new SecureRandom().nextBytes(aesKey)
    Base64.getEncoder.encodeToString(aesKey)
  }

  val encrypter =
    new ADEncrypter(new SecureGCMCipher2(aesKey))(
      associatedDataPath  = __ \ "_id"
    )


  val myObjectSchema =
    // schema doesn't catch if there are extra fields (e.g. if encryption has merged with unencrypted object)
    BsonDocument(
      """
      { bsonType: "object"
      , required: [ "_id", "sensitiveString", "sensitiveBoolean", "sensitiveLong", "sensitiveNested" ]
      , properties:
        { _id              : { bsonType: "string" }
        , sensitiveString  : { bsonType: "object", required: [ "value", "nonce" ], properties: { value: { bsonType: "string" }, "nonce": { bsonType: "string"} } }
        , sensitiveBoolean : { bsonType: "object", required: [ "value", "nonce" ], properties: { value: { bsonType: "string" }, "nonce": { bsonType: "string"} } }
        , sensitiveLong    : { bsonType: "object", required: [ "value", "nonce" ], properties: { value: { bsonType: "string" }, "nonce": { bsonType: "string"} } }
        , sensitiveNested  : { bsonType: "object", required: [ "value", "nonce" ], properties: { value: { bsonType: "string" }, "nonce": { bsonType: "string"} } }
        , sensitiveOptional: { bsonType: "object", required: [ "value", "nonce" ], properties: { value: { bsonType: "string" }, "nonce": { bsonType: "string"} } }
        }
      }
      """
    )

  val playMongoRepository = new PlayMongoRepository[MyObject](
    mongoComponent   = mongoComponent,
    collectionName   = "myobject",
    domainFormat     = MyObject.format,
    indexes          = Seq.empty,
    jsonTransformer  = encrypter,
    optSchema        = Some(myObjectSchema)
  )

  "Encryption" should {
    def shouldBeEncrypted(raw: BsonDocument, path: JsPath, required: Boolean = true): Unit =
      path(Json.parse(raw.toJson)) match {
        case Seq(x)        => x.as[JsObject].keys shouldBe Set("value", "nonce")
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
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveBoolean" )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveLong"    )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveNested"  )
         _   =  shouldBeEncrypted(raw, __ \ "sensitiveOptional", required = false)
       } yield ()
      ).futureValue
    }

    // Attention! Cannot update (or search by) a leaf with associated data
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

object ADEncrypterSpec {
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
    implicit val oif = MongoFormats.Implicits.objectIdFormat
    import ADEncrypter.Implicits._
    implicit val snf: OFormat[SensitiveNested] = {
      implicit val nf  = Nested.format
      ADEncrypter.format[Nested , SensitiveNested](SensitiveNested.apply, SensitiveNested.unapply)
    }
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
