package filodb.coordinator

import java.net.InetAddress

import scala.concurrent.duration._
import scala.util.Try

import akka.actor.{ActorRef, AddressFromURIString, PoisonPill, Props}
import akka.pattern.gracefulStop
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}

import org.velvia.filo.ZeroCopyUTF8String
import filodb.core._
import filodb.core.metadata.{DataColumn, Dataset, RichProjection}
import filodb.core.query.{AggregationFunction, ColumnFilter, Filter, HistogramBucket}
import filodb.core.store._

object NodeCoordinatorActorSpec extends ActorSpecConfig

// This is really an end to end ingestion test, it's what a client talking to a FiloDB node would do
class NodeCoordinatorActorSpec extends ActorTest(NodeCoordinatorActorSpec.getNewSystem)
  with ScalaFutures with BeforeAndAfterEach {

  import akka.testkit._
  import DatasetCommands._
  import IngestionCommands._
  import GdeltTestData._
  import ShardSubscriptions._
  import NodeClusterActor._
  import ActorName.ShardName

  implicit val defaultPatience =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(50, Millis))

  val config = ConfigFactory.parseString(
                      """filodb.memtable.flush-trigger-rows = 100
                         filodb.memtable.max-rows-per-table = 100
                         filodb.memtable.noactivity.flush.interval = 2 s
                         filodb.memtable.write.interval = 500 ms""")
                            .withFallback(ConfigFactory.load("application_test.conf"))
                            .getConfig("filodb")

  private val host = InetAddress.getLocalHost.getHostAddress
  private val selfAddress = AddressFromURIString(s"akka.tcp://${system.name}@$host:2552")
  private val cluster = FilodbCluster(system)
  private lazy val memStore = cluster.memStore
  private lazy val metaStore = cluster.metaStore

  implicit val ec = cluster.ec
  implicit val context = scala.concurrent.ExecutionContext.Implicits.global

  val strategy = new DefaultShardAssignmentStrategy
  val shardActor = system.actorOf(Props(new ShardCoordinatorActor(new DefaultShardAssignmentStrategy)), ShardName)
  var coordinatorActor: ActorRef = _
  var probe: TestProbe = _
  var shardMap = new ShardMapper(1)
  val colNames = schema.map(_.name)
  val nodeCoordProps = NodeCoordinatorActor.props(metaStore, memStore, selfAddress, config)

  override def beforeAll(): Unit = {
    super.beforeAll()
    metaStore.initialize().futureValue
  }

  override def beforeEach(): Unit = {
    metaStore.clearAllData().futureValue
    memStore.reset()
    shardMap.clear()

    coordinatorActor = system.actorOf(nodeCoordProps, s"test-node-coord-${System.nanoTime}")

    shardActor ! AddMember(coordinatorActor)
    expectMsg(CoordinatorAdded(coordinatorActor, Seq.empty))
    coordinatorActor ! CoordinatorRegistered(self, shardActor)

    probe = TestProbe()
  }

  override def afterEach(): Unit = {
    shardActor ! NodeProtocol.ResetState
    expectMsg(NodeProtocol.StateReset)

    gracefulStop(coordinatorActor, 3.seconds.dilated, PoisonPill).futureValue
  }

  def createTable(dataset: Dataset, columns: Seq[DataColumn]): Unit = {
    metaStore.newDataset(dataset).futureValue should equal (Success)
    val ref = DatasetRef(dataset.name)
    columns.foreach { col => metaStore.newColumn(col, ref).futureValue should equal (Success) }
  }

  def startIngestion(projection: RichProjection, dataColumns: Seq[DataColumn], numShards: Int): Unit = {
    val resources = DatasetResourceSpec(numShards, 1)
    val noOpSource = IngestionSource(classOf[NoOpStreamFactory].getName)
    val columns = dataColumns.map(_.toString)// this test wants toString vs _.name
    val dataset = Dataset(projection.datasetRef, Seq.empty, Seq.empty)
    val sd = SetupDataset(projection.datasetRef, columns, resources, noOpSource)
    shardActor ! AddDataset(sd, dataset, Seq.empty, Set(coordinatorActor), self)
    val added = expectMsgPF() {
      case e: DatasetAdded =>
        probe.send(coordinatorActor, IngestionCommands.DatasetSetup(projection.dataset, columns, 0))
        e
    }
    shardActor ! Subscribe(probe.ref, projection.datasetRef)
    probe.expectMsgPF() { case CurrentShardSnapshot(ds, mapper) =>
      shardMap = mapper
    }

    added.shards(coordinatorActor) foreach { shard =>
      probe.send(coordinatorActor, StartShardIngestion(projection.datasetRef, shard, None))
      probe.expectMsgPF() {
        case e: IngestionStarted =>
          shardMap.updateFromEvent(e)
          e.node shouldEqual coordinatorActor
          e.shard shouldEqual shard
      }
    }
  }

  describe("NodeCoordinatorActor DatasetOps commands") {
    it("should be able to create new dataset") {
      probe.send(coordinatorActor, CreateDataset(dataset1, schema))
      probe.expectMsg(DatasetCreated)
    }

    it("should return DatasetAlreadyExists creating dataset that already exists") {
      probe.send(coordinatorActor, CreateDataset(dataset1, schema))
      probe.expectMsg(DatasetCreated)

      probe.send(coordinatorActor, CreateDataset(dataset1, schema))
      probe.expectMsg(DatasetAlreadyExists)
    }
  }

  describe("QueryActor commands and responses") {
    import MachineMetricsData._
    import QueryCommands._

    def setupTimeSeries(numShards: Int = 1): DatasetRef = {
      probe.send(coordinatorActor, CreateDataset(dataset1, schemaWithSeries))
      probe.expectMsg(DatasetCreated)

      startIngestion(MachineMetricsData.projection1, schemaWithSeries, numShards)
      projection.datasetRef
    }

    it("should return UnknownDataset if attempting to query before ingestion set up") {
      probe.send(coordinatorActor, CreateDataset(dataset1, schemaWithSeries))
      probe.expectMsg(DatasetCreated)

      val ref = MachineMetricsData.projection1.datasetRef
      val q1 = RawQuery(ref, 0, Seq("min"), SinglePartitionQuery(Seq("Series 1")), AllPartitionData)
      probe.send(coordinatorActor, q1)
      probe.expectMsg(UnknownDataset)
    }

    it("should return raw chunks with a RawQuery after ingesting rows") {
      val ref = setupTimeSeries()
      probe.send(coordinatorActor, IngestRows(ref, 0, 0, records(multiSeriesData()).take(20)))
      probe.expectMsg(Ack(19L))

      // Query existing partition: Series 1
      val q1 = RawQuery(ref, 0, Seq("min"), SinglePartitionQuery(Seq("Series 1")), AllPartitionData)
      probe.send(coordinatorActor, q1)
      val info1 = probe.expectMsgPF(3.seconds.dilated) {
        case q @ QueryInfo(_, ref, _) => q
      }
      probe.expectMsgPF(3.seconds.dilated) {
        case QueryRawChunks(info1.id, _, chunks) => chunks.size should equal (1)
      }
      probe.expectMsg(QueryEndRaw(info1.id))

      // Query nonexisting partition
      val q2 = RawQuery(ref, 0, Seq("min"), SinglePartitionQuery(Seq("NotSeries")), AllPartitionData)
      probe.send(coordinatorActor, q2)
      val info2 = probe.expectMsgPF(3.seconds.dilated) {
        case q @ QueryInfo(_, ref, _) => q
      }
      probe.expectMsg(QueryEndRaw(info2.id))
    }

    it("should return BadArgument/BadQuery if wrong type of partition key passed") {
      val ref = setupTimeSeries()
      val q1 = RawQuery(ref, 0, Seq("min"), SinglePartitionQuery(Seq(-1)), AllPartitionData)
      probe.send(coordinatorActor, q1)
      probe.expectMsgClass(classOf[BadQuery])
    }

    it("should return BadQuery if aggregation function not defined") {
      val ref = setupTimeSeries()
      val q1 = AggregateQuery(ref, 0, QueryArgs("not-a-func", "foo"), SinglePartitionQuery(Seq("Series 1")))
      probe.send(coordinatorActor, q1)
      probe.expectMsg(BadQuery("No such aggregation function not-a-func"))

      val q2 = AggregateQuery(ref, 0, QueryArgs("TimeGroupMin", "min"), SinglePartitionQuery(Seq("Series 1")))
      probe.send(coordinatorActor, q2)
      probe.expectMsg(BadQuery("No such aggregation function TimeGroupMin"))
    }

    // Don't have a function that returns this yet.  time_group_* _used_ to but doesn't anymore
    ignore("should return WrongNumberOfArgs if number of arguments wrong") {
      val ref = setupTimeSeries()
      val q1 = AggregateQuery(ref, 0, QueryArgs("time_group_avg", "min"),
                              SinglePartitionQuery(Seq("Series 1")))
      probe.send(coordinatorActor, q1)
      probe.expectMsg(WrongNumberOfArgs(2, 5))
    }

    it("should return BadArgument if arguments could not be parsed successfully") {
      val ref = setupTimeSeries()
      val q1 = AggregateQuery(ref, 0, QueryArgs("time_group_avg", "min", Seq("a1b")),
                              SinglePartitionQuery(Seq("Series 1")))
      probe.send(coordinatorActor, q1)
      probe.expectMsgClass(classOf[BadArgument])
    }

    val timeScan = KeyRangeQuery(Seq(110000L), Seq(130000L))

    it("should return BadArgument if wrong types of columns are passed") {
      val ref = setupTimeSeries()
      // Test 1: Cannot pass in a non-double column for time_group_avg
      val args = QueryArgs("time_group_avg", "timestamp", Seq("100"), timeScan)
      val q1 = AggregateQuery(ref, 0, args, SinglePartitionQuery(Seq("Series 1")))
      probe.send(coordinatorActor, q1)
      val msg = probe.expectMsgClass(classOf[BadArgument])
      msg.msg should include ("not in allowed set")
    }

    it("should return BadQuery if time function used on a non-timeseries dataset") {
      import GdeltTestData._

      probe.send(coordinatorActor, CreateDataset(dataset4, schema))
      probe.expectMsg(DatasetCreated)

      // No need to initialize ingestion, because this test doesn't query data itself

      val ref4 = dataset4.projections.head.dataset
      probe.send(coordinatorActor, DatasetSetup(dataset4, schema.map(_.toString), 0))

      // case 1: scan all data in partition, but no timestamp column ->
      val args = QueryArgs("time_group_avg", "AvgTone", Nil)
      val q1 = AggregateQuery(ref4, 0, args, FilteredPartitionQuery(Nil))
      probe.send(coordinatorActor, q1)
      val msg = probe.expectMsgClass(classOf[BadQuery])
      msg.msg should include ("time-based functions")

      // case 2: using a time-based range scan should not be valid for non-timeseries dataset
      val q2 = AggregateQuery(ref4, 0, args.copy(dataQuery = MostRecentTime(5000)), FilteredPartitionQuery(Nil))
      probe.send(coordinatorActor, q2)
      val msg2 = probe.expectMsgClass(classOf[BadQuery])
      msg2.msg should include ("Not a time")
    }

    it("should return results in AggregateResponse if valid AggregateQuery") {
      val ref = setupTimeSeries()
      probe.send(coordinatorActor, IngestRows(ref, 0, 0, records(linearMultiSeries()).take(30)))
      probe.expectMsg(Ack(29L))

      val args = QueryArgs("time_group_avg", "min", Seq("2"), timeScan)
      val series = (1 to 3).map(n => Seq(s"Series $n"))
      val q1 = AggregateQuery(ref, 0, args, MultiPartitionQuery(series))
      probe.send(coordinatorActor, q1)
      val answer = probe.expectMsgClass(classOf[AggregateResponse[Double]])
      answer.elementClass should equal (classOf[Double])
      answer.elements should equal (Array(13.0, 23.0))

      // Try a filtered partition query
      import ZeroCopyUTF8String._
      val series2 = (2 to 4).map(n => s"Series $n").toSet.asInstanceOf[Set[Any]]
      val multiFilter = Seq(ColumnFilter("series", Filter.In(series2)))
      val q2 = AggregateQuery(ref, 0, args, FilteredPartitionQuery(multiFilter))
      probe.send(coordinatorActor, q2)
      val answer2 = probe.expectMsgClass(classOf[AggregateResponse[Double]])
      answer2.elementClass should equal (classOf[Double])
      answer2.elements should equal (Array(14.0, 24.0))

      // What if filter returns no results?
      val filter3 = Seq(ColumnFilter("series", Filter.Equals("foobar".utf8)))
      val q3 = AggregateQuery(ref, 0, args, FilteredPartitionQuery(filter3))
      probe.send(coordinatorActor, q3)
      val answer3 = probe.expectMsgClass(classOf[AggregateResponse[Double]])
      answer3.elementClass should equal (classOf[Double])
      answer3.elements.length should equal (2)
    }

    it("should aggregate from multiple shards") {
      val ref = setupTimeSeries(2)
      probe.send(coordinatorActor, IngestRows(ref, 0, 0, records(linearMultiSeries()).take(30)))
      probe.expectMsg(Ack(29L))
      probe.send(coordinatorActor, IngestRows(ref, 0, 1, records(linearMultiSeries(130000L)).take(20)))
      probe.expectMsg(Ack(19L))

      // Should return results from both shards
      // shard 1 - timestamps 110000 -< 130000;  shard 2 - timestamps 130000 <- 1400000
      val args = QueryArgs("time_group_avg", "min", Seq("3"), timeScan.copy(end = Seq(140000L)))
      val series2 = (2 to 4).map(n => s"Series $n").toSet.asInstanceOf[Set[Any]]
      val multiFilter = Seq(ColumnFilter("series", Filter.In(series2)))
      val q2 = AggregateQuery(ref, 0, args, FilteredPartitionQuery(multiFilter))
      probe.send(coordinatorActor, q2)
      val answer2 = probe.expectMsgClass(classOf[AggregateResponse[Double]])
      answer2.elementClass should equal (classOf[Double])
      answer2.elements should equal (Array(14.0, 24.0, 4.0))
    }

    it("should aggregate using histogram combiner") {
      val ref = setupTimeSeries()
      probe.send(coordinatorActor, IngestRows(ref, 0, 0, records(linearMultiSeries()).take(30)))
      probe.expectMsg(Ack(29L))

      val args = QueryArgs("sum", "min", combinerName="histogram", combinerArgs=Seq("2000"))
      val q1 = AggregateQuery(ref, 0, args, FilteredPartitionQuery(Nil))
      probe.send(coordinatorActor, q1)
      val answer = probe.expectMsgClass(classOf[AggregateResponse[HistogramBucket]])
      answer.elementClass should equal (classOf[HistogramBucket])
      val buckets = answer.elements.toSeq
      buckets should have length (10)
      buckets.map(_.count) should equal (Seq(0, 0, 0, 0, 4, 6, 0, 0, 0, 0))
    }

    it("should query partitions in AggregateQuery") {
      val ref = setupTimeSeries()
      probe.send(coordinatorActor, IngestRows(ref, 0, 0, records(linearMultiSeries()).take(30)))
      probe.expectMsg(Ack(29L))

      val args = QueryArgs("partition_keys", "min")
      val series2 = (2 to 4).map(n => s"Series $n").toSet
      val multiFilter = Seq(ColumnFilter("series", Filter.In(series2.asInstanceOf[Set[Any]])))
      val q2 = AggregateQuery(ref, 0, args, FilteredPartitionQuery(multiFilter))
      probe.send(coordinatorActor, q2)
      val answer2 = probe.expectMsgClass(classOf[AggregateResponse[String]])
      answer2.elementClass should equal (classOf[String])
      answer2.elements.toSet should equal (series2.map(s => s"b[$s]"))
    }

    it("should respond to GetIndexNames and GetIndexValues") {
      val ref = setupTimeSeries()
      probe.send(coordinatorActor, IngestRows(ref, 0, 0, records(linearMultiSeries()).take(30)))
      probe.expectMsg(Ack(29L))

      probe.send(coordinatorActor, GetIndexNames(ref))
      probe.expectMsg(Seq("series"))

      probe.send(coordinatorActor, GetIndexValues(ref, "series", limit=4))
      probe.expectMsg(Seq("Series 0", "Series 1", "Series 2", "Series 3"))
    }
  }

  it("should be able to start ingestion, send rows, and get an ack back") {
    val ref = projection6.datasetRef

    probe.send(coordinatorActor, CreateDataset(dataset6, schema))
    probe.expectMsg(DatasetCreated)
    startIngestion(projection6, schema, 1)
    probe.send(coordinatorActor, IngestRows(ref, 0, 0, records(projection6)))
    probe.expectMsg(Ack(98L))

    // Flush not needed for MemStores.....
    // probe.send(coordActor, Flush(ref, 0))
    // probe.expectMsg(Flushed)

    probe.send(coordinatorActor, GetIngestionStats(ref, 0))
    probe.expectMsg(IngestionActor.IngestionStatus(99))

    // Now, read stuff back from the column store and check that it's all there
    val split = memStore.getScanSplits(ref, 1).head
    val query = QuerySpec("AvgTone", AggregationFunction.Sum)
    val agg1 = memStore.aggregate(projection6, 0, query, FilteredPartitionScan(split))
                       .get.runAsync.futureValue
    agg1.result.asInstanceOf[Array[Double]](0) should be (575.24 +- 0.01)
  }

  it("should stop datasetActor if error occurs and prevent further ingestion") {
    probe.send(coordinatorActor, CreateDataset(projection1.dataset, schema))
    probe.expectMsg(DatasetCreated)

    val ref = projection1.datasetRef
    startIngestion(projection1, schema, 1)

    EventFilter[NumberFormatException](occurrences = 1) intercept {
      probe.send(coordinatorActor, IngestRows(ref, 0, 0, records(projection1, readers ++ Seq(badLine))))
      // This should trigger an error, and datasetCoordinatorActor will stop.  A stop event will come.
      probe.expectMsgClass(classOf[IngestionStopped])
    }

    // Now, if we send more rows, we will get UnknownDataset
    probe.send(coordinatorActor, IngestRows(ref, 0, 0, records(projection1)))
    probe.expectMsg(UnknownDataset)
  }
}

