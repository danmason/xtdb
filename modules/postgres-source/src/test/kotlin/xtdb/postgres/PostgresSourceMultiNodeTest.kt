package xtdb.postgres

import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.Network
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.lifecycle.Startables
import org.testcontainers.postgresql.PostgreSQLContainer
import software.amazon.awssdk.regions.Region.AWS_ISO_GLOBAL
import xtdb.api.Xtdb
import xtdb.api.log.KafkaCluster
import xtdb.api.storage.Storage
import xtdb.aws.S3
import xtdb.cache.DiskCache
import xtdb.util.info
import xtdb.util.logger
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Reproduction attempts for https://github.com/xtdb/xtdb/issues/5618:
 * 3 nodes against shared Kafka + MinIO with a Postgres CDC source — every node
 * should converge on the same row and tx counts after snapshot, streaming, and
 * after a forced block flush.
 *
 * Containers are per-test (BeforeEach/AfterEach) to side-step a teardown hang
 * we hit when reusing a Postgres replication slot across tests in the same JVM.
 */
@Tag("integration")
class PostgresSourceMultiNodeTest {

    companion object {
        private val log = PostgresSourceMultiNodeTest::class.logger

        private const val NUM_TABLES = 10
        private const val ROWS_PER_TABLE = 100
        private const val MINIO_BUCKET = "xtdb-test"
    }

    private lateinit var network: Network
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var kafka: ConfluentKafkaContainer
    private lateinit var minio: MinIOContainer

    @BeforeEach
    fun beforeEach() {
        log.info("beforeEach: starting postgres, kafka, minio containers")
        network = Network.newNetwork()

        postgres = PostgreSQLContainer("postgres:17-alpine")
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withCommand("postgres", "-c", "wal_level=logical")

        kafka = ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0")
            .withNetwork(network)
            .withNetworkAliases("kafka")

        minio = MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z")
            .withNetwork(network)
            .withNetworkAliases("minio")

        Startables.deepStart(postgres, kafka, minio).join()

        MinioClient.builder()
            .endpoint(minio.s3URL).credentials(minio.userName, minio.password)
            .build()
            .makeBucket(MakeBucketArgs.builder().bucket(MINIO_BUCKET).build())
        log.info("beforeEach: containers ready")
    }

    @AfterEach
    fun afterEach() {
        log.info("afterEach: stopping containers")
        postgres.stop()
        kafka.stop()
        minio.stop()
        network.close()
        log.info("afterEach: done")
    }

