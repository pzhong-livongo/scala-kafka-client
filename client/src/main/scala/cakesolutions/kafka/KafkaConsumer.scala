package cakesolutions.kafka

import cakesolutions.kafka.TypesafeConfigExtensions._
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.internals.NoOpConsumerRebalanceListener
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRebalanceListener, ConsumerRecords, OffsetResetStrategy, KafkaConsumer => JKafkaConsumer}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.Deserializer

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * Utilities for creating a Kafka consumer.
  *
  * This singleton object provides tools for creating Kafka consumers using helpful functions.
  * Unlike with [[KafkaProducer]], the consumer object is not wrapped in an object that provides a Scala-like API.
  */
object KafkaConsumer {

  /**
    * Utilities for creating Kafka consumer configurations.
    */
  object Conf {

    /**
      * Kafka consumer configuration constructor with common configurations as parameters.
      * For more detailed configuration, use the other [[Conf]] constructors.
      *
      * @param keyDeserializer deserializer for the key
      * @param valueDeserializer deserializer for the value
      * @param bootstrapServers a list of host/port pairs to use for establishing the initial connection to the Kafka cluster
      * @param groupId a unique string that identifies the consumer group this consumer belongs to
      * @param enableAutoCommit if true the consumer's offsets will be periodically committed in the background
      * @param autoCommitInterval the frequency in milliseconds that the consumer offsets are auto-committed to Kafka when auto commit is enabled
      * @param sessionTimeoutMs the timeout used to detect failures when using Kafka's group management facilities
      * @param maxPartitionFetchBytes the maximum amount of data per-partition the server will return
      * @param maxPollRecords the maximum number of records returned in a single call to poll()
      * @param autoOffsetReset what to do when there is no initial offset in Kafka or if the current offset does not exist any more on the server
      * @tparam K key deserialiser type
      * @tparam V value deserialiser type
      * @return consumer configuration consisting of all the given values
      */
    def apply[K, V](
      keyDeserializer: Deserializer[K],
      valueDeserializer: Deserializer[V],
      bootstrapServers: String = "localhost:9092",
      groupId: String,
      enableAutoCommit: Boolean = true,
      autoCommitInterval: Int = 1000,
      sessionTimeoutMs: Int = 30000,
      maxPartitionFetchBytes: Int = ConsumerConfig.DEFAULT_MAX_PARTITION_FETCH_BYTES,
      maxPollRecords: Int = Integer.MAX_VALUE,
      autoOffsetReset: OffsetResetStrategy = OffsetResetStrategy.LATEST
    ): Conf[K, V] = {

      val configMap = Map[String, AnyRef](
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> bootstrapServers,
        ConsumerConfig.GROUP_ID_CONFIG -> groupId,
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> enableAutoCommit.toString,
        ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG -> autoCommitInterval.toString,
        ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG -> sessionTimeoutMs.toString,
        ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG -> maxPartitionFetchBytes.toString,
        ConsumerConfig.MAX_POLL_RECORDS_CONFIG -> maxPollRecords.toString,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> autoOffsetReset.toString.toLowerCase
      )

      apply(configMap, keyDeserializer, valueDeserializer)
    }

    /**
      * Creates a Kafka consumer configuration from a Typesafe config.
      *
      * The configuration names and values must match the Kafka's `ConsumerConfig` style.
      *
      * @param config a Typesafe config to build configuration from
      * @param keyDeserializer deserialiser for the key
      * @param valueDeserializer deserialiser for the value
      * @tparam K key deserialiser type
      * @tparam V value deserialiser type
      * @return consumer configuration
      */
    def apply[K, V](config: Config, keyDeserializer: Deserializer[K], valueDeserializer: Deserializer[V]): Conf[K, V] =
      apply(config.toPropertyMap, keyDeserializer, valueDeserializer)
  }

