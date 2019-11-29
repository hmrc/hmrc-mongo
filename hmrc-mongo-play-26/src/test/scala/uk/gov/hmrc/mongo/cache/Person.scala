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
import play.api.libs.functional.syntax.{unlift, _}
import play.api.libs.json.{OFormat, __}

final case class Person(
  name: String,
  age: Int,
  sex: String
)

object Person {
  implicit val format: OFormat[Person] =
    ((__ \ "name").format[String]
      ~ (__ \ "age").format[Int]
      ~ (__ \ "sex").format[String])(Person.apply, unlift(Person.unapply))
}
