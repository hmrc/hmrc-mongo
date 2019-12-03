
# metrix

Metrix is a library to help with [Dropwizard](https://metrics.dropwizard.io) metric collection across a multi-node system, 
when you only want one node to do the data gathering. This can help with slow metric reporting, so that only one node is 
doing the slow-running action.

It does this by using a backing Mongo persistence store that is shared across all nodes, using `mongo-lock` to ensure 
that only one node can write the metrics.

All nodes then periodically refresh an internal cache of the metrics from the persisted collection. 

Ultimately, the custom metrics collected via this library are all stored as a [Gauge[Int]](https://metrics.dropwizard.io/3.1.0/getting-started/#gauges) 
in the normal Dropwizard metric registries, meaning that they will be treated the same way as any other metrics you are 
collecting, and will be shipped out by whatever method you are using. 

> In DropWizard, a gauge is an instantaneous measurement of a value. They implemented a single method interface, which is just:
> `T getValue();`

## How data gathering works
<img src="https://github.com/hmrc/hmrc-mongo/blob/master/hmrc-mongo-metrix-play-26/diagrams/metrixDataGathering.png" width="500" alt="Metric Gathering">

Metrix has a very simple interface, and all metrics collected are a simple integer count.

There is a single method to be implemented to gather metrics, in `MetricSource`:

```
def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]]
```

All nodes periodically try to acquire the lock. As seen on the above picture after one of the nodes manages to acquire the 
lock, it will gather the data through predefined classes that implement the MetricSource trait, and write it to the datastore.
The lock is needed as the metric gathering might be a resource and time heavy operation.

All the nodes will also periodically get all the metrics and update their local MetricCache. The mongo lock is not needed 
for this operation. They also control the creation and registration of individual CachedGaguges which are tied with the standard metric reporting mechanism. They would typically read values from MetricCache whenever asked for it. See below for details.

## How reporting works

<img src="https://github.com/hmrc/hmrc-mongo/blob/master/hmrc-mongo-metrix-play-26/diagrams/metricReportingMechanism.png" width="250" alt="Metric Reporting">

There is a CacheGauge registered in the MetricRegistry for each metric gathered from metric sources.

Graphite reporter is will run on a regular (configured) basis and retrieve all registered Gaguges, calling the 
getValue() method on each of them.

The CacheGauges will then read the value for a given metric from the MetricCache and return it. GraphiteReporter will pass 
this information to graphite.

## Example construction
``` scala
import scala.concurrent.duration.DurationInt


val lockId: String                            = "your-lock-id"
val ttl: Duration                             = 10.seconds
val mongoLockService                          = mongoLockRepository.toService(lockId, ttl)

val sources: List[MetricSource] = ... AddYourMetricSourcesHere ...

val metricOrchestrator new MetricOrchestrator(
  metricSources     = sources,
  lockService       = mongoLockService,
  metricRepository  = metricRepository,
  metricRegistry    = metricRegistry
)
```
## Example usage    
``` scala
metricOrchestrator.attemptMetricRefresh().map(_.andLogTheResult())
    .recover { case e: RuntimeException => Logger.error(s"An error occurred processing metrics: ${e.getMessage}", e) }
```      

There are two filters of the form `(PersistedMetric => Boolean)` you can pass to the `attemptMetricRefresh` function:

 * skipReportingFor => Disable reporting for a particular metric
 * resetToZeroFor   => Set a particular metric to zero (reset it)

## Installing
 
Metrix is now part of and versioned with the `hmrc-mongo` library, which provides helper utilities for building systems with mongo.
 
Include the following dependency in your SBT build
 
``` scala
resolvers += Resolver.bintrayRepo("hmrc", "releases")
 
libraryDependencies += "uk.gov.hmrc" %% "hmrc-mongo-metrix-play-26" % "[INSERT-VERSION]" 
```
## Compatibility
Metrix uses the official [mongo-scala](https://mongodb.github.io/mongo-scala-driver/) driver, instead of [ReactiveMongo](https://github.com/ReactiveMongo/ReactiveMongo) 
which was used in [previous (now deprecated) releases](https://github.com/hmrc/metrix) under the `metrix` (instead of `hmrc-mongo-metrix`) artifact ID. 

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
    
