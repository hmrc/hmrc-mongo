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

package uk.gov.hmrc.mongo.workitem

import org.bson.BsonString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsError, JsString, Json}
import uk.gov.hmrc.mongo.play.json.Codecs

class ProcessingStatusSpec extends AnyWordSpec with Matchers {
  import ProcessingStatus._

  "reading processing status from JSON" should {
    implicit val psf = ProcessingStatus.format
    "handle ToDo" in {
      Json.parse("\"todo\"").as[ProcessingStatus] should be(ToDo)
    }
    "handle InProgress" in {
      Json.parse("\"in-progress\"").as[ProcessingStatus] should be(InProgress)
    }
    "handle Succeeded" in {
      Json.parse("\"succeeded\"").as[ProcessingStatus] should be(Succeeded)
    }
    "handle Failed" in {
      Json.parse("\"failed\"").as[ProcessingStatus] should be(Failed)
    }
    "handle PermanentlyFailed" in {
      Json.parse("\"permanently-failed\"").as[ProcessingStatus] should be(PermanentlyFailed)
    }
    "handle Ignored" in {
      Json.parse("\"ignored\"").as[ProcessingStatus] should be(Ignored)
    }
    "handle Duplicate" in {
      Json.parse("\"duplicate\"").as[ProcessingStatus] should be(Duplicate)
    }
    "handle Cancelled" in {
      Json.parse("\"cancelled\"").as[ProcessingStatus] should be(Cancelled)
    }
    "error reading other values" in {
      Json.parse("\"In Progress\"").validate[ProcessingStatus] should be(a[JsError])
    }
  }

  "writing processing status to JSON" should {
    implicit val psf = ProcessingStatus.format
    "handle ToDo" in {
      Json.toJson(ToDo: ProcessingStatus) should be (JsString("todo"))
    }
    "handle InProgress" in {
      Json.toJson(InProgress: ProcessingStatus) should be (JsString("in-progress"))
    }
    "handle Succeeded" in {
      Json.toJson(Succeeded: ProcessingStatus) should be (JsString("succeeded"))
    }
    "handle Failed" in {
      Json.toJson(Failed: ProcessingStatus) should be (JsString("failed"))
    }
    "handle PermanentlyFailed" in {
      Json.toJson(PermanentlyFailed: ProcessingStatus) should be (JsString("permanently-failed"))
    }
    "handle Ignored" in {
      Json.toJson(Ignored: ProcessingStatus) should be (JsString("ignored"))
    }
    "handle Duplicate" in {
      Json.toJson(Duplicate: ProcessingStatus) should be (JsString("duplicate"))
    }
    "handle Cancelled" in {
      Json.toJson(Cancelled: ProcessingStatus) should be (JsString("cancelled"))
    }
  }

  "writing processing status to BSON" should {
    "handle ToDo" in {
      ProcessingStatus.toBson(ToDo) shouldBe new BsonString("todo")
    }
    "handle InProgress" in {
      ProcessingStatus.toBson(InProgress) shouldBe new BsonString("in-progress")
    }
    "handle Succeeded" in {
      ProcessingStatus.toBson(Succeeded) shouldBe new BsonString("succeeded")
    }
    "handle Failed" in {
      ProcessingStatus.toBson(Failed) shouldBe new BsonString("failed")
    }
    "handle PermanentlyFailed" in {
      ProcessingStatus.toBson(PermanentlyFailed) shouldBe new BsonString("permanently-failed")
    }
    "handle Ignored" in {
      ProcessingStatus.toBson(Ignored) shouldBe new BsonString("ignored")
    }
    "handle Duplicate" in {
      ProcessingStatus.toBson(Duplicate) shouldBe new BsonString("duplicate")
    }
    "handle Cancelled" in {
      ProcessingStatus.toBson(Cancelled) shouldBe new BsonString("cancelled")
    }
  }

  "ProcessingStatus.toBson" should {
    "be compatible with toJson" in {
      implicit val psf = ProcessingStatus.format
      ProcessingStatus.values.map { status =>
        ProcessingStatus.toBson(status) shouldBe Codecs.toBson[ProcessingStatus](status)
      }
    }
  }
}
