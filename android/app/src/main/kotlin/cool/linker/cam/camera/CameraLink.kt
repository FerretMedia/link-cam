package cool.linker.cam.camera

import android.content.Context
import android.net.Network
import android.util.Log
import cool.linker.cam.services.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException

/** The steps of getting to a camera, in order, for the setup screen to show where things stand. */
enum class LinkStep { WIFI, FIND, HELLO, LIVE }

/**
 * Where the link to a camera stands. [camera] is the id of the saved camera
 * concerned, or null while pairing one Link Cam doesn't know yet.
 */
sealed interface CameraLinkState {
    val camera: String? get() = null

    /** Not connected and not trying: nothing is paired, or the user disconnected. */
    data object Off : CameraLinkState

    /** Paired cameras, none of them around. Link Cam keeps looking every few seconds. */
    data class Waiting(val hint: String) : CameraLinkState

    /** [background] while going through the saved cameras with nobody waiting on it, which the monitor keeps quiet about. */
    data class Searching(
        val hint: String,
        val step: LinkStep,
        override val camera: String? = null,
        val background: Boolean = false,
    ) : CameraLinkState

    /** [hint] says what the camera is waiting for, such as a press of Cancel on its screen. */
    data class Connecting(
        val hint: String? = null,
        val step: LinkStep = LinkStep.HELLO,
        override val camera: String? = null,
    ) : CameraLinkState

    data class Connected(val name: String, val settings: CameraSettings, override val camera: String) : CameraLinkState

    /** Lost the camera; keeps the last settings it reported and keeps trying. */
    data class Reconnecting(
        val name: String,
        val settings: CameraSettings,
        val reason: String,
        override val camera: String,
    ) : CameraLinkState

    /** Didn't get through, stuck at [step]. [retrying] while it keeps trying on its own. */
    data class Failed(
        val reason: String,
        val retrying: Boolean = true,
        val step: LinkStep = LinkStep.HELLO,
        override val camera: String? = null,
    ) : CameraLinkState
}

/** The settings the camera last reported, while it's connected or being reconnected to. */
val CameraLinkState.cameraSettings: CameraSettings?
    get() = when (this) {
        is CameraLinkState.Connected -> settings
        is CameraLinkState.Reconnecting -> settings
        else -> null
    }

/** Pairing a camera Link Cam doesn't know yet, rather than reaching a saved one. */
val CameraLinkState.pairing: Boolean
    get() = camera == null && (this is CameraLinkState.Searching || this is CameraLinkState.Connecting || this is CameraLinkState.Failed)

/**
 * Whether the link is free to start pairing from Wi-Fi details seen on a
 * camera's screen: nothing under way that the user is waiting on.
 */
val CameraLinkState.open: Boolean
    get() = when (this) {
        CameraLinkState.Off, is CameraLinkState.Waiting -> true
        is CameraLinkState.Searching -> background
        is CameraLinkState.Failed -> !retrying
        else -> false
    }

/**
 * The link to Canon camera bodies over Wi-Fi: reads ISO, aperture, shutter
 * speed and exposure compensation and follows them as they change. HDMI
 * carries only the picture, so this is the only way to get them.
 *
 * A camera is paired once: Link Cam joins the Wi-Fi the camera shows, finds
 * the camera on it and, once connected, keeps it in [cameras]. After that it
 * reconnects by itself whenever the app is in front and the user hasn't
 * disconnected, trying the saved cameras newest first. Android joins a
 * camera's Wi-Fi without asking once it knows the network's name, so that
 * takes no one's attention.
 *
 * Two of Canon's protocols reach a body. "Remote control (EOS Utility)" mode
 * speaks PTP/IP, needs nothing but the camera's OK the first time, and is
 * [EosSession]. Camera Control API needs Canon to activate it first, and is
 * kept for bodies set up that way.
 *
 * One attempt runs at a time, as an [Attempt]; starting another stops it. A
 * stopped attempt can still be unwinding on its thread, so it only writes
 * [state] while it's the current one.
 */
