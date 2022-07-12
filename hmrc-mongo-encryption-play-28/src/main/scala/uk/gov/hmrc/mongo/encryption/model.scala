/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.encryption


// we can't use a polymorphic implementation since Codecs are looked up by runtime type.
// the client can implement own model to support any A
// Could move Sensitive (and basic implementations) to crypto (to avoid client's model depending on mongo)
trait Sensitive[A] {
  def value: A
  override def toString() =
    "Sensitive(...)"
}

object Sensitive {
  case class SensitiveString(value: String) extends Sensitive[String]
  case class SensitiveBoolean(value: Boolean) extends Sensitive[Boolean]
  case class SensitiveLong(value: Long) extends Sensitive[Long]
  case class SensitiveDouble(value: Double) extends Sensitive[Double]
  // TODO Instant etc. ?
}
