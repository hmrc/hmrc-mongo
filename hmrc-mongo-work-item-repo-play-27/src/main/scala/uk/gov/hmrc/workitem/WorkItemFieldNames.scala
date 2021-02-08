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

package uk.gov.hmrc.workitem

  /** Customisable names of the internal fields.
    * e.g.
    * {{{
    * new WorkItemFieldNames {
    * val receivedAt   = "receivedAt"
    * val updatedAt    = "updatedAt"
    * val availableAt  = "receivedAt"
    * val status       = "status"
    * val id           = "_id"
    * val failureCount = "failureCount"
    * }
    * }}}
    */

trait WorkItemFieldNames  {
  val receivedAt, availableAt, updatedAt, status, id, failureCount: String
}
