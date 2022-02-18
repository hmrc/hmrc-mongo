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

package uk.gov.hmrc.mongo.play.json

import org.bson.codecs.Codec
import org.bson.codecs.configuration.{CodecProvider, CodecRegistry}
import play.api.libs.json.Format

import scala.reflect.ClassTag

trait CodecProviders {

  def createProvider[A](codec: Codec[A])(implicit ct: ClassTag[A]): CodecProvider =
    new CodecProvider {
      override def get[T](clazz: Class[T], registry: CodecRegistry): Codec[T] =
        if (clazz == ct.runtimeClass) codec.asInstanceOf[Codec[T]]
        else null
    }

  def playFormatCodecProvider[A: ClassTag](format: Format[A]): CodecProvider =
    createProvider[A](Codecs.playFormatCodec(format))
}

object CodecProviders extends CodecProviders
