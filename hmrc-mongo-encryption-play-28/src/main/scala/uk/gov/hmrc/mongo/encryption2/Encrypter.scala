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

package uk.gov.hmrc.mongo.encryption2

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.crypto.{EncryptedValue, SecureGCMCipher}
import uk.gov.hmrc.mongo.play.json.JsonTransformer

import scala.util.{Success, Try}

case class Sensitive[A](value: A) {
  override def toString() =
    "Sensitive(...)"
}

object Sensitive {
  def format[A: Format]: OFormat[Sensitive[A]] =
    ( (__ \ "sensitive").format[Boolean]
    ~ (__ \ "value"    ).format[A]
    )((_, v) => Sensitive(v), unlift(Sensitive.unapply[A]).andThen(v => (true, v)))
}

class Encrypter(
  secureGCMCipher    : SecureGCMCipher
)(
  associatedDataPath : JsPath,
  aesKey             : String,
  previousAesKeys    : Seq[String] = Seq.empty
) extends JsonTransformer {
  private def associatedData(jsValue: JsValue) =
    associatedDataPath(jsValue) match {
      case List(associatedData) => associatedData.as[String] // TODO only supports associatedDataPath as a String - so can't use "_id" if it's an ObjectId
      case Nil                  => sys.error(s"No associatedData was found with $associatedDataPath")
      case _                    => sys.error(s"Multiple associatedData was found with $associatedDataPath")
    }

  private val encryptedValueFormat: OFormat[EncryptedValue] =
    ( (__ \ "value").format[String]
    ~ (__ \ "nonce").format[String]
    )(EncryptedValue.apply, unlift(EncryptedValue.unapply))

  // calling js.transform(path.json.update(transformF)) actually merges the result of transform, so would leave the unencrypted data in the object!
  def transformWithoutMerge(js: JsValue, path: JsPath, transformFn: JsValue => JsValue): JsResult[JsValue] =
    js.transform(path.json.update(implicitly[Reads[JsValue]].map(_ => transformFn(path(js).head)))
      .composeWith(path.json.update(implicitly[Reads[JsValue]].map(_ => JsNull)))
    )

  override def encoderTransform(jsValue: JsValue): JsValue = {
    val ad = associatedData(jsValue)
    def transform(js: JsValue): JsValue =
      encryptedValueFormat.writes(secureGCMCipher.encrypt(js.toString, ad, aesKey))
    def encryptSubEncryptables(jsValue: JsValue): JsValue =
      jsValue match {
        case o: JsObject if o.keys.contains("sensitive") => transform(o)
        case o: JsObject => o.fields.foldLeft(JsObject(Map.empty[String, JsValue])) {
                              case (acc, (k, o: JsObject)) =>  acc + (k -> encryptSubEncryptables(o))
                              case (acc, (k, v)) => acc + (k -> v)
                            }
        case v           => v
      }
    encryptSubEncryptables(jsValue)
  }

  override def decoderTransform(jsValue: JsValue): JsValue = {
    val ad = associatedData(jsValue)
    def transform(js: JsValue): JsValue =
      encryptedValueFormat.reads(js) match {
        case JsSuccess(ev, _) => (aesKey +: previousAesKeys).toStream
                                   .map(aesKey => Try(secureGCMCipher.decrypt(ev, ad, aesKey)))
                                   .collectFirst { case Success(res) => Json.parse(res) }
                                   .getOrElse(sys.error("Unable to decrypt value with any key"))
        case JsError(errors)  => sys.error(s"Failed to decrypt value: $errors")
      }
    def decryptSubEncryptables(jsValue: JsValue): JsValue =
      jsValue match {
        case o: JsObject if o.keys.contains("nonce") && o.keys.contains("value") => transform(o)
        case o: JsObject => o.fields.foldLeft(JsObject(Map.empty[String, JsValue])) {
                              case (acc, (k, o: JsObject)) =>  acc + (k -> decryptSubEncryptables(o))
                              case (acc, (k, v)) => acc + (k -> v)
                            }
        case v           => v
      }
    decryptSubEncryptables(jsValue)
  }
}
