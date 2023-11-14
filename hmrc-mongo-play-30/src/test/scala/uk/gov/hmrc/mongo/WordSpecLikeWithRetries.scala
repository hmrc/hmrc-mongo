/*
 * Copyright 2023 HM Revenue & Customs
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

import org.scalatest.{BeforeAndAfterEach, Failed, Outcome, Retries}
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.{Logger, LoggerFactory}

abstract class WordSpecLikeWithRetries extends AnyWordSpecLike with Retries {
  this: BeforeAndAfterEach =>

  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  // Number of times to rerun the test before giving up
  val retries = 50

  override def withFixture(test: NoArgTest): Outcome =
    if (isRetryable(test))
      withFixture(test, retries)
    else
      super.withFixture(test)

  def withFixture(test: NoArgTest, count: Int): Outcome = {
    beforeEach() // Be completely sure we're starting fresh, in the retried tests
    logger.info(s"Running fixture, remaining retries: $count")
    val outcome = super.withFixture(test)
    outcome match {
      case Failed(_) => if (count == 1)  super.withFixture(test) else withFixture(test, count - 1)
      case other => other
    }
  }
}
