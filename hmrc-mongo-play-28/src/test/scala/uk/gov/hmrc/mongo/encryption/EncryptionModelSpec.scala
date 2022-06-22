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

import com.mongodb.ClientEncryptionSettings
import org.mongodb.scala.{ConnectionString, MongoClientSettings, MongoNamespace}
import org.mongodb.scala.bson.{BsonDocument, BsonString}
import org.mongodb.scala.model.{Filters, Indexes, IndexOptions}
import org.mongodb.scala.model.vault.{DataKeyOptions, EncryptOptions}
import org.mongodb.scala.vault.ClientEncryptions
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}
import play.api.libs.functional.syntax._
import play.api.libs.json._


import java.security.SecureRandom
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global
import org.mongodb.scala.vault.ClientEncryption
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import java.util.Base64
import org.bson.BsonBinarySubType
import org.mongodb.scala.bson.BsonBinary
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository


class EncryptionModelSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach
     with OptionValues {
  import EncryptionModelSpec._

  val databaseName: String =
    "test-" + this.getClass.getSimpleName

  val mongoComponent =
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")

  val collectionName = "myobject"
  val collection =
    mongoComponent.database.getCollection[BsonDocument](collectionName = collectionName)

  val keyVaultNamespace = new MongoNamespace(s"$databaseName.keyVault")
  // The following exists to add `keyAltNames` unique index, which we are not using
  // and to wipe any existing keys (would be recreated on each instance restart?)
  // otherwise, the number of entries would grow on each instance restart
  val keyVaultCollection = {

    // Set up the key vault for this example
    val keyVaultCollection =
      mongoComponent.database
      .getCollection(keyVaultNamespace.getCollectionName)

    //keyVaultCollection.drop().headOption()

    // Ensure that two data keys cannot share the same keyAltName.
    keyVaultCollection.createIndex(
      Indexes.ascending("keyAltNames"),
      new IndexOptions().unique(true).partialFilterExpression(Filters.exists("keyAltNames"))
    )
  }


  val clientEncryption: ClientEncryption = {
    // This would have to be the same master key as was used to create the encryption key
    // using a local key is only for development since it is stored in mongo
    // aws provider will be more appropriate for deploying
    val localMasterKey = {
      val localMasterKey = new Array[Byte](96)
      new SecureRandom().nextBytes(localMasterKey)
      localMasterKey

      // must be 96 bytes
      //"O6y5Pm0g2ZTuwm%r7tdFM+ADGdCakr&j0wNWKBmech+JjYadveJGPY&veeyzO7Sk&yaRfgG%$Uke!ajR4kS4q$%26N=uZKUm".getBytes
    }

    val kmsProviders =
      Map("local" ->
        Map[String, AnyRef]("key" ->
          localMasterKey
        ).asJava
      ).asJava



    // Create the ClientEncryption instance
    val clientEncryptionSettings = ClientEncryptionSettings.builder()
      .keyVaultMongoClientSettings(
        MongoClientSettings.builder()
          .applyConnectionString(ConnectionString("mongodb://localhost"))
          .build()
      )
      .keyVaultNamespace(keyVaultNamespace.getFullName)
      .kmsProviders(kmsProviders)
      .build()

    ClientEncryptions.create(clientEncryptionSettings)
  }

  "Encryption" should {

    val playMongoRepository = new PlayMongoRepository[MyObject](
      mongoComponent = mongoComponent,
      collectionName = "myobject",
      domainFormat   = MyObject.format(clientEncryption),
      indexes        = Seq.empty
    )


    // Explicit Client Side encryption
    "store" in {
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
      exists <- MongoUtils.existsCollection(mongoComponent, collection)
      _      <- if (exists) collection.deleteMany(BsonDocument()).toFuture()
                // until Mongo 4.4 implicit collection creation (on insert/upsert) will fail when in a transaction
                // create explicitly
                else mongoComponent.database.createCollection(collectionName).toFuture()
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
