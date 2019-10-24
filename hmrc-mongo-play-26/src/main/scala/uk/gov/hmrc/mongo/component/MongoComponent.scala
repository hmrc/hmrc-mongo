/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.component

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.mongodb.ConnectionString
import org.mongodb.scala.{MongoClient, MongoDatabase}
import play.api.{Configuration, Environment, Logger}
import play.api.inject.ApplicationLifecycle

import scala.concurrent.Future

@ImplementedBy(classOf[PlayMongoComponent])
trait MongoComponent {
  def client: MongoClient
  def database: MongoDatabase
}

@Singleton
class PlayMongoComponent @Inject() (
  configuration: Configuration,
  environment: Environment,
  lifecycle: ApplicationLifecycle
) extends MongoComponent {

  Logger.info("MongoComponent starting...")

  private val dbUri =
    configuration.get[String]("mongodb.uri")

  private val connection: ConnectionString = new ConnectionString(dbUri)

  override val client: MongoClient     = MongoClient(uri = dbUri)
  override val database: MongoDatabase = client.getDatabase(connection.getDatabase)

  Logger.debug(s"MongoComponent: MongoConnector configuration being used: $dbUri")

  lifecycle.addStopHook { () =>
    Future.successful {
      Logger.info("MongoComponent stops, closing connections...")
      client.close()
    }
  }
}
