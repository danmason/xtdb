package xtdb.profiler

import org.apache.arrow.memory.RootAllocator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xtdb.storage.MemoryStorage

class ProfilerTest {
    private lateinit var allocator: RootAllocator
    private lateinit var storage: MemoryStorage

    @BeforeEach
    fun setUp() {
        allocator = RootAllocator()
        storage = MemoryStorage(allocator, epoch = 0)
    }

    @AfterEach
    fun tearDown() {
        storage.close()
        allocator.close()
    }

    @Test
    fun `records a flamegraph and uploads it to the buffer pool`() {
        val profiler = Profiler()

        val path = profiler.record(storage) {
            val x = 1 + 1
            val y = x * x
            val z = y - x
        }

        assertTrue(path.toString().startsWith("profiles/"), "unexpected path: $path")
        assertTrue(path.toString().endsWith(".html"), "expected .html suffix: $path")

        val bytes = storage.getByteArray(path)
        assertTrue(bytes.isNotEmpty(), "expected non-empty flamegraph upload")

        val head = String(bytes.copyOf(minOf(bytes.size, 512)))
        assertTrue(
            head.contains("<!DOCTYPE html>", ignoreCase = true) || head.contains("<html", ignoreCase = true),
            "expected flamegraph HTML, got: $head"
        )
    }
}
