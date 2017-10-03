package filodb.jmh

import ch.qos.logback.classic.{Level, Logger}
import com.typesafe.config.ConfigRenderOptions
import java.util.concurrent.TimeUnit
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.functions.sum
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.openjdk.jmh.annotations._
import org.velvia.filo.{RowReader, TupleRowReader}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scalaxy.loops._

import filodb.core._
import filodb.core.memstore.IngestRecord
import filodb.core.metadata.{Column, Dataset}
import filodb.core.store._
import filodb.spark.{FiloDriver, FiloExecutor, FiloRelation}

/**
 * A benchmark to compare performance of FiloRelation against different scenarios,
 * for an analytical query summing 5 million random integers from a single column of a
 * FiloDB dataset.  Description:
 * - sparkSum(): Sum 5 million integers stored using MemStore.
 * - sparkBaseline(): Get the first 2 records.  Just to see what the baseline latency is of a
 *   DataFrame query.
 * - memStoreOnly(): No Spark, just reading rows from the MemStore
 * - sparkCassSum(): Sum 5 million integers using CassandraColumnStore.  Must have run CreateCassTestData
 *   first to populate into Cassandra.
 *   NOTE: This has been moved to SparkCassBenchmark.scala
 *
 * To get the scan speed, one needs to subtract the baseline from the total time of sparkSum/sparkCassSum.
 * For example, on my laptop, here is the JMH output:
 * {{{
 *  Benchmark                              Mode  Cnt  Score   Error  Units
 *  SparkReadBenchmark.memStoreOnly          ss   15  ≈ 10⁻³            s/op
 *  SparkReadBenchmark.sparkBaseline         ss   15   0.013 ±  0.001   s/op
 *  SparkReadBenchmark.sparkSum              ss   15   0.045 ±  0.002   s/op
 *  SparkCassBenchmark.sparkCassSum          ss   15   0.226 ± 0.035   s/op
 * }}}
 *
 * (The above run against Cassandra 2.1.6, 5GB heap, with jmh:run -i 3 -wi 3 -f3 filodb.jmh.SparkReadBenchmark)
 *
 * Thus:
 * - Cassandra scan speed = 5000000 / (0.226 - 0.013) =  23.47 million ops/sec
 * - InMemory scan speed  = 5000000 / (0.045 - 0.013) = 156.25 million ops/sec
 */
@State(Scope.Benchmark)
class SparkReadBenchmark {
  org.slf4j.LoggerFactory.getLogger("filodb").asInstanceOf[Logger].setLevel(Level.ERROR)

  val NumRows = 5000000
  // Source of rows
  val sess = SparkSession.builder.master("local[4]")
                                 .appName("test")
                                 .config("spark.ui.enabled", "false")
                                 .config("spark.filodb.store", "in-memory")
                                 .config("spark.filodb.memstore.chunks-to-keep", "20")
                                 .getOrCreate
  val sc = sess.sparkContext
  // Below is to make sure that Filo actor system stuff is run before test code
  // so test code is not hit with unnecessary slowdown
  val filoConfig = FiloDriver.initAndGetConfig(sc)

  import IntSumReadBenchmark._

  // Initialize metastore
  import filodb.coordinator.client.Client._
  parse(FiloDriver.metaStore.newDataset(dataset)) { x => x }
  val createColumns = schema.map { col => FiloDriver.metaStore.newColumn(col, ref) }
  parse(Future.sequence(createColumns)) { x => x }
  FiloDriver.memStore.setup(projection, 0)
  val split = FiloDriver.memStore.getScanSplits(ref).head

  // Write raw data into MemStore
  rowIt.take(NumRows).zipWithIndex
       .map { case (row, i) => IngestRecord(projection, TupleRowReader(row), i) }
       .grouped(500)
       .foreach { records =>
         FiloDriver.memStore.ingest(ref, 0, records)
       }

  @TearDown
  def shutdownFiloActors(): Unit = {
    FiloDriver.shutdown()
    FiloExecutor.shutdown()
    sc.stop()
  }

  val df = sess.read.format("filodb.spark").option("dataset", dataset.name).load

  // How long does it take to iterate through all the rows
  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def sparkSum(): Any = {
    df.agg(sum(df("int"))).collect().head
  }

  val readOnlyProjStr = projection.toReadOnlyProjString(Seq("int"))
  val configStr = filoConfig.root.render(ConfigRenderOptions.concise)

  // Measure the speed of MemStore's ScanChunks etc. over many chunks
  // Including null check
  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def memStoreOnly(): Any = {
    val it = FiloRelation.perPartitionRowScanner(configStr, readOnlyProjStr, 0,
                            FilteredPartitionScan(split), AllChunkScan).asInstanceOf[Iterator[InternalRow]]
    var sum = 0
    while (it.hasNext) {
      val row = it.next
      if (!row.isNullAt(0)) sum += row.getInt(0)
    }
    sum
  }

  // Baseline comparison ... see what the minimal time for a Spark task is.
  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def sparkBaseline(): Any = {
    df.select("int").limit(2).collect()
  }
}