class CameraLink(context: Context, private val store: SettingsStore) {
    private val network = CameraNetwork(context)
    private val finder = CameraFinder(network)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var current: Attempt? = null

    /** The password last paired from the camera's screen: each is tried once, so a misreading can't loop. */
    @Volatile private var triedFromScreen: String? = null

    /** Whether the app is on screen; out of it, the link stops looking for cameras that aren't around. */
    @Volatile private var foreground = false

    private val _state = MutableStateFlow<CameraLinkState>(CameraLinkState.Off)
    val state: StateFlow<CameraLinkState> = _state.asStateFlow()

    private val _cameras = MutableStateFlow(CameraBook.ordered(store.loadCameras()))

    /** The paired cameras, most recently used first. */
    val cameras: StateFlow<List<SavedCamera>> = _cameras.asStateFlow()

    /** False once the user disconnects, until they connect again. */
    val autoConnect: Boolean get() = store.loadCameraAutoConnect()

    /** The app is in front: back to the cameras it knows, unless the user disconnected. */
    fun resume() {
        foreground = true
        if (job?.isActive != true && autoConnect && CameraBook.quietOrder(cameras.value).isNotEmpty()) {
            start { reconnect(only = null) }
        }
    }

    /**
     * The app went to the back: stops looking for cameras, which costs Wi-Fi
     * scans, but keeps a camera that's connected and anything the user
     * started.
     */
    fun pause() {
        foreground = false
        val now = _state.value
        if (now is CameraLinkState.Waiting || (now is CameraLinkState.Searching && now.background)) {
            stop()
            network.releaseCameraWifi()
            _state.value = CameraLinkState.Off
        }
    }

    /** Connects to a saved camera, asking the user to approve its Wi-Fi if Android needs that. */
    fun connect(camera: SavedCamera) {
        store.saveCameraAutoConnect(true)
        start { reconnect(only = camera) }
    }

    /** Pairs the camera whose Wi-Fi details these are, as typed or read off its screen. */
    fun pair(wifi: CameraWifi, fromScreen: Boolean = false) {
        store.saveCameraAutoConnect(true)
        start { pair(wifi, if (fromScreen) JOINING_FROM_SCREEN else JOINING) }
    }

    /** Pairs a camera on the Wi-Fi the phone is on already. */
    fun pairOnThisWifi() {
        store.saveCameraAutoConnect(true)
        start { pair(wifi = null, joining = JOINING) }
    }

    /** Stops a pairing under way and goes back to the saved cameras, if the user hasn't disconnected them. */
    fun cancelPairing() {
        if (!_state.value.pairing) return
        stop()
        network.releaseCameraWifi()
        _state.value = CameraLinkState.Off
        resume()
    }

    /** Connects to what someone typed: the camera's address alone is enough. */
    fun connectTo(typed: String) {
        val host = CameraEndpoint.typedHost(typed)
        if (host == null) {
            _state.value = CameraLinkState.Failed("Enter the address the camera shows.", retrying = false, step = LinkStep.FIND)
            return
        }
        store.saveCameraAutoConnect(true)
        start { connectTo(typed, host) }
    }

    /**
     * A camera's Wi-Fi details, read off its screen while it waits to pair.
     * Pairs with them when nothing else is under way, or reconnects if they're
     * a saved camera's. A camera the user disconnected stays off unless it
     * shows new details, which only a fresh pairing does.
     */
    fun offerFromScreen(seen: WifiScreen.Credentials) {
        val now = _state.value
        if (!now.open || seen.password == triedFromScreen) return
        val wifi = CameraWifi(seen.ssid, seen.password)
        val known = CameraBook.withWifi(cameras.value, wifi)
        if (known != null && (!autoConnect || known.id == now.camera)) return
        triedFromScreen = seen.password
        Log.i(TAG, "read ${seen.ssid} off the camera's screen")
        if (known != null) connect(known) else pair(wifi, fromScreen = true)
    }

