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

import org.mongodb.scala.bson.{BsonDocument, BsonString}
import org.mongodb.scala.model.vault.{DataKeyOptions, EncryptOptions}
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global


class EncryptionSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach
     with OptionValues
     with Encryption {

  val databaseName: String =
    "test-" + this.getClass.getSimpleName

  override lazy val mongoComponent =
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")

  val collection =
    mongoComponent.database.getCollection[BsonDocument]("myobject")

  "Encryption" should {
    // Explicit Client Side encryption
    "encrypt and decrypt manually" in {
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
                else Future.unit
     } yield ()
    ).futureValue

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    prepareDatabase()
  }
}
