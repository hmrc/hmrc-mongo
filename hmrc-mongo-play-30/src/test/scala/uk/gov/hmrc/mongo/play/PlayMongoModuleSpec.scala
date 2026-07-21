/*
 * Copyright 2026 HM Revenue & Customs
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

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.{Configuration, Environment, Mode}
import play.api.inject.guice.GuiceApplicationBuilder

import scala.annotation.tailrec

class PlayMongoModuleSpec extends AnyWordSpec with Matchers {

  "PlayMongoModule" should {
    "not eagerly instantiate PlayMongoComponent in test mode" in {
      val app = applicationBuilder(Mode.Test).build()

      app.stop()
    }

    "eagerly instantiate PlayMongoComponent outside test mode" in {
      val exception =
        intercept[Exception] {
          val app = applicationBuilder(Mode.Dev).build()

          app.stop()
        }

      exceptionMessages(exception).mkString(" ") should include("mongodb://")
    }
  }

  private def applicationBuilder(mode: Mode): GuiceApplicationBuilder =
    new GuiceApplicationBuilder(environment = Environment.simple(mode = mode))
      .loadConfig(env => Configuration.load(env.classLoader, System.getProperties, Map.empty, true))
      .configure(playMongoModuleConfiguration)

  private val playMongoModuleConfiguration: Configuration =
    Configuration(
      ConfigFactory
        .parseString(
          """
            |play.modules.enabled += "uk.gov.hmrc.mongo.play.PlayMongoModule"
            |mongodb.uri = "not-a-mongo-uri"
            |""".stripMargin
        )
        .withFallback(Configuration.reference.underlying)
        .resolve()
    )

  private def exceptionMessages(t: Throwable): Seq[String] = {
    @tailrec
    def loop(current: Throwable, messages: List[String]): List[String] =
      if (current == null) messages.reverse
      else loop(current.getCause, Option(current.getMessage).fold(messages)(_ :: messages))

    loop(t, List.empty)
  }
}
