package xtdb.indexer

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xtdb.SimulationTestBase
import xtdb.api.log.Log
import kotlin.time.Duration.Companion.seconds

class SimLogTest : SimulationTestBase() {

    @Test
    fun `plainConsumer processRecords failure propagates via the parent scope`() = runTest(timeout = 5.seconds) {
        val ex = assertThrows<IllegalStateException> {
            coroutineScope {
                SimLog<String>("test", dispatcher + coroutineContext.job, rand).use { log ->
                    launch(dispatcher) {
                        log.tailAll(afterMsgId = -1) { _ -> error("plainConsumer failure") }
                    }

                    log.appendMessage("trigger")
                    yield()
                }
            }
        }

        assertEquals("plainConsumer failure", ex.message)
    }

    @Test
    fun `group consumer processRecords failure propagates via the parent scope`() = runTest(timeout = 5.seconds) {
        val ex = assertThrows<IllegalStateException> {
            coroutineScope {
                SimLog<String>("test", dispatcher + coroutineContext.job, rand).use { log ->
                    val listener = object : Log.SubscriptionListener<String> {
                        override suspend fun onPartitionsAssigned(partitions: Collection<Int>) =
                            Log.TailSpec<String>(afterMsgId = -1L) { _ -> error("groupConsumer failure") }

                        override suspend fun onPartitionsRevoked(partitions: Collection<Int>) {}
                    }

                    launch(dispatcher) { log.openGroupSubscription(listener) }

                    log.appendMessage("trigger")
                    yield()
                }
            }
        }

        assertEquals("groupConsumer failure", ex.message)
    }
}
