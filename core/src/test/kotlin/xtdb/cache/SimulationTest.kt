package xtdb.cache

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.OutOfMemoryException
import org.apache.arrow.memory.RootAllocator
import org.junit.jupiter.api.*
import xtdb.SimulationTestBase
import xtdb.cache.MemoryCache.Slice
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val testIterations = 10

class SimulationTest : SimulationTestBase() {
    private lateinit var allocator: BufferAllocator

    class TestPathLoader(val seed: Int) : MemoryCache.PathLoader {
        private val rand = Random(seed)
        private val loadCounts = mutableMapOf<Path, Int>()

        override fun load(path: Path, slice: Slice, arena: Arena): MemorySegment {
            val count = loadCounts.compute(path) { _, c -> (c ?: 0) + 1 }!!
            return arena.allocate(slice.length)
                .also { it.set(JAVA_BYTE, 0, (rand.nextInt(256)).toByte()) }
        }

        fun getLoadCount(path: Path) = loadCounts[path] ?: 0
    }

    @BeforeEach
    override fun setUpSimulation() {
        super.setUpSimulation()
        allocator = RootAllocator()
    }

    @AfterEach
    override fun tearDownSimulation() {
        allocator.close()
        super.tearDownSimulation()
    }

    @RepeatedTest(testIterations)
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `deterministic single fetch and evict`() {
        val loader = TestPathLoader(currentSeed)
        MemoryCache(allocator, 250, loader, dispatcher).use { cache ->
            val sliceSize = rand.nextLong(1L, 250L)
            val path = Path.of("test/$sliceSize")
            var evicted = false
            val onEvict = AutoCloseable { evicted = true }

            cache.get(path, Slice(0, sliceSize)) { it to onEvict }.use { buf ->
                Assertions.assertNotNull(buf)
                Assertions.assertEquals(sliceSize, buf.readableBytes())
                Assertions.assertEquals(MemoryCache.Stats(sliceSize, 250L - sliceSize), cache.stats0)
            }

            Assertions.assertTrue(evicted)
            Assertions.assertEquals(MemoryCache.Stats(0L, 250L), cache.stats0)
            Assertions.assertEquals(1, loader.getLoadCount(path))
        }
    }

    @RepeatedTest(testIterations)
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `deterministic reuse of same path-slice`() {
        val loader = TestPathLoader(currentSeed)
        MemoryCache(allocator, 250, loader, dispatcher).use { cache ->
            val sliceSize = rand.nextLong(1L, 250L)
            val path = Path.of("test/$sliceSize")
            val slice = Slice(0, sliceSize)

            // Nested fetches of same path-slice should reuse
            cache.get(path, slice) { it to null }.use {
                Assertions.assertEquals(sliceSize, cache.stats0.usedBytes)

                cache.get(path, slice) { it to null }.use {
                    // Should reuse, not double-count
                    Assertions.assertEquals(sliceSize, cache.stats0.usedBytes)

                    cache.get(path, slice) { it to null }.use {
                        Assertions.assertEquals(sliceSize, cache.stats0.usedBytes)
                    }
                }
            }

            Assertions.assertEquals(MemoryCache.Stats(0L, 250L), cache.stats0)
        }
    }

    @RepeatedTest(testIterations)
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `deterministic oom handling`() {
        val loader = TestPathLoader(currentSeed)
        MemoryCache(allocator, 100, loader, dispatcher).use { cache ->
            val tooBigSlice = rand.nextLong(101L, 200L)
            val smallSlice = rand.nextLong(1L, 50L)
            // Try to fetch something larger than cache capacity
            Assertions.assertThrows(OutOfMemoryException::class.java) {
                try {
                    cache.get(Path.of("big/$tooBigSlice"), Slice(0, tooBigSlice)) { it to null }.use { }
                } catch (e: Exception) {
                    throw e.cause ?: e
                }
            }

            // Cache should still be functional
            cache.get(Path.of("small/$smallSlice"), Slice(0, smallSlice)) { it to null }.use { buf ->
                Assertions.assertEquals(smallSlice, buf.readableBytes())
            }
        }
    }
}