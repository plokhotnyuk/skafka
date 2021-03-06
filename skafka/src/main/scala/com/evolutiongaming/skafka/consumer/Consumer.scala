package com.evolutiongaming.skafka.consumer

import java.lang.{Long => LongJ}
import java.util.regex.Pattern

import com.evolutiongaming.concurrent.FutureHelper._
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.skafka.Converters._
import com.evolutiongaming.skafka._
import com.evolutiongaming.skafka.consumer.ConsumerConverters._
import org.apache.kafka.clients.consumer.{KafkaConsumer, Consumer => ConsumerJ}

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

/**
  * See [[org.apache.kafka.clients.consumer.Consumer]]
  */
trait Consumer[K, V] {

  def assign(partitions: Nel[TopicPartition]): Unit

  def assignment(): Set[TopicPartition]

  def subscribe(topics: Nel[Topic], listener: Option[RebalanceListener]): Unit

  def subscribe(pattern: Pattern, listener: Option[RebalanceListener]): Unit

  def subscription(): Set[Topic]

  def unsubscribe(): Future[Unit]

  def poll(timeout: FiniteDuration): Future[ConsumerRecords[K, V]]

  def commit(): Future[Unit]

  def commit(offsets: Map[TopicPartition, OffsetAndMetadata]): Future[Unit]

  def commitLater(): Future[Map[TopicPartition, OffsetAndMetadata]]

  def commitLater(offsets: Map[TopicPartition, OffsetAndMetadata]): Future[Unit]

  def seek(partition: TopicPartition, offset: Offset): Unit

  def seekToBeginning(partitions: Nel[TopicPartition]): Unit

  def seekToEnd(partitions: Nel[TopicPartition]): Unit

  def position(partition: TopicPartition): Future[Offset]

  def committed(partition: TopicPartition): Future[OffsetAndMetadata]

  def partitionsFor(topic: Topic): Future[List[PartitionInfo]]

  def listTopics(): Future[Map[Topic, List[PartitionInfo]]]

  def pause(partitions: Nel[TopicPartition]): Unit

  def paused(): Set[TopicPartition]

  def resume(partitions: Nel[TopicPartition]): Unit

  def offsetsForTimes(timestampsToSearch: Map[TopicPartition, Offset]): Future[Map[TopicPartition, Option[OffsetAndTimestamp]]]

  def beginningOffsets(partitions: Nel[TopicPartition]): Future[Map[TopicPartition, Offset]]

  def endOffsets(partitions: Nel[TopicPartition]): Future[Map[TopicPartition, Offset]]

  def close(): Future[Unit]

  def close(timeout: FiniteDuration): Future[Unit]

  def wakeup(): Future[Unit]
}


object Consumer {

  def apply[K, V](
    config: ConsumerConfig,
    ecBlocking: ExecutionContext)(implicit
    valueFromBytes: FromBytes[V],
    keyFromBytes: FromBytes[K]): Consumer[K, V] = {

    val valueDeserializer = valueFromBytes.asJava
    val keyDeserializer = keyFromBytes.asJava
    val consumer = new KafkaConsumer(config.properties, keyDeserializer, valueDeserializer)
    apply(consumer, ecBlocking)
  }

