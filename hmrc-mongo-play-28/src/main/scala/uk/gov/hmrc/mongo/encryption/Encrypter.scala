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

import play.api.libs.json._


class Encrypter(
  associatedDataPath : JsPath,
  encryptedFieldPaths: Seq[JsPath],
  aesKey             : String
) {
  val secureGCMCipher = new SecureGCMCipher()

  private def associatedData(jsValue: JsValue) =
    associatedDataPath(jsValue) match {
      case List(associatedData) => associatedData.as[String] // TODO only supports associatedDataPath as a String - so can't use "_id" if it's an ObjectId
      case Nil                  => sys.error(s"No associatedData was found with $associatedDataPath")
      case _                    => sys.error(s"Multiple associatedData was found with $associatedDataPath")
    }

  def encrypt(jsValue: JsValue): JsValue = {
    val ad = associatedData(jsValue)
    def transform(js: JsValue): JsValue =
      js match {
        case JsString(s) => EncryptedValue.format.writes(secureGCMCipher.encrypt(s, ad, aesKey))
        case other       => other
      }
    encryptedFieldPaths.foldLeft(jsValue)((js, encryptedFieldPath) =>
      js.transform(encryptedFieldPath.json.update(implicitly[Reads[JsValue]].map(transform _))) match {
        case JsSuccess(r, _) => r
        case JsError(errors) => sys.error(s"Could not encrypt at $encryptedFieldPath: $errors")
      }
    )
  }

  def decrypt(jsValue: JsValue): JsValue = {
    val ad = associatedData(jsValue)
    def transform(js: JsValue): JsValue =
      EncryptedValue.format.reads(js) match {
        case JsSuccess(ev, _) => JsString(secureGCMCipher.decrypt(ev, ad, aesKey))
        case JsError(errors)  => sys.error(s"Failed to decrypt value: $errors")
      }
    encryptedFieldPaths.foldLeft(jsValue)((js, encryptedFieldPath) =>
      js.transform(encryptedFieldPath.json.update(implicitly[Reads[JsValue]].map(transform _))) match {
        case JsSuccess(r, _) => r
        case JsError(errors) => sys.error(s"Could not decrypt at $encryptedFieldPath: $errors")
      }
    )
  }
}
