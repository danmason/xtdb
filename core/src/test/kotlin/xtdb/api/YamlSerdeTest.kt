package xtdb.api

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import xtdb.api.Authenticator.Factory.OpenIdConnect
import xtdb.api.Authenticator.Factory.UserTable
import xtdb.api.Authenticator.Method.*
import xtdb.api.Authenticator.MethodRule
import xtdb.api.log.KafkaCluster
import xtdb.api.log.LocalLog.Factory
import xtdb.api.log.Log.Companion.inMemoryLog
import xtdb.api.module.XtdbModule
import xtdb.api.storage.Storage.InMemoryStorageFactory
import xtdb.api.storage.Storage.LocalStorageFactory
import xtdb.api.storage.Storage.RemoteStorageFactory
import xtdb.aws.CloudWatchMetrics
import xtdb.aws.S3.Companion.s3
import xtdb.azure.AzureMonitorMetrics
import xtdb.azure.BlobStorage.Companion.azureBlobStorage
import xtdb.cache.DiskCache
import xtdb.gcp.CloudStorage.Companion.googleCloudStorage
import xtdb.util.asPath
import java.net.InetAddress
import java.net.URL
import java.nio.file.Paths

class YamlSerdeTest {

    @Suppress("UNCHECKED_CAST")
    private fun <T : XtdbModule.Factory> Xtdb.Config.findModule(module: Class<out XtdbModule.Factory>): T? =
        getModules().find { module.isAssignableFrom(it::class.java) } as T?

    private inline fun <reified T : XtdbModule.Factory> Xtdb.Config.findModule(): T? = findModule(T::class.java)

    @Test
    fun testDecoder() {
        val input = """
        server: 
            port: 3000
            numThreads: 42
            ssl: 
                keyStore: test-path
                keyStorePassword: password
        databases:
          xtdb:
            log: !InMemory
            storage: !Local
                path: local-storage
        memoryCache:
            maxSizeBytes: 1025
        indexer:
            logLimit: 65
            flushDuration: PT4H
        healthz:
            port: 3000
        defaultTz: "America/Los_Angeles"
        """.trimIndent()

        println(nodeConfig(input).toString())
    }

    @Test
    fun testMetricsConfigDecoding() {
        val input = """
        healthz: 
          port: 3000
        """.trimIndent()

        assertEquals(3000, nodeConfig(input).healthz?.port)

        val awsInput = """
        modules: 
          - !CloudWatch
            namespace: "aws.namespace" 
        """.trimIndent()

        assertEquals("aws.namespace", nodeConfig(awsInput).findModule<CloudWatchMetrics>()?.namespace)

        val azureInput = """
        modules: 
          - !AzureMonitor
            connectionString: "InstrumentationKey=00000000-0000-0000-0000-000000000000;" 
        """.trimIndent()

        assertEquals(
            "InstrumentationKey=00000000-0000-0000-0000-000000000000;",
            nodeConfig(azureInput).findModule<AzureMonitorMetrics>()?.connectionString
        )
    }

    @Test
    fun testLogDecoding() {
        val inMemoryConfig = """
            databases:
              xtdb:
                log: !InMemory
        """.trimIndent()

        assertEquals(inMemoryLog, nodeConfig(inMemoryConfig).databases["xtdb"]!!.log)

        val localConfig = """
            databases:
              xtdb:
                log: !Local
                    path: test-path
        """.trimIndent()

        assertEquals(
            Factory(path = Paths.get("test-path")),
            nodeConfig(localConfig).databases["xtdb"]!!.log
        )

        val kafkaConfig = """
          logClusters:
            kafkaCluster: !Kafka
              bootstrapServers: "localhost:9092"

          databases:
            xtdb:
              log: !Kafka
                cluster: kafkaCluster
                topic: xtdb_topic
            
        """.trimIndent()

        assertEquals(
            KafkaCluster.ClusterFactory(bootstrapServers = "localhost:9092"),
            nodeConfig(kafkaConfig).logClusters["kafkaCluster"]
        )

        assertEquals(
            KafkaCluster.LogFactory("kafkaCluster", "xtdb_topic"),
            nodeConfig(kafkaConfig).databases["xtdb"]!!.log
        )
    }