    private fun pgExecute(vararg statements: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                for (sql in statements) stmt.execute(sql)
            }
        }
    }

    private fun openNode(nodeDir: Path, sourceTopic: String): Xtdb = Xtdb.openNode {
        server { port = 0 }; flightSql = null
        diskCache(DiskCache.factory(nodeDir.resolve("disk-cache")))
        logCluster("kafka", KafkaCluster.ClusterFactory(kafka.bootstrapServers))
        remote("pg", PostgresRemote.Factory(
            hostname = postgres.host,
            port = postgres.getMappedPort(5432),
            database = "testdb",
            username = "testuser",
            password = "testpass",
        ))
        log(KafkaCluster.LogFactory("kafka", sourceTopic))
        storage(Storage.remote(S3.s3(MINIO_BUCKET) {
            endpoint(minio.s3URL)
            credentials(minio.userName, minio.password)
            region(AWS_ISO_GLOBAL.id())
            prefix(Path("primary"))
            pathStyleAccessEnabled(true)
        }))
    }

    private fun attachPostgresSource(
        node: Xtdb,
        replicaTopic: String,
        cdcStoragePrefix: String,
        slotName: String,
        publicationName: String,
        dbName: String = "cdc",
    ) {
        node.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    ATTACH DATABASE $dbName WITH $$
                        log: !Kafka
                          cluster: kafka
                          topic: $replicaTopic
                        storage: !Remote
                          objectStore: !S3
                            bucket: "$MINIO_BUCKET"
                            endpoint: "${minio.s3URL}"
                            region: "${AWS_ISO_GLOBAL.id()}"
                            credentials:
                              accessKey: "${minio.userName}"
                              secretKey: "${minio.password}"
                            pathStyleAccessEnabled: true
                            prefix: "$cdcStoragePrefix"
                        externalSource: !Postgres
                          remote: pg
                          slotName: $slotName
                          publicationName: $publicationName
                          schemaIncludeList: [public]
                    $$""".trimIndent()
                )
            }
        }
    }

    private fun xtQueryDb(node: Xtdb, dbName: String, sql: String): List<Map<String, Any?>> =
        node.createConnectionBuilder().database(dbName).build().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    val metadata = rs.metaData
                    val cols = (1..metadata.columnCount).map { metadata.getColumnName(it) }
                    buildList {
                        while (rs.next()) {
                            add(cols.associateWith { rs.getObject(it) })
                        }
                    }
                }
            }
        }

    private suspend fun awaitCondition(description: String, timeout: Duration = 60.seconds, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(check).getOrDefault(false)) return
            runInterruptible { Thread.sleep(250) }
        }
        throw AssertionError("Timed out waiting for: $description")
    }

    private fun tableName(runId: String, idx: Int) = "pg_mn_${runId}_t${"%02d".format(idx)}"

    private fun totalRowsAcrossTables(node: Xtdb, runId: String): Long =
        (1..NUM_TABLES).sumOf { i ->
            xtQueryDb(node, "cdc", "SELECT count(*) AS c FROM public.${tableName(runId, i)}")[0]["c"] as Long
        }

    private fun txCount(node: Xtdb): Long =
        xtQueryDb(node, "cdc", "SELECT count(*) AS c FROM xt.txs FOR ALL VALID_TIME")[0]["c"] as Long

    private fun listCdcBlockKeys(prefix: String): List<String> {
        val client = MinioClient.builder()
            .endpoint(minio.s3URL).credentials(minio.userName, minio.password)
            .build()
        return client.listObjects(
            ListObjectsArgs.builder().bucket(MINIO_BUCKET).prefix(prefix).recursive(true).build()
        ).map { it.get().objectName() }.filter { "/blocks/" in it }
    }

    private suspend fun awaitAllNodes(
        label: String,
        runId: String,
        nodes: List<Xtdb>,
        expectedRows: Long,
        expectedTxs: Long,
        timeout: Duration = 30.seconds,
    ) {
        awaitCondition("$label: every node sees $expectedRows rows and $expectedTxs txs", timeout) {
            nodes.all { totalRowsAcrossTables(it, runId) == expectedRows && txCount(it) == expectedTxs }
        }
        nodes.forEachIndexed { i, n ->
            assertEquals(expectedRows, totalRowsAcrossTables(n, runId), "$label: node${i + 1} row total")
            assertEquals(expectedTxs, txCount(n), "$label: node${i + 1} tx count")
        }
    }

    @Test
    fun `3-node cluster snapshot via postgres-source - all nodes see all rows`(
        @TempDir node1Dir: Path,
        @TempDir node2Dir: Path,
        @TempDir node3Dir: Path,
        @TempDir lateNodeDir: Path,
    ) = runTest(timeout = 90.seconds) {
        val runId = UUID.randomUUID().toString().replace("-", "_").take(12)
        val pubName = "test_pub_$runId"
        val slotName = "test_slot_$runId"
        val sourceTopic = "test-src-$runId"
        val replicaTopic = "test-replica-$runId"
        val cdcStoragePrefix = "cdc-$runId"

        val createStmts = (1..NUM_TABLES).flatMap { i ->
            val name = tableName(runId, i)
            listOf("CREATE TABLE IF NOT EXISTS $name (_id INT PRIMARY KEY, payload TEXT)") +
                (1..ROWS_PER_TABLE).map { r -> "INSERT INTO $name (_id, payload) VALUES ($r, 'row-$r')" }
        }
        val tableList = (1..NUM_TABLES).joinToString(", ") { tableName(runId, it) }
        pgExecute(*createStmts.toTypedArray(), "CREATE PUBLICATION $pubName FOR TABLE $tableList")

        // 1 tx per table snapshot + 1 snapshot-complete marker
        val snapshotTxs = (NUM_TABLES + 1).toLong()
        val snapshotRows = (NUM_TABLES * ROWS_PER_TABLE).toLong()

        openNode(node1Dir, sourceTopic).use { node1 ->
            openNode(node2Dir, sourceTopic).use { node2 ->
                openNode(node3Dir, sourceTopic).use { node3 ->
                    val nodes = listOf(node1, node2, node3)

                    attachPostgresSource(node1,
                        replicaTopic = replicaTopic,
                        cdcStoragePrefix = cdcStoragePrefix,
                        slotName = slotName,
                        publicationName = pubName)

                    // Phase 1: wait for snapshot to converge across all 3 before streaming.
                    awaitAllNodes("after snapshot", runId, nodes, snapshotRows, snapshotTxs)

                    // Phase 2: stream one row, confirm convergence again.
                    pgExecute("INSERT INTO ${tableName(runId, 1)} (_id, payload) VALUES (9999, 'streamed')")
                    awaitAllNodes("after streaming", runId, nodes, snapshotRows + 1, snapshotTxs + 1)

                    // Phase 3: spin up a late-joining 4th node and watch it catch up.
                    openNode(lateNodeDir, sourceTopic).use { lateNode ->
                        awaitAllNodes("late joiner caught up", runId,
                            nodes + lateNode, snapshotRows + 1, snapshotTxs + 1)
                    }

                    // Mirrors the issue: snapshot completed but no block files should exist.
                    val blockKeys = listCdcBlockKeys(cdcStoragePrefix)
                    assertEquals(emptyList<String>(), blockKeys,
                        "expected no cdc block files in object store, found: $blockKeys")
                }
            }
        }
    }

    @Test
    fun `3-node cluster snapshot via postgres-source - flush block after snapshot`(
        @TempDir node1Dir: Path,
        @TempDir node2Dir: Path,
        @TempDir node3Dir: Path,
        @TempDir lateNodeDir: Path,
    ) = runTest(timeout = 90.seconds) {
        val runId = UUID.randomUUID().toString().replace("-", "_").take(12)
        val pubName = "test_pub_$runId"
        val slotName = "test_slot_$runId"
        val sourceTopic = "test-src-$runId"
        val replicaTopic = "test-replica-$runId"
        val cdcStoragePrefix = "cdc-$runId"

        val createStmts = (1..NUM_TABLES).flatMap { i ->
            val name = tableName(runId, i)
            listOf("CREATE TABLE IF NOT EXISTS $name (_id INT PRIMARY KEY, payload TEXT)") +
                (1..ROWS_PER_TABLE).map { r -> "INSERT INTO $name (_id, payload) VALUES ($r, 'row-$r')" }
        }
        val tableList = (1..NUM_TABLES).joinToString(", ") { tableName(runId, it) }
        pgExecute(*createStmts.toTypedArray(), "CREATE PUBLICATION $pubName FOR TABLE $tableList")

        val snapshotTxs = (NUM_TABLES + 1).toLong()
        val snapshotRows = (NUM_TABLES * ROWS_PER_TABLE).toLong()

        openNode(node1Dir, sourceTopic).use { node1 ->
            openNode(node2Dir, sourceTopic).use { node2 ->
                openNode(node3Dir, sourceTopic).use { node3 ->
                    val nodes = listOf(node1, node2, node3)

                    attachPostgresSource(node1,
                        replicaTopic = replicaTopic,
                        cdcStoragePrefix = cdcStoragePrefix,
                        slotName = slotName,
                        publicationName = pubName)

                    // Phase 1: snapshot converges, no blocks yet.
                    awaitAllNodes("before flush", runId, nodes, snapshotRows, snapshotTxs)
                    assertEquals(emptyList<String>(), listCdcBlockKeys(cdcStoragePrefix),
                        "no block files expected before flush")

                    // Phase 2: force a cdc block flush from any node.
                    val cat = (node1 as Xtdb.XtdbInternal).dbCatalog
                    cat["cdc"]!!.sendFlushBlockMessage()
                    cat.syncAll(15.seconds.toJavaDuration())

                    // Row/tx counts should be unchanged across all nodes after the flush.
                    awaitAllNodes("after flush", runId, nodes, snapshotRows, snapshotTxs)

                    // Phase 3: spin up a late-joining 4th node and watch it catch up.
                    openNode(lateNodeDir, sourceTopic).use { lateNode ->
                        awaitAllNodes("late joiner caught up", runId,
                            nodes + lateNode, snapshotRows, snapshotTxs)
                    }

                    val blockKeys = listCdcBlockKeys(cdcStoragePrefix)
                    assertTrue(blockKeys.isNotEmpty(),
                        "expected cdc block files in object store after flush, found none")
                }
            }
        }
    }
}
