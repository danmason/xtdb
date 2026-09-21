package xtdb.postgres

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import io.kotest.assertions.nondeterministic.eventually
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.lifecycle.Startables
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A two-node rolling upgrade for a Postgres-source secondary: node A leads on the old release and
 * holds the replication slot, node B has already rolled to the new release.
 *
 * The two nodes don't hand leadership over via the same mechanism.
 * A (FROM_IMAGE) elects via a Kafka consumer group (`groupId`, #5448); B (TO_IMAGE) has already
 * moved to the term-fenced poll in allium/log-processor-lifecycle.allium (#5817), which carries no
 * group of its own.
 * With only one FROM_IMAGE node in this test there's no group to contest, so B taking over turns
 * entirely on the replica log both mechanisms write compatible term stamps to.
 *
 * A is stopped, the ordinary rolling-restart step, and this checks the `xtdb.log.leader` gauge
 * directly throughout — both before the stop (only A may ever hold it) and after (B must take it
 * over) — since data visibility and `/healthz/alive` alone can't tell a leader from a follower: a
 * follower sees the same rows a leader does, off the same shared replica log.
 */
@Tag("integration")
class PostgresSourceUpgradeLeadershipTest {

    companion object {
        private const val FROM_IMAGE = "ghcr.io/xtdb/xtdb-azure:g8208b94"
        private const val TO_IMAGE = "ghcr.io/xtdb/xtdb-azure:2.2.0-beta2"

        private val network: Network = Network.newNetwork()

        private val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withCommand("postgres", "-c", "wal_level=logical")

        private val kafka = ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0")
            .withNetwork(network)
            .withNetworkAliases("kafka")

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            Startables.deepStart(postgres, kafka).join()
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            postgres.stop()
            kafka.stop()
            network.close()
        }
    }

    private fun unique(prefix: String) = "${prefix}_${UUID.randomUUID().toString().replace("-", "_")}"

    private fun pgExecute(vararg statements: String) =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { s -> statements.forEach { s.execute(it) } }
        }

    /** [groupId] selects FROM_IMAGE's own Kafka-consumer-group leader election (#5448); TO_IMAGE
     *  has already dropped it for the term-fenced poll in allium/log-processor-lifecycle.allium
     *  (#5817), so each node's config carries only what its own release accepts. */
    private fun configYaml(primaryTopic: String, groupId: String?): String = buildString {
        appendLine("server:")
        appendLine("  host: '*'")
        appendLine("  port: 5432")
        appendLine()
        appendLine("log: !Kafka")
        appendLine("  cluster: kafka")
        appendLine("  topic: $primaryTopic")
        appendLine()
        appendLine("storage: !Local")
        appendLine("  path: \"/var/lib/xtdb/buffers\"")
        appendLine()
        appendLine("healthz:")
        appendLine("  host: '*'")
        appendLine("  port: 8080")
        appendLine()
        appendLine("remotes:")
        appendLine("  pg: !Postgres")
        appendLine("    hostname: postgres")
        appendLine("    port: 5432")
        appendLine("    database: testdb")
        appendLine("    username: testuser")
        appendLine("    password: testpass")
        appendLine("  kafka: !Kafka")
        appendLine("    bootstrapServers: \"kafka:9093\"")
        if (groupId != null) appendLine("    groupId: \"$groupId\"")
    }

    private fun xtdbContainer(image: String, volumeName: String, configYaml: String): GenericContainer<*> =
        GenericContainer(image)
            .withNetwork(network)
            .withExposedPorts(5432, 8080)
            .withCopyToContainer(Transferable.of(configYaml), "/usr/local/lib/xtdb/config.yaml")
            .withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig!!.withBinds(Bind(volumeName, Volume("/var/lib/xtdb")))
            }
            .waitingFor(Wait.forHttp("/healthz/alive").forPort(8080).withStartupTimeout(Duration.ofMinutes(2)))
            .dependsOn(kafka)

    private fun xtConn(node: GenericContainer<*>, dbName: String = "xtdb"): Connection =
        DriverManager.getConnection("jdbc:postgresql://${node.host}:${node.getMappedPort(5432)}/$dbName", "xtdb", "")

    private fun attachCdc(node: GenericContainer<*>, dbName: String, logTopic: String, slot: String, pub: String) =
        xtConn(node).use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """
                    ATTACH DATABASE $dbName WITH $$
                        log: !Kafka
                          cluster: kafka
                          topic: $logTopic
                        storage: !Local
                          path: "/var/lib/xtdb/$dbName-storage"
                        externalSource: !Postgres
                          remote: pg
                          slotName: $slot
                          publicationName: $pub
                          indexer: !DirectMirror {}
                    $$""".trimIndent()
                )
            }
        }

    private fun xtQuery(node: GenericContainer<*>, dbName: String, sql: String): List<Map<String, Any?>> =
        xtConn(node, dbName).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
                    buildList { while (rs.next()) add(cols.associateWith { rs.getObject(it) }) }
                }
            }
        }

    private fun assertPrimaryDbHealthy(node: GenericContainer<*>) {
        val id = unique("health")
        xtConn(node).use { c ->
            c.createStatement().use { s ->
                s.execute("INSERT INTO primary_health (_id, v) VALUES ('$id', 'ok')")
                s.executeQuery("SELECT v FROM primary_health WHERE _id = '$id'").use { rs ->
                    assertTrue(rs.next(), "primary insert should be visible")
                    assertEquals("ok", rs.getString("v"))
                }
            }
        }
    }

    private val httpClient = HttpClient.newHttpClient()

    private fun healthzAliveStatus(node: GenericContainer<*>): Int =
        httpClient.send(
            HttpRequest.newBuilder(URI("http://${node.host}:${node.getMappedPort(8080)}/healthz/alive")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    /** The `xtdb.log.leader` gauge (LogProcessor.kt, present on both FROM_IMAGE and TO_IMAGE) —
     *  1 if this node currently holds the leader term for [dbName], 0 if it's following. */
    private fun isLeading(node: GenericContainer<*>, dbName: String): Boolean {
        val body = httpClient.send(
            HttpRequest.newBuilder(URI("http://${node.host}:${node.getMappedPort(8080)}/metrics")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        val value = Regex("""xtdb_log_leader\{[^}]*db="${Regex.escape(dbName)}"[^}]*}\s+([0-9.]+)""").find(body)
            ?.groupValues?.get(1)
            ?: error("no xtdb_log_leader metric for db '$dbName' at ${node.host}:${node.getMappedPort(8080)}")
        return value.toDouble() == 1.0
    }

    private fun assertOnlyALeads(nodeA: GenericContainer<*>, nodeB: GenericContainer<*>, dbName: String) {
        val aLeads = isLeading(nodeA, dbName)
        val bLeads = isLeading(nodeB, dbName)
        assertTrue(aLeads && !bLeads, "expected only A leading '$dbName', got A=$aLeads B=$bLeads")
    }

    private fun dumpLogs(dir: Path, node: GenericContainer<*>, name: String) {
        Files.writeString(dir.resolve("$name.log"), node.logs)
    }

    @Test
    fun `a peer on 2_2_0-beta2 takes over a Postgres-source secondary from a stopped g8208b94 leader`() =
        runBlocking {
            withTimeout(480.seconds) {
            val pub = unique("pub")
            val slot = unique("slot")
            val primaryTopic = unique("primary_log")
            val secondaryTopic = unique("secondary_log")
            val cdcDb = unique("cdc")

            pgExecute(
                "CREATE TABLE widgets (_id INT PRIMARY KEY, name TEXT)",
                "INSERT INTO widgets (_id, name) VALUES (1, 'snapshot-row')",
                "CREATE PUBLICATION $pub FOR TABLE widgets",
            )

            val nodeA = xtdbContainer(FROM_IMAGE, unique("vol-a"), configYaml(primaryTopic, groupId = unique("group")))
            val nodeB = xtdbContainer(TO_IMAGE, unique("vol-b"), configYaml(primaryTopic, groupId = null))

            val logDir = Path.of("build/test-logs/PostgresSourceUpgradeLeadershipTest", DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()))
                .also { Files.createDirectories(it) }

            // both stay open for the whole test — B has to survive past A's stop to observe the
            // handover, so neither can close at the end of a narrower `use` block
            nodeA.use {
                nodeB.use {
                    try {
                        nodeA.start()

                        // sole member of its own consumer group, so this is a deterministic
                        // leadership grant, not a race
                        attachCdc(nodeA, cdcDb, secondaryTopic, slot, pub)

                        eventually(30.seconds) {
                            assertTrue(xtQuery(nodeA, cdcDb, "SELECT _id FROM public.widgets WHERE _id = 1").isNotEmpty(), "snapshot row visible on the leader")
                        }

                        pgExecute("INSERT INTO widgets (_id, name) VALUES (2, 'streamed-row')")
                        eventually(30.seconds) {
                            assertTrue(xtQuery(nodeA, cdcDb, "SELECT _id FROM public.widgets WHERE _id = 2").isNotEmpty(), "streamed row visible on the leader")
                        }

                        // A runs alone as sole leader for a while before B ever exists — the real
                        // shape being modelled: an established leader, not a peer B could contest
                        // with from its very first read
                        repeat(3) { i ->
                            pgExecute("INSERT INTO widgets (_id, name) VALUES (${5 + i}, 'solo-$i')")
                            eventually(30.seconds) {
                                assertTrue(xtQuery(nodeA, cdcDb, "SELECT _id FROM public.widgets WHERE _id = ${5 + i}").isNotEmpty(), "leader streaming solo")
                            }
                            delay(5.seconds)
                        }

                        // joins late, already-rolled, tailing the same primary and secondary Kafka
                        // topics — it must only follow while A stays live (LiveLeadershipIsVisibleOnTheLog)
                        nodeB.start()
                        eventually(30.seconds) {
                            assertTrue(xtQuery(nodeB, cdcDb, "SELECT _id FROM public.widgets WHERE _id = 7").isNotEmpty(), "follower has replayed the replica log")
                        }

                        // the actual claim, not just a data-visibility proxy for it — B can see
                        // every row either way, since a follower replays the same shared log
                        assertOnlyALeads(nodeA, nodeB, cdcDb)

                        // both nodes run side by side for a while, as they would through a real
                        // rolling deploy's overlap window. Re-checking leadership on every
                        // iteration is the point: B silently taking over here — succeeding at the
                        // slot rather than contending for it and failing — would otherwise leave
                        // no trace, since the data and healthz checks alone can't tell a follower
                        // from a leader
                        repeat(5) { i ->
                            pgExecute("INSERT INTO widgets (_id, name) VALUES (${10 + i}, 'coexist-$i')")
                            eventually(30.seconds) {
                                assertTrue(xtQuery(nodeA, cdcDb, "SELECT _id FROM public.widgets WHERE _id = ${10 + i}").isNotEmpty(), "leader still streaming during the overlap")
                                assertTrue(xtQuery(nodeB, cdcDb, "SELECT _id FROM public.widgets WHERE _id = ${10 + i}").isNotEmpty(), "follower still replaying during the overlap")
                            }
                            assertOnlyALeads(nodeA, nodeB, cdcDb)
                            delay(5.seconds)
                        }

                        // a further quiet stretch side by side, past the writes above, matching how
                        // long a real rolling deploy's overlap window actually sits before the old
                        // pod is torn down
                        assertOnlyALeads(nodeA, nodeB, cdcDb)
                        delay(30.seconds)
                        assertOnlyALeads(nodeA, nodeB, cdcDb)

                        assertEquals(200, healthzAliveStatus(nodeA), "test precondition: A ingesting cleanly before the restart")

                        // captured here, not just in the top-level `finally` — GenericContainer.stop()
                        // below removes the container, taking its logs with it
                        dumpLogs(logDir, nodeA, "node-a")

                        // the rolling-restart step itself
                        nodeA.stop()

                        // written with no clean leader in place — must be picked up once B claims
                        pgExecute("INSERT INTO widgets (_id, name) VALUES (3, 'after-handover')")

                        // does B actually take over, and does ingestion stop anywhere along the
                        // way — bounded by PgWireDriver's own slot-retry budget (7 attempts, 1s
                        // doubling to 64s, PgWireDriver.kt), past which it gives up rather than
                        // keep retrying
                        eventually(200.seconds) {
                            assertEquals(200, healthzAliveStatus(nodeB), "B reports no ingestionError while taking over")
                            assertTrue(isLeading(nodeB, cdcDb), "B has actually claimed leadership, not just replayed A's last writes")
                            assertTrue(
                                xtQuery(nodeB, cdcDb, "SELECT _id FROM public.widgets WHERE _id = 3").isNotEmpty(),
                                "row written during the handover reaches the new leader",
                            )
                        }

                        assertPrimaryDbHealthy(nodeB)
                    } finally {
                        // best-effort: on the happy path A is already dumped and stopped, so this
                        // second attempt just fails harmlessly; on a failure before that point, A
                        // is still running and this is the only capture it gets
                        runCatching { dumpLogs(logDir, nodeA, "node-a") }
                        dumpLogs(logDir, nodeB, "node-b")
                    }
                }
            }
        }
        }
}
