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

package uk.gov.hmrc.mongo.play.json

import akka.util.ByteString
import org.bson.types.ObjectId
import org.joda.{time => jot}
import org.mongodb.scala.{Document, ReadPreference}
import org.mongodb.scala.bson.{BsonDocument, BsonString}
import org.mongodb.scala.model.{Filters, Updates}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import java.util.UUID
import java.{time => jat}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

import ExecutionContext.Implicits.global

class PlayMongoRepositoryADTSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with ScalaCheckDrivenPropertyChecks
     with BeforeAndAfterAll {

  import Codecs.toBson
  import PlayMongoRepositoryADTSpec._

  import org.scalacheck.Shrink.shrinkAny // disable shrinking here - will just generate invalid inputs

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds)

  val mongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")
  }

  val playMongoRepository = new PlayMongoRepository[Sum](
    mongoComponent = mongoComponent,
    collectionName = "sum",
    domainFormat   = sumFormat,
    indexes        = Seq.empty,
    extraCodecs    = Seq(
                       Codecs.playFormatSumCodec[Sum, Sum1](sumFormat),
                       Codecs.playFormatSumCodec[Sum, Sum2](sumFormat)
                     )
  )

  "PlayMongoRepository.collection" should {
    "read and write object with fields" in {
      forAll(sumGen) { sum =>
        prepareDatabase()

        val result = playMongoRepository.collection.insertOne(sum).toFuture()
        result.futureValue.wasAcknowledged shouldBe true

        val writtenObj = playMongoRepository.collection.find().toFuture()
        writtenObj.futureValue shouldBe List(sum)
      }
    }
  }

  def prepareDatabase(): Unit =
    (for {
      exists <- MongoUtils.existsCollection(mongoComponent, playMongoRepository.collection)
      _      <- if (exists) playMongoRepository.collection.deleteMany(BsonDocument()).toFuture()
                else Future.unit
     } yield ()
    ).futureValue

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    updateIndexPreference(requireIndexedQuery = false).futureValue
  }

  protected def updateIndexPreference(requireIndexedQuery: Boolean): Future[Boolean] = {
    val notablescan = if (requireIndexedQuery) 1 else 0

    mongoComponent.client
      .getDatabase("admin")
      .withReadPreference(ReadPreference.primaryPreferred())
      .runCommand(Document(
        "setParameter" -> 1,
        "notablescan" -> notablescan
      ))
      .toFuture()
      .map(_.getBoolean("was"))
  }
}

object PlayMongoRepositoryADTSpec {

  sealed trait Sum
  case class Sum1(key1: String) extends Sum
  case class Sum2(key2: String) extends Sum

  val sum1Formats: OFormat[Sum1] = Json.format[Sum1]
  val sum2Formats: OFormat[Sum2] = Json.format[Sum2]

  val sumFormat = {
    val reads: Reads[Sum] =
      (js: JsValue) => (js \ "type").validate[String].flatMap {
        case "sum1" => sum1Formats.reads(js)
        case "sum2" => sum2Formats.reads(js)
        case other  => JsError(s"Unsupported type $other")
      }

    val writes: OWrites[Sum] =
      (p: Sum) => p match {
        case sum1: Sum1 => sum1Formats.transform((_: JsObject) + ("type" -> JsString("sum1"))).writes(sum1)
        case sum2: Sum2 => sum2Formats.transform((_: JsObject) + ("type" -> JsString("sum2"))).writes(sum2)
      }

    OFormat(reads, writes)
  }

  def sumGen =
    for {
      isSum1 <- Arbitrary.arbitrary[Boolean]
      s      <- Arbitrary.arbitrary[String]
    } yield
      if (isSum1) Sum1(s) else Sum2(s)
}
