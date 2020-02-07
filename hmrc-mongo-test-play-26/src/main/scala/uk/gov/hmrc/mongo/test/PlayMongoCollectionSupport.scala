/*
 * Copyright 2020 HM Revenue & Customs
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

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.IndexModel
import uk.gov.hmrc.mongo.play.json.PlayMongoCollection

trait PlayMongoCollectionSupport[A] extends MongoCollectionSupport {
  protected def collection: PlayMongoCollection[A]

  override protected lazy val collectionName: String          = collection.collectionName
  override protected lazy val indexes: Seq[IndexModel]        = collection.indexes
  override protected lazy val optSchema: Option[BsonDocument] = collection.optSchema
}
