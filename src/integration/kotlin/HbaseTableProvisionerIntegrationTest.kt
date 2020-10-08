import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.HConstants
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client.Connection
import org.apache.hadoop.hbase.client.ConnectionFactory
import uk.gov.dwp.dataworks.logging.DataworksLogger
import kotlin.time.ExperimentalTime
import kotlin.time.minutes
import kotlin.time.seconds

@ExperimentalTime
class HbaseTableProvisionerIntegrationTest : StringSpec() {
    init {
        "Collections are provisioned as tables into Hbase" {
            verifyHbase()
        }
    }

    private val expectedTablesAndRegions = mapOf(
        "accepted_data:address" to 0,
        "accepted_data:childrenCircumstances" to 0,
        "core:assessmentPeriod" to 0,
        "core:toDo" to 0,
        "crypto:encryptedData" to 0)

    private fun hbaseConnection(): Connection {
        val host = System.getenv("HBASE_ZOOKEEPER_QUORUM") ?: "localhost"
        val config = Configuration().apply {
            set(HConstants.ZOOKEEPER_ZNODE_PARENT, "/hbase")
            set(HConstants.ZOOKEEPER_QUORUM, host)
            setInt("hbase.zookeeper.port", 2181)
        }
        return ConnectionFactory.createConnection(HBaseConfiguration.create(config))
    }

    private fun testTables(): List<String> {
        val tables = hbaseConnection().admin.listTableNames()
            .map(TableName::getNameAsString)
            .sorted()
        logger.info("...hbase tables", "number" to "${tables.size}", "all_tables" to "$tables")
        return tables
    }

    private suspend fun verifyHbase() {
        var waitSoFarSecs = 0
        val longInterval = 10
        val expectedTablesSorted = expectedTablesAndRegions.keys.sorted()
        logger.info("Waiting for ${expectedTablesSorted.size} hbase tables to appear with given regions",
            "expected_tables_sorted" to "$expectedTablesSorted")

        hbaseConnection().use { hbase ->
            withTimeout(10.minutes) {
                do {
                    val foundTablesSorted = testTables()
                    logger.info("Waiting for ${expectedTablesSorted.size} hbase tables to appear",
                        "found_tables_so_far" to "${foundTablesSorted.size}",
                        "total_seconds_elapsed" to "$waitSoFarSecs")
                    delay(longInterval.seconds)
                    waitSoFarSecs += longInterval
                }
                while (expectedTablesSorted.toSet() != foundTablesSorted.toSet())

                testTables().forEach { tableName ->
                    launch(Dispatchers.IO) {
                        hbase.getTable(TableName.valueOf(tableName)).use { table ->

                            val configs = mutableMapOf<String,String>()
                            table.configuration.iterator().forEachRemaining { config ->
                                configs[config.key] = config.value
                            }

                            logger.info("Found table",
                                "table_name" to "${table.name}",
                                "table_configuration" to "${configs}",
                                "table_descriptor" to table.tableDescriptor.toStringCustomizedValues(),
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        val logger = DataworksLogger.getLogger(HbaseTableProvisionerIntegrationTest::class.toString())
    }
}
