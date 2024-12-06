package nl.uiterlix.dnsupdater

import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern

class DNSUpdater(
    private val settings: Settings
) {
    private var httpClient = HttpClient.newHttpClient()

    fun checkAndUpdateDNSEntries() {
        // read ip from file
        var storedIp: String? = null
        val ipFile = File("external_ip.txt")
        val externalIP = externalIp
        println("Current external IP address: $externalIP")

        // ip changed, replace
        if (ipFile.exists()) {
            storedIp = try {
                Files.readString(ipFile.toPath())
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
        if (externalIP == storedIp && isValidIP(externalIP)) {
            println("IP address has not changed. Exiting.")
            return
        }

        notifyViaMqtt(storedIp ?: "", externalIP)

        // store the ip
        try {
            ipFile.delete()
            Files.writeString(ipFile.toPath(), externalIP)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        val currentDNSIPs = currentARecordIp
        for (subdomain in settings.subDomains) {
            val currentDNSIP = currentDNSIPs[subdomain]
                ?: throw IllegalArgumentException("Could not find current ip for subdomain: $subdomain")
            System.out.printf("Current IP of %s in DNS: %s%n", subdomain, currentDNSIP)
            if (currentDNSIP != externalIP) {
                println("Deleting current A record")
                deleteRecord(subdomain)
                println("Recreating A record")
                addRecord(subdomain, externalIP)
            } else {
                println("IP is already up to date.")
            }
        }
    }

    private fun isValidIP(ip: String): Boolean {
        return IP_PATTERN.matcher(ip).matches()
    }

    fun deleteRecord(name: String?) {
        val parameters = java.util.Map.of(
            "domain", settings.domain,
            "arecs0", "name=${name}",
            "delete", "Delete Selected",
            "action", "select"
        )
        val request = buildHttpPOSTRequest(parameters)
        executeRequest(request)
    }

    fun addRecord(name: String, ip: String) {
        val parameters = java.util.Map.of(
            "domain", settings.domain,
            "action", "add",
            "type", "A",
            "name", name,
            "value", ip,
            "add", "Add"
        )
        val request = buildHttpPOSTRequest(parameters)
        executeRequest(request)
    }

    private val currentRecords: String
        get() {
            val request = buildHttpGETRequest()
            val response = executeRequest(request)
            return response.body()
        }

    private val currentARecordIp: Map<String, String>
        get() {
            val result: MutableMap<String, String> = HashMap()
            val pattern = Pattern.compile("([a-z]*)\\t600\\tIN\\tA\\t(\\d+.\\d+.\\d+.\\d+)")
            val lines = currentRecords.split("\n".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()
            for (line in lines) {
                val matcher = pattern.matcher(line)
                if (matcher.matches()) {
                    val name = matcher.group(1)
                    val curIp = matcher.group(2)
                    result[name] = curIp
                }
            }
            return result
        }

    private fun executeRequest(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw RuntimeException(e)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }

    private fun buildHttpPOSTRequest(parameters: Map<String, String>): HttpRequest {
        return buildHttpRequest()
            .POST(HttpRequest.BodyPublishers.ofString(mapToURLEncodedString(parameters)))
            .build()
    }

    private fun buildHttpGETRequest(): HttpRequest {
        return buildHttpRequest()
            .GET()
            .build()
    }

    private fun buildHttpRequest(): HttpRequest.Builder {
        val token =
            String(Base64.getEncoder().encode("${settings.user}:${settings.password}".toByteArray()))
        val urlString = "https://${settings.host}:${settings.port}/CMD_API_DNS_CONTROL?domain=${settings.domain}"
        return HttpRequest.newBuilder(URI.create(urlString))
            .header("Authorization", "Basic ${token}")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "*/*")
    }

    private val externalIp: String
        get() {
            val request = HttpRequest.newBuilder(URI.create("https://checkip.amazonaws.com/"))
                .GET()
                .build()
            return try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                response.body().trim { it <= ' ' }
            } catch (e: IOException) {
                throw RuntimeException(e)
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
        }

    private fun mapToURLEncodedString(parameters: Map<String, String>): String {
        return parameters.entries.joinToString("&") { (key, value) ->
            "${key}=${URLEncoder.encode(value, Charsets.UTF_8.toString())}"
        }
    }

    private fun notifyViaMqtt(oldIp: String, newIp: String) {
        val broker = "tcp://${settings.mqttHost}:${settings.mqttPort}"
        val clientId = MqttClient.generateClientId()
        val topic = settings.mqttTopic
        val content = "IP address changed from $oldIp to $newIp."

        try {
            val client = MqttClient(broker, clientId, MemoryPersistence())
            client.connect()
            val message = MqttMessage(content.toByteArray())
            message.qos = 2
            client.publish(topic, message)
            client.disconnect()
            println("Message sent to MQTT topic $topic: $content")
        } catch (e: Exception) {
            println("Error sending MQTT message: ${e.message}")
            throw RuntimeException(e)
        }
    }

    companion object {
        private val IP_PATTERN =
            Pattern.compile("^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$")

        @JvmStatic
        fun main(args: Array<String>) {
            if (args.isEmpty()) {
                println("Please provide the absolute file location for settings.json as an argument.")
                return
            }

            val filePath = args[0]
            val jsonString = Files.readString(Paths.get(filePath))
            val settings = Json.decodeFromString<Settings>(jsonString)
            println(settings)
            val updater = DNSUpdater(settings)
            updater.checkAndUpdateDNSEntries()
        }
    }
}