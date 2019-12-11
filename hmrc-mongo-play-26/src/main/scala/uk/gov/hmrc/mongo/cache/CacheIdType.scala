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

package uk.gov.hmrc.mongo.cache

import play.api.mvc.Request
import scala.concurrent.{ExecutionContext, Future}

trait CacheIdType[CacheId] {
  def run: CacheId => String
}

object CacheIdType {
  object SimpleCacheId extends CacheIdType[String] {
    override def run: String => String = identity
  }

  object SessionCacheId extends CacheIdType[Request[Any]] {
    override def run: Request[Any] => String =
      _.session
        .get("sessionId")
        .getOrElse(throw NoSessionException)

    case object NoSessionException extends Exception("Could not find sessionId")
  }

  case class SessionUuid(sessionIdKey: String) extends CacheIdType[Request[Any]] {
    override def run: Request[Any] => String =
      _.session
        .get(sessionIdKey)
        .getOrElse(java.util.UUID.randomUUID.toString)
  }
}
