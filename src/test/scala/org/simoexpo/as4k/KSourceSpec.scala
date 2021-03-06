package org.simoexpo.as4k

import akka.stream.scaladsl.{Sink, Source}
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.mockito.Mockito.{atLeast => invokedAtLeast, _}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.simoexpo.as4k.KSource._
import org.simoexpo.as4k.consumer.KafkaConsumerActor.KafkaCommitException
import org.simoexpo.as4k.consumer.KafkaConsumerAgent
import org.simoexpo.as4k.model.KRecord
import org.simoexpo.as4k.producer.KafkaProducerActor.KafkaProduceException
import org.simoexpo.as4k.producer.{KafkaSimpleProducerAgent, KafkaTransactionalProducerAgent}
import org.simoexpo.as4k.testing.{ActorSystemSpec, BaseSpec, DataHelperSpec}

import scala.concurrent.Future

class KSourceSpec
    extends BaseSpec
    with ScalaFutures
    with ActorSystemSpec
    with IntegrationPatience
    with BeforeAndAfterEach
    with DataHelperSpec {

  private val kafkaConsumerAgent: KafkaConsumerAgent[Int, String] = mock[KafkaConsumerAgent[Int, String]]
  private val kafkaSimpleProducerAgent: KafkaSimpleProducerAgent[Int, String] = mock[KafkaSimpleProducerAgent[Int, String]]
  private val kafkaTransactionalProducerAgent: KafkaTransactionalProducerAgent[Int, String] =
    mock[KafkaTransactionalProducerAgent[Int, String]]

  override def beforeEach(): Unit =
    reset(kafkaConsumerAgent, kafkaSimpleProducerAgent, kafkaTransactionalProducerAgent)

  "KSource" when {

    val topic = "topic"
    val partitions = 3

    val records1 = Range(0, 100).map(n => aKRecord(n, n, s"value$n", topic, n % partitions, "defaultGroup")).toList
    val records2 = Range(100, 200).map(n => aKRecord(n, n, s"value$n", topic, n % partitions, "defaultGroup")).toList
    val records3 = Range(200, 220).map(n => aKRecord(n, n, s"value$n", topic, n % partitions, "defaultGroup")).toList

    "producing a source of KRecord" should {

      "ask kafka consumer for new records" in {

        val totalRecordsSize = Seq(records1, records2, records3).map(_.size).sum
        val expectedRecords = Seq(records1, records2, records3).flatten

        when(kafkaConsumerAgent.askForRecords)
          .thenReturn(Future.successful(records1))
          .thenReturn(Future.successful(records2))
          .thenReturn(Future.successful(records3))

        val recordsConsumed =
          KSource.fromKafkaConsumer(kafkaConsumerAgent).take(totalRecordsSize).runWith(Sink.seq)

        whenReady(recordsConsumed) { records =>
          records.size shouldBe totalRecordsSize
          records.toList shouldBe expectedRecords

          verify(kafkaConsumerAgent, invokedAtLeast(3)).askForRecords

        }
      }

      "not end when not receiving new records from kafka consumer agent" in {

        val totalRecordsSize = Seq(records1, records2).map(_.size).sum
        val expectedRecords = Seq(records1, records2).flatten

        val emptyRecords = List.empty[KRecord[Int, String]]

        when(kafkaConsumerAgent.askForRecords)
          .thenReturn(Future.successful(records1))
          .thenReturn(Future.successful(emptyRecords))
          .thenReturn(Future.successful(records2))

        val recordsConsumed = KSource.fromKafkaConsumer(kafkaConsumerAgent).take(totalRecordsSize).runWith(Sink.seq)

        whenReady(recordsConsumed) { records =>
          records.size shouldBe totalRecordsSize
          records.toList shouldBe expectedRecords

          verify(kafkaConsumerAgent, invokedAtLeast(3)).askForRecords

        }
      }
    }

    "committing records" should {

      val callback = (offsets: Map[TopicPartition, OffsetAndMetadata], exception: Option[Exception]) =>
        exception match {
          case None    => println(s"successfully commit offset $offsets")
          case Some(_) => println(s"fail commit offset $offsets")
      }

      "call commit on KafkaConsumerAgent for a single KRecord" in {

        records1.foreach { record =>
          when(kafkaConsumerAgent.commit(record, Some(callback))).thenReturn(Future.successful(record))
        }

        val recordConsumed =
          Source.fromIterator(() => records1.iterator).commit()(kafkaConsumerAgent, Some(callback)).runWith(Sink.seq)

        whenReady(recordConsumed) { _ =>
          records1.foreach { record =>
            verify(kafkaConsumerAgent).commit(record, Some(callback))
          }
        }
      }

      "call commit on KafkaConsumerAgent for a list of KRecord" in {

        when(kafkaConsumerAgent.commitBatch(records1, Some(callback))).thenReturn(Future.successful(records1))

        val recordConsumed =
          Source.single(records1).commit()(kafkaConsumerAgent, Some(callback)).mapConcat(_.toList).runWith(Sink.seq)

        whenReady(recordConsumed) { _ =>
          verify(kafkaConsumerAgent).commitBatch(records1, Some(callback))
        }
      }

      "fail if kafka consumer fail to commit for a single KRecord" in {

        when(kafkaConsumerAgent.commit(any[KRecord[Int, String]], mockitoEq(Some(callback))))
          .thenReturn(Future.failed(KafkaCommitException(new RuntimeException("Something bad happened!"))))

        val recordConsumed =
          Source.fromIterator(() => records1.iterator).commit()(kafkaConsumerAgent, Some(callback)).runWith(Sink.seq)

        whenReady(recordConsumed.failed) { exception =>
          verify(kafkaConsumerAgent).commit(records1.head, Some(callback))

          exception shouldBe a[KafkaCommitException]
        }
      }

      "fail if kafka consumer fail to commit for a list of KRecord" in {

        when(kafkaConsumerAgent.commitBatch(records1, Some(callback)))
          .thenReturn(Future.failed(KafkaCommitException(new RuntimeException("Something bad happened!"))))

        val recordConsumed =
          Source.single(records1).commit()(kafkaConsumerAgent, Some(callback)).mapConcat(_.toList).runWith(Sink.seq)

        whenReady(recordConsumed.failed) { exception =>
          verify(kafkaConsumerAgent).commitBatch(records1, Some(callback))

          exception shouldBe a[KafkaCommitException]
        }
      }
    }

    "producing KRecords on a topic" should {

      "allow to call produce on KafkaSimpleProducerAgent for a single KRecord" in {

        records1.foreach { record =>
          when(kafkaSimpleProducerAgent.produce(record)).thenReturn(Future.successful(record))
        }

        val recordProduced =
          Source.fromIterator(() => records1.iterator).produce()(kafkaSimpleProducerAgent).runWith(Sink.seq)

        whenReady(recordProduced) { _ =>
          records1.foreach { record =>
            verify(kafkaSimpleProducerAgent).produce(record)
          }
        }

      }

      "allow to call produce on KafkaTransactionalProducerAgent for a list of KRecord" in {

        when(kafkaTransactionalProducerAgent.produce(records1)).thenReturn(Future.successful(records1))

        val recordProduced =
          Source.single(records1).produce(kafkaTransactionalProducerAgent).runWith(Sink.seq)

        whenReady(recordProduced) { _ =>
          verify(kafkaTransactionalProducerAgent).produce(records1)
        }
      }

      "fail with a KafkaProduceException if KafkaSimpleProducerAgent fail to produce a single KRecord" in {

        val failedIndex = 20

        records1.slice(0, failedIndex).foreach { record =>
          when(kafkaSimpleProducerAgent.produce(record)).thenReturn(Future.successful(record))
        }

        when(kafkaSimpleProducerAgent.produce(records1(failedIndex)))
          .thenReturn(Future.failed(KafkaProduceException(new RuntimeException("Something bad happened!"))))

        val recordProduced =
          Source.fromIterator(() => records1.iterator).produce()(kafkaSimpleProducerAgent).runWith(Sink.seq)

        whenReady(recordProduced.failed) { exception =>
          records1.slice(0, failedIndex).foreach { record =>
            verify(kafkaSimpleProducerAgent).produce(record)
          }
          verify(kafkaSimpleProducerAgent).produce(records1(failedIndex))

          exception shouldBe a[KafkaProduceException]
        }
      }

      "fail with a KafkaProduceException if KafkaTransactionalProducerAgent fail to produce a list of KRecord" in {

        when(kafkaTransactionalProducerAgent.produce(records1))
          .thenReturn(Future.failed(KafkaProduceException(new RuntimeException("Something bad happened!"))))

        val recordProduced =
          Source.single(records1).produce(kafkaTransactionalProducerAgent).runWith(Sink.seq)

        whenReady(recordProduced.failed) { exception =>
          verify(kafkaTransactionalProducerAgent).produce(records1)

          exception shouldBe a[KafkaProduceException]
        }
      }
    }

    "producing and committing KRecord on a topic" should {

      val consumerGroup = kafkaConsumerAgent.consumerGroup

      "allow to call produceAndCommit on KafkaTransactionalProducerAgent for a single KRecord" in {

        records1.foreach { record =>
          when(kafkaTransactionalProducerAgent.produceAndCommit(record)).thenReturn(Future.successful(record))
        }

        val recordProduced =
          Source.fromIterator(() => records1.iterator).produceAndCommit(kafkaTransactionalProducerAgent).runWith(Sink.seq)

        whenReady(recordProduced) { _ =>
          records1.foreach { record =>
            verify(kafkaTransactionalProducerAgent).produceAndCommit(record)
          }
        }
      }

      "allow to call produceAndCommit on KafkaTransactionalProducerAgent for a list of KRecord" in {

        when(kafkaTransactionalProducerAgent.produceAndCommit(records1)).thenReturn(Future.successful(records1))

        val recordProduced =
          Source.single(records1).produceAndCommit(kafkaTransactionalProducerAgent).runWith(Sink.seq)

        whenReady(recordProduced) { _ =>
          verify(kafkaTransactionalProducerAgent).produceAndCommit(records1)
        }
      }

      "fail with a KafkaProduceException if KafkaTransactionalProducerAgent fail to produce and commit in transaction a single KRecord" in {

        val failedIndex = 20

        records1.slice(0, failedIndex).foreach { record =>
          when(kafkaTransactionalProducerAgent.produceAndCommit(record)).thenReturn(Future.successful(record))
        }

        when(kafkaTransactionalProducerAgent.produceAndCommit(records1(failedIndex)))
          .thenReturn(Future.failed(KafkaProduceException(new RuntimeException("Something bad happened!"))))

        val recordProduced =
          Source.fromIterator(() => records1.iterator).produceAndCommit(kafkaTransactionalProducerAgent).runWith(Sink.seq)

        whenReady(recordProduced.failed) { exception =>
          records1.slice(0, failedIndex).foreach { record =>
            verify(kafkaTransactionalProducerAgent).produceAndCommit(record)
          }
          verify(kafkaTransactionalProducerAgent).produceAndCommit(records1(failedIndex))

          exception shouldBe a[KafkaProduceException]
        }
      }

      "fail with a KafkaProduceException if KafkaTransactionalProducerAgent fail to produce and commit in transaction a list of KRecord" in {

        when(kafkaTransactionalProducerAgent.produceAndCommit(records1))
          .thenReturn(Future.failed(KafkaProduceException(new RuntimeException("Something bad happened!"))))

        val recordProduced =
          Source.single(records1).produceAndCommit(kafkaTransactionalProducerAgent).runWith(Sink.seq)

        whenReady(recordProduced.failed) { exception =>
          verify(kafkaTransactionalProducerAgent).produceAndCommit(records1)

          exception shouldBe a[KafkaProduceException]
        }
      }
    }

    "mapping the value of KRecord" should {

      "return a Source of KRecord with mapped value" in {

        val recordMapped =
          Source.fromIterator(() => records1.iterator).mapValue(_.toUpperCase).runWith(Sink.seq)

        whenReady(recordMapped) { records =>
          records shouldBe records1.map(_.mapValue(_.toUpperCase))
        }
      }

    }
  }

}
