package cool.linker.cam.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The parts of Canon's Camera Control API (CCAPI) that Link Cam reads: the API
 * index, the shooting settings and the change events.
 *
 * CCAPI is plain HTTP and JSON. A camera lists what it supports at `/ccapi`,
 * grouped by version (`ver100`, `ver110`, ...), and the same API can appear
 * under several versions. Bodies differ in which versions they carry, so
 * nothing here assumes one: [Index] picks the newest version of each API the
 * camera offers.
 *
 * Settings come back as `{"value": "...", "ability": [...]}`, singly or keyed
 * by name (`iso`, `av`, `tv`, `exposure`) in the all-settings response and in
 * change events, so one parser reads all three.
 */
object Ccapi {
    const val DEFAULT_PORT = 8080

    const val SETTINGS = "shooting/settings"
    const val POLLING = "event/polling"
    const val DEVICE_INFO = "deviceinformation"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The base URL for what someone typed: the camera shows
     * `http://192.168.1.2:8080/ccapi`, but "192.168.1.2" alone should work.
     */
    fun baseUrl(typed: String): String? {
        var s = typed.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val afterScheme = s.substringAfter("://")
        val host = afterScheme.substringBefore('/')
        if (host.isEmpty()) return null
        val withPort = if (host.contains(':')) host else "$host:$DEFAULT_PORT"
        return s.substringBefore("://") + "://" + withPort + "/ccapi"
    }

    /** The address a camera on [host] answers on, for when there's only a host to go on. */
    fun baseUrlFor(host: String): String = "http://$host:$DEFAULT_PORT/ccapi"

    /** Whether [body] is a CCAPI listing, which is how a probe tells a camera from anything else on port 8080. */
    fun isIndex(body: String): Boolean {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return false
        return root.keys.any { it.startsWith("ver") && it.removePrefix("ver").toIntOrNull() != null }
    }

    /**
     * The search target Canon cameras answer to over SSDP when their Camera
     * Control API is on.
     */
    const val SSDP_TARGET = "urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1"

    /** The host in an SSDP reply's LOCATION header, which is where the camera is. */
    fun ssdpHost(reply: String): String? {
        val location = reply.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')?.trim() ?: return null
        return location.substringAfter("://", "").substringBefore('/').substringBefore(':').ifEmpty { null }
    }

    /** One API the camera offers, at the newest version it offers it in. */
    data class Api(val url: String, val version: Int, val get: Boolean)

    class Index(val apis: Map<String, Api>) {
        operator fun get(name: String): Api? = apis[name]

        companion object {
            /**
             * Reads the `/ccapi` listing. [base] is used for entries given as
             * paths rather than full URLs.
             */
            fun parse(body: String, base: String): Index {
                val root = json.parseToJsonElement(body) as? JsonObject ?: return Index(emptyMap())
                val origin = base.substringBefore("/ccapi")
                val apis = mutableMapOf<String, Api>()
                for ((key, entries) in root) {
                    val version = key.removePrefix("ver").toIntOrNull() ?: continue
                    for (entry in entries as? JsonArray ?: continue) {
                        val e = entry as? JsonObject ?: continue
                        val path = (e["path"] as? JsonPrimitive)?.contentOrNull ?: continue
                        val name = path.substringAfter("/ccapi/$key/", missingDelimiterValue = "")
                        if (name.isEmpty()) continue
                        val url = if (path.startsWith("http")) path else origin + path
                        val get = (e["get"] as? JsonPrimitive)?.booleanOrNull ?: true
                        val known = apis[name]
                        if (known == null || version > known.version) apis[name] = Api(url, version, get)
                    }
                }
                return Index(apis)
            }
        }
    }

    /**
     * The query that makes a polling request wait for the next change. ver100
     * asks with `continue=on`; ver110 and later replaced it with `timeout`.
     */
    fun pollingQuery(version: Int): String = if (version >= 110) "?timeout=long" else "?continue=on"

    /**
     * Applies a settings response or a change event to [current]. Keys that
     * aren't in [body] keep their value; a key present with no value means the
     * camera has no such setting right now, as when the lens can't report an
     * aperture.
     */
    fun apply(body: String, current: CameraSettings): CameraSettings {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return current
        return current.copy(
            iso = pick(root, "iso", current.iso),
            aperture = pick(root, "av", current.aperture),
            shutter = pick(root, "tv", current.shutter),
            exposureComp = pick(root, "exposure", current.exposureComp),
        )
    }

    /** A single setting's response, as from `shooting/settings/iso`. */
    fun value(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return valueOf(root)
    }

    /** The product name from `deviceinformation`, such as "Canon EOS R10". */
    fun productName(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return (root["productname"] as? JsonPrimitive)?.contentOrNull
    }

    private fun pick(root: JsonObject, key: String, current: String?): String? {
        if (key !in root) return current
        val entry = root[key]
        return if (entry is JsonObject) valueOf(entry) else null
    }

    private fun valueOf(o: JsonObject): String? {
        val v: JsonElement = o["value"] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
