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

package uk.gov.hmrc.mongo.test

import com.mongodb.MongoQueryException
import org.scalatest._
import scala.concurrent.duration.DurationInt

trait DefaultMongoCollectionSupport extends CleanMongoCollectionSupport with IndexedMongoQueriesSupport {
  this: TestSuite =>

  override implicit val patienceConfig = PatienceConfig(timeout = 30.seconds, interval = 100.millis)
}

trait CleanMongoCollectionSupport extends MongoCollectionSupport with BeforeAndAfterEach {
  this: TestSuite =>

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    prepareDatabase()
  }
}

trait IndexedMongoQueriesSupport extends MongoCollectionSupport with BeforeAndAfterAll with TestSuiteMixin {
  this: TestSuite =>

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    updateIndexPreference(onlyAllowIndexedQuery = true)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    updateIndexPreference(onlyAllowIndexedQuery = false)
  }

  abstract override def withFixture(test: NoArgTest): Outcome =
    super.withFixture(test) match {
      case Failed(e: MongoQueryException) if e.getMessage contains "No query solutions" =>
        Failed("Mongo query could not be satisfied by an index:\n" + e.getMessage, e)
      case other => other
    }
}
