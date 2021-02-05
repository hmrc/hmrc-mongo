# work-item-repo

## How to use

### WorkItemModuleRepository

See Scaladoc for [WorkItemRepository](../master/src/main/scala/uk/gov/hmrc/workitem/WorkItemRepository.scala)

Typically you will use `uk.gov.hmrc.workitem.WorkItemRepository` to create and retrieve `WorkItem`s for processing.
It is parameterised by the Id representation (typically `BSONObjectID` or `String`) and your PAYLOAD representation.

It is an abstract class, so you will have to extend it to define the following:

* `def now: DateTime` - gets the current timestamp for setting the WorkItem updatedAt field.
* `def workItemFields: WorkItemFieldNames` - defines how to map the WorkItem into your mongo collection.
* `def inProgressRetryAfterProperty: String` - defines the configuration key for setting how long to wait before retrying failed WorkItems.



e.g.

```scala
@Singleton
class GithubRequestsQueueRepository @Inject()(configuration: Configuration, reactiveMongoComponent: ReactiveMongoComponent) extends WorkItemRepository[MyWorkItem, BSONObjectID](
  collectionName = "myWorkItems",
  mongo          = reactiveMongoComponent.mongoConnector.db,
  itemFormat     = MyWorkItem.mongoFormats,
  config         = configuration.underlying
) {
  override def now: DateTime =
    DateTime.now

  override lazy val workItemFields: WorkItemFieldNames =
    new WorkItemFieldNames {
      val receivedAt   = "receivedAt"
      val updatedAt    = "updatedAt"
      val availableAt  = "receivedAt"
      val status       = "status"
      val id           = "_id"
      val failureCount = "failureCount"
    }

  override val inProgressRetryAfterProperty: String =
    "queue.retryAfter"
```

### Using WorkItemModuleRepository

- `pushNew(item: T, receivedAt: DateTime, availableAt: DateTime, initialState: T => ProcessingStatus): Future[WorkItem[T]]`

You can push new WorkItems into the queue with `pushNew`. This function is overloaded to allow bulk creation. You can explicitly set the initial status, else it will default to `ToDo`. You can also explicitly set the `availableAt`, otherwise it be available for processing at the same time as `receivedAt`.

- `pullOutstanding(failedBefore: DateTime, availableBefore: DateTime): Future[Option[WorkItem[T]]]`

You can retrieve WorkItems for processing with `pullOutstanding`. This returns one WorkItem if available, and also updates it's status atomically to `InProgress` which means it will be hidden from subsequent calls, ensuring that a single WorkItem will only be seen by a single instance of your service.
You should resolve the WorkItem once you have finished processing it my calling `complete`, `cancel` or `markAs` to change it's status.
Should you fail to update the status, e.g. you process crashes, then the WorkItem will be hidden from subsequent calls to `pullOutstanding` until it has "timed out". The timeout expiration is configured in configuration by the key as defined in `inProgressRetryAfterProperty`.

You can repeatedly call `pullOutstanding` (e.g. on a scheduler) until there are no more WorkItems to be processed.

If you mark a WorkItem as `Failed` (with `markAs`), it will be timestamped with the result of `now`. This allows you to not reprocess `Failed` WorkItems immediately by providing an appropriate `failedBefore` parameter.
Similarly, `ToDo` WorkItems will only be returned when their `availableAt` field is after the provided `availableBefore` parameter.

e.g.
```scala
def process: Future[Unit] =
  workItemRepository.pullOutstanding(failedBefore = now.minus(1, day), availableBefore = now) // grab the next WorkItem
    .flatMap {
      case None => Future.successful(()) // there is no more - we've finished
      case Some(wi) => processWorkItem(wi.item).flatMap { // call your function to process a WorkItem
        case Success => workItemRepository.complete(wi.id) // mark as completed
        case Failure if wi.failureCount < config.maxRetries => workItemRepository.markAs(wi.id, Failed, None) // mark as failed - it will be reprocessed after a duration specified by `inProgressRetryAfterProperty`
        case Failure => workItemRepository.markAs(wi.id, PermanentlyFailed, None) // you can also mark as any other status defined by `ProcessingStatus`
      }.flatMap(_ => process) // and repeat
    }
```