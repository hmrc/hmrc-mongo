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

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import org.bson.UuidRepresentation
import org.bson.codecs.UuidCodec
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters
import org.scalatest.LoneElement
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.slf4j.MDC
import uk.gov.hmrc.mdc.MdcExecutionContext
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._


import ExecutionContext.Implicits.global

class PlayMongoRepositoryMdcSpec
  extends AnyWordSpec
     with Matchers
     with LoneElement
     with ScalaFutures
     with ScalaCheckDrivenPropertyChecks {

  import PlayMongoRepositorySpec._

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds)

  private val logger = play.api.Logger(getClass)

  val mongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")
  }

  val playMongoRepository = new PlayMongoRepository[MyObject](
    mongoComponent = mongoComponent,
    collectionName = "myobject",
    domainFormat   = myObjectFormat,
    indexes        = Seq.empty,
    optSchema      = Some(myObjectSchema),
    extraCodecs    = Seq(new UuidCodec(UuidRepresentation.STANDARD))
  )

  "PlayMongoRepository.collection" should {
    "preserve MDC" in withCaptureOfLoggingFrom(logger) { logList =>
      implicit val global: ExecutionContext = // named global to override Implicit.global
        MdcExecutionContext()

      import uk.gov.hmrc.mongo.logging.ObservableFutureImplicits._

      val myObj = {
        def gen(): MyObject =
          myObjectGen.sample.getOrElse(gen())
        gen()
      }

      prepareDatabase()

      MDC.setContextMap(Map("k" -> "v").asJava)

      val res =
        (for {
            _   <- // SingleObservable#toFuture
                   playMongoRepository.collection.insertOne(myObj).toFuture()
            _   =  logger.info("")
            _   <- // SingleObservable#toFutureOption
                   playMongoRepository.collection.findOneAndReplace(filter = Filters.empty(), replacement = myObj).toFutureOption()
            res <- // Observable#toFuture
                   playMongoRepository.collection.find().toFuture()
            _   =  logger.info("")
          } yield res
        ).futureValue

      res shouldBe List(myObj)

      logList.foreach(_.getMDCPropertyMap.asScala.toMap should contain("k" -> "v"))
    }
  }

  def prepareDatabase(): Unit = {
    import org.mongodb.scala.ObservableFuture
    (for {
      exists <- MongoUtils.existsCollection(mongoComponent, playMongoRepository.collection)
      _      <- if (exists) playMongoRepository.collection.deleteMany(BsonDocument()).toFuture()
                else Future.unit
     } yield ()
    ).futureValue
  }

  def withCaptureOfLoggingFrom(logger: LogbackLogger)(body: (=> List[ILoggingEvent]) => Unit): Unit = {
    val appender = new ListAppender[ILoggingEvent]()
    appender.setContext(logger.getLoggerContext)
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.ALL)
    logger.setAdditive(true)
    body(appender.list.asScala.toList)
  }

  def withCaptureOfLoggingFrom(logger: play.api.LoggerLike)(body: (=> List[ILoggingEvent]) => Unit): Unit =
    withCaptureOfLoggingFrom(logger.logger.asInstanceOf[LogbackLogger])(body)
}
