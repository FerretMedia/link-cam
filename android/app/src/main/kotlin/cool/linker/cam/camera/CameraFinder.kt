package cool.linker.cam.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.resume

/**
 * The phone's Wi-Fi, as far as talking to a camera goes: which network to send
 * on, and where on it a camera might be.
 *
 * A camera's own access point has no internet, and Android then sends an app's
 * traffic over mobile data unless the app picks the network itself, so every
 * request to a camera goes through [open] or [socket].
 *
 * [joinCameraWifi] puts the phone on the camera's own Wi-Fi for Link Cam
 * alone: Android asks the user to confirm, and the rest of the phone keeps its
 * internet over mobile data. Releasing it puts the phone back on its usual
 * Wi-Fi.
 */
class CameraNetwork(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var request: ConnectivityManager.NetworkCallback? = null

    private val held = MutableStateFlow<Network?>(null)

    /** The camera's Wi-Fi while Link Cam holds it, and null once it's gone: the camera's off or out of range. */
    val cameraWifiState: StateFlow<Network?> = held.asStateFlow()
    val cameraWifi: Network? get() = held.value

    fun wifi(): List<Network> {
        @Suppress("DEPRECATION") // allNetworks is the simple way to look once; a callback would track changes we don't need.
        val all = connectivity.allNetworks.filter {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val camera = cameraWifi ?: return all
        return listOf(camera) + all.filter { it != camera }
    }

    /**
     * Asks Android for a camera's own Wi-Fi. By its exact name when that's
     * known: Android asks the user once per name and joins silently after
     * that. Without the name, any network with "Canon" in it, which is how
     * Canon names a camera's access point, and Android asks every time.
     *
     * Null if the user declined, the password was wrong or the camera's
     * Wi-Fi wasn't in range within [timeoutMs].
     */
    suspend fun joinCameraWifi(wifi: CameraWifi, timeoutMs: Int = JOIN_TIMEOUT_MS): Network? {
        releaseCameraWifi()
        val specifier = try {
            WifiNetworkSpecifier.Builder()
                .apply {
                    val ssid = wifi.ssid
                    if (ssid != null) setSsid(ssid)
                    else setSsidPattern(PatternMatcher(CAMERA_SSID_PATTERN, PatternMatcher.PATTERN_SIMPLE_GLOB))
                }
                .setWpa2Passphrase(wifi.password)
                .build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "camera Wi-Fi details not usable", e)
            return null
        }
        val wanted = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        return suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    held.value = network
                    if (cont.isActive) cont.resume(network)
                }

                override fun onUnavailable() {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onLost(network: Network) {
                    held.compareAndSet(network, null)
                }
            }
            request = callback
            connectivity.requestNetwork(wanted, callback, timeoutMs)
            cont.invokeOnCancellation { if (cameraWifi == null) releaseCameraWifi() }
        }
    }

    /** Lets go of the camera's Wi-Fi, so the phone goes back to its usual network. */
    fun releaseCameraWifi() {
        request?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        request = null
        held.value = null
    }

    /**
     * The Wi-Fi network with a route to [host], preferring one whose own
     * subnet holds it over one that would only reach it through a gateway.
     */
    fun networkFor(host: String): Network? {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        cameraWifi?.let { camera -> if (routes(camera).any { !it.isDefaultRoute && it.matches(address) }) return camera }
        val wifi = wifi()
        return wifi.firstOrNull { n -> routes(n).any { !it.isDefaultRoute && it.matches(address) } }
            ?: wifi.firstOrNull()
    }

    fun open(url: String): HttpURLConnection {
        val u = URL(url)
        return (networkFor(u.host)?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
    }

    /** A TCP socket to [host] on the Wi-Fi that reaches it. */
    fun socket(host: String, port: Int, connectTimeoutMs: Int): Socket {
        val socket = networkFor(host)?.socketFactory?.createSocket() ?: Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    /** The gateway of [network]. On a camera's own access point, that's the camera. */
    fun gateway(network: Network): String? =
        routes(network).firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress

    /**
     * Every other address on [network]'s subnet, the phone's own /24 first. A
     * subnet wider than /22 is cut to the /22 around the phone, so a sweep
     * stays a few seconds long.
     */
    fun neighbours(network: Network): List<String> {
        val link = connectivity.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address } ?: return emptyList()
        val own = link.address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val prefix = link.prefixLength.coerceAtLeast(22)
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val first = (own and mask) + 1
        val last = (own and mask) + (mask.inv() and 0xFFFFFFFFL) - 1
        fun dotted(v: Long) = "${v shr 24 and 0xFF}.${v shr 16 and 0xFF}.${v shr 8 and 0xFF}.${v and 0xFF}"
        val all = (first..last).filter { it != own }
        val (near, far) = all.partition { it shr 8 == own shr 8 }
        return (near + far).map(::dotted)
    }

    private fun routes(n: Network) = connectivity.getLinkProperties(n)?.routes.orEmpty()

    private companion object {
        const val TAG = "LinkCamCamera"

        /** Canon's access points are named like "EOSR10_Name_Canon0A". */
        const val CAMERA_SSID_PATTERN = ".*Canon.*"
        /** Long enough to find the camera in Android's list and tap it. */
        const val JOIN_TIMEOUT_MS = 30_000
    }
}

