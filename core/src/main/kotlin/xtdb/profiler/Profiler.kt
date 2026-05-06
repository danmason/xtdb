package xtdb.profiler

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import one.profiler.AsyncProfiler
import xtdb.storage.BufferPool
import xtdb.util.asPath
import xtdb.util.info
import xtdb.util.logger

private val LOG = Profiler::class.logger

/**
 * Wraps a block of work in an async-profiler CPU recording, uploads the resulting flamegraph HTML
 * to the supplied [BufferPool], and returns the upload path.
 *
 * The native profiler is process-wide, so concurrent [record] calls are serialised.
 */
class Profiler {
    private val asyncProfiler: AsyncProfiler = AsyncProfiler.getInstance()
    private val lock = ReentrantLock()

    init {
        // async-profiler's first attach can fail ("Could not find VMThread bridge") if it happens
        // while the JVM is mid-work; do a no-op start/stop now while things are quiet so
        // subsequent record() calls reuse the cached bridge.
        asyncProfiler.execute("start,event=cpu")
        asyncProfiler.execute("stop")
    }

    fun record(bufferPool: BufferPool, block: Runnable): Path =
            lock.withLock {
                val tmpFile = Files.createTempFile("xtdb-profile-", ".html")
                try {
                    asyncProfiler.execute("start,flamegraph,event=cpu")
                    try {
                        block.run()
                    } finally {
                        asyncProfiler.execute("stop,file=$tmpFile")
                    }

                    val objPath = "profiles/${UUID.randomUUID()}.html".asPath
                    bufferPool.putObject(objPath, ByteBuffer.wrap(Files.readAllBytes(tmpFile)))
                    LOG.info("profile written: $objPath")

                    objPath
                } finally {
                    Files.deleteIfExists(tmpFile)
                }
            }
}
