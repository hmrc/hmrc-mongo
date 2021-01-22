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

package uk.gov.hmrc.mongo.play

import com.google.inject.{AbstractModule, Inject, Singleton}
import com.mongodb.ConnectionString
import org.mongodb.scala.{MongoClient, MongoDatabase}
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logger}
import uk.gov.hmrc.mongo.MongoComponent

import scala.concurrent.Future

@Singleton
class PlayMongoComponent @Inject() (
  configuration: Configuration,
  lifecycle: ApplicationLifecycle
) extends MongoComponent {

  private val logger = Logger(getClass)

  logger.info("MongoComponent starting...")

  private val mongoUri =
    configuration.get[String]("mongodb.uri")

  override val client: MongoClient     = MongoClient(uri = mongoUri)
  override val database: MongoDatabase = client.getDatabase((new ConnectionString(mongoUri)).getDatabase)

  logger.debug(s"MongoComponent: MongoConnector configuration being used: $mongoUri")

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
