package xtdb.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.io.path.listDirectoryEntries

class DiskCacheTest {

    @Test
    fun `staging file is cleaned up when fetch fails`(@TempDir rootPath: Path) {
        val cache = DiskCache.Factory(rootPath).build()
        val stagingDir = rootPath.resolve(".tmp")

        val future = cache.get(Path.of("missing-key")) { _, _ ->
            CompletableFuture.failedFuture(RuntimeException("simulated fetch failure"))
        }

        assertThrows<ExecutionException> { future.get() }

        assertEquals(
            emptyList<Path>(), stagingDir.listDirectoryEntries(),
            "staging files should be cleaned up after fetch failure"
        )
    }
}
