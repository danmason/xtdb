package xtdb.api.metrics

import kotlinx.serialization.Serializable

@Serializable
data class OtelConfig(
    var enabled: Boolean = false,
    var endpoint: String = "http://localhost:4317",
    var serviceName: String = "xtdb"
) {
    fun enabled(enabled: Boolean) = apply { this.enabled = enabled }
    fun endpoint(endpoint: String) = apply { this.endpoint = endpoint }
    fun serviceName(serviceName: String) = apply { this.serviceName = serviceName }
}
