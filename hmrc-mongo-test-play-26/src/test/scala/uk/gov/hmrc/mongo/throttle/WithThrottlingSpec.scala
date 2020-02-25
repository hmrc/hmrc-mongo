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

package uk.gov.hmrc.mongo.throttle

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.Configuration

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class WithThrottlingSpec extends AnyWordSpecLike with Matchers with ScalaFutures {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(60.seconds)

  "WithThrottling.throttling" should {

    "not exceed throttleSize" in {
      val throttleSize = 2
      val config = new ThrottleConfig(Configuration("mongodb.throttle.size" -> throttleSize))
      // a def, since it shouldn't matter how many WithThrottling we use, as long as we have one config.
      def throttle = new WithThrottling {
        override def throttleConfig = config
      }

      val counter = new java.util.concurrent.atomic.AtomicInteger()

      val range = 1 to 100

      Future.traverse(range) { _ =>
        throttle.throttling(counter){ counter =>
          Future {
            val count = counter.incrementAndGet
            if (count > throttleSize) sys.error(s"Count $count exceeded $throttleSize")
            Thread.sleep(100)
            counter.getAndDecrement
            ()
          }
        }
      }.futureValue shouldBe range.map(_ => ())
    }
  }
}