    @Test
    fun testStorageDecoding() {
        mockkObject(EnvironmentVariableProvider)

        val inMemoryConfig = """
        databases:
          xtdb:
            storage: !InMemory
        """.trimIndent()

        assertEquals(
            InMemoryStorageFactory::class,
            nodeConfig(inMemoryConfig).databases["xtdb"]!!.storage::class
        )

        val localConfig = """
        databases:
          xtdb:
            storage: !Local
                path: test-path
        """.trimIndent()

        assertEquals(
            LocalStorageFactory(path = Paths.get("test-path")),
            nodeConfig(localConfig).databases["xtdb"]!!.storage
        )

        every { EnvironmentVariableProvider.getEnvVariable("S3_BUCKET") } returns "xtdb-bucket"

        val s3Config = """
        databases:
          xtdb:
            storage: !Remote
                objectStore: !S3
                  bucket: !Env S3_BUCKET
                  prefix: xtdb-object-store
              
        diskCache: 
            path: test-path
        """.trimIndent()

        val s3NodeConfig = nodeConfig(s3Config)

        assertEquals(
            RemoteStorageFactory(
                objectStore = s3(bucket = "xtdb-bucket").prefix(Paths.get("xtdb-object-store")),
            ),
            s3NodeConfig.databases["xtdb"]!!.storage
        )

        assertEquals(
            DiskCache.Factory("test-path".asPath),
            s3NodeConfig.diskCache
        )

        every { EnvironmentVariableProvider.getEnvVariable("AZURE_STORAGE_ACCOUNT") } returns "storage-account"

        val azureConfig = """
        databases:
          xtdb:
            storage: !Remote
                objectStore: !Azure
                  storageAccount: !Env AZURE_STORAGE_ACCOUNT
                  container: xtdb-container
                  prefix: xtdb-object-store
        """.trimIndent()

        assertEquals(
            RemoteStorageFactory(
                objectStore = azureBlobStorage(
                    storageAccount = "storage-account",
                    container = "xtdb-container"
                ).prefix(Paths.get("xtdb-object-store")),
            ),
            nodeConfig(azureConfig).databases["xtdb"]!!.storage
        )

        every { EnvironmentVariableProvider.getEnvVariable("GCP_PROJECT_ID") } returns "xtdb-project"

        val googleCloudConfig = """
        databases:
          xtdb:
            storage: !Remote
              objectStore: !GoogleCloud
                projectId: !Env GCP_PROJECT_ID
                bucket: xtdb-bucket
                prefix: xtdb-object-store
        """.trimIndent()

        assertEquals(
            RemoteStorageFactory(
                objectStore = googleCloudStorage(
                    projectId = "xtdb-project",
                    bucket = "xtdb-bucket",
                ).prefix(Paths.get("xtdb-object-store")),
            ),
            nodeConfig(googleCloudConfig).databases["xtdb"]!!.storage
        )

        unmockkObject(EnvironmentVariableProvider)
    }

    @Test
    fun testModuleDecoding() {
        val input = """
        modules:
            - !HttpServer
              port: 3001
            - !FlightSqlServer
              port: 9833
        """.trimIndent()

        assertEquals(
            listOf(
                HttpServer.Factory(port = 3001),
                FlightSqlServer.Factory(port = 9833)
            ),
            nodeConfig(input).getModules()
        )
    }

    @Test
    fun testEnvVarsWithUnsetVariable() {
        val inputWithEnv = """
        databases:
          xtdb:
            log: !Local
                path: !Env TX_LOG_PATH
        """.trimIndent()

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            nodeConfig(inputWithEnv)
        }

