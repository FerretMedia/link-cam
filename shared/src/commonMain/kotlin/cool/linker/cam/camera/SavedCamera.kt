package cool.linker.cam.camera

import kotlinx.serialization.Serializable

/**
 * A camera Link Cam has connected to, kept so it reconnects without pairing
 * again. Pairing is once per camera; a phone can know several.
 */
@Serializable
data class SavedCamera(
    /** Stable across pairings: the serial number where the camera gives one. See [CameraIdentity.id]. */
    val id: String,
    val model: String,
    /** The name the camera's owner gave it, which it shows in its Wi-Fi name. */
    val nickname: String = "",
    /** The camera's own Wi-Fi, if it makes one; null when it joins the same Wi-Fi as the phone. */
    val wifi: CameraWifi? = null,
    /** [CameraEndpoint.address] where it answered last. */
    val address: String? = null,
    /** When it last connected, in milliseconds since the epoch. */
    val lastConnected: Long = 0,
) {
    val name: String
        get() {
            // Bodies prefix the owner's name with their model, as in "EOSR10_A-cam"; the model's shown already.
            val compactModel = model.removePrefix("Canon ").replace(" ", "")
            val own = nickname.removePrefix("${compactModel}_").removePrefix(compactModel).trim('_', ' ')
            return if (own.isBlank() || own in model) model else "$model “$own”"
        }

    val endpoint: CameraEndpoint? get() = address?.let(CameraEndpoint::saved)

    /**
     * Whether Link Cam can reconnect with nobody looking. Android asks the
     * user before an app joins a Wi-Fi, but only once for a network asked for
     * by its exact name: so a camera whose Wi-Fi name is known rejoins
     * silently, as does one on the phone's own Wi-Fi, and one known only by
     * password needs the user each time.
     */
    val reconnectsQuietly: Boolean get() = wifi == null || wifi.ssid != null
}

/** A camera's own access point. The camera makes a new one each time it pairs, and keeps it for that pairing. */
@Serializable
data class CameraWifi(val ssid: String? = null, val password: String)

/** Who a camera says it is once connected. */
data class CameraIdentity(val model: String, val serial: String = "", val nickname: String = "") {
    /**
     * The serial number if the camera gives one; otherwise model and
     * nickname, which tell a phone's cameras apart unless two of the same
     * model share a name.
     */
    val id: String get() = if (serial.isNotBlank()) "serial:$serial" else "name:$model/$nickname"
}

/** The cameras a phone knows, and the rules for keeping that list. Pure, so it can be tested. */
object CameraBook {
    /** Most recently used first: the order to offer them, and to try them in when reconnecting. */
    fun ordered(cameras: List<SavedCamera>): List<SavedCamera> = cameras.sortedByDescending { it.lastConnected }

    /** The cameras to try with nobody looking, in the order to try them. */
    fun quietOrder(cameras: List<SavedCamera>): List<SavedCamera> = ordered(cameras).filter { it.reconnectsQuietly }

    /**
     * Records a connection: the camera's entry, created or brought up to
     * date, and the list with it. The same camera paired again updates its
     * entry rather than adding another; so does one that's moved address.
     * [wifi] is the Wi-Fi it was reached on, or null to keep what was known.
     */
    fun connected(
        cameras: List<SavedCamera>,
        identity: CameraIdentity,
        wifi: CameraWifi?,
        address: String,
        now: Long,
    ): Pair<List<SavedCamera>, SavedCamera> {
        val existing = cameras.firstOrNull { it.id == identity.id }
            ?: wifi?.ssid?.let { ssid -> cameras.firstOrNull { it.wifi?.ssid == ssid } }
        val camera = SavedCamera(
            id = identity.id,
            model = identity.model.ifBlank { existing?.model ?: "Canon camera" },
            nickname = identity.nickname.ifBlank { existing?.nickname.orEmpty() },
            wifi = wifi?.let { new -> if (new.ssid == null && existing?.wifi?.password == new.password) existing.wifi else new }
                ?: existing?.wifi,
            address = address,
            lastConnected = now,
        )
        return cameras.filter { it.id != identity.id && it.id != existing?.id } + camera to camera
    }

    fun forget(cameras: List<SavedCamera>, id: String): List<SavedCamera> = cameras.filter { it.id != id }

    /** The camera whose Wi-Fi this is, as when a saved camera shows its Wi-Fi details again. */
    fun withWifi(cameras: List<SavedCamera>, wifi: CameraWifi): SavedCamera? =
        cameras.firstOrNull { it.wifi?.password == wifi.password && (wifi.ssid == null || it.wifi?.ssid == wifi.ssid) }
}