    /**
     * Stops whatever the link is doing, lets go of the camera's Wi-Fi and
     * stays off, across restarts too, until the user connects again. The
     * cameras stay saved.
     */
    fun disconnect() {
        store.saveCameraAutoConnect(false)
        stop()
        network.releaseCameraWifi()
        _state.value = CameraLinkState.Off
    }

    /** Forgets [camera]; disconnects first if it's the one in hand. */
    fun forget(camera: SavedCamera) {
        if (_state.value.camera == camera.id) {
            stop()
            network.releaseCameraWifi()
            _state.value = CameraLinkState.Off
        }
        _cameras.update { CameraBook.forget(it, camera.id) }
        store.saveCameras(_cameras.value)
    }

    private fun start(block: suspend Attempt.() -> Unit) {
        stop()
        val attempt = Attempt()
        current = attempt
        job = scope.launch { attempt.block() }
    }

    private fun stop() {
        val attempt = current
        current = null
        job?.cancel()
        job = null
        // Unblocks a read in progress; the cancelled attempt then ends quietly.
        attempt?.close()
    }

    /** Saves a camera as it connects, and returns its entry. */
    private fun remember(identity: CameraIdentity, wifi: CameraWifi?, endpoint: CameraEndpoint): SavedCamera {
        lateinit var saved: SavedCamera
        _cameras.update { list ->
            val (next, camera) = CameraBook.connected(list, identity, wifi, endpoint.address, System.currentTimeMillis())
            saved = camera
            CameraBook.ordered(next)
        }
        store.saveCameras(_cameras.value)
        return saved
    }

    /** How a run of the link ended. */
    private enum class Outcome {
        /** Nothing answered there. */
        UNREACHED,

        /** Something answered but the link didn't come up; [state] says why. */
        FAILED,

        /** The camera was connected, then went away. */
        LOST,
    }

