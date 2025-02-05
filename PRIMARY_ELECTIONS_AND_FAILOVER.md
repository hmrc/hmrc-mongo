
## Primary Elections and Failover

Here follows a description of how the official Mongo driver behaves with primary elections and failovers. Many parameters are configurable, so please refer to the official docs for how to alter them.

Heartbeats are sent to each Mongo node every 10 seconds.

When the primary node steps down, or is lost, you will see this error:

```
2021-02-04 13:57:37,360 level=[INFO] logger=[org.mongodb.driver.cluster] thread=[cluster-ClusterId{value='601bfd4f6a5f1c698125405b', description='null'}-mongo1:27017] rid=[] user=[]
message=[Exception in monitor thread while connecting to server mongo1:27017] exception=[com.mongodb.MongoSocketReadException: Prematurely reached end of stream
    at ...
```

`“MongoSocketReadException: Prematurely reached end of stream”` is the first indication that the application cannot talk properly to the cluster.

During the subsequent MongoDB election, this message will be seen frequently, as the driver checks to see if it knows which node is primary.

```
No server chosen by WritableServerSelector from cluster description [ ... ]  Waiting for 30000 ms before timing out
```

The default timeout is 30 seconds. Please note, this is around three times longer than the ReactiveMongo default timeout as used previously. Configure your microservice as required.

Whilst the driver cannot find a Primary node, it increases the frequency of sending heartbeats to query the cluster. This is controlled by the `minHeartbeatFrequency` setting, which defaults to 500ms.

When a primary is found, you will see a log such as this:

```
Discovered replica set primary {name}
```

If no primary is found before the timeout, the driver will send the request to the last known primary and you will likely see this error (as the last known primary is almost certainly no longer primary, and cannot handle writes).

```
com.mongodb.MongoNotPrimaryException: Command failed with error 10107 (NotMaster): 'not master' on server mongo1:27017
```

Otherwise, the timeout happens without being sent:

```
com.mongodb.MongoTimeoutException: Timed out after 20000 ms while waiting for a server that matches WritableServerSelector
```

Once other failure scenario to consider:
If the driver starts up, and cannot connect to the cluster you will get this exception

```
2021-02-08 12:16:22,491 level=[INFO] logger=[org.mongodb.driver.cluster] thread=[cluster-ClusterId{value='60212b8108dfec7fa2f47c0e', description='null'}-mongo1:27017] rid=[] user=[] message=[Exception in monitor thread while connecting to server mongo1:27017] exception=[com.mongodb.MongoSocketException: mongo1
    at com.mongodb.ServerAddress.getSocketAddresses(ServerAddress.java:211)
```