  def apply[K, V](consumer: ConsumerJ[K, V], ecBlocking: ExecutionContext): Consumer[K, V] = {

    def blocking[T](f: => T): Future[T] = Future(f)(ecBlocking)

    def callbackAndFuture() = {
      val promise = Promise[Map[TopicPartition, OffsetAndMetadata]]()
      val callback = new CommitCallback {
        def apply(offsets: Try[Map[TopicPartition, OffsetAndMetadata]]) = {
          promise.complete(offsets)
        }
      }
      (callback, promise.future)
    }

    new Consumer[K, V] {

      def assign(partitions: Nel[TopicPartition]) = {
        val partitionsJ = partitions.toList.map(_.asJava).asJavaCollection
        consumer.assign(partitionsJ)
      }

      def assignment(): Set[TopicPartition] = {
        val partitionsJ = consumer.assignment()
        partitionsJ.asScala.map(_.asScala).toSet
      }

      def subscribe(topics: Nel[Topic], listener: Option[RebalanceListener]) = {
        val topicsJ = topics.asJava
        consumer.subscribe(topicsJ, (listener getOrElse RebalanceListener.Empty).asJava)
      }

      def subscribe(pattern: Pattern, listener: Option[RebalanceListener]) = {
        consumer.subscribe(pattern, (listener getOrElse RebalanceListener.Empty).asJava)
      }

      def subscription() = {
        consumer.subscription().asScala.toSet
      }

      def unsubscribe() = {
        blocking {
          consumer.unsubscribe()
        }
      }

      def poll(timeout: FiniteDuration): Future[ConsumerRecords[K, V]] = {
        blocking {
          val records = consumer.poll(timeout.toMillis)
          records.asScala
        }
      }

      def commit() = {
        blocking {
          consumer.commitSync()
        }
      }

      def commit(offsets: Map[TopicPartition, OffsetAndMetadata]) = {
        val offsetsJ = offsets.asJavaMap(_.asJava, _.asJava)
        blocking {
          consumer.commitSync(offsetsJ)
        }
      }

      def commitLater() = {
        try {
          val (callback, future) = callbackAndFuture()
          consumer.commitAsync(callback.asJava)
          future
        } catch {
          case NonFatal(failure) => Future.failed(failure)
        }
      }

      def commitLater(offsets: Map[TopicPartition, OffsetAndMetadata]) = {
        try {
          val (callback, future) = callbackAndFuture()
          val offsetsJ = offsets.asJavaMap(_.asJava, _.asJava)
          consumer.commitAsync(offsetsJ, callback.asJava)
          future.unit
        } catch {
          case NonFatal(failure) => Future.failed(failure)
        }
      }

      def seek(partition: TopicPartition, offset: Offset) = {
        consumer.seek(partition.asJava, offset)
      }

      def seekToBeginning(partitions: Nel[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJava
        consumer.seekToBeginning(partitionsJ)
      }

      def seekToEnd(partitions: Nel[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJava
        consumer.seekToEnd(partitionsJ)
      }

      def position(partition: TopicPartition) = {
        blocking {
          consumer.position(partition.asJava)
        }
      }

      def committed(partition: TopicPartition) = {
        val partitionJ = partition.asJava
        blocking {
          val result = consumer.committed(partitionJ)
          result.asScala
        }
      }

      def partitionsFor(topic: Topic) = {
        blocking {
          val result = consumer.partitionsFor(topic)
          result.asScala.map(_.asScala).toList
        }
      }

      def listTopics() = {
        blocking {
          val result = consumer.listTopics()
          result.asScalaMap(k => k, _.asScala.map(_.asScala).toList)
        }
      }

      def pause(partitions: Nel[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJava
        consumer.pause(partitionsJ)
      }

      def paused(): Set[TopicPartition] = {
        val partitionsJ = consumer.paused()
        partitionsJ.asScala.map(_.asScala).toSet
      }

      def resume(partitions: Nel[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJava
        consumer.resume(partitionsJ)
      }

      def offsetsForTimes(timestampsToSearch: Map[TopicPartition, Offset]) = {
        val timestampsToSearchJ = timestampsToSearch.asJavaMap(_.asJava, LongJ.valueOf)
        blocking {
          val result = consumer.offsetsForTimes(timestampsToSearchJ)
          result.asScalaMap(_.asScala, v => Option(v).map(_.asScala))
        }
      }

      def beginningOffsets(partitions: Nel[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJava
        blocking {
          val result = consumer.beginningOffsets(partitionsJ)
          result.asScalaMap(_.asScala, v => v)
        }
      }

      def endOffsets(partitions: Nel[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJava
        blocking {
          val result = consumer.endOffsets(partitionsJ)
          result.asScalaMap(_.asScala, v => v)
        }
      }

      def close() = {
        blocking {
          consumer.close()
        }
      }

      def close(timeout: FiniteDuration) = {
        blocking {
          consumer.close(timeout.length, timeout.unit)
        }
      }

      def wakeup() = {
        blocking {
          consumer.wakeup()
        }
      }
    }
  }
}

