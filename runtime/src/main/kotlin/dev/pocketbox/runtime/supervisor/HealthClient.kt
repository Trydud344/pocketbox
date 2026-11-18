package dev.pocketbox.runtime.supervisor

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Health-check client for the OpenCode server.
 *
 * V1 (`opencode serve` from the v1.x release track): GET /global/health
 *     -> {"healthy":true,"version":"1.19.6"}
 * V2: GET /api/info (auth required) -> {"version":"2.3.0",...}
 *     (the plain /global/health path is claimed by the web SPA in v2)
 *
 * We accept either: health is considered OK when a known-good endpoint
 * answers 200 with valid JSON. Protocol detection records which one works.
 */
class HealthClient(private val username: String, private val password: String) {

    enum class Protocol { V1, V2 }

    data class Health(val healthy: Boolean, val version: String?, val protocol: Protocol?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val detected = AtomicReference<Protocol?>(null)

    fun check(host: String, port: Int): Health {
        val auth = Credentials.basic(username, password)
        detected.get()?.let { return checkWith(it, host, port, auth) }
        // Probe in order: v2 first (/api/info), then v1 (/global/health).
        for (p in listOf(Protocol.V2, Protocol.V1)) {
            val h = checkWith(p, host, port, auth)
            if (h.healthy) {
                detected.set(p)
                return h
            }
        }
        return Health(false, null, null)
    }

    private fun checkWith(p: Protocol, host: String, port: Int, auth: String): Health {
        val path = when (p) {
            Protocol.V1 -> "/global/health"
            Protocol.V2 -> "/api/info"
        }
        val req = Request.Builder()
            .url("http://$host:$port$path")
            .header("Authorization", auth)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Health(false, null, null)
                val body = resp.body?.string() ?: return Health(false, null, null)
                parseHealth(body, p)
            }
        } catch (_: Exception) {
            Health(false, null, null)
        }
    }

    private fun parseHealth(body: String, protocol: Protocol? = null): Health {
        return try {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(body)
            val obj = json as kotlinx.serialization.json.JsonObject
            val version = (obj["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val healthy = (obj["healthy"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrict()
                ?: true // /api/info has no "healthy" flag; a 200 means healthy
            Health(healthy, version, protocol)
        } catch (_: Exception) {
            Health(false, null, null)
        }
    }
}
