import kotlinx.serialization.Serializable

@Serializable
data class Settings(val host: String, val port: Int, val user: String, val password: String,
    val domain: String, val mqttHost: String, val mqttPort: Int, val mqttTopic: String, val subDomains: List<String>)