  /**
    * Configuration object for the Kafka consumer.
    *
    * The config is compatible with Kafka's `ConsumerConfig`.
    * All the key-value properties are specified in the given map, except the deserializers.
    * The key and value deserialiser instances are provided explicitly to ensure type-safety.
    *
    * @param props map of `ConsumerConfig` Properties
    * @tparam K key deserializer type
    * @tparam V value deserializer type
    */
  final case class Conf[K, V](
    props: Map[String, AnyRef],
    keyDeserializer: Deserializer[K],
    valueDeserializer: Deserializer[V]
  ) {

    /**
      * Extend the config with additional Typesafe config.
      * The supplied config overrides existing properties.
      */
    def withConf(config: Config): Conf[K, V] =
      copy(props = props ++ config.toPropertyMap)

    /**
      * Extend the configuration with a single key-value pair.
      */
    def withProperty(key: String, value: AnyRef) =
      copy(props = props + (key -> value))
  }

  /**
    * Create a Kafka consumer using the provided consumer configuration.
    *
    * @param conf configuration for the consumer
    * @tparam K key serialiser type
    * @tparam V value serialiser type
    * @return Kafka consumer client
    */
  def apply[K, V](conf: Conf[K, V]): KafkaConsumer[K, V] =
    new KafkaConsumer(new JKafkaConsumer[K, V](conf.props.asJava, conf.keyDeserializer, conf.valueDeserializer))
}

final class KafkaConsumer[K, V](val consumer: JKafkaConsumer[K, V]) {

  /**
    * Unsubscribe from topics currently subscribed to.
    *
    * This also clears any directly assigned partitions.
    */
  def unsubscribe(): Unit = consumer.unsubscribe()

  /**
    * Subscribe to Kafka using given subscription strategy.
    *
    * @param sub subscription mode to subscribe with
    * @param rebalanceListener listener for consumer rebalances. Only used with auto-partition mode.
    */
  def subscribe(sub: Subscribe, rebalanceListener: ConsumerRebalanceListener = new NoOpConsumerRebalanceListener): Unit =
    sub match {
      case Subscribe.AutoPartition(topics) =>
        consumer.subscribe(topics.toList.asJava, rebalanceListener)

      case Subscribe.ManualPartition(topicPartitions) =>
        consumer.assign(topicPartitions.toList.asJava)

      case Subscribe.ManualOffset(offsets) =>
        consumer.assign(offsets.topicPartitions.asJava)
        seekOffsets(offsets)
    }

  /**
    * Seek consumer to use given topic partitions.
    */
  def seekOffsets(offsets: Offsets): Unit =
    offsets.offsetsMap.foreach {
      case (key, value) => consumer.seek(key, value)
    }

  /**
    * Commit given offsets synchronously.
    */
  def commitSync(offsets: Offsets): Unit =
    consumer.commitSync(offsets.toCommitMap.asJava)


  /**
    * Commit offsets for the last consumed records synchronously.
    */
  def commitSyncLast(): Unit =
    consumer.commitSync()

  /**
    * Commit offsets asynchronously
    */
  def commitAsync(offsets: Offsets, callback: Try[Offsets] => Unit): Unit =
    consumer.commitAsync(offsets.toCommitMap.asJava, OffsetCommitCallback(callback))

  /**
    * Commit offsets for the last consumed records asynchronously.
    */
  def commitAsyncLast(callback: Try[Offsets] => Unit): Unit =
    consumer.commitAsync(OffsetCommitCallback(callback))

  /**
    * Get the offsets for given partitions.
    */
  def offsets(partitions: Iterable[TopicPartition]): Offsets =
    Offsets(
      partitions.map(p => p -> consumer.position(p)).toMap
    )

  /**
    * Get the current consumer offsets.
    */
  def offsets: Offsets = offsets(consumer.assignment().asScala)

  /**
    * Close consumer.
    */
  def close(): Unit = consumer.close()

  /**
    * Fetch data from subscribed topics/partitions.
    *
    * @param timeout the time in milliseconds to wait for incoming data
    * @return consumer records fetched from Kafka
    */
  def poll(timeout: Long = 0L): ConsumerRecords[K, V] =
    consumer.poll(timeout)
}
