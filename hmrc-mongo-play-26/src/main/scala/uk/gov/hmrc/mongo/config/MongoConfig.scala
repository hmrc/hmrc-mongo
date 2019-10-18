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

package uk.gov.hmrc.mongo.config

import com.google.inject.Inject
import play.api.{Configuration, Environment, Mode}

class MongoConfig @Inject()(environment: Environment, configuration: Configuration) {

  private val mongoConfig: Configuration = configuration
    .getOptional[Configuration]("mongodb")
    .orElse(configuration.getOptional[Configuration](s"${environment.mode}.mongodb"))
    .orElse(configuration.getOptional[Configuration](s"${Mode.Dev}.mongodb"))
    .getOrElse(sys.error("The config is missing the required 'mongodb' configuration block"))

  val uri: String = mongoConfig
    .getOptional[String]("uri")
    .getOrElse(sys.error("mongodb.uri not defined"))
}
