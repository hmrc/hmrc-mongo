/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.mongo

import java.util.concurrent.Executors

import _root_.play.api.libs.json.__
import _root_.play.api.libs.functional.syntax._
import com.mongodb.MongoServerException
import com.mongodb.client.model.ReturnDocument
import org.mongodb.scala.model.{Filters, FindOneAndUpdateOptions, Updates}
import org.scalatest.{AppendedClues, BeforeAndAfterEach}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Retryable
import org.scalacheck.Arbitrary._
import uk.gov.hmrc.mongo.MongoUtils.DuplicateKey
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import scala.collection.parallel.ExecutionContextTaskSupport
import scala.collection.parallel.immutable.ParRange
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class DuplicateErrorOnUpsertSpec
  extends WordSpecLikeWithRetries
     with Matchers
     with BeforeAndAfterEach
     with ScalaFutures
     with IntegrationPatience
     with AppendedClues {

    val mongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")
  }

  case class MyObject(
    id   : String,
    data : Long
  )
  val myObjectFormat =
    ( (__ \ "_id" ).format[String]
    ~ (__ \ "data").format[Long]
    )(MyObject.apply _, unlift(MyObject.unapply))

  val playMongoRepository = new PlayMongoRepository[MyObject](
    mongoComponent = mongoComponent,
    collectionName = "myobject",
    domainFormat   = myObjectFormat,
    indexes        = Seq.empty,
    optSchema      = None
  )

  def upsert(id: String, data: Long): Future[MyObject] =
    playMongoRepository.collection
      .findOneAndUpdate(
        filter = Filters.equal("_id", id),
        update = Updates.combine(
                  Updates.set("data", data),
                  Updates.setOnInsert("_id", id)
                ),
        options = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
      )
      .toFuture()


  // MongoUtils.retryOnDuplicateKey is provided as a wrapper to retry for DuplicateErrors.
  //
  // This test checks that this error still ocurrs, and asserts that it leads to the expected exception.
  //
  // This test is tagged as Retryable and will be attempted a few times, as the race condition isn't triggered always,
  // but can be quite easily reproduced over a number of iterations
  "upsert" should {
    "fail with DuplicateError" taggedAs Retryable in {
      val id = "id_" + arbitrary[Long].sample.get.toString // Use a random ID
      val data = arbitrary[Long].sample.get

      val fixedPool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(50)) // Use a lot of threads
      val taskSupport = new ExecutionContextTaskSupport(fixedPool)

      val expectedToFail: Future[List[MyObject]] =
        Future.sequence {
          val parRange = new ParRange(0 to 1000)
          parRange.tasksupport = taskSupport
          parRange.map(_ => upsert(id, data)).toList
        }(implicitly, executor = fixedPool)

      whenReady(expectedToFail.failed) { ex =>
        ex shouldBe a[MongoServerException] withClue "If no exception was thrown, the test was not aggressive enough to trigger the race condition on upsert"
        DuplicateKey.unapply(ex.asInstanceOf[MongoServerException]) shouldBe defined
      }
    }
  }
}
