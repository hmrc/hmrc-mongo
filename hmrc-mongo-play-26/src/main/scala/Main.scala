/*
 * Copyright 2020 HM Revenue & Customs
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

package main

import com.mongodb.ConnectionString
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{CreateCollectionOptions, ValidationAction, ValidationLevel, ValidationOptions}
import org.mongodb.scala.{Completed, Document, MongoClient, MongoCollection}
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

// sbt "runMain main.Main"

// sbt
// > project hmrc-mongo-play-26_2_12
// > runMain main.Main

object Main extends App {
  val jsonSchema =
    BsonDocument("""
     {
        bsonType: "object",
        required: [ "phone" ],
        properties: {
           phone: {
              bsonType: "string",
              description: "must be a string and is required"
           },
           email: {
              bsonType : "string",
              description: "must be a string and match the regular expression pattern"
           },
           status: {
              enum: [ "Unknown", "Incomplete" ],
              description: "can only be one of the enum values"
           }
        }
     }
    """)

  type A = BsonDocument

  val obj: A = BsonDocument("phone"-> "123", "status" -> "Unknown")

  val f =
    for {
      databaseName   <- Future.successful("a")
      collectionName =  "a"
      mongoUri       =  s"mongodb://localhost:27017/$databaseName"
      database       =  MongoClient(mongoUri).getDatabase(new ConnectionString(mongoUri).getDatabase)
      _              <- database.drop().toFuture
      _              <- database
                          .createCollection(
                              collectionName
                            , CreateCollectionOptions()
                                .validationOptions(
                                  ValidationOptions()
                                    .validator(new BsonDocument(f"$$jsonSchema", jsonSchema))
                                    .validationLevel(ValidationLevel.STRICT)
                                    .validationAction(ValidationAction.ERROR)
                                )
                            )
                          .toFuture
      collection     =  database.getCollection[A](collectionName)
      _              =  println(s"Inserting $obj")
      res            <- collection.insertOne(obj).toFuture
      _              =  println(s"Inserted: $res")
    } yield ()
  Await.result(f, 10.seconds)
}