/**
 * Finds a Canon camera on the phone's Wi-Fi, so nobody has to type an address.
 *
 * Cheapest first: the camera used last time, then the Wi-Fi's gateway (the
 * camera itself when the phone is on the camera's access point), then an SSDP
 * search, then every address on the phone's subnet. A host counts if it takes
 * a PTP/IP connection, which is the camera's "Remote control (EOS Utility)"
 * mode, or if `/ccapi` answers with a Camera Control API listing.
 */
class CameraFinder(private val network: CameraNetwork) {

    /** Searches [only] if given, as when the phone has just joined the camera's Wi-Fi, otherwise all Wi-Fi. */
    suspend fun find(last: CameraEndpoint?, only: Network? = null): CameraEndpoint? = withContext(Dispatchers.IO) {
        last?.let { if (answers(it, PROBE_CONNECT_MS * 3)) return@withContext it }
        val tried = mutableSetOf<String>()
        suspend fun first(hosts: List<String>, parallel: Int = 1): CameraEndpoint? =
            firstAnswering(hosts.filter { tried.add(it) }, parallel)

        for (wifi in only?.let(::listOf) ?: network.wifi()) {
            network.gateway(wifi)?.let { gw -> first(listOf(gw))?.let { return@withContext it } }
            first(ssdp(wifi))?.let { return@withContext it }
            first(network.neighbours(wifi), parallel = SWEEP_PARALLEL)?.let { return@withContext it }
        }
        null
    }

    /** What's at [host], if it's a camera: EOS remote control first, as it needs no activation. */
    fun identify(host: String, connectMs: Int = PROBE_CONNECT_MS * 3): CameraEndpoint? {
        val eos = CameraEndpoint.Eos(host)
        if (answers(eos, connectMs)) return eos
        val ccapi = CameraEndpoint.ControlApi(Ccapi.baseUrlFor(host))
        return ccapi.takeIf { answers(it, connectMs) }
    }

    private fun answers(endpoint: CameraEndpoint, connectMs: Int): Boolean = when (endpoint) {
        is CameraEndpoint.Eos -> try {
            network.socket(endpoint.host, EosPtp.PORT, connectMs).close()
            true
        } catch (_: IOException) {
            false
        }
        is CameraEndpoint.ControlApi -> probe(endpoint.base, connectMs)
    }

    private suspend fun firstAnswering(hosts: List<String>, parallel: Int): CameraEndpoint? {
        if (hosts.isEmpty()) return null
        return coroutineScope {
            val found = CompletableDeferred<CameraEndpoint?>()
            val gate = Semaphore(parallel)
            val probes = hosts.map { host ->
                launch {
                    gate.withPermit {
                        if (found.isCompleted) return@withPermit
                        identify(host, PROBE_CONNECT_MS)?.let { found.complete(it) }
                    }
                }
            }
            launch {
                probes.joinAll()
                found.complete(null)
            }
            found.await().also { coroutineContext.cancelChildren() }
        }
    }

    private fun probe(base: String, connectMs: Int): Boolean = try {
        val c = network.open(base)
        try {
            c.connectTimeout = connectMs
            c.readTimeout = PROBE_READ_MS
            c.responseCode in 200..299 && Ccapi.isIndex(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    } catch (_: IOException) {
        false
    }

    /** Hosts that answered an SSDP search for CCAPI. Many cameras don't answer; that's fine. */
    private fun ssdp(wifi: Network): List<String> {
        val hosts = linkedSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                wifi.bindSocket(socket)
                socket.soTimeout = 300
                val group = InetAddress.getByName("239.255.255.250")
                val search = (
                    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                        "MX: 1\r\nST: ${Ccapi.SSDP_TARGET}\r\n\r\n"
                    ).toByteArray()
                repeat(2) { socket.send(DatagramPacket(search, search.size, group, 1900)) }
                val buffer = ByteArray(2048)
                val deadline = System.nanoTime() + SSDP_WAIT_MS * 1_000_000L
                while (System.nanoTime() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    Ccapi.ssdpHost(String(packet.data, 0, packet.length))?.let(hosts::add)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "SSDP search failed", e)
        }
        return hosts.toList()
    }

    private companion object {
        const val TAG = "LinkCamCamera"
        const val PROBE_CONNECT_MS = 700
        const val PROBE_READ_MS = 2_000
        const val SSDP_WAIT_MS = 1_500L
        const val SWEEP_PARALLEL = 32
    }
}
