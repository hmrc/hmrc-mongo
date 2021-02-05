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

package uk.gov.hmrc.workitem

import com.typesafe.config.Config
import org.joda.time.{DateTime, Duration}
import play.api.libs.json._
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.{DB, ReadPreference}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers.JsObjectDocumentWriter
import uk.gov.hmrc.metrix.domain.MetricSource
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.{ExecutionContext, Future}

/** The repository to set and get the work item's for processing.
  * See [[pushNew(T,DateTime)]] for creating work items, and [[pullOutstanding]] for retrieving them.
  */
abstract class WorkItemRepository[T, ID](collectionName: String,
                                         mongo: () => DB,
                                         itemFormat: Format[WorkItem[T]],
                                         config: Config
                                        )(implicit idFormat: Format[ID], mfItem: Manifest[T], mfID: Manifest[ID])
  extends ReactiveRepository[WorkItem[T], ID](collectionName, mongo, itemFormat, idFormat)
  with Operations.Cancel[ID]
  with Operations.FindById[ID, T]
  with MetricSource {

  /** Returns the current date time for setting the updatedAt field.
    * abstract to allow for test friendly implementations.
    */
  def now: DateTime

  /** Returns customisable names of the internal fields.
    * e.g.
    * {{{
    * new WorkItemFieldNames {
    * val receivedAt   = "receivedAt"
    * val updatedAt    = "updatedAt"
    * val availableAt  = "receivedAt"
    * val status       = "status"
    * val id           = "_id"
    * val failureCount = "failureCount"
    * }
    * }}}
    */
  def workItemFields: WorkItemFieldNames

  /** Returns the property key which defines the millis in Long format, for the [[inProgressRetryAfter]]
    * to be looked up in the [[config]].
    */
  def inProgressRetryAfterProperty: String


  def metricPrefix: String = collectionName

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    Future.traverse(ProcessingStatus.processingStatuses.toList) { status =>
      count(status).map(value => s"$metricPrefix.${status.name}" -> value)
    }.map(_.toMap)
  }

  private implicit val dateFormats: Format[DateTime] = ReactiveMongoFormats.dateTimeFormats

  /** Returns the timeout of any WorkItems marked as InProgress.
    * WorkItems marked as InProgress will be hidden from [[pullOutstanding]] until this window expires.
    */
  lazy val inProgressRetryAfter: Duration = Duration.millis(
    config.getLong(inProgressRetryAfterProperty)
  )

  private def newWorkItem(receivedAt: DateTime, availableAt: DateTime, initialState: T => ProcessingStatus)(item: T) = WorkItem(
    id = BSONObjectID.generate,
    receivedAt = receivedAt,
    updatedAt = now,
    availableAt = availableAt,
    status = initialState(item),
    failureCount = 0,
    item = item
  )

  override def indexes: Seq[Index] = Seq(
    Index(
      key = Seq(
        workItemFields.status -> IndexType.Ascending,
        workItemFields.updatedAt -> IndexType.Ascending
      ),
      unique = false,
      background = true),
    Index(
      key = Seq(
        workItemFields.status -> IndexType.Ascending,
        workItemFields.availableAt -> IndexType.Ascending
      ),
      unique = false,
      background = true),
    Index(
      key = Seq(workItemFields.status -> IndexType.Ascending),
      unique = false,
      background = true)
  )

  private def toDo(item: T): ProcessingStatus = ToDo

  /** Creates a new [[WorkItem]] with status ToDo and availableAt equal to receivedAt */
  def pushNew(item: T, receivedAt: DateTime)(implicit ec: ExecutionContext): Future[WorkItem[T]] =
    pushNew(item, receivedAt, receivedAt, toDo _)

  /** Creates a new [[WorkItem]] with availableAt equal to receivedAt */
  def pushNew(item: T, receivedAt: DateTime, initialState: T => ProcessingStatus)(implicit ec: ExecutionContext): Future[WorkItem[T]] =
    pushNew(item, receivedAt, receivedAt, initialState)

  /** Creates a new [[WorkItem]] with status ToDo */
  def pushNew(item: T, receivedAt: DateTime, availableAt: DateTime)(implicit ec: ExecutionContext): Future[WorkItem[T]] =
    pushNew(item, receivedAt, availableAt, toDo _)

  /** Creates a new [[WorkItem]].
    * @param item the item to store in the WorkItem
    * @param receivedAt when the item was received
    * @param availableAt when to defer processing until
    * @param initialState defines the initial state of the WorkItem for the item
    */
  def pushNew(item: T, receivedAt: DateTime, availableAt: DateTime, initialState: T => ProcessingStatus)(implicit ec: ExecutionContext): Future[WorkItem[T]] = {
    val workItem = newWorkItem(receivedAt, availableAt, initialState)(item)
    insert(workItem).map(_ => workItem)
  }

  /** Creates a batch of new [[WorkItem]]s with status ToDo and availableAt equal to receivedAt */
  def pushNew(items: Seq[T], receivedAt: DateTime)(implicit ec: ExecutionContext): Future[Seq[WorkItem[T]]] =
    pushNew(items, receivedAt, receivedAt, toDo _)

  /** Creates a batch of new [[WorkItem]]s with availableAt equal to receivedAt */
  def pushNew(items: Seq[T], receivedAt: DateTime, initialState: T => ProcessingStatus)(implicit ec: ExecutionContext): Future[Seq[WorkItem[T]]] =
    pushNew(items, receivedAt, receivedAt, initialState)

  /** Creates a batch of new [[WorkItem]]s.
    * @param items the items to store as WorkItems
    * @param receivedAt when the items were received
    * @param availableAt when to defer processing until
    * @param initialState defines the initial state of the WorkItems for the item
    */
  def pushNew(items: Seq[T], receivedAt: DateTime, availableAt: DateTime, initialState: T => ProcessingStatus)(implicit ec: ExecutionContext): Future[Seq[WorkItem[T]]] = {
    val workItems = items.map(newWorkItem(receivedAt, availableAt, initialState))

    bulkInsert(workItems).map { savedCount =>
      if (savedCount.n == workItems.size) workItems
      else throw new RuntimeException(s"Only $savedCount items were saved")
    }
  }

  private case class IdList(_id : BSONObjectID)
  private implicit val read: Reads[IdList] = {
    implicit val objectIdReads: Reads[BSONObjectID] = ReactiveMongoFormats.objectIdRead
    Json.reads[IdList]
  }

  /** Returns a WorkItem to be processed, if available.
    * The item will be atomically set to [[InProgress]], so it will not be picked up by other calls to pullOutstanding until
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
  def pullOutstanding(failedBefore: DateTime, availableBefore: DateTime)(implicit ec: ExecutionContext): Future[Option[WorkItem[T]]] = {

    def getWorkItem(idList: IdList): Future[Option[WorkItem[T]]] = {
      import ReactiveMongoFormats.objectIdWrite
      collection.find(
        selector = Json.obj(workItemFields.id -> idList._id),
        projection = None
      ).one[WorkItem[T]]
    }

    val id = findNextItemId(failedBefore, availableBefore)
    id.map(_.map(getWorkItem)).flatMap(_.getOrElse(Future.successful(None)))
  }

  private def findNextItemId(failedBefore: DateTime, availableBefore: DateTime)(implicit ec: ExecutionContext) : Future[Option[IdList]] = {

    def findNextItemIdByQuery(query: JsObject)(implicit ec: ExecutionContext): Future[Option[IdList]] =
      findAndUpdate(
        query = query,
        update = setStatusOperation(InProgress, None),
        fetchNewObject = true,
        fields = Some(Json.obj(workItemFields.id -> 1))
      ).map(
        _.value.map(Json.toJson(_).as[IdList])
      )

    def todoQuery: JsObject =
      Json.obj(
        workItemFields.status -> ToDo,
        workItemFields.availableAt -> Json.obj("$lt" -> availableBefore)
      )

    def failedQuery: JsObject =
      Json.obj("$or" -> Seq(
        Json.obj(workItemFields.status -> Failed, workItemFields.updatedAt -> Json.obj("$lt" -> failedBefore), workItemFields.availableAt -> Json.obj("$lt" -> availableBefore)),
        Json.obj(workItemFields.status -> Failed, workItemFields.updatedAt -> Json.obj("$lt" -> failedBefore), workItemFields.availableAt -> Json.obj("$exists" -> false))
      ))


    def inProgressQuery: JsObject =
      Json.obj(
        workItemFields.status -> InProgress,
        workItemFields.updatedAt -> Json.obj("$lt" -> now.minus(inProgressRetryAfter))
      )

    findNextItemIdByQuery(todoQuery).flatMap {
      case None => findNextItemIdByQuery(failedQuery).flatMap {
        case None => findNextItemIdByQuery(inProgressQuery)
        case item => Future.successful(item)
      }
      case item => Future.successful(item)
    }
  }

  /** Sets the ProcessingStatus of a WorkItem.
    * It will also update the updatedAt timestamp.
    */
  def markAs(id: ID, status: ProcessingStatus, availableAt: Option[DateTime] = None)(implicit ec: ExecutionContext): Future[Boolean] =
    collection.update(
      selector = Json.obj(workItemFields.id -> id),
      update = setStatusOperation(status, availableAt)
    ).map(_.n > 0)

  /** Sets the ProcessingStatus of a WorkItem to a ResultStatus.
    * It will also update the updatedAt timestamp.
    * It will return false if the WorkItem is not InProgress.
    */
  def complete(id: ID, newStatus: ProcessingStatus with ResultStatus)(implicit ec: ExecutionContext): Future[Boolean] =
    collection.update(
      selector = Json.obj(workItemFields.id -> id, workItemFields.status -> InProgress),
      update = setStatusOperation(newStatus, None)
    ).map(_.nModified > 0)

  /** Sets the ProcessingStatus of a WorkItem to Cancelled.
    * @return [[StatusUpdateResult.Updated]] if the WorkItem is cancelled,
    * [[StatusUpdateResult.NotFound]] if it's not found,
    * and [[StatusUpdateResult.NotUpdated]] if it's not in a cancellable ProcessingStatus.
    */
  def cancel(id: ID)(implicit ec: ExecutionContext): Future[StatusUpdateResult] = {
    import uk.gov.hmrc.workitem.StatusUpdateResult._
    findAndUpdate(
      query = Json.obj(
        workItemFields.id -> id,
        workItemFields.status -> Json.obj("$in" -> List(ToDo, Failed, PermanentlyFailed, Ignored, Duplicate, Deferred)) // TODO we should be able to express the valid to/from states in traits of ProcessingStatus
      ),
      update = setStatusOperation(Cancelled, None),
      fetchNewObject = false
    ).flatMap { res =>
      res.value match {
        case Some(item) => Future.successful(Updated(
          previousStatus = Json.toJson(item).\(workItemFields.status).as[ProcessingStatus],
          newStatus = Cancelled
        ))
        case None => findById(id).map {
          case Some(item) => NotUpdated(item.status)
          case None => NotFound
        }
      }
    }
  }

  /** Returns the number of WorkItems in the specified ProcessingStatus */
  def count(state: ProcessingStatus)(implicit ec: ExecutionContext): Future[Int] =
    count(Json.obj(workItemFields.status -> state.name), ReadPreference.secondaryPreferred)

  private def setStatusOperation(newStatus: ProcessingStatus, availableAt: Option[DateTime]): JsObject = {
    val fields = Json.obj(
      workItemFields.status -> newStatus,
      workItemFields.updatedAt -> now
    ) ++ availableAt.map(when => Json.obj(workItemFields.availableAt -> when)).getOrElse(Json.obj())

    val ifFailed =
      if (newStatus == Failed)
        Json.obj("$inc" -> Json.obj(workItemFields.failureCount -> 1))
      else Json.obj()

    Json.obj("$set" -> fields) ++ ifFailed
  }

}
object Operations {
  trait Cancel[ID] {
    def cancel(id: ID)(implicit ec: ExecutionContext): Future[StatusUpdateResult]
  }
  trait FindById[ID, T] {
    def findById(id: ID, readPreference : reactivemongo.api.ReadPreference)(implicit ec: ExecutionContext): Future[Option[WorkItem[T]]]
  }
}
