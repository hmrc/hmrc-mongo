/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.workitem

import org.bson.types.ObjectId
import org.bson.conversions.Bson
import org.bson.codecs.Codec
import org.mongodb.scala.{ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model._
import play.api.libs.json._
import uk.gov.hmrc.mongo.metrix.MetricSource
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import java.time.{Duration, Instant}
import scala.concurrent.{ExecutionContext, Future}

/** The repository to set and get the work item's for processing.
  * See [[pushNew]] for creating work items, and [[pullOutstanding]] for retrieving them.
  * @param collectionName the name of the mongo collection.
  * @param mongoComponent
  * @param itemFormat a play Json format to map the item to mongo entities
  * @param workItemFields the internal fields for [[WorkItem]], allowing customisation
  * @param replaceIndexes optional - default is true
  *   If true, existing indices should be removed/updated to match the provided indices.
  *   If false, any old indices are left behind, and indices with changed definitions will throw IndexConflict exceptions.
  * @param extraIndexes optional - to add additional indexes
  * @param extraCodecs optional - to support more types
  */
abstract class WorkItemRepository[T](
  collectionName    : String,
  mongoComponent    : MongoComponent,
  itemFormat        : Format[T],
  val workItemFields: WorkItemFields,
  replaceIndexes    : Boolean         = true,
  extraIndexes      : Seq[IndexModel] = Seq.empty,
  extraCodecs       : Seq[Codec[_]]   = Seq.empty
)(implicit
  ec: ExecutionContext,
) extends PlayMongoRepository[WorkItem[T]](
  collectionName = collectionName,
  mongoComponent = mongoComponent,
  domainFormat   = WorkItem.formatForFields[T](workItemFields)(itemFormat),
  indexes        = Seq(
                     IndexModel(Indexes.ascending(workItemFields.status, workItemFields.updatedAt), IndexOptions().background(true)),
                     IndexModel(Indexes.ascending(workItemFields.status, workItemFields.availableAt), IndexOptions().background(true)),
                     IndexModel(Indexes.ascending(workItemFields.status), IndexOptions().background(true))
                   ) ++ extraIndexes,
  replaceIndexes = replaceIndexes,
  extraCodecs    = Codecs.playFormatCodecsBuilder(ProcessingStatus.format)
                     .forType[ProcessingStatus.ToDo.type]
                     .forType[ProcessingStatus.InProgress.type]
                     .forType[ProcessingStatus.Succeeded.type]
                     .forType[ProcessingStatus.Deferred.type]
                     .forType[ProcessingStatus.Failed.type]
                     .forType[ProcessingStatus.PermanentlyFailed.type]
                     .forType[ProcessingStatus.Ignored.type]
                     .forType[ProcessingStatus.Duplicate.type]
                     .forType[ProcessingStatus.Cancelled.type]
                     .build
                     ++ extraCodecs
) with Operations.Cancel
  with Operations.FindById[T]
  with MetricSource {

  /** Returns the timeout of any WorkItems marked as InProgress.
    * WorkItems marked as InProgress will be hidden from [[pullOutstanding]] until this window expires.
    */
  def inProgressRetryAfter: Duration

  /** Returns the current date time for setting the updatedAt field.
    * abstract to allow for test friendly implementations.
    */
  def now(): Instant

  def metricPrefix: String = collectionName

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    Future.traverse(ProcessingStatus.values.toList) { status =>
      count(status).map(value => s"$metricPrefix.${status.name}" -> value.toInt)
    }.map(_.toMap)

  private def newWorkItem(
    availableAt : Instant,
    initialState: T => ProcessingStatus
  )(item: T) =
    WorkItem(
      id           = new ObjectId(),
      receivedAt   = now(),
      updatedAt    = now(),
      availableAt  = availableAt,
      status       = initialState(item),
      failureCount = 0,
      item         = item
    )

  private def toDo(item: T): ProcessingStatus = ProcessingStatus.ToDo

  /** Creates a new [[WorkItem]].
    * @param item the item to store in the WorkItem
    * @param availableAt when to defer processing until
    * @param initialState defines the initial state of the WorkItem for the item
    */
  def pushNew(
    item        : T,
    availableAt : Instant               = now(),
    initialState: T => ProcessingStatus = toDo _
  ): Future[WorkItem[T]] = {
    val workItem = newWorkItem(availableAt, initialState)(item)
    collection.insertOne(workItem).toFuture().map(_ => workItem)
  }

  /** Creates a batch of new [[WorkItem]]s.
    * @param items the items to store as WorkItems
    * @param availableAt when to defer processing until
    * @param initialState defines the initial state of the WorkItems for the item
    */
  def pushNewBatch(
    items       : Seq[T],
    availableAt : Instant               = now(),
    initialState: T => ProcessingStatus = toDo _
  ): Future[Seq[WorkItem[T]]] = {
    val workItems = items.map(newWorkItem(availableAt, initialState))

    collection.insertMany(workItems).toFuture().map { result =>
      if (result.getInsertedIds.size == workItems.size) workItems
      else throw new RuntimeException(s"Only ${result.getInsertedIds.size} items were saved")
    }
  }

  /** Returns a WorkItem to be processed, if available.
    * The item will be atomically set to [[ProcessingStatus.InProgress]], so it will not be picked up by other calls to pullOutstanding until
    * it's status has been explicitly marked as Failed or ToDo, or it's progress status has timed out (set by [[inProgressRetryAfter]]).
    *
    * A WorkItem will be considered for processing in the following order:
    * 1) Has ToDo status, and the availableAt field is before the availableBefore param.
    * 2) Has Failed status, and it was marked as Failed before the failedBefore param.
    * 3) Has InProgress status, and was marked as InProgress before the inProgressRetryAfter configuration. Basically a timeout to ensure WorkItems that don't advance from InProgress do not get stuck.
    *
    * @param failedBefore it will only consider WorkItems in FailedState if they were marked as Failed before the failedBefore. This can avoid retrying a failure immediately.
    * @param availableBefore it will only consider WorkItems where the availableAt field is before the availableBefore
    */
  def pullOutstanding(failedBefore: Instant, availableBefore: Instant): Future[Option[WorkItem[T]]] = {
    def findNextItemByQuery(query: Bson): Future[Option[WorkItem[T]]] =
      collection
        .findOneAndUpdate(
          filter  = query,
          update  = setStatusOperation(ProcessingStatus.InProgress, None),
          options = FindOneAndUpdateOptions()
                      .returnDocument(ReturnDocument.AFTER)
        ).toFutureOption()

    def todoQuery: Bson =
      Filters.and(
        Filters.equal(workItemFields.status, ProcessingStatus.ToDo),
        Filters.lt(workItemFields.availableAt, availableBefore)
      )

    def failedQuery: Bson =
      Filters.and(
        Filters.equal(workItemFields.status, ProcessingStatus.Failed),
        Filters.lt(workItemFields.updatedAt, failedBefore),
        Filters.lt(workItemFields.availableAt, availableBefore)
      )

    def inProgressQuery: Bson =
      Filters.and(
        Filters.equal(workItemFields.status, ProcessingStatus.InProgress),
        Filters.lt(workItemFields.updatedAt, now().minus(inProgressRetryAfter))
      )

    findNextItemByQuery(todoQuery).flatMap {
      case None => findNextItemByQuery(failedQuery)
                     .flatMap {
                       case None => findNextItemByQuery(inProgressQuery)
                       case item => Future.successful(item)
                     }
      case item => Future.successful(item)
    }
  }

  /** Sets the ProcessingStatus of a WorkItem.
    * It will also update the updatedAt timestamp.
    */
  def markAs(id: ObjectId, status: ProcessingStatus, availableAt: Option[Instant] = None): Future[Boolean] =
    collection.updateOne(
      filter = Filters.equal(workItemFields.id, id),
      update = setStatusOperation(status, availableAt)
    ).toFuture()
     .map(_.getMatchedCount > 0)

  /** Sets the ProcessingStatus of a WorkItem to a ResultStatus.
    * It will also update the updatedAt timestamp.
    * It will return false if the WorkItem is not InProgress.
    */
  def complete(id: ObjectId, newStatus: ResultStatus): Future[Boolean] =
    collection.updateOne(
      filter = Filters.and(
                 Filters.equal(workItemFields.id, id),
                 Filters.equal(workItemFields.status, ProcessingStatus.InProgress)
               ),
      update = setStatusOperation(newStatus, None)
    ).toFuture()
     .map(_.getModifiedCount > 0)

  /** Deletes the WorkItem.
    * It will return false if the WorkItem is not InProgress.
    */
  def completeAndDelete(id: ObjectId): Future[Boolean] =
    collection.deleteOne(
      filter = Filters.and(
                 Filters.equal(workItemFields.id, id),
                 Filters.equal(workItemFields.status, ProcessingStatus.InProgress)
               )
    ).toFuture()
     .map(_.getDeletedCount > 0)

  /** Sets the ProcessingStatus of a WorkItem to Cancelled.
    * @return [[StatusUpdateResult.Updated]] if the WorkItem is cancelled,
    * [[StatusUpdateResult.NotFound]] if it's not found,
    * and [[StatusUpdateResult.NotUpdated]] if it's not in a cancellable ProcessingStatus.
    */
  def cancel(id: ObjectId): Future[StatusUpdateResult] =
    collection.findOneAndUpdate(
      filter = Filters.and(
                 Filters.equal(workItemFields.id, id),
                 Filters.in(workItemFields.status, ProcessingStatus.cancellable.toSeq: _*)
               ),
      update  = setStatusOperation(ProcessingStatus.Cancelled, None),
    ).toFuture()
     .flatMap { res =>
       Option(res) match {
         case Some(item) => Future.successful(
                              StatusUpdateResult.Updated(
                                previousStatus = item.status,
                                newStatus      = ProcessingStatus.Cancelled
                              )
                            )
         case None       => findById(id).map {
                              case Some(item) => StatusUpdateResult.NotUpdated(item.status)
                              case None       => StatusUpdateResult.NotFound
                            }
       }
     }

  def findById(id: ObjectId): Future[Option[WorkItem[T]]] =
    collection.find(Filters.equal("_id", id))
      .toFuture()
      .map(_.headOption)

  /** Returns the number of WorkItems in the specified ProcessingStatus */
  def count(state: ProcessingStatus): Future[Long] =
    collection.countDocuments(filter = Filters.equal(workItemFields.status, state.name)).toFuture()

  private def setStatusOperation(newStatus: ProcessingStatus, availableAt: Option[Instant]): Bson =
    Updates.combine(
      Updates.set(workItemFields.status, newStatus),
      Updates.set(workItemFields.updatedAt, now()),
      (availableAt.map(when => Updates.set(workItemFields.availableAt, when)).getOrElse(BsonDocument())),
      (if (newStatus == ProcessingStatus.Failed) Updates.inc(workItemFields.failureCount, 1) else BsonDocument())
    )
}

object Operations {
  trait Cancel {
    def cancel(id: ObjectId): Future[StatusUpdateResult]
  }
  trait FindById[T] {
    def findById(id: ObjectId): Future[Option[WorkItem[T]]]
  }
}