        assertEquals("Environment variable 'TX_LOG_PATH' not found", thrown.message)
    }

    @Test
    fun testEnvVarsWithSetVariable() {
        mockkObject(EnvironmentVariableProvider)
        every { EnvironmentVariableProvider.getEnvVariable("TX_LOG_PATH") } returns "test-path"

        val inputWithEnv = """
        databases:
          xtdb:
            log: !Local
                path: !Env TX_LOG_PATH
        """.trimIndent()

        assertEquals(
            Factory(path = Paths.get("test-path")),
            nodeConfig(inputWithEnv).databases["xtdb"]!!.log
        )

        unmockkObject(EnvironmentVariableProvider)
    }

    @Test
    fun testEnvVarsMultipleSetVariables() {
        mockkObject(EnvironmentVariableProvider)
        every { EnvironmentVariableProvider.getEnvVariable("BUCKET") } returns "xtdb-bucket"
        every { EnvironmentVariableProvider.getEnvVariable("DISK_CACHE_PATH") } returns "test-path"

        val inputWithEnv = """
        databases:
          xtdb:
            storage: !Remote
                objectStore: !S3
                  bucket: !Env BUCKET 
        diskCache: 
            path: !Env DISK_CACHE_PATH
        """.trimIndent()

        val nodeConfig = nodeConfig(inputWithEnv)

        assertEquals(
            RemoteStorageFactory(objectStore = s3(bucket = "xtdb-bucket")),
            nodeConfig.databases["xtdb"]!!.storage
        )

        assertEquals(
            DiskCache.Factory("test-path".asPath),
            nodeConfig.diskCache
        )

        unmockkObject(EnvironmentVariableProvider)
    }

    @Test
    fun testNestedEnvVarInMaps() {
        mockkObject(EnvironmentVariableProvider)
        every { EnvironmentVariableProvider.getEnvVariable("KAFKA_BOOTSTRAP_SERVERS") } returns "localhost:9092"
        val saslConfig =
            "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"user\" password=\"password\";"
        every { EnvironmentVariableProvider.getEnvVariable("KAFKA_SASL_JAAS_CONFIG") } returns saslConfig

        val inputWithEnv = """
            logClusters:
              kafkaCluster: !Kafka
                bootstrapServers: !Env KAFKA_BOOTSTRAP_SERVERS
                propertiesMap:
                  security.protocol: SASL_SSL
                  sasl.mechanism: PLAIN
                  sasl.jaas.config: !Env KAFKA_SASL_JAAS_CONFIG

            databases:
              xtdb:
                log: !Kafka
                  cluster: kafkaCluster
                  topic: xtdb_topic
        """.trimIndent()

        assertEquals(
            KafkaCluster.ClusterFactory(
                bootstrapServers = "localhost:9092",
                propertiesMap = mapOf(
                    "security.protocol" to "SASL_SSL",
                    "sasl.mechanism" to "PLAIN",
                    "sasl.jaas.config" to saslConfig
                )
            ),
            nodeConfig(inputWithEnv).logClusters["kafkaCluster"]
        )

        unmockkObject(EnvironmentVariableProvider)
    }

    @Test
    fun testPortSetWithEnvVar() {
        mockkObject(EnvironmentVariableProvider)
        every { EnvironmentVariableProvider.getEnvVariable("HEALTHZ_PORT") } returns "8081"

        val input = """
        healthz: 
          port: !Env HEALTHZ_PORT
        """.trimIndent()

        assertEquals(8081, nodeConfig(input).healthz?.port)

        unmockkObject(EnvironmentVariableProvider)
    }

    @Test
    fun testAuthnConfigDecoding() {
        val input = """
        authn: !UserTable
          rules:
              - user: admin
                remoteAddress: 127.0.0.42
                method: TRUST  
              - remoteAddress: 127.0.0.1
                method: PASSWORD  
        """.trimIndent()

        assertEquals(
            UserTable(
                listOf(
                    MethodRule(TRUST, user = "admin", remoteAddress = "127.0.0.42"),
                    MethodRule(PASSWORD, remoteAddress = "127.0.0.1")
                )
            ),
            nodeConfig(input).authn
        )
    }

    @Test
    fun testOidcConfigDecoding() {
        mockkObject(EnvironmentVariableProvider)
        every { EnvironmentVariableProvider.getEnvVariable("OIDC_CLIENT_SECRET") } returns "my-secret-key"

        val input = """
        authn: !OpenIdConnect
          issuerUrl: http://localhost:8080/realms/master
          clientId: xtdb-client
          clientSecret: !Env OIDC_CLIENT_SECRET
          rules:
              - user: xtdb
                method: PASSWORD
                remoteAddress: 127.0.0.1
              - user: oid-client  
                method: CLIENT_CREDENTIALS
              - method: DEVICE_AUTH
        """.trimIndent()

        assertEquals(
            OpenIdConnect(
                issuerUrl = URL("http://localhost:8080/realms/master"),
                clientId = "xtdb-client", 
                clientSecret = "my-secret-key",
                rules = listOf(
                    MethodRule(PASSWORD, user = "xtdb", remoteAddress = "127.0.0.1"),
                    MethodRule(CLIENT_CREDENTIALS, user = "oid-client"),
                    MethodRule(DEVICE_AUTH)
                )
            ),
            nodeConfig(input).authn
        )

        unmockkObject(EnvironmentVariableProvider)
    }

    @Test
    fun testHost() {
        assertEquals(
            ServerConfig().host(InetAddress.getByName("localhost")),
            nodeConfig(
                """
                    server: 
                      host: localhost
                """.trimIndent()
            ).server
        )

        assertEquals(
            ServerConfig().host(InetAddress.getByName("127.0.0.1")),
            nodeConfig(
                """
                    server: 
                      host: 127.0.0.1
                """.trimIndent()
            ).server
        )

        assertEquals(
            ServerConfig().host(InetAddress.getByName("::")),
            nodeConfig(
                """
                    server: 
                      host: '::'
                """.trimIndent()
            ).server
        )
    }
}
