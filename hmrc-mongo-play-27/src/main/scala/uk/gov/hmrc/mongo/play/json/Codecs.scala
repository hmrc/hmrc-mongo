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

package uk.gov.hmrc.mongo.play.json

import org.bson._
import org.bson.codecs.{Codec, DecoderContext, EncoderContext}
import org.bson.json.{JsonMode, JsonReader, JsonWriter, JsonWriterSettings}
import org.bson.types.Decimal128
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json._
import org.mongodb.scala.{Document => ScalaDocument}

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

trait Codecs {
  outer =>
  val logger: Logger = LoggerFactory.getLogger(classOf[Codecs].getName)

  private val bsonDocumentCodec = DEFAULT_CODEC_REGISTRY.get(classOf[BsonDocument])
  private val bsonValueCodec    = DEFAULT_CODEC_REGISTRY.get(classOf[BsonValue])

  /** @param legacyNumbers `true` will preserve the Number modifications which occured with simple-reactivemongo when storing
    * extremely large and small numbers.
    * The default value `false` should be preferred in most cases. This does change the previous behaviour from reactivemongo,
    * but only for extreme values not within typical usage (e.g. 4.648216657858037E+74). This ensures that should numbers in
    * this extreme range occur, they will be stored and retrieved accurately, whereas with the legacy behaviour they may be
    * modified in unexpected ways.
    */
  def playFormatCodec[A](
    format: Format[A],
    legacyNumbers: Boolean = false
  )(implicit ct: ClassTag[A]): Codec[A] = new Codec[A] {

    override def getEncoderClass: Class[A] =
      ct.runtimeClass.asInstanceOf[Class[A]]

    override def encode(writer: BsonWriter, value: A, encoderContext: EncoderContext): Unit = {
      val bs: BsonValue = jsonToBson(legacyNumbers)(format.writes(value))
      bsonValueCodec.encode(writer, bs, encoderContext)
    }

    override def decode(reader: BsonReader, decoderContext: DecoderContext): A = {
      val bs: BsonValue =
        bsonValueCodec
          .decode(reader, decoderContext)

      val json = bsonToJson(bs)

      format.reads(json) match {
        case JsSuccess(v, _) => v
        case JsError(errors) => sys.error(s"Failed to parse json as ${ct.runtimeClass.getName} '$json': $errors")
      }
    }
  }

  def toBson[A: Writes](a: A, legacyNumbers: Boolean = false): BsonValue =
    jsonToBson(legacyNumbers)(Json.toJson(a))

  def fromBson[A: Reads](bs: BsonValue): A = bsonToJson(bs).as[A]

  private def jsonToBson(legacyNumbers: Boolean)(js: JsValue): BsonValue =
    js match {
      case JsNull       => BsonNull.VALUE
      case JsBoolean(b) => BsonBoolean.valueOf(b)
      case JsNumber(n) =>
        if (legacyNumbers) toBsonNumberLegacy(n)
        else toBsonNumber(n)
      case JsString(s) => new BsonString(s)
      case JsArray(a)  => new BsonArray(a.map(jsonToBson(legacyNumbers)).asJava)
      case o: JsObject =>
        if (o.keys.exists(k => k.startsWith("$") && !List("$numberDecimal", "$numberLong").contains(k)))
          // mongo types, identified with $ in `MongoDB Extended JSON format`  (e.g. BsonObjectId, BsonDateTime)
          // should use default conversion to Json. Then PlayJsonReaders will then convert as appropriate
          // The exception are numbers handled above (otherwise precision of $numberDecimal will be lost)
          fromJsonDefault(o)
        else
          new BsonDocument(
            o.fields.map {
              case (k, v) =>
                new BsonElement(k, jsonToBson(legacyNumbers)(v))
            }.asJava
          )
    }

