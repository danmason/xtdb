package xtdb.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Utility for creating test dispatcher contexts with strict exception handling.
 *
 * Any uncaught exception in a coroutine will be immediately thrown, causing test failures.
 *
 * Example usage:
 * ```kotlin
 * @Test
 * fun myTest() = runTest {
 *     val log = Log.localLog(path).coroutineContext(TestDispatchers.Default)
 * }
 * ```
 */
object ThrowingDispatchers {
    private val throwingHandler = CoroutineExceptionHandler { _, throwable ->
        throw throwable
    }
    val Default: CoroutineContext = Dispatchers.Default + throwingHandler
    val IO: CoroutineContext = Dispatchers.IO + throwingHandler
}
