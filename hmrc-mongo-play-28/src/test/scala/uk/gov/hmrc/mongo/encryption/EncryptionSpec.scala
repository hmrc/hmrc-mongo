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

import java.security.SecureRandom
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global


class EncryptionSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach
     with OptionValues {

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


  val clientEncryption = {
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

    // Explicit Client Side encryption
    "store" in {
      val unencryptedString = BsonString("123456789")
      (for {
         dataKeyId           <- clientEncryption.createDataKey("local", DataKeyOptions()).toFuture()
         encryptedFieldValue <- clientEncryption.encrypt(
                                   unencryptedString,
                                   EncryptOptions("AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic").keyId(dataKeyId)
                                 ).headOption()
         _                    <- collection.insertOne(BsonDocument("encryptedField" -> encryptedFieldValue)).headOption()
         doc                  <- collection.find().headOption().map(_.value)
         _                    =  println(s"read: ${doc.toJson}")
         readEncryptedField   =  doc.getBinary("encryptedField")
         readUnencryptedField <- clientEncryption.decrypt(readEncryptedField).headOption().map(_.value)
         _                    =  readUnencryptedField shouldBe unencryptedString
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
