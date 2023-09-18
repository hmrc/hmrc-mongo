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

package uk.gov.hmrc.mongo.play

import com.google.inject.AbstractModule
import com.mongodb.ConnectionString
import org.mongodb.scala.{MongoClient, MongoDatabase}
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logger}
import uk.gov.hmrc.mongo.MongoComponent

import javax.inject.{Inject, Singleton}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

@Singleton
class PlayMongoComponent @Inject() (
  configuration: Configuration,
  lifecycle    : ApplicationLifecycle
)(implicit
  ec           : ExecutionContext
) extends MongoComponent {

  private val logger = Logger(getClass)

  logger.info("MongoComponent starting...")

  private val mongoUri =
    configuration.get[String]("mongodb.uri")

  logger.info(s"MongoComponent: MongoConnector configuration being used: $mongoUri")

  override lazy val client: MongoClient =
    MongoClient(uri = mongoUri)

  override lazy val initTimeout =
    configuration.get[FiniteDuration]("hmrc.mongo.init.timeout")

  override val database: MongoDatabase = {
    val database = client.getDatabase(new ConnectionString(mongoUri).getDatabase)
    try {
      Await.result(database.listCollectionNames().toFuture().map { collectionNames =>
        logger.info(s"Existing collections:${collectionNames.mkString("\n  ", "\n  ", "")}")
      }, initTimeout)
    } catch {
      case t: Throwable => logger.error(s"Failed to connect to Mongo: ${t.getMessage}", t); throw t
    }
    database
  }

  lifecycle.addStopHook { () =>
    Future.successful {
      logger.info("MongoComponent stops, closing connections...")
      client.close()
    }
  }
}

class PlayMongoModule extends AbstractModule {
  override def configure(): Unit =
    bind(classOf[MongoComponent]).to(classOf[PlayMongoComponent]).asEagerSingleton()
}
