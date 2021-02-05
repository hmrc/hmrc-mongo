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

import org.scalatest.{Matchers, WordSpec}
import play.api.libs.json.{JsError, JsString, Json}
import reactivemongo.bson.{BSONValue, BSON, BSONString}

class ProcessingStatusSpec extends WordSpec with Matchers {
  "reading processing status from JSON" should {
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
    "handle ToDo" in {
      Json.toJson(ToDo) should be (JsString("todo"))
    }
    "handle InProgress" in {
      Json.toJson(InProgress) should be (JsString("in-progress"))
    }
    "handle Succeeded" in {
      Json.toJson(Succeeded) should be (JsString("succeeded"))
    }
    "handle Failed" in {
      Json.toJson(Failed) should be (JsString("failed"))
    }
    "handle PermanentlyFailed" in {
      Json.toJson(PermanentlyFailed) should be (JsString("permanently-failed"))
    }
    "handle Ignored" in {
      Json.toJson(Ignored) should be (JsString("ignored"))
    }
    "handle Duplicate" in {
      Json.toJson(Duplicate) should be (JsString("duplicate"))
    }
    "handle Cancelled" in {
      Json.toJson(Cancelled) should be (JsString("cancelled"))
    }
  }
  "reading processing status from BSON" should {
    "handle ToDo" in {
      BSONString("todo").as[ProcessingStatus] should be(ToDo)
    }
    "handle InProgress" in {
      BSONString("in-progress").as[ProcessingStatus] should be(InProgress)
    }
    "handle Succeeded" in {
      BSONString("succeeded").as[ProcessingStatus] should be(Succeeded)
    }
    "handle Failed" in {
      BSONString("failed").as[ProcessingStatus] should be(Failed)
    }
    "handle PermanentlyFailed" in {
      BSONString("permanently-failed").as[ProcessingStatus] should be(PermanentlyFailed)
    }
    "handle Ignored" in {
      BSONString("ignored").as[ProcessingStatus] should be(Ignored)
    }
    "handle Duplicate" in {
      BSONString("duplicate").as[ProcessingStatus] should be(Duplicate)
    }
    "handle Cancelled" in {
      BSONString("cancelled").as[ProcessingStatus] should be(Cancelled)
    }
    "error reading other values" in {
      an [Exception] should be thrownBy BSONString("In Progress").as[ProcessingStatus]
    }
  }
  "writing processing status to BSON" should {
    "handle ToDo" in {
      BSON.write(ToDo) should be (BSONString("todo"))
    }
    "handle InProgress" in {
      BSON.write(InProgress) should be (BSONString("in-progress"))
    }
    "handle Succeeded" in {
      BSON.write(Succeeded) should be (BSONString("succeeded"))
    }
    "handle Failed" in {
      BSON.write(Failed) should be (BSONString("failed"))
    }
    "handle PermanentlyFailed" in {
      BSON.write(PermanentlyFailed) should be (BSONString("permanently-failed"))
    }
    "handle Ignored" in {
      BSON.write(Ignored) should be (BSONString("ignored"))
    }
    "handle Duplicate" in {
      BSON.write(Duplicate) should be (BSONString("duplicate"))
    }
    "handle Cancelled" in {
      BSON.write(Cancelled) should be (BSONString("cancelled"))
    }
  }
}