    /** One go at the link, from its start until [stop]. */
    private inner class Attempt {
        @Volatile private var inFlight: HttpURLConnection? = null
        @Volatile private var eos: EosSession? = null
        @Volatile private var closed = false

        private val isCurrent: Boolean get() = current === this

        private fun set(state: CameraLinkState) {
            if (isCurrent) _state.value = state
        }

        /**
         * Ends the attempt's connection. Off the calling thread, which is
         * usually the main one: closing a session tells the camera so over
         * the network.
         */
        fun close() {
            closed = true
            scope.launch {
                inFlight?.disconnect()
                eos?.close()
            }
        }

        /**
         * [camera]'s last state if it dropped and is being got back, so its
         * readout stays up, greyed, instead of vanishing until it's back.
         */
        private fun dropped(camera: String?): CameraLinkState.Reconnecting? =
            (_state.value as? CameraLinkState.Reconnecting)?.takeIf { camera != null && it.camera == camera }

        /**
         * Tries [only], or with nobody waiting every saved camera that
         * rejoins without asking, newest first. When one drops, starts over at
         * once; while none is around, looks again every few seconds, as long
         * as the app is on screen.
         *
         * [only] is tried once if it never answers, so the user hears it isn't
         * there; once it has connected, a drop is waited out like any other.
         */
        suspend fun reconnect(only: SavedCamera?) {
            var keepAt = only == null
            while (currentCoroutineContext().isActive) {
                val order = only?.let(::listOf) ?: CameraBook.quietOrder(cameras.value)
                if (order.isEmpty()) {
                    set(CameraLinkState.Off)
                    return
                }
                var dropped = false
                for (camera in order) {
                    if (reach(camera, quiet = keepAt) == Outcome.LOST) {
                        dropped = true
                        break
                    }
                }
                if (dropped) {
                    // A camera that asks the user each time it's joined isn't rejoined unasked.
                    if (only?.reconnectsQuietly != false) keepAt = true
                    continue
                }
                if (!keepAt) {
                    if (_state.value !is CameraLinkState.Failed) {
                        set(CameraLinkState.Failed(notAround(only!!.name), retrying = false, step = LinkStep.WIFI, camera = only.id))
                    }
                    return
                }
                network.releaseCameraWifi()
                if (!foreground) {
                    // Looking costs Wi-Fi scans; the app picks this up again when it's back on screen.
                    Log.i(TAG, "stopped looking for cameras with the app in the background")
                    set(CameraLinkState.Off)
                    return
                }
                set(CameraLinkState.Waiting(waiting(order)))
                delay(ROUND_MS)
            }
        }

        /** Joins [camera]'s Wi-Fi if it has one, then goes straight to where it was, searching only if it's moved. */
        private suspend fun reach(camera: SavedCamera, quiet: Boolean): Outcome {
            val keep = dropped(camera.id) != null
            fun progress(state: CameraLinkState) {
                if (!keep) set(state)
            }
            val wifi = camera.wifi
            val joined = if (wifi == null) {
                null
            } else {
                progress(CameraLinkState.Searching(joiningCamera(camera.name), LinkStep.WIFI, camera.id, background = quiet))
                network.joinCameraWifi(wifi, if (quiet) QUIET_JOIN_MS else JOIN_MS) ?: return Outcome.UNREACHED
            }
            progress(CameraLinkState.Searching(reaching(camera.name), LinkStep.FIND, camera.id, background = quiet))
            // No search and no probe first: a camera takes a moment to get over a probe.
            camera.endpoint?.let { saved ->
                val outcome = run(saved, camera, wifi, joined)
                if (outcome != Outcome.UNREACHED) return outcome
            }
            // On its own Wi-Fi a camera can come back at another address; elsewhere, a sweep is too slow to repeat.
            if (joined == null && quiet) return Outcome.UNREACHED
            val found = finder.find(last = null, only = joined) ?: return Outcome.UNREACHED
            delay(SETTLE_MS)
            return run(found, camera, wifi, joined)
        }

        /**
         * Joins [wifi] if given, finds the camera there and connects, saving
         * it once connected. A camera pairing for the first time holds the
         * hello, or drops the connection and restarts its Wi-Fi when the user
         * presses Cancel on it, and lets Link Cam in on a later try; so this
         * keeps rejoining and saying hello until the camera answers, the user
         * stops it, or [PAIRING_PATIENCE_MS] runs out. Rejoins don't ask the
         * user again: Android remembers a network approved by name.
         */
        suspend fun pair(wifi: CameraWifi?, joining: String) {
            val deadline = System.currentTimeMillis() + PAIRING_PATIENCE_MS
            fun patient() = System.currentTimeMillis() < deadline
            var held: Network? = null
            var joinedOnce = false
            var endpoint: CameraEndpoint? = null
            var firstHello = true
            while (currentCoroutineContext().isActive) {
                val joined = if (wifi == null) {
                    null
                } else if (held != null && network.cameraWifi == held) {
                    held
                } else {
                    set(CameraLinkState.Searching(if (joinedOnce) REJOINING else joining, LinkStep.WIFI))
                    held = network.joinCameraWifi(wifi, if (joinedOnce) QUIET_JOIN_MS else JOIN_MS)
                    if (held == null) {
                        // Declined, or a wrong password, the first time; later, the camera restarting its Wi-Fi.
                        if (!joinedOnce || !patient()) {
                            set(CameraLinkState.Failed(NO_WIFI, retrying = false, step = LinkStep.WIFI))
                            return
                        }
                        delay(SETTLE_MS)
                        continue
                    }
                    joinedOnce = true
                    held
                }
                if (endpoint == null) {
                    set(CameraLinkState.Searching(if (joined != null) SEARCHING_CAMERA_WIFI else SEARCHING, LinkStep.FIND))
                    endpoint = finder.find(last = null, only = joined)
                    if (endpoint == null) {
                        // A camera that's just started its Wi-Fi can take a moment to answer on it.
                        if (joined == null || !patient()) {
                            set(CameraLinkState.Failed(NOT_FOUND, retrying = false, step = LinkStep.FIND))
                            return
                        }
                        delay(SETTLE_MS)
                        continue
                    }
                    Log.i(TAG, "found a camera: ${endpoint.address}")
                    // Finding it opened and closed a connection, which a camera needs a moment to get over.
                    delay(SETTLE_MS)
                }
                when (run(endpoint, known = null, wifi, joined, shortFirstHello = firstHello)) {
                    // Paired, connected, then gone: from here it's a saved camera like any other.
                    Outcome.LOST -> return reconnect(only = null)
                    Outcome.FAILED -> return
                    Outcome.UNREACHED -> {
                        firstHello = false
                        if (!patient()) {
                            set(CameraLinkState.Failed(NO_HELLO, retrying = false))
                            return
                        }
                        delay(SETTLE_MS)
                    }
                }
            }
        }

        suspend fun connectTo(typed: String, host: String) {
            set(CameraLinkState.Connecting(step = LinkStep.FIND))
            val endpoint = if (CameraEndpoint.typedIsCcapi(typed)) {
                Ccapi.baseUrl(typed)?.let(CameraEndpoint::ControlApi)
            } else {
                finder.identify(host)?.also { delay(SETTLE_MS) }
            }
            val outcome = endpoint?.let { run(it, known = null, wifi = null, joined = null) }
            when (outcome) {
                Outcome.LOST -> reconnect(only = null)
                null, Outcome.UNREACHED ->
                    set(CameraLinkState.Failed("Nothing at $host answers as a Canon camera. $SETUP", retrying = false, step = LinkStep.FIND))
                Outcome.FAILED -> Unit
            }
        }

        /**
         * Runs the link to [endpoint] until it ends. [known] is the saved
         * camera when reconnecting, null when pairing; [joined] the camera's
         * Wi-Fi if Link Cam joined one, whose loss means the camera's gone.
         */
        private suspend fun run(
            endpoint: CameraEndpoint,
            known: SavedCamera?,
            wifi: CameraWifi?,
            joined: Network?,
            shortFirstHello: Boolean = false,
        ): Outcome = when (endpoint) {
            is CameraEndpoint.Eos -> runEos(endpoint, known, wifi, joined, shortFirstHello)
            is CameraEndpoint.ControlApi -> runCcapi(endpoint, known, wifi, joined)
        }

        /**
         * Remote control (EOS Utility) mode: keeps a session up, reconnecting
         * when it drops. Ends UNREACHED when the camera doesn't let Link Cam
         * in, for the caller to rejoin or move on, and LOST when a camera that
         * was connected goes away.
         */
        private suspend fun runEos(
            endpoint: CameraEndpoint.Eos,
            known: SavedCamera?,
            wifi: CameraWifi?,
            joined: Network?,
            shortFirstHello: Boolean,
        ): Outcome {
            var live: SavedCamera? = null
            // Back after a drop: the last settings stay up until the camera sends its own.
            val carried = dropped(known?.id)
            var settings = carried?.settings ?: CameraSettings()
            var failures = 0
            fun notYet(reason: String) =
                set(carried?.copy(reason = reason) ?: CameraLinkState.Failed(reason, retrying = true, camera = known?.id))
            // A camera meeting Link Cam for the first time holds its answer and shows "Start EOS
            // Utility on the computer", then lets the same phone in on a later try. So a first hello
            // gets a few seconds before trying again, and the next the time to walk to the camera.
            var firstHello = shortFirstHello
            while (currentCoroutineContext().isActive) {
                val session = EosSession(network, endpoint.host, store.clientGuid(), CLIENT_NAME)
                eos = session
                // Stopped between the loop's check and here: close() may have missed this session.
                if (closed) {
                    session.close()
                    break
                }
                try {
                    whileOn(joined, session) {
                        session.run(
                            onWaiting = { if (live == null && carried == null) set(CameraLinkState.Connecting(APPROVE, camera = known?.id)) },
                            onConnected = { identity ->
                                val camera = remember(identity, wifi, endpoint)
                                live = camera
                                failures = 0
                                set(CameraLinkState.Connected(camera.name, settings, camera.id))
                            },
                            onSettings = {
                                settings = it
                                live?.let { camera -> set(CameraLinkState.Connected(camera.name, settings, camera.id)) }
                            },
                            helloTimeoutMs = when {
                                // A camera that knows Link Cam answers at once; one that holds the hello is asking its user.
                                known != null -> KNOWN_HELLO_MS
                                firstHello -> FIRST_HELLO_MS
                                else -> EosSession.APPROVAL_TIMEOUT_MS
                            },
                        )
                    }
                } catch (e: EosSession.Unanswered) {
                    if (!currentCoroutineContext().isActive) break
                    if (firstHello) {
                        Log.i(TAG, "no answer to the first hello; trying again, as a camera meeting Link Cam lets it in then")
                        firstHello = false
                        delay(SETTLE_MS)
                        continue
                    }
                    if (live == null) {
                        Log.i(TAG, "the camera held the hello")
                        notYet(NO_HELLO)
                        return Outcome.UNREACHED
                    }
                } catch (e: EosSession.Refused) {
                    Log.w(TAG, "the camera refused Link Cam: ${e.message}")
                    set(CameraLinkState.Failed(describe(e), retrying = false, camera = known?.id))
                    return Outcome.FAILED
                } catch (e: IOException) {
                    if (!currentCoroutineContext().isActive) break
                    Log.w(TAG, "EOS link failed at ${session.stage}: ${e.message}")
                    // Nothing took the connection: the camera isn't at this address, or isn't on.
                    if (live == null && session.stage == EosSession.STAGE_CONNECTING) return Outcome.UNREACHED
                    val reason = describe(e)
                    val camera = live
                    if (camera != null) {
                        set(CameraLinkState.Reconnecting(camera.name, settings, reason, camera.id))
                    } else {
                        notYet(reason)
                        if (++failures >= HELLO_ATTEMPTS) return Outcome.UNREACHED
                    }
                } finally {
                    eos = null
                }
                // The camera's Wi-Fi went with it: the camera's off, out of range, or restarting its
                // Wi-Fi after a Cancel. Rejoining is the next step, and the caller's to take.
                if (joined != null && network.cameraWifi != joined) {
                    Log.i(TAG, "the camera's Wi-Fi went away")
                    return if (live != null) Outcome.LOST else Outcome.UNREACHED
                }
                delay(if (live != null) RETRY_MS else SETTLE_MS)
            }
            return Outcome.LOST
        }

        /**
         * Runs [block], a blocking session, and closes [session] the moment
         * [joined] goes away, so a wait on a camera that's gone ends at once
         * rather than when it times out.
         */
        private suspend fun whileOn(joined: Network?, session: EosSession, block: () -> Unit) = coroutineScope {
            val watch = joined?.let { wifi ->
                launch {
                    network.cameraWifiState.first { it != wifi }
                    session.close()
                }
            }
            try {
                block()
            } finally {
                watch?.cancel()
            }
        }

        /** Camera Control API: reads everything once, then follows change events. */
        private suspend fun runCcapi(endpoint: CameraEndpoint.ControlApi, known: SavedCamera?, wifi: CameraWifi?, joined: Network?): Outcome {
            val base = endpoint.base
            var live: SavedCamera? = null
            var settings = CameraSettings()
            var failures = 0
            while (currentCoroutineContext().isActive) {
                try {
                    val index = Ccapi.Index.parse(get(base), base)
                    val product = index[Ccapi.DEVICE_INFO]?.let { api -> Ccapi.productName(get(api.url)) }
                    settings = readAll(index, settings)
                    val camera = live ?: remember(CameraIdentity(product ?: known?.model ?: "Canon camera"), wifi, endpoint)
                    live = camera
                    failures = 0
                    set(CameraLinkState.Connected(camera.name, settings, camera.id))
                    Log.i(TAG, "connected to ${camera.name}: $settings")

                    var useEvents = index[Ccapi.POLLING] != null
                    if (useEvents) {
                        try {
                            follow(index[Ccapi.POLLING]!!) { body ->
                                settings = Ccapi.apply(body, settings)
                                set(CameraLinkState.Connected(camera.name, settings, camera.id))
                            }
                        } catch (e: HttpStatusException) {
                            // Listed but refused, as when another app already holds the events.
                            Log.w(TAG, "events refused (${e.code}); asking once a second instead")
                            useEvents = false
                        }
                    }
                    if (!useEvents) {
                        while (currentCoroutineContext().isActive) {
                            delay(1_000)
                            settings = readAll(index, settings)
                            set(CameraLinkState.Connected(camera.name, settings, camera.id))
                        }
                    }
                } catch (e: IOException) {
                    if (!currentCoroutineContext().isActive) break
                    Log.w(TAG, "camera link: ${e.message}")
                    if (live == null && e is ConnectException) return Outcome.UNREACHED
                    val reason = describe(e)
                    val camera = live
                    if (camera != null) {
                        set(CameraLinkState.Reconnecting(camera.name, settings, reason, camera.id))
                    } else if (++failures >= HELLO_ATTEMPTS) {
                        set(CameraLinkState.Failed(reason, retrying = false, camera = known?.id))
                        return Outcome.FAILED
                    } else {
                        set(CameraLinkState.Failed(reason, retrying = true, camera = known?.id))
                    }
                    if (joined != null && network.cameraWifi != joined) return if (live != null) Outcome.LOST else Outcome.UNREACHED
                    delay(RETRY_MS)
                }
            }
            return Outcome.LOST
        }

        /** Every setting at once where the camera offers that, otherwise one by one. */
        private fun readAll(index: Ccapi.Index, current: CameraSettings): CameraSettings {
            index[Ccapi.SETTINGS]?.let { return Ccapi.apply(get(it.url), current) }
            fun one(name: String, fallback: String?): String? =
                index["${Ccapi.SETTINGS}/$name"]?.let { Ccapi.value(get(it.url)) } ?: fallback
            return current.copy(
                iso = one("iso", current.iso),
                aperture = one("av", current.aperture),
                shutter = one("tv", current.shutter),
                exposureComp = one("exposure", current.exposureComp),
            )
        }

        /**
         * Waits on change events until the link fails. An empty answer or a
         * timeout just means nothing changed.
         */
        private suspend fun follow(polling: Ccapi.Api, onEvent: (String) -> Unit) {
            val url = polling.url + Ccapi.pollingQuery(polling.version)
            while (currentCoroutineContext().isActive) {
                val body = try {
                    get(url, readTimeoutMs = POLL_TIMEOUT_MS)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (body.isNotBlank()) onEvent(body)
                // Events can arrive in bursts while a dial spins; don't spin with them.
                delay(50)
            }
        }

        private fun get(url: String, readTimeoutMs: Int = READ_TIMEOUT_MS): String {
            val c = network.open(url)
            inFlight = c
            try {
                c.connectTimeout = CONNECT_TIMEOUT_MS
                c.readTimeout = readTimeoutMs
                c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                if (code !in 200..299) throw HttpStatusException(code, url)
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                inFlight = null
                c.disconnect()
            }
        }
    }

    private class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code from $url")

    private fun describe(e: IOException): String = when (e) {
        is EosSession.Refused -> "The camera turned the connection down. Choose OK when the camera asks about Link Cam."
        is EosSession.Busy -> "The camera is connected to something else. Close EOS Utility or other camera apps."
        is HttpStatusException -> when (e.code) {
            401, 403 -> "The camera refused the connection. Check Camera Control API is on."
            503 -> "The camera is busy. Close other apps connected to it."
            else -> "The camera answered with an error (${e.code})."
        }
        is SocketTimeoutException -> "No answer from the camera."
        // Refused means something is at that address but nothing is listening for remote control.
        is ConnectException ->
            if (e.message?.contains("ECONNREFUSED") == true) {
                "The camera is there, but not in remote control mode. $SETUP"
            } else {
                "Can't reach the camera at that address."
            }
        is java.net.NoRouteToHostException -> "Can't reach the camera at that address."
        is java.net.UnknownHostException -> "That address isn't valid."
        // Anything else is the socket going away under us; its message is for the log, not the screen.
        else -> "The connection to the camera dropped."
    }

    private companion object {
        const val TAG = "LinkCamCamera"
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 6_000

        /** Longer than the camera holds a long-poll open, so its own timeout answers first. */
        const val POLL_TIMEOUT_MS = 70_000
        const val RETRY_MS = 3_000L
        const val SETTLE_MS = 1_500L

        /** Joining when the user asked: long enough to find the camera in Android's list and tap it. */
        const val JOIN_MS = 30_000

        /** Joining with nobody looking: a camera that's on shows up in a scan or two. */
        const val QUIET_JOIN_MS = 12_000

        /** Between looks for the saved cameras while none is around. */
        const val ROUND_MS = 8_000L
        const val FIRST_HELLO_MS = 4_000

        /** A saved camera that knows Link Cam answers the hello at once; longer than this, it's asking its user. */
        const val KNOWN_HELLO_MS = 8_000

        /** How long pairing keeps rejoining and saying hello before it gives up. */
        const val PAIRING_PATIENCE_MS = 3 * 60_000L

        /** Tries at a camera that answers but won't connect, before saying so and stopping. */
        const val HELLO_ATTEMPTS = 3

        /** How Link Cam introduces itself; the camera shows this when it asks to allow the connection. */
        const val CLIENT_NAME = "Link Cam"

        const val SETUP = "On the camera, open Wi-Fi → Remote control (EOS Utility)."
        const val NOT_FOUND = "No camera found. $SETUP Then show Link Cam the Wi-Fi details the camera gives, or put both on the same Wi-Fi."
        const val NO_WIFI = "Couldn't join the camera's Wi-Fi. Check the camera is showing its Wi-Fi details and that the password matches: it changes each time the camera pairs."
        const val JOINING = "Joining the camera's Wi-Fi…"
        const val JOINING_FROM_SCREEN = "Read the Wi-Fi details off the camera's screen. Joining its Wi-Fi…"
        const val SEARCHING = "Looking for the camera on this Wi-Fi…"
        const val SEARCHING_CAMERA_WIFI = "On the camera's Wi-Fi. Looking for the camera…"
        const val APPROVE = "Waiting for the camera. If it shows \"Start EOS Utility on the computer\", press Cancel on the camera: Link Cam then connects on its own."
        const val REJOINING = "The camera closed the connection, as it does the first time. Joining its Wi-Fi again…"
        const val NO_HELLO = "The camera didn't answer. If it shows \"Start EOS Utility on the computer\", press Cancel on the camera."

        fun joiningCamera(name: String) = "Joining $name's Wi-Fi…"
        fun reaching(name: String) = "Connecting to $name…"
        fun notAround(name: String) = "Couldn't reach $name. On the camera, open Remote control (EOS Utility) and choose its connection to this phone, then try again."
        fun waiting(order: List<SavedCamera>) =
            "Waiting for ${order.first().name}${if (order.size > 1) " or another of your cameras" else ""}. " +
                "On the camera, open Remote control (EOS Utility) and Link Cam connects."
    }
}