  private def bsonToJson(bs: BsonValue): JsValue =
    bs match {
      case _: BsonNull        => JsNull
      case b: BsonBoolean     => JsBoolean(b.getValue)
      case i: BsonInt32       => JsNumber(i.getValue)
      case l: BsonInt64       => JsNumber(l.getValue)
      case d: BsonDouble      => JsNumber(d.getValue)
      case bd: BsonDecimal128 => // throws ArithmeticException if the Decimal128 value is NaN, Infinity, -Infinity, or -0, none of which can be represented as a BigDecimal
        // Should be OK since these values will not have been written to db from BigDecimal.
        JsNumber(bd.getValue.bigDecimalValue)
      case s: BsonString => JsString(s.getValue)
      case d: BsonDocument =>
        JsObject(
          d.asScala.map { case (k, v) => (k, bsonToJson(v)) }
        )
      case other => // other types, attempt to convert to json object (Strict = `MongoDB Extended JSON format`)
        toJsonDefault(other, JsonMode.STRICT) match {
          case JsDefined(s)   => s
          case _: JsUndefined => logger.debug(s"Could not convert $other to Json"); JsNull
        }
    }

  // Following number conversion comes from https://github.com/ReactiveMongo/Play-ReactiveMongo/blob/4071a4fd580d7c6edeccac318d839456f69a847d/src/main/scala/play/modules/reactivemongo/Formatters.scala#L62-L64
  // It will loose precision on BigDecimals which can't be represented as doubles, and incorrectly identify some large Doubles as Long.
  // But is backward compatible with simple-reactivemongo
  private def toBsonNumberLegacy(bd: BigDecimal): BsonValue =
    if (!bd.ulp.isWhole) new BsonDouble(bd.toDouble)
    else if (bd.isValidInt) new BsonInt32(bd.toInt)
    else new BsonInt64(bd.toLong)

  private def toBsonNumber(bd: BigDecimal): BsonValue =
    if (bd.isValidInt) new BsonInt32(bd.intValue)
    else if (bd.isValidLong) new BsonInt64(bd.longValue)
    else if (bd.isDecimalDouble) new BsonDouble(bd.doubleValue)
    else // Not all bigDecimals are representable as Decimal128. Will throw [java.lang.NumberFormatException] with message: `Conversion to Decimal128 would require inexact rounding of -4.2176255923279509728936555398034786404E-54.`
      new BsonDecimal128(new Decimal128(bd.bigDecimal))

  private def toJsonDefault(bs: BsonValue, mode: JsonMode): JsLookupResult = {
    // wrap value in a document inorder to reuse the document -> JsonString, then extract
    val writer = new java.io.StringWriter
    val doc    = new BsonDocument("tempKey", bs)
    val writerSettings = JsonWriterSettings.builder.outputMode(mode).build
    bsonDocumentCodec.encode(new JsonWriter(writer, writerSettings), doc, EncoderContext.builder.build)
    Json.parse(writer.toString) \ "tempKey"
  }

  private def fromJsonDefault(o: JsObject): BsonValue = {
    // wrap value in a document inorder to reuse the Json -> document, then extract
    val o2  = JsObject(Seq(("tempKey", o)))
    val doc = bsonDocumentCodec.decode(new JsonReader(o2.toString), DecoderContext.builder.build)
    doc.get("tempKey")
  }

  implicit class JsonOps[A: Writes](a: A) {
    def toBson(legacyNumbers: Boolean = false): BsonValue = outer.toBson(a, legacyNumbers)

    def toDocument(legacyNumbers: Boolean = false): ScalaDocument = outer.toBson(a, legacyNumbers).asDocument()
  }

  implicit class BsonOps(bs: BsonValue) {
    def fromBson[T: Reads]: T = outer.fromBson(bs)
  }

  implicit class DocumentOps(document: ScalaDocument) {
    def fromBson[T: Reads]: T = outer.fromBson(document.toBsonDocument)
  }

  implicit class DocumentsOps(documents: Seq[ScalaDocument]) {
    def fromBson[T: Reads]: Seq[T] = documents.map(document => outer.fromBson(document.toBsonDocument))
  }
}

object Codecs extends Codecs
