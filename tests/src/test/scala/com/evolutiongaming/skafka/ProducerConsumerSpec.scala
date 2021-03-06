package com.evolutiongaming.skafka

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.{TestDuration, TestKit, TestKitExtension}
import com.evolutiongaming.concurrent.FutureHelper._
import com.evolutiongaming.kafka.StartKafka
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.safeakka.actor.ActorLog
import com.evolutiongaming.skafka.consumer._
import com.evolutiongaming.skafka.producer._
import org.scalatest.{BeforeAndAfterAll, FunSuite, Matchers}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ProducerConsumerSpec extends FunSuite with BeforeAndAfterAll with Matchers {
  import ProducerConsumerSpec._

  implicit lazy val system: ActorSystem = ActorSystem(getClass.getSimpleName)
  implicit lazy val ec = system.dispatcher

  lazy val shutdown = StartKafka()

  val timeout = TestKitExtension(system).DefaultTimeout.duration.dilated

  override def beforeAll() = {
    super.beforeAll()
    shutdown
  }

  override def afterAll() = {
    shutdown()

    val futures = for {
      (_, producers) <- combinations
      producer <- producers
    } yield for {
      _ <- producer.flush()
      _ <- producer.close()
    } yield {}

    Await.result(Future.foldUnit(futures), timeout)

    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }


  val headers = List(Header(key = "key", value = "value".getBytes(UTF_8)))

  def producers(acks: Acks) = {
    val ecBlocking = system.dispatcher
    val config = ProducerConfig.Default.copy(acks = acks)
    List(
      LoggingProducer(Producer(config, ecBlocking), ActorLog.empty),
      Producer(config, ecBlocking, system))
  }

  lazy val combinations = for {
    acks <- List(Acks.One, Acks.None)
  } yield (acks, producers(acks))

  for {
    (acks, producers) <- combinations
    (producer, idx) <- producers.zipWithIndex
  } yield {

    val topic = s"$idx-$acks"
    val name = s"[topic:$topic,acks:$acks]"

    def produce(record: ProducerRecord[String, String]) = {
      val future = producer.send(record)
      Await.result(future, timeout)
    }

    lazy val consumer = consumerOf()

    def consumerOf() = {
      val config = ConsumerConfig.Default.copy(
        groupId = Some(s"group-$topic"),
        autoOffsetReset = AutoOffsetReset.Earliest,
        autoCommit = false,
        common = CommonConfig(clientId = Some(UUID.randomUUID().toString)))

      val consumer = Consumer[String, String](config, ec)
      consumer.subscribe(Nel(topic), None)
      consumer
    }

    test(s"$name produce and consume record") {
      val key = "key1"
      val value = "value1"
      val timestamp = Instant.now()
      val record = ProducerRecord(
        topic = topic,
        value = Some(value),
        key = Some(key),
        timestamp = Some(timestamp),
        headers = headers)
      val metadata = produce(record)
      val offset = if (acks == Acks.None) None else Some(0l)
      metadata.offset shouldEqual offset

      val records = consumer.consume(timeout).map(Record(_))

      val expected = Record(
        record = ConsumerRecord(
          topicPartition = metadata.topicPartition,
          offset = 0l,
          timestampAndType = Some(TimestampAndType(timestamp, TimestampType.Create)),
          key = Some(WithSize(key, 4)),
          value = Some(WithSize(value, 6)),
          headers = Nil),
        headers = List(Record.Header(key = "key", value = "value")))

      records shouldEqual List(expected)
    }

    test(s"$name produce and delete record") {
      val key = "key2"
      val record = ProducerRecord(topic, value = "value2", key = key)
      val metadata = produce(record)
      val offset = if (acks == Acks.None) None else Some(1l)
      metadata.offset shouldEqual offset

      val keyAndValues = consumer.consume(timeout).map { record => (record.key.map(_.value), record.value.map(_.value)) }
      keyAndValues shouldEqual List((Some(key), record.value))

      val timestamp = Instant.now()
      val delete = ProducerRecord[String, String](
        topic = topic,
        key = Some(key),
        timestamp = Some(timestamp),
        headers = headers)

      val deleteMetadata = produce(delete)

      val records = consumer.consume(timeout).map(Record(_))

      val expected = Record(
        record = ConsumerRecord[String, String](
          topicPartition = deleteMetadata.topicPartition,
          offset = 2l,
          timestampAndType = Some(TimestampAndType(timestamp, TimestampType.Create)),
          key = Some(WithSize(key, 4)),
          headers = Nil),
        headers = List(Record.Header(key = "key", value = "value")))

      records shouldEqual List(expected)
    }

    test(s"$name produce and consume empty record") {
      val timestamp = Instant.now()
      val empty = ProducerRecord[String, String](
        topic = topic,
        timestamp = Some(timestamp),
        headers = headers)

      val metadata = produce(empty)

      val records = consumer.consume(timeout).map(Record(_))

      val expected = Record(
        record = ConsumerRecord[String, String](
          topicPartition = metadata.topicPartition,
          offset = 3l,
          timestampAndType = Some(TimestampAndType(timestamp, TimestampType.Create)),
          headers = Nil),
        headers = List(Record.Header(key = "key", value = "value")))

      records shouldEqual List(expected)
    }

    test(s"$name commit and subscribe from last committed position") {
      val key = "key3"
      val value = "value3"
      val timestamp = Instant.now()

      Await.result(consumer.commit(), timeout)
      Await.result(consumer.close(), timeout)

      val record = ProducerRecord(
        topic = topic,
        value = Some(value),
        key = Some(key),
        timestamp = Some(timestamp),
        headers = headers)

      val metadata = produce(record)

      val consumer2 = consumerOf()

      val records = consumer2.consume(timeout).map(Record(_))

      val expected = Record(
        record = ConsumerRecord(
          topicPartition = metadata.topicPartition,
          offset = 4l,
          timestampAndType = Some(TimestampAndType(timestamp, TimestampType.Create)),
          key = Some(WithSize(key, 4)),
          value = Some(WithSize(value, 6)),
          headers = Nil),
        headers = List(Record.Header(key = "key", value = "value")))

      records shouldEqual List(expected)
    }
  }
}

object ProducerConsumerSpec {

  final case class Record(record: ConsumerRecord[String, String], headers: List[Record.Header])

  object Record {

    def apply(record: ConsumerRecord[String, String]): Record = {

      val headers = record.headers.map { header =>
        Header(key = header.key, value = new String(header.value, UTF_8))
      }
      Record(
        record = record.copy(headers = Nil),
        headers = headers)
    }

    final case class Header(key: String, value: String)
  }


  implicit class ConsumersOps(val self: Consumer[String, String]) extends AnyVal {

    def consume(timeout: FiniteDuration): List[ConsumerRecord[String, String]] = {
      @tailrec
      def consume(attempts: Int): List[ConsumerRecord[String, String]] = {
        if (attempts <= 0) Nil
        else {
          val future = self.poll(100.millis)
          val records = Await.result(future, timeout).values.values.flatten
          if (records.isEmpty) consume(attempts - 1)
          else records.toList
        }
      }

      consume(100)
    }
  }
}