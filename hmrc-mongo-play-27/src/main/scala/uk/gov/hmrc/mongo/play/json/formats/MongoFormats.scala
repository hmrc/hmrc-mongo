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

package uk.gov.hmrc.mongo.play.json.formats

import org.bson.types.ObjectId
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

trait MongoFormats {
  outer =>

  // ObjectId

  final val objectIdReads: Reads[ObjectId] = Reads[ObjectId] { json =>
    (json \ "$oid").validate[String].flatMap { str =>
      Try(new ObjectId(str)) match {
        case Success(bsonId) => JsSuccess(bsonId)
        case Failure(err)    => JsError(__, s"Invalid BSON Object ID $json; ${err.getMessage}")
      }
    }
  }

  final val objectIdWrites: Writes[ObjectId] =
    (objectId: ObjectId) =>
      Json.obj("$oid" -> objectId.toString)

  final val objectIdFormat: Format[ObjectId] = Format(objectIdReads, objectIdWrites)

  trait Implicits {
    implicit val objectIdFormat: Format[ObjectId] = outer.objectIdFormat
  }

  object Implicits extends Implicits

  // MongoEntity

  private def copyKey(fromPath: JsPath, toPath: JsPath): Reads[JsObject] =
    __.json.update(toPath.json.copyFrom(fromPath.json.pick))

  private def moveKey(fromPath: JsPath, toPath: JsPath): JsValue => JsObject =
    (json: JsValue) => json.transform(copyKey(fromPath, toPath) andThen fromPath.json.prune).get

  /** Maps a Format for an entity with 'id' field to mongo by renaming the id field to internal '_id'.
    * Useful for auto generated Formats, where the model id key is named 'id'.
    *
    * {{{
    * case class MyObject(id: ObjectId)
    * val formats: Format[MyObject] = mongoEntity(Json.format[MyObject]}
    * }}}
    *
    * This is deprecated since an explicit Format, mapping id to `_id` is preferred.
    * Also any queries on id would still need to use the underlying '_id' name.
    */
  @deprecated("Map entity `id` directly to `_id`, rather than using JSON automated macro.", "0.35.0")
  def mongoEntity[A](baseFormat: Format[A]): Format[A] = {
    val publicIdPath: JsPath  = __ \ '_id
    val privateIdPath: JsPath = __ \ 'id
    new Format[A] {
      def reads(json: JsValue): JsResult[A] =
        baseFormat.compose(copyKey(publicIdPath, privateIdPath)).reads(json)

      def writes(o: A): JsValue =
        baseFormat.transform(moveKey(privateIdPath, publicIdPath)).writes(o)
    }
  }
}

object MongoFormats extends MongoFormats
