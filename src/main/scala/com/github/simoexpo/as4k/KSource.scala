package com.github.simoexpo.as4k

import akka.stream.scaladsl.{RestartSource, Source}
import com.github.simoexpo.as4k.consumer.{KafkaConsumerAgent, KafkaConsumerIterator}
import com.github.simoexpo.as4k.factory.CallbackFactory.CustomCommitCallback
import com.github.simoexpo.as4k.factory.KRecord
import com.github.simoexpo.as4k.producer.KafkaProducerAgent
import org.apache.kafka.clients.consumer._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.implicitConversions

object KSource {

  def fromKafkaConsumer[K, V](kafkaConsumerAgent: KafkaConsumerAgent[K, V])(
      implicit ec: ExecutionContext): Source[KRecord[K, V], Any] =
    Source
      .fromIterator(KafkaConsumerIterator.getKafkaIterator)
      .mapAsync(1) { token =>
        kafkaConsumerAgent.askForRecords(token)
      }
      .mapConcat(identity)

  implicit class KRecordSourceConverter[K, V](stream: Source[KRecord[K, V], Any]) {

    def commit(parallelism: Int = 1)(kafkaConsumerAgent: KafkaConsumerAgent[K, V],
                                     customCallback: Option[CustomCommitCallback] = None): Source[KRecord[K, V], Any] =
      stream.mapAsync(parallelism)(record => kafkaConsumerAgent.commit(record, customCallback))

    def mapValue[Out](fun: V => Out): Source[KRecord[K, Out], Any] =
      stream.map(_.mapValue(fun))

    def produce(kafkaProducerAgent: KafkaProducerAgent[K, V]): Source[KRecord[K, V], Any] =
      stream.mapAsync(1)(kafkaProducerAgent.produce)

    def produceAndCommit(kafkaProducerAgent: KafkaProducerAgent[K, V],
                         kafkaConsumerAgent: KafkaConsumerAgent[K, V]): Source[KRecord[K, V], Any] =
      stream.mapAsync(1)(record => kafkaProducerAgent.produceAndCommit(record, kafkaConsumerAgent.consumerGroup))

  }

  implicit class KRecordSeqSourceConverter[K, V](stream: Source[Seq[KRecord[K, V]], Any]) {

    def commit(parallelism: Int = 1)(kafkaConsumerAgent: KafkaConsumerAgent[K, V],
                                     customCallback: Option[CustomCommitCallback] = None): Source[Seq[KRecord[K, V]], Any] =
      stream.mapAsync(parallelism) { records =>
        kafkaConsumerAgent.commitBatch(records, customCallback)
      }

    def produce(kafkaProducerAgent: KafkaProducerAgent[K, V]): Source[Seq[KRecord[K, V]], Any] =
      stream.mapAsync(1)(kafkaProducerAgent.produce)

    def produceAndCommit(kafkaProducerAgent: KafkaProducerAgent[K, V],
                         kafkaConsumerAgent: KafkaConsumerAgent[K, V]): Source[Seq[KRecord[K, V]], Any] =
      stream.mapAsync(1)(records => kafkaProducerAgent.produceAndCommit(records, kafkaConsumerAgent.consumerGroup))

  }

}
