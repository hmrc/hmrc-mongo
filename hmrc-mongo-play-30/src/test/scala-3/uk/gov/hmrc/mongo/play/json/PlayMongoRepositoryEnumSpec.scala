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

package uk.gov.hmrc.mongo.play.json

import org.mongodb.scala.{Document, ObservableFuture, ReadPreference, SingleObservableFuture, documentToUntypedDocument}
import org.mongodb.scala.bson.BsonDocument
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.json._
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

import ExecutionContext.Implicits.global

class PlayMongoRepositoryEnumSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with ScalaCheckDrivenPropertyChecks
     with BeforeAndAfterAll:
  import PlayMongoRepositoryEnumSpec._

  import org.scalacheck.Shrink.shrinkAny // disable shrinking here - will just generate invalid inputs

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds)

  val mongoComponent =
    val databaseName: String = "test-" + this.getClass.getSimpleName
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")

  "Codecs.playFormatCodecsBuilder" should:
    "enable registering codecs for all subtypes" in:
      val playMongoRepository = new PlayMongoRepository[Sum](
        mongoComponent = mongoComponent,
        collectionName = "sum",
        domainFormat   = sumFormat,
        indexes        = Seq.empty,
        extraCodecs    = Codecs.playFormatSumCodecs(sumFormat)
      )
      forAll(sumGen): sum =>
        prepareDatabase(playMongoRepository)

        playMongoRepository.collection.insertOne(sum).toFuture().futureValue

        val writtenObj = playMongoRepository.collection.find().toFuture().futureValue
        writtenObj shouldBe List(sum)

    "enable use of subtypes in filters" in:
      import org.mongodb.scala.model.Filters
      import play.api.libs.functional.syntax.toInvariantFunctorOps
      case class Wrapper(sum: Sum)
      val wrapperFormat = Format.at[Sum](__ \ "sum")(sumFormat).inmap(Wrapper.apply, _.sum)
      val playMongoRepository = new PlayMongoRepository[Wrapper](
        mongoComponent = mongoComponent,
        collectionName = "wrapper",
        domainFormat   = wrapperFormat,
        indexes        = Seq.empty,
        extraCodecs    = Codecs.playFormatSumCodecs(sumFormat)
      )

      forAll(sumGen): sum =>
        prepareDatabase(playMongoRepository)

        playMongoRepository.collection.insertOne(Wrapper(sum)).toFuture().futureValue

        val result = playMongoRepository.collection.find(Filters.equal("sum", sum)).toFuture().futureValue
        result shouldBe List(Wrapper(sum))

  def prepareDatabase(playMongoRepository: PlayMongoRepository[_]): Unit =
    (for
       exists <- MongoUtils.existsCollection(mongoComponent, playMongoRepository.collection)
       _      <- if exists then playMongoRepository.collection.deleteMany(BsonDocument()).toFuture()
                 else Future.unit
     yield ()
    ).futureValue

  override protected def beforeAll(): Unit =
    super.beforeAll()
    updateIndexPreference(requireIndexedQuery = false).futureValue

  protected def updateIndexPreference(requireIndexedQuery: Boolean): Future[Boolean] =
    val notablescan = if requireIndexedQuery then 1 else 0

    mongoComponent.client
      .getDatabase("admin")
      .withReadPreference(ReadPreference.primaryPreferred())
      .runCommand(Document(
        "setParameter" -> 1,
        "notablescan" -> notablescan
      ))
      .toFuture()
      .map(_.getBoolean("was"))

object PlayMongoRepositoryEnumSpec:

  enum Sum(val asString: String):
    case Sum1(key1: String) extends Sum(key1)
    case Sum2() extends Sum("key2")
    case Sum3   extends Sum("key3")

  val sum1Formats: OFormat[Sum.Sum1] = Json.format[Sum.Sum1]
  val sum2Formats: OFormat[Sum.Sum2] = Json.format[Sum.Sum2]

  val sumFormat =
    val reads: Reads[Sum] =
      (js: JsValue) => (js \ "type").validate[String].flatMap:
        case "sum1" => sum1Formats.reads(js)
        case "sum2" => sum2Formats.reads(js)
        case "sum3" => JsSuccess(Sum.Sum3)
        case other  => JsError(s"Unsupported type $other")

    val writes: OWrites[Sum] =
      (p: Sum) => p match
        case sum1: Sum.Sum1 => sum1Formats.transform((_: JsObject) + ("type" -> JsString("sum1"))).writes(sum1)
        case sum2: Sum.Sum2 => sum2Formats.transform((_: JsObject) + ("type" -> JsString("sum2"))).writes(sum2)
        case Sum.Sum3       => Json.obj("type" -> "sum3")

    OFormat(reads, writes)

  def sumGen =
    for
      i <- Gen.choose(0, 2)
      s <- Arbitrary.arbitrary[String]
    yield
      i match
        case 0 => Sum.Sum1(s)
        case 1 => Sum.Sum2()
        case _ => Sum.Sum3
