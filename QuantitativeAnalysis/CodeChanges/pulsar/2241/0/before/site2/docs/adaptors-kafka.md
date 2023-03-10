---
id: adaptors-kafka
title: Pulsar adaptor for Apache Kafka
sidebar_label: Kafka client wrappter
---


Pulsar provides an easy option for applications that are currently written using the [Apache Kafka](http://kafka.apache.org) Java client API.

## Using the Pulsar Kafka compatibility wrapper

In an existing application, change the regular Kafka client dependency and replace it with the Pulsar Kafka wrapper. Remove:

```xml
<dependency>
  <groupId>org.apache.kafka</groupId>
  <artifactId>kakfa-clients</artifactId>
  <version>0.10.2.1</version>
</dependency>
```

Then include this dependency for the Pulsar Kafka wrapper:

```xml
<dependency>
  <groupId>org.apache.pulsar</groupId>
  <artifactId>pulsar-client-kafka</artifactId>
  <version>{{pulsar:version}}</version>
</dependency>
```

With the new dependency, the existing code should work without any changes. The only
thing that needs to be adjusted is the configuration, to make sure to point the
producers and consumers to Pulsar service rather than Kafka and to use a particular
Pulsar topic.

## Using the Pulsar Kafka compatibility wrapper together with existing kafka client.

When migrating from Kafka to Pulsar, the application might have to use the original kafka client
and the pulsar kafka wrapper together during migration. Then you should consider using the
unshaded pulsar kafka client wrapper.

```xml
<dependency>
  <groupId>org.apache.pulsar</groupId>
  <artifactId>pulsar-client-kafka-original</artifactId>
  <version>{{pulsar:version}}</version>
</dependency>
```

When using this dependency, you need to construct producer using `org.apache.kafka.clients.producer.PulsarKafkaProducer`
instead of `org.apache.kafka.clients.producer.KafkaProducer` and `org.apache.kafka.clients.producer.PulsarKafkaConsumer` for consumers.

## Producer example

```java
// Topic needs to be a regular Pulsar topic
String topic = "persistent://public/default/my-topic";

Properties props = new Properties();
// Point to a Pulsar service
props.put("bootstrap.servers", "pulsar://localhost:6650");

props.put("key.serializer", IntegerSerializer.class.getName());
props.put("value.serializer", StringSerializer.class.getName());

Producer<Integer, String> producer = new KafkaProducer<>(props);

for (int i = 0; i < 10; i++) {
    producer.send(new ProducerRecord<Integer, String>(topic, i, "hello-" + i));
    log.info("Message {} sent successfully", i);
}

producer.close();
```

## Consumer example

```java
String topic = "persistent://public/default/my-topic";

Properties props = new Properties();
// Point to a Pulsar service
props.put("bootstrap.servers", "pulsar://localhost:6650");
props.put("group.id", "my-subscription-name");
props.put("enable.auto.commit", "false");
props.put("key.deserializer", IntegerDeserializer.class.getName());
props.put("value.deserializer", StringDeserializer.class.getName());

Consumer<Integer, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList(topic));

while (true) {
    ConsumerRecords<Integer, String> records = consumer.poll(100);
    records.forEach(record -> {
        log.info("Received record: {}", record);
    });

    // Commit last offset
    consumer.commitSync();
}
```

## Complete Examples

You can find the complete producer and consumer examples
[here](https://github.com/apache/incubator-pulsar/tree/master/pulsar-client-kafka-compat/pulsar-client-kafka-tests/src/test/java/org/apache/pulsar/client/kafka/compat/examples).

## Compatibility matrix

Currently the Pulsar Kafka wrapper supports most of the operations offered by the Kafka API.

#### Producer

APIs:

| Producer Method                                                               | Supported | Notes                                                                    |
|:------------------------------------------------------------------------------|:----------|:-------------------------------------------------------------------------|
| `Future<RecordMetadata> send(ProducerRecord<K, V> record)`                    | Yes       | Currently no support for explicitly set the partition id when publishing |
| `Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback)` | Yes       |                                                                          |
| `void flush()`                                                                | Yes       |                                                                          |
| `List<PartitionInfo> partitionsFor(String topic)`                             | No        |                                                                          |
| `Map<MetricName, ? extends Metric> metrics()`                                 | No        |                                                                          |
| `void close()`                                                                | Yes       |                                                                          |
| `void close(long timeout, TimeUnit unit)`                                     | Yes       |                                                                          |

Properties:

| Config property                         | Supported | Notes                                                                         |
|:----------------------------------------|:----------|:------------------------------------------------------------------------------|
| `acks`                                  | Ignored   | Durability and quorum writes are configured at the namespace level            |
| `batch.size`                            | Ignored   |                                                                               |
| `block.on.buffer.full`                  | Yes       | If true it will block producer, otherwise give error                          |
| `bootstrap.servers`                     | Yes       | Needs to point to a single Pulsar service URL                                 |
| `buffer.memory`                         | Ignored   |                                                                               |
| `client.id`                             | Ignored   |                                                                               |
| `compression.type`                      | Yes       | Allows `gzip` and `lz4`. No `snappy`.                                         |
| `connections.max.idle.ms`               | Ignored   |                                                                               |
| `interceptor.classes`                   | Ignored   |                                                                               |
| `key.serializer`                        | Yes       |                                                                               |
| `linger.ms`                             | Yes       | Controls the group commit time when batching messages                         |
| `max.block.ms`                          | Ignored   |                                                                               |
| `max.in.flight.requests.per.connection` | Ignored   | In Pulsar ordering is maintained even with multiple requests in flight        |
| `max.request.size`                      | Ignored   |                                                                               |
| `metric.reporters`                      | Ignored   |                                                                               |
| `metrics.num.samples`                   | Ignored   |                                                                               |
| `metrics.sample.window.ms`              | Ignored   |                                                                               |
| `partitioner.class`                     | Ignored   |                                                                               |
| `receive.buffer.bytes`                  | Ignored   |                                                                               |
| `reconnect.backoff.ms`                  | Ignored   |                                                                               |
| `request.timeout.ms`                    | Ignored   |                                                                               |
| `retries`                               | Ignored   | Pulsar client retries with exponential backoff until the send timeout expires |
| `send.buffer.bytes`                     | Ignored   |                                                                               |
| `timeout.ms`                            | Ignored   |                                                                               |
| `value.serializer`                      | Yes       |                                                                               |


#### Consumer

APIs:

| Consumer Method                                                                                         | Supported | Notes |
|:--------------------------------------------------------------------------------------------------------|:----------|:------|
| `Set<TopicPartition> assignment()`                                                                      | No        |       |
| `Set<String> subscription()`                                                                            | Yes       |       |
| `void subscribe(Collection<String> topics)`                                                             | Yes       |       |
| `void subscribe(Collection<String> topics, ConsumerRebalanceListener callback)`                         | No        |       |
| `void assign(Collection<TopicPartition> partitions)`                                                    | No        |       |
| `void subscribe(Pattern pattern, ConsumerRebalanceListener callback)`                                   | No        |       |
| `void unsubscribe()`                                                                                    | Yes       |       |
| `ConsumerRecords<K, V> poll(long timeoutMillis)`                                                        | Yes       |       |
| `void commitSync()`                                                                                     | Yes       |       |
| `void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets)`                                       | Yes       |       |
| `void commitAsync()`                                                                                    | Yes       |       |
| `void commitAsync(OffsetCommitCallback callback)`                                                       | Yes       |       |
| `void commitAsync(Map<TopicPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback)`       | Yes       |       |
| `void seek(TopicPartition partition, long offset)`                                                      | Yes       |       |
| `void seekToBeginning(Collection<TopicPartition> partitions)`                                           | Yes       |       |
| `void seekToEnd(Collection<TopicPartition> partitions)`                                                 | Yes       |       |
| `long position(TopicPartition partition)`                                                               | Yes       |       |
| `OffsetAndMetadata committed(TopicPartition partition)`                                                 | Yes       |       |
| `Map<MetricName, ? extends Metric> metrics()`                                                           | No        |       |
| `List<PartitionInfo> partitionsFor(String topic)`                                                       | No        |       |
| `Map<String, List<PartitionInfo>> listTopics()`                                                         | No        |       |
| `Set<TopicPartition> paused()`                                                                          | No        |       |
| `void pause(Collection<TopicPartition> partitions)`                                                     | No        |       |
| `void resume(Collection<TopicPartition> partitions)`                                                    | No        |       |
| `Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(Map<TopicPartition, Long> timestampsToSearch)` | No        |       |
| `Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions)`                     | No        |       |
| `Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions)`                           | No        |       |
| `void close()`                                                                                          | Yes       |       |
| `void close(long timeout, TimeUnit unit)`                                                               | Yes       |       |
| `void wakeup()`                                                                                         | No        |       |

Properties:

| Config property                 | Supported | Notes                                                 |
|:--------------------------------|:----------|:------------------------------------------------------|
| `group.id`                      | Yes       | Maps to a Pulsar subscription name                    |
| `max.poll.records`              | Ignored   |                                                       |
| `max.poll.interval.ms`          | Ignored   | Messages are "pushed" from broker                     |
| `session.timeout.ms`            | Ignored   |                                                       |
| `heartbeat.interval.ms`         | Ignored   |                                                       |
| `bootstrap.servers`             | Yes       | Needs to point to a single Pulsar service URL         |
| `enable.auto.commit`            | Yes       |                                                       |
| `auto.commit.interval.ms`       | Ignored   | With auto-commit, acks are sent immediately to broker |
| `partition.assignment.strategy` | Ignored   |                                                       |
| `auto.offset.reset`             | Ignored   |                                                       |
| `fetch.min.bytes`               | Ignored   |                                                       |
| `fetch.max.bytes`               | Ignored   |                                                       |
| `fetch.max.wait.ms`             | Ignored   |                                                       |
| `metadata.max.age.ms`           | Ignored   |                                                       |
| `max.partition.fetch.bytes`     | Ignored   |                                                       |
| `send.buffer.bytes`             | Ignored   |                                                       |
| `receive.buffer.bytes`          | Ignored   |                                                       |
| `client.id`                     | Ignored   |                                                       |


## Custom Pulsar configurations

You can configure Pulsar authentication provider directly from the Kafka properties.

### Pulsar client properties:

| Config property                        | Default | Notes                                                                                  |
|:---------------------------------------|:--------|:---------------------------------------------------------------------------------------|
| `pulsar.authentication.class`          |         | Configure to auth provider. Eg. `org.apache.pulsar.client.impl.auth.AuthenticationTls` |
| [`pulsar.use.tls`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ClientConfiguration.html#setUseTls-boolean-)                       | `false` | Enable TLS transport encryption                                                        |
| [`pulsar.tls.trust.certs.file.path`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ClientConfiguration.html#setTlsTrustCertsFilePath-java.lang.String-)   |         | Path for the TLS trust certificate store                                               |
| [`pulsar.tls.allow.insecure.connection`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ClientConfiguration.html#setTlsAllowInsecureConnection-boolean-) | `false` | Accept self-signed certificates from brokers                                           |
| [`pulsar.operation.timeout.ms`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ClientConfiguration.html#setOperationTimeout-int-java.util.concurrent.TimeUnit-) | `30000` | General operations timeout |
| [`pulsar.stats.interval.seconds`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ClientConfiguration.html#setStatsInterval-long-java.util.concurrent.TimeUnit-) | `60` | Pulsar client lib stats printing interval |
| [`pulsar.num.io.threads`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ClientConfiguration.html#setIoThreads-int-) | `1` | Number of Netty IO threads to use |
| [`pulsar.connections.per.broker`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ClientConfiguration.html#setConnectionsPerBroker-int-) | `1` | Max number of connection to open to each broker |
| [`pulsar.use.tcp.nodelay`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ClientConfiguration.html#setUseTcpNoDelay-boolean-) | `true` | TCP no-delay |
| [`pulsar.concurrent.lookup.requests`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ClientConfiguration.html#setConcurrentLookupRequest-int-) | `50000` | Max number of concurrent topic lookups |
| [`pulsar.max.number.rejected.request.per.connection`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ClientConfiguration.html#setMaxNumberOfRejectedRequestPerConnection-int-) | `50` | Threshold of errors to forcefully close a connection |


### Pulsar producer properties

| Config property                        | Default | Notes                                                                                  |
|:---------------------------------------|:--------|:---------------------------------------------------------------------------------------|
| [`pulsar.producer.name`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ProducerConfiguration.html#setProducerName-java.lang.String-) | | Specify producer name |
| [`pulsar.producer.initial.sequence.id`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ProducerConfiguration.html#setInitialSequenceId-long-) |  | Specify baseline for sequence id for this producer |
| [`pulsar.producer.max.pending.messages`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ProducerConfiguration.html#setMaxPendingMessages-int-) | `1000` | Set the max size of the queue holding the messages pending to receive an acknowledgment from the broker.  |
| [`pulsar.producer.max.pending.messages.across.partitions`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ProducerConfiguration.html#setMaxPendingMessagesAcrossPartitions-int-) | `50000` | Set the number of max pending messages across all the partitions  |
| [`pulsar.producer.batching.enabled`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ProducerConfiguration.html#setBatchingEnabled-boolean-) | `true` | Control whether automatic batching of messages is enabled for the producer |
| [`pulsar.producer.batching.max.messages`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ProducerConfiguration.html#setBatchingMaxMessages-int-) | `1000` | The maximum number of messages permitted in a batch |


### Pulsar consumer Properties

| Config property                        | Default | Notes                                                                                  |
|:---------------------------------------|:--------|:---------------------------------------------------------------------------------------|
| [`pulsar.consumer.name`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ConsumerConfiguration.html#setConsumerName-java.lang.String-) | | Set the consumer name |
| [`pulsar.consumer.receiver.queue.size`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ConsumerConfiguration.html#setReceiverQueueSize-int-) | 1000 | Sets the size of the consumer receive queue |
| [`pulsar.consumer.total.receiver.queue.size.across.partitions`](http://pulsar.apache.org/api/client/org/apache/pulsar/client/api/ConsumerConfiguration.html#setMaxTotalReceiverQueueSizeAcrossPartitions-int-) | 50000 | Set the max total receiver queue size across partitons |

