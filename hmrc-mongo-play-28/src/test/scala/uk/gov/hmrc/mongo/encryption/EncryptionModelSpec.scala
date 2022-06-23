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

import org.bson.BsonBinarySubType
import org.mongodb.scala.bson.{BsonBinary, BsonDocument, BsonString}
import org.mongodb.scala.model.vault.{DataKeyOptions, EncryptOptions}
import org.mongodb.scala.vault.ClientEncryption
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import java.util.Base64
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import ExecutionContext.Implicits.global


class EncryptionModelSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach
     with OptionValues
     with Encryption {
  import EncryptionModelSpec._

  val databaseName: String =
    "test-" + this.getClass.getSimpleName

  override lazy val mongoComponent =
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")

  val playMongoRepository = new PlayMongoRepository[MyObject](
    mongoComponent = mongoComponent,
    collectionName = "myobject",
    domainFormat   = MyObject.format(clientEncryption),
    indexes        = Seq.empty
  )

  "Encryption" should {
    "encrypt and decrypt model" in {
      val unencryptedString = "123456789"
      (for {
         _                    <- playMongoRepository.collection.insertOne(MyObject(Sensitive(unencryptedString))).headOption()
         res                  <- playMongoRepository.collection.find().headOption().map(_.value)
         _                    =  res.sensitive.asString shouldBe unencryptedString
         // and confirm it is stored as binary, which can be decrypted manually
         raw                  <- mongoComponent.database.getCollection[BsonDocument]("myobject").find().headOption().map(_.value)
         readEncryptedField   =  raw.getBinary("sensitive")
         readUnencryptedField <- clientEncryption.decrypt(readEncryptedField).headOption().map(_.value)
         _                    =  readUnencryptedField shouldBe BsonString(unencryptedString)

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

object EncryptionModelSpec {
  // a wrapper to indicate this should be encrypted in mongo
  // (and suppress toString for logging)
  case class Sensitive(asString: String) extends AnyVal {
    override def toString(): String =
      "Sensitive(..)"
  }
  object Sensitive {
    private val encoder = Base64.getEncoder
    private val decoder = Base64.getDecoder

    private val EncryptedBinarySubtype = BsonBinarySubType.ENCRYPTED.getValue

    def format(clientEncryption: ClientEncryption) = {
      // we can't have async Formats - so we block...
      val sensitiveReads: Reads[Sensitive] =
        Reads
          .at[String](__ \ "$binary" \ "subType")
          .map(_.toByte)
          .flatMap {
            case `EncryptedBinarySubtype` =>
              Reads
                .at[String](__ \ "$binary" \ "base64")
                .map { s =>
                  Sensitive(Await.result(
                    clientEncryption.decrypt(BsonBinary(decoder.decode(s))).toFuture().map(_.asString.getValue),
                    2.seconds
                  ))
                }
            case other =>
              Reads.failed(f"Invalid BSON binary subtype for generic binary data: '$other%02x'")
          }

      val sensitiveWrites: Writes[Sensitive] = Writes { sensitive =>
          val dataKeyId           = Await.result(clientEncryption.createDataKey("local", DataKeyOptions()).toFuture(), 2.seconds)
          val encryptedFieldValue = Await.result(clientEncryption.encrypt(
                                      BsonString(sensitive.asString),
                                      EncryptOptions("AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic").keyId(dataKeyId)
                                    ).toFuture(), 2.seconds)
        Json.obj(
          "$binary" -> Json.obj(
            "base64"  -> encoder.encodeToString(encryptedFieldValue.getData),
            "subType" -> f"$EncryptedBinarySubtype%02x"
          )
        )
      }

      Format(sensitiveReads, sensitiveWrites)
    }
  }

  case class MyObject(
    sensitive: Sensitive
  )

  object MyObject {
    def format(clientEncryption: ClientEncryption) = {
      implicit val f: Format[Sensitive] = Sensitive.format(clientEncryption)
      Format.at[Sensitive](__ \ "sensitive").inmap(MyObject.apply, unlift(MyObject.unapply))
    }
  }
}
