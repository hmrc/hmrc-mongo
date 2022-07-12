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


import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.crypto.{EncryptedValue, SecureGCMCipher2}
import uk.gov.hmrc.mongo.play.json.JsonTransformer

import scala.util.{Success, Try}


class ADEncrypter(
  secureGCMCipher    : SecureGCMCipher2
)(
  associatedDataPath : JsPath
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
      encryptedValueFormat.writes(secureGCMCipher.encrypt(js.toString, ad))
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
        case JsSuccess(ev, _) => Json.parse(secureGCMCipher.decrypt(ev, ad))
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

object ADEncrypter { outer =>
  import Sensitive._

  // These formats don't encrypt - they just convert to an intermediate format, that the ADEncrypter can recognise as sensitive
  // and then the json transformer will encrypt
  def format[A: Format, B <: Sensitive[A]](apply: A => B, unapply: B => Option[A]): OFormat[B] =
    ( (__ \ "sensitive").format[Boolean]
    ~ (__ \ "value"    ).format[A]
    )((_, v) => apply(v), unlift(unapply).andThen(v => (true, v)))

  val sensitiveStringFormat : OFormat[SensitiveString ] = format[String , SensitiveString ](SensitiveString .apply, SensitiveString .unapply)
  val sensitiveBooleanFormat: OFormat[SensitiveBoolean] = format[Boolean, SensitiveBoolean](SensitiveBoolean.apply, SensitiveBoolean.unapply)
  val sensitiveLongFormat   : OFormat[SensitiveLong   ] = format[Long   , SensitiveLong   ](SensitiveLong   .apply, SensitiveLong   .unapply)
  val sensitiveDoubleFormat : OFormat[SensitiveDouble ] = format[Double , SensitiveDouble ](SensitiveDouble .apply, SensitiveDouble .unapply)

  object Implicits {
    implicit val sensitiveStringFormat  = outer.sensitiveStringFormat
    implicit val sensitiveBooleanFormat = outer.sensitiveBooleanFormat
    implicit val sensitiveLongFormat    = outer.sensitiveLongFormat
    implicit val sensitiveDoubleFormat  = outer.sensitiveDoubleFormat
  }
}
