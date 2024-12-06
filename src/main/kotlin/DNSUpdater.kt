import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern
import java.util.stream.Collectors

class DNSUpdater(
    private val settings: Settings
) {
    var httpClient = HttpClient.newHttpClient()

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

        notifyIPAddressChange(storedIp ?: "", externalIP)

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
            "arecs0", "name=%s".formatted(name),
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
        private get() {
            val request = buildHttpGETRequest()
            val response = executeRequest(request)
            return response.body()
        }

    val currentARecordIp: Map<String, String>
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
        val response: HttpResponse<String>? = null
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
            String(Base64.getEncoder().encode("%s:%s".formatted(settings.user, settings.password).toByteArray()))
        val urlString = "https://%s:%d/CMD_API_DNS_CONTROL?domain=%s"
            .formatted(settings.host, settings.port, settings.domain)
        return HttpRequest.newBuilder(URI.create(urlString))
            .header("Authorization", "Basic %s".formatted(token))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "*/*")
    }

    private val externalIp: String
        private get() {
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
        return parameters.entries.stream()
            .map { (key, value): Map.Entry<String, String> ->
                key + "=" + URLEncoder.encode(
                    value
                )
            }
            .collect(Collectors.joining("&"))
    }

    private fun notifyIPAddressChange(oldIp: String, newIp: String) {
        val url = "http://192.168.2.91:6335/telegram_urgent"
        val requestBody = "IP address changed from $oldIp to $newIp."
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "text/plain")
            .POST(BodyPublishers.ofString(requestBody))
            .version(HttpClient.Version.HTTP_1_1)
            .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            println("Response from telegram_urgent: ${response.body()}")
        } catch (e: IOException) {
            throw RuntimeException(e)
        } catch (e: InterruptedException) {
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