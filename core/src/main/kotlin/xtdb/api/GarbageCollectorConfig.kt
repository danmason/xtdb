@file:UseSerializers(IntWithEnvVarSerde::class, DurationSerde::class)
package xtdb.api
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import xtdb.DurationSerde
import java.time.Duration

@Serializable
data class GarbageCollectorConfig(
    var enabled: Boolean = true,
    var blocksToKeep: Int = 10,
    var approxRunInterval: Duration = Duration.ofMinutes(10),
) {
    fun enabled(enabled: Boolean) = apply { this.enabled = enabled }
    fun blocksToKeep(blocksToKeep: Int) = apply { this.blocksToKeep = blocksToKeep }
    fun approxRunInterval(approxRunInterval: Duration) = apply { this.approxRunInterval = approxRunInterval }
}