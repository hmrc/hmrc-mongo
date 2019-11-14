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

package uk.gov.hmrc.mongo.play.json

import org.bson._
import org.bson.codecs.{BsonTypeCodecMap, Codec, DecoderContext, EncoderContext}
import org.bson.json.{JsonMode, JsonWriter, JsonWriterSettings}
import org.bson.types.Decimal128
import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.collection.immutable.Document
import play.api.libs.json._
import scala.collection.JavaConverters._

import scala.reflect.ClassTag

trait BsonConversion {
  def toBson[A: Writes](a: A): Document =
    Document(Json.toJson(a).toString)
}

object BsonConversion extends BsonConversion

trait Codecs {
  import BsonConversion._

  def playFormatCodec[A](format: Format[A])(implicit ct: ClassTag[A]): Codec[A] = new Codec[A] {
    private val bsonDocumentCodec = DEFAULT_CODEC_REGISTRY.get(classOf[BsonDocument])
    private val bsonValueCodec    = DEFAULT_CODEC_REGISTRY.get(classOf[BsonValue])
    private val bsonTypeCodecMap  =
      new BsonTypeCodecMap(org.bson.codecs.BsonValueCodecProvider.getBsonTypeClassMap(), DEFAULT_CODEC_REGISTRY)

    override def getEncoderClass: Class[A] =
      ct.runtimeClass.asInstanceOf[Class[A]]

    override def encode(writer: BsonWriter, value: A, encoderContext: EncoderContext): Unit = {
      val bs: BsonValue = jsonToBson(format.writes(value))
      bsonValueCodec.encode(writer, bs, encoderContext)
    }

    def jsonToBson(js: JsValue): BsonValue =
      js match {
        case JsNull                           => BsonNull.VALUE
        case JsBoolean(b)                     => BsonBoolean.valueOf(b)
        case JsNumber(n) if n.isValidInt      => new BsonInt32(n.intValue)
        case JsNumber(n) if n.isValidLong     => new BsonInt64(n.longValue)
        case JsNumber(n) if n.isDecimalDouble => new BsonDouble(n.doubleValue)
        case JsNumber(n)                      => //println(s"Converting Number: $n");
                                                 val res = new BsonDecimal128(new Decimal128(n.bigDecimal))
                                                 // How to handle `java.lang.NumberFormatException, with message: Conversion to Decimal128 would require inexact rounding of -4.2176255923279509728936555398034786404E-54.`
                                                 // Alternative format? For now, avoiding in BigDecimal test data generation.
                                                 //println(s"Converted $res");
                                                 res
        case JsString(s)                      => new BsonString(s)
        case JsArray(a)                       => new BsonArray(a.map(jsonToBson).asJava)
        case o: JsObject                      => //println(s"Converting Doc: $o")
                                                 val res =
                                                   if (o.keys.exists(k => k.startsWith("$") && !List("$numberDecimal", "$numberLong").contains(k)))
                                                     // mongo types, identified with $ in `MongoDB Extended JSON format`  (e.g. BsonObjectId, BsonDateTime)
                                                     // should use default conversion to Json. Then PlayJsonReaders will then convert as appropriate
                                                     // The exception are numbers handled above (otherwise precision of $numberDecimal will be lost)
                                                     fromJsonDefault(o)
                                                   else
                                                     new BsonDocument(
                                                       o.fields.map { case (k, v) =>
                                                         new BsonElement(k, jsonToBson(v))
                                                       }.asJava
                                                     )
                                                 //println(s"Converted $res")
                                                 res

      }

    def bsonToJson(bs: BsonValue): JsValue =
      bs match {
        case _ : BsonNull       => JsNull
        case b : BsonBoolean    => JsBoolean(b.getValue)
        case i : BsonInt32      => JsNumber(i.getValue)
        case l : BsonInt64      => JsNumber(l.getValue)
        case d : BsonDouble     => JsNumber(d.getValue)
        case bd: BsonDecimal128 => JsNumber(bd.getValue.bigDecimalValue) // * @throws ArithmeticException if the Decimal128 value is NaN, Infinity, -Infinity, or -0, none of which can be represented as a
        case s : BsonString     => JsString(s.getValue)
        case d : BsonDocument   => JsObject(
                                     d.asScala.map { case (k, v) => (k, bsonToJson(v)) }
                                   )
        case other              => // other types, attempt to convert to json object (Strict = `MongoDB Extended JSON format`)
                                   toJsonDefault(other, JsonMode.STRICT) match {
                                     case JsDefined(s)   => /*println(s"Converted $other to $s");*/ s
                                     case _: JsUndefined => println(s"Could not convert $other to Json"); JsNull // TODO logger
                                   }
      }

    def toJsonDefault(bs: BsonValue, mode: JsonMode): JsLookupResult = {
      // wrap value in a document inorder to reuse the document -> JsonString, then extract
      val writer = new java.io.StringWriter
      val doc = new BsonDocument("tempKey", bs)
      bsonDocumentCodec.encode(new JsonWriter(writer, new JsonWriterSettings(mode)), doc, EncoderContext.builder.build)
      Json.parse(writer.toString) \ "tempKey"
    }

    def fromJsonDefault(o: JsObject): BsonValue = {
      // wrap value in a document inorder to reuse the Json -> document, then extract
      val o2 = JsObject(Seq(("tempKey", o)))
      val doc = BsonDocument.parse(o2.toString) // bsonDocumentCodec.decode(new JsonReader(json), DecoderContext.builder.build)
      doc.get("tempKey")
    }

    override def decode(reader: BsonReader, decoderContext: DecoderContext): A = {
      //println(s">>>>>>>> reader.getCurrentBsonType=${reader.getCurrentBsonType}")

      val bs: BsonValue =
        bsonTypeCodecMap.get(reader.getCurrentBsonType)
          .decode(reader, decoderContext)
          .asInstanceOf[BsonValue]

      val json = bsonToJson(bs)

      format.reads(json) match {
        case JsSuccess(v, _) => v
        case JsError(errors) => sys.error(s"Failed to parse json as ${ct.runtimeClass.getName} '$json': $errors")
      }
    }
  }
}

object Codecs extends Codecs
