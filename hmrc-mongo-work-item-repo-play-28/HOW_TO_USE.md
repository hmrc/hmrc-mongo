# work-item-repo

## How to use

### WorkItemRepository

See Scaladoc for [WorkItemRepository](../master/src/main/scala/uk/gov/hmrc/workitem/WorkItemRepository.scala)

Typically you will use `uk.gov.hmrc.hmrc.mongo.workitem.WorkItemRepository` to create and retrieve `WorkItem`s for processing.
It is parameterised by your PAYLOAD representation.

It is an abstract class, so you will have to extend it to define the following:

* `def now: Instant` - gets the current timestamp for setting the WorkItem updatedAt field.
* `def inProgressRetryAfter: Duration` - defines how long to wait before retrying failed WorkItems.


e.g.

```scala
@Singleton
class GithubRequestsQueueRepository @Inject()(
  configuration : Configuration,
  mongoComponent: MongoComponent
) extends WorkItemRepository[MyWorkItem](
  collectionName = "myWorkItems",
  mongoComponent = mongoComponent,
  itemFormat     = MyWorkItem.mongoFormat,
  workItemFields = WorkItemFields.default
) {
  override def now(): Instant =
    Instant.now()

  override val inProgressRetryAfter: Duration =
    configuration.getDuration("queue.retryAfter")

```

### Using WorkItemRepository

- `pushNew(item: T, availableAt: Instant, initialState: T => ProcessingStatus): Future[WorkItem[T]]`

You can push new WorkItems into the queue with `pushNew`. You can use `pushNewBatch` for bulk creation. You can explicitly set the initial status, else it will default to `ToDo`. You can also explicitly set the `availableAt`, otherwise it be available for processing immediately.

- `pullOutstanding(failedBefore: DateTime, availableBefore: DateTime): Future[Option[WorkItem[T]]]`

You can retrieve WorkItems for processing with `pullOutstanding`. This returns one WorkItem if available, and also updates it's status atomically to `InProgress` which means it will be hidden from subsequent calls, ensuring that a single WorkItem will only be seen by a single instance of your service.
You should resolve the WorkItem once you have finished processing it my calling `complete`, `completeAndDelete`, `cancel` or `markAs` to change it's status.
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
        case Success => workItemRepository.complete(wi.id, ProcessingStatus.Succeeded) // mark as completed
                        workItemRepository.completeAndDelete(wi.id) // alternatively, remove from mongo
        case Failure if wi.failureCount < config.maxRetries =>
                        workItemRepository.markAs(wi.id, ProcessingStatus.Failed) // mark as failed - it will be reprocessed after a duration specified by `inProgressRetryAfterProperty`
        case Failure => workItemRepository.markAs(wi.id, ProcessingStatus.PermanentlyFailed) // you can also mark as any other status defined by `ProcessingStatus`
      }.flatMap(_ => process) // and repeat
    }
```
