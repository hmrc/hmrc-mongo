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
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoFormats

import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import java.security.SecureRandom
import java.util.Base64


class EncryptionCodecSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach
     with OptionValues {
  import EncryptionCodecSpec._

  val databaseName: String =
    "test-" + this.getClass.getSimpleName

  val mongoComponent =
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")

  val encrypter =
    new Encrypter(
      associatedDataPath  = __ \ "_id",
      encryptedFieldPaths = Seq(__ \ "sensitive"),
      aesKey              = { val aesKey = new Array[Byte](32)
                              new SecureRandom().nextBytes(aesKey)
                              Base64.getEncoder.encodeToString(aesKey)
                            }
    )

  val playMongoRepository = new PlayMongoRepository[MyObject](
    mongoComponent   = mongoComponent,
    collectionName   = "myobject",
    domainFormat     = MyObject.format,
    indexes          = Seq.empty,
    encoderTransform = encrypter.encrypt,
    decoderTransform = encrypter.decrypt
  )

  "Encryption" should {
    "encrypt and decrypt model" in {
      val unencryptedString = "123456789"
      (for {
         _                    <- playMongoRepository.collection.insertOne(MyObject(
                                   id        = ObjectId.get().toString,
                                   sensitive = unencryptedString
                                 )).headOption()
         res                  <- playMongoRepository.collection.find().headOption().map(_.value)
         _                    =  res.sensitive shouldBe unencryptedString
         // and confirm it is stored as an EncryptedValue
         raw                  <- mongoComponent.database.getCollection[BsonDocument]("myobject").find().headOption().map(_.value)
         readEncryptedField   =  println(EncryptedValue.format.reads(Json.parse(raw.getDocument("sensitive").toJson)))

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

object EncryptionCodecSpec {
  case class MyObject(
    id       : String,
    sensitive: String
  )

  object MyObject {
    implicit val oif = MongoFormats.Implicits.objectIdFormat
    val format =
      ( (__ \ "_id"      ).format[String]
      ~ (__ \ "sensitive").format[String]
      )(MyObject.apply, unlift(MyObject.unapply))
  }
}
