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

import org.mongodb.scala.bson.BsonObjectId
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

trait MongoFormats {
  outer =>

  val objectIdRead: Reads[BsonObjectId] = Reads[BsonObjectId] { json =>
    (json \ "$oid").validate[String].flatMap { str =>
      Try(BsonObjectId(str)) match {
        case Success(bsonId) => JsSuccess(bsonId)
        case Failure(err)    => JsError(__, s"Invalid BSON Object ID $json; ${err.getMessage}")
      }
    }
  }

  val objectIdWrite: Writes[BsonObjectId] = new Writes[BsonObjectId] {
    def writes(objectId: BsonObjectId): JsValue = Json.obj(
      "$oid" -> objectId.getValue.toString
    )
  }

  val objectIdFormats: Format[BsonObjectId] = Format(objectIdRead, objectIdWrite)

  trait Implicits {
    implicit val objectIdFormats: Format[BsonObjectId] = outer.objectIdFormats
  }

  object Implicits extends Implicits

  private def copyKey(fromPath: JsPath, toPath: JsPath): Reads[JsObject] =
    __.json.update(toPath.json.copyFrom(fromPath.json.pick))

  private def moveKey(fromPath: JsPath, toPath: JsPath): JsValue => JsObject =
    (json: JsValue) => json.transform(copyKey(fromPath, toPath) andThen fromPath.json.prune).get

  def mongoEntity[A](baseFormat: Format[A]): Format[A] = {
    val publicIdPath: JsPath  = __ \ '_id
    val privateIdPath: JsPath = __ \ 'id
    new Format[A] {
      def reads(json: JsValue): JsResult[A] = baseFormat.compose(copyKey(publicIdPath, privateIdPath)).reads(json)

      def writes(o: A): JsValue = baseFormat.transform(moveKey(privateIdPath, publicIdPath)).writes(o)
    }
  }
}

object MongoFormats extends MongoFormats
