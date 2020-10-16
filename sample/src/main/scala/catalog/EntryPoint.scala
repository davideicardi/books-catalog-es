package catalog

import akka.actor.ActorSystem
import catalog.authors.http.AuthorsRoutes
import catalog.authors.{Author, AuthorCommand, AuthorEvent, AuthorEventsJsonFormats, AuthorJsonFormats}
import com.davideicardi.kaa.KaaSchemaRegistry
import com.davideicardi.kaa.kafka.GenericSerde
import es4kafka._
import es4kafka.http.{MetadataRoutes, RouteController}
import es4kafka.streaming.{DefaultSnapshotsStateReader, MetadataService}
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams

object EntryPoint extends App with EventSourcingApp {
  val serviceConfig: ServiceConfig = Config
  implicit val system: ActorSystem = ActorSystem(serviceConfig.applicationId)
  val schemaRegistry = new KaaSchemaRegistry(serviceConfig.kafka_brokers)
  val streamingPipeline = new StreamingPipeline(serviceConfig, schemaRegistry)
  val streams: KafkaStreams = new KafkaStreams(
    streamingPipeline.createTopology(),
    streamingPipeline.properties)
  val hostInfoService = new HostInfoServices(serviceConfig.http_endpoint)
  val metadataService = new MetadataService(streams, hostInfoService)

  // Authors
  val authorsCommandSender = new DefaultCommandSender[String, AuthorCommand, AuthorEvent](
    system,
    serviceConfig,
    Config.Author,
    metadataService,
    streams,
    Serdes.String(),
    new GenericSerde[Envelop[AuthorCommand]](schemaRegistry),
    AuthorEventsJsonFormats.AuthorEventFormat,
  )
  val authorsStateReader = new DefaultSnapshotsStateReader[String, Author](
    system,
    metadataService,
    streams,
    Config.Author,
    Serdes.String,
    AuthorJsonFormats.AuthorFormat,
  )
  val authorsRoutes = new AuthorsRoutes(authorsCommandSender, authorsStateReader, Config.Author)

  val controllers: Seq[RouteController] = Seq(
    new MetadataRoutes(metadataService),
    authorsRoutes
  )

  run()

  override protected def shutDown(): Unit = {
    super.shutDown()
    authorsCommandSender.close()
    schemaRegistry.shutdown()
  }
}
