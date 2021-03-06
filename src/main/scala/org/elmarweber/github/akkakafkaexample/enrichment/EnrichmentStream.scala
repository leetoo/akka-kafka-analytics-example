package org.elmarweber.github.akkakafkaexample.enrichment

import java.net.InetAddress

import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerMessage, ProducerSettings, Subscriptions}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
import org.apache.kafka.clients.producer.ProducerRecord
import org.elmarweber.github.akkakafkaexample.lib.{AnalyticsEvent, EnrichedAnalyticsEvent}
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

trait EnrichmentStream extends StrictLogging {
  def createStream(consumerSettings: ConsumerSettings[String, String],
                   producerSettings: ProducerSettings[String, String],
                   sourceTopic: String, targetTopic: String)
                  (implicit fmt: Materializer, ec: ExecutionContext) = {
    val metricTags = Map("jvm-host" -> InetAddress.getLocalHost().getHostName(), "instance-id" -> Random.nextInt(100).toString)
    val latencyHistogram = Kamon.metrics.histogram("EnrichmentStream.latency", metricTags)
    val consumedEventsCounter = Kamon.metrics.counter("EnrichmentStream.consumed-events", metricTags)

    Consumer.committableSource(consumerSettings, Subscriptions.topics(sourceTopic))
      .map { msg =>
        latencyHistogram.record(System.currentTimeMillis() - msg.record.timestamp())
        consumedEventsCounter.increment()
        (msg.record.value().parseJson.convertTo[AnalyticsEvent], msg.committableOffset)
      }
      .mapAsync(2) { case (event, offset) =>
        Future {
          Thread.sleep(500) // simulate some hardcore CPU modeling
          val artist = {
            if (event.songTitle.indexOf("-") == -1) "Unknown Artist"
            else event.songTitle.substring(0, event.songTitle.indexOf("-")).trim()
          }
          val enrichedEvent = EnrichedAnalyticsEvent(
            id = event.id,
            clientId = event.clientId,
            songTitle = event.songTitle,
            artist = artist,
            ip = event.ip
          )
          logger.debug(s"Enriched ${event.id} with artist '${artist}' (used song title '${event.songTitle}')")
          (enrichedEvent, offset)
        }
      }
      .map { case (enrichedEvent, offset) =>
        ProducerMessage.Message(new ProducerRecord(targetTopic, enrichedEvent.id, enrichedEvent.toJson.toString), offset)
      }
      .via(Producer.flow(producerSettings))
      .mapAsync(1) { resultMessage =>
        resultMessage.message.passThrough.commitScaladsl()
      }
      .runWith(Sink.ignore)
  }
}


object EnrichmentStream extends EnrichmentStream