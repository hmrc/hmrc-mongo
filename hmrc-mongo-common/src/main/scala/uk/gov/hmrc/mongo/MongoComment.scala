/*
 * Copyright 2025 HM Revenue & Customs
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

import org.mongodb.scala.bson.BsonDocument

object MongoComment {

  /** Add as a comment to operations to help log analysis tools
    * understand where the lack of an index is expected.
    * E.g. `collection.find().comment(NoIndexRequired)`
    */
  val NoIndexRequired: BsonDocument = BsonDocument("no-index-required"-> true)
}
