package cool.linker.cam.camera

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket

/**
 * One remote-control session with a Canon EOS body over PTP/IP, reading its
 * exposure settings. The byte layouts live in [EosPtp]; this owns the two
 * sockets and the order things happen in.
 *
 * Blocking, for a background thread. [close] from any thread ends it: the
 * blocked read throws and [run] returns through its IOException. That holds
 * even while a socket is still connecting: a session closed then must not
 * carry on and hold the camera, which takes one session at a time.
 */
class EosSession(
    private val network: CameraNetwork,
    private val host: String,
    private val guid: ByteArray,
    private val clientName: String,
) {
    @Volatile private var command: Socket? = null
    @Volatile private var events: Socket? = null
    @Volatile private var closed = false
    @Volatile private var opened = false

    /** How far the handshake got, for the log when a camera turns us away. */
    @Volatile var stage = STAGE_CONNECTING
        private set
    private var transaction = 0

    class Refused(val reason: Int) : IOException("camera refused the connection (0x%x)".format(reason))

    /** The camera has another session, or hasn't let go of the last one yet: worth another try. */
    class Busy : IOException("camera busy with another connection")

    /** The camera kept quiet after hello: a body meeting Link Cam for the first time does that. */
    class Unanswered : IOException("no answer to InitCommandRequest")

    /**
     * Connects, then reports settings until the link fails or [close] is
     * called. [onWaiting] fires while the camera may be asking its user to
     * allow the connection, which it does the first time it sees this phone.
     */
    fun run(
        onWaiting: () -> Unit,
        onConnected: (CameraIdentity) -> Unit,
        onSettings: (CameraSettings) -> Unit,
        helloTimeoutMs: Int = APPROVAL_TIMEOUT_MS,
    ) {
        try {
            val cmd = network.socket(host, EosPtp.PORT, CONNECT_TIMEOUT_MS).also { command = it }
            // Closed while connecting: close() may have looked before the socket was there.
            if (closed) throw IOException("closed")
            val cmdIn = DataInputStream(cmd.getInputStream().buffered())
            val cmdOut = cmd.getOutputStream()

            // The camera holds its answer while it asks whether to allow us.
            cmd.soTimeout = helloTimeoutMs
            stage = "init command"
            cmdOut.send(EosPtp.initCommandRequest(guid, clientName))
            onWaiting()
            val ack = try {
                cmdIn.frame(cmdOut)
            } catch (_: java.net.SocketTimeoutException) {
                throw Unanswered()
            }
            when (ack.type) {
                EosPtp.Packet.INIT_COMMAND_ACK -> Unit
                EosPtp.Packet.INIT_FAIL -> {
                    val reason = EosPtp.parseInitFail(ack.payload)
                    throw if (reason == EosPtp.InitFailReason.BUSY) Busy() else Refused(reason)
                }
                else -> throw IOException("unexpected packet ${ack.type} instead of InitCommandAck")
            }
            val init = EosPtp.parseInitAck(ack.payload)
            cmd.soTimeout = READ_TIMEOUT_MS

            stage = "init event"
            val evt = network.socket(host, EosPtp.PORT, CONNECT_TIMEOUT_MS).also { events = it }
            if (closed) throw IOException("closed")
            evt.soTimeout = READ_TIMEOUT_MS
            val evtIn = DataInputStream(evt.getInputStream().buffered())
            val evtOut = evt.getOutputStream()
            evtOut.send(EosPtp.initEventRequest(init.connection))
            val evtAck = evtIn.frame(evtOut)
            if (evtAck.type != EosPtp.Packet.INIT_EVENT_ACK) throw IOException("event channel refused (${evtAck.type})")
            drainEvents(evtIn, evtOut)

            stage = "open session"
            transact(cmdIn, cmdOut, EosPtp.Op.OPEN_SESSION, 1, okAlso = EosPtp.Response.SESSION_ALREADY_OPEN)
            opened = true
            stage = "remote mode"
            // What the camera can do depends on its Wi-Fi mode, so ask rather than assume.
            val info = runCatching {
                val (code, data) = transact(cmdIn, cmdOut, EosPtp.Op.GET_DEVICE_INFO, retryBusy = false)
                if (code == EosPtp.Response.OK) EosPtp.parseDeviceInfo(data) else null
            }.getOrNull()
            if (info != null) {
                Log.i(TAG, "${info.model}: operations ${info.operations.hex()}")
                Log.i(TAG, "${info.model}: properties ${info.properties.hex()}, events ${info.events.hex()}")
            }
            fun offers(op: Int) = info == null || op in info.operations

            // Remote and event mode are how EOS Utility gets change events. A body in a mode that
            // refuses them can still be asked for its settings one by one, so neither is fatal.
            for (op in listOf(EosPtp.Op.SET_REMOTE_MODE, EosPtp.Op.SET_EVENT_MODE)) {
                if (!offers(op)) continue
                val (code, _) = transact(cmdIn, cmdOut, op, 1, retryBusy = false)
                if (code != EosPtp.Response.OK) Log.w(TAG, "operation 0x%x answered 0x%x; carrying on".format(op, code))
            }
            Log.i(TAG, "EOS session open with ${init.cameraName}")
            // The ack carries the camera's nickname; the model and serial come from DeviceInfo.
            onConnected(
                CameraIdentity(
                    model = info?.model?.ifBlank { null } ?: init.cameraName.ifBlank { "Canon camera" },
                    serial = info?.serial.orEmpty(),
                    nickname = init.cameraName,
                ),
            )
            stage = "events"

            var settings = CameraSettings()
            fun report(next: CameraSettings) {
                if (next != settings) {
                    settings = next
                    onSettings(settings)
                }
            }
            // The battery isn't in the change events; it's asked for now and then.
            val hasBattery = info == null || EosPtp.Prop.BATTERY_LEVEL in info.properties
            var batteryAt = 0L
            var useEvents = offers(EosPtp.Op.GET_EVENT_DATA)
            var eventFailures = 0
            var firstEvents = true
            while (!closed) {
                if (hasBattery && System.currentTimeMillis() - batteryAt >= BATTERY_POLL_MS) {
                    batteryAt = System.currentTimeMillis()
                    // Canon refuses GetDevicePropValue here; the description carries the value too.
                    val (code, data) = transact(cmdIn, cmdOut, EosPtp.Op.GET_DEVICE_PROP_DESC, EosPtp.Prop.BATTERY_LEVEL, retryBusy = false)
                    val desc = if (code == EosPtp.Response.OK) EosPtp.parsePropDesc(data) else null
                    if (desc != null) report(settings.copy(battery = EosPtp.batteryPercent(desc.current, desc.range)))
                }
                if (useEvents) {
                    val (code, data) = transact(cmdIn, cmdOut, EosPtp.Op.GET_EVENT_DATA, retryBusy = false)
                    if (code == EosPtp.Response.OK) {
                        eventFailures = 0
                        val changes = EosPtp.propChanges(data)
                        if (firstEvents) {
                            firstEvents = false
                            Log.i(TAG, "first events: ${data.size} bytes, ${changes.size} property changes: " +
                                changes.joinToString { "%x=%x".format(it.prop, it.value) })
                        }
                        if (changes.isNotEmpty()) report(EosPtp.apply(changes, settings))
                    } else if (code == EosPtp.Response.DEVICE_BUSY) {
                        // Busy in a menu or while writing a file. Asking setting by setting wouldn't help:
                        // Canon refuses that, and the readout would freeze until the link restarted.
                        Thread.sleep(BUSY_BACKOFF_MS)
                    } else if (++eventFailures >= EVENT_FAILURES_BEFORE_POLLING) {
                        Log.w(TAG, "GetEventData keeps answering 0x%x; asking for each setting instead".format(code))
                        useEvents = false
                    }
                } else {
                    val changes = EXPOSURE_PROPS.filter { info == null || it in info.properties }.mapNotNull { prop ->
                        val (code, data) = transact(cmdIn, cmdOut, EosPtp.Op.GET_DEVICE_PROP_VALUE, prop, retryBusy = false)
                        if (code == EosPtp.Response.OK) EosPtp.propValue(data)?.let { EosPtp.PropChange(prop, it) } else null
                    }
                    report(EosPtp.apply(changes, settings))
                }
                Thread.sleep(if (useEvents) POLL_MS else PROP_POLL_MS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            close()
        }
    }

    /** Ends the session. Safe to call more than once, from any thread; the sockets close every time. */
    fun close() {
        val first = !closed
        closed = true
        // A polite CloseSession if a session was opened; the sockets go either way.
        if (first && opened) {
            runCatching {
                command?.getOutputStream()?.send(EosPtp.operation(EosPtp.Op.CLOSE_SESSION, ++transaction))
            }
        }
        runCatching { events?.close() }
        runCatching { command?.close() }
    }

    /** Sends an operation and collects its data and response, answering probes on the way. */
    private fun transact(
        input: DataInputStream,
        output: OutputStream,
        op: Int,
        vararg params: Int,
        okAlso: Int = EosPtp.Response.OK,
        retryBusy: Boolean = true,
    ): Pair<Int, ByteArray> {
        repeat(if (retryBusy) BUSY_RETRIES else 1) { attempt ->
            val id = ++transaction
            output.send(EosPtp.operation(op, id, *params))
            var data = ByteArray(0)
            while (true) {
                val f = input.frame(output)
                when (f.type) {
                    EosPtp.Packet.START_DATA -> data = ByteArray(0)
                    EosPtp.Packet.DATA, EosPtp.Packet.END_DATA -> data += EosPtp.dataOf(f.payload)
                    EosPtp.Packet.OPERATION_RESPONSE -> {
                        val r = EosPtp.parseResponse(f.payload)
                        if (r.transaction != id) continue
                        if (r.code == EosPtp.Response.OK || r.code == okAlso) return r.code to data
                        if (r.code == EosPtp.Response.DEVICE_BUSY && retryBusy && attempt < BUSY_RETRIES - 1) break
                        if (!retryBusy) return r.code to data
                        throw IOException("operation 0x%x failed with 0x%x".format(op, r.code))
                    }
                }
            }
            Thread.sleep(300)
        }
        throw IOException("camera stayed busy for operation 0x%x".format(op))
    }

    /** The event channel only needs its probes answered; the settings come from GetEventData. */
    private fun drainEvents(input: DataInputStream, output: OutputStream) {
        Thread({
            try {
                events?.soTimeout = 0
                while (!closed) input.frame(output)
            } catch (_: IOException) {
                // Closed, or the camera went away; the command channel notices too.
            }
        }, "LinkCamEosEvents").apply { isDaemon = true }.start()
    }

    /** Reads one frame, answering a probe request if that's what it is. */
    private fun DataInputStream.frame(reply: OutputStream): EosPtp.Frame {
        while (true) {
            val header = ByteArray(8)
            readFully(header)
            val length = EosPtp.frameLength(header)
            if (length < 8 || length > MAX_FRAME) throw IOException("bad PTP/IP frame length $length")
            val payload = ByteArray(length - 8)
            readFully(payload)
            val type = EosPtp.frameType(header)
            if (type == EosPtp.Packet.PROBE_REQUEST) {
                synchronized(reply) { reply.send(EosPtp.probeResponse()) }
                continue
            }
            return EosPtp.Frame(type, payload)
        }
    }

    private fun OutputStream.send(bytes: ByteArray) = synchronized(this) {
        write(bytes)
        flush()
    }

    companion object {
        /** Before the first socket is up: failing here means nothing at that address took the connection. */
        const val STAGE_CONNECTING = "connecting"

        /** Long enough to walk to the camera and press OK, or Cancel. */
        const val APPROVAL_TIMEOUT_MS = 60_000

        private const val TAG = "LinkCamCamera"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 8_000

        /** EOS Utility asks five times a second. */
        private const val POLL_MS = 200L
        private const val BUSY_RETRIES = 5
        private const val EVENT_FAILURES_BEFORE_POLLING = 5
        private const val PROP_POLL_MS = 500L
        private const val BUSY_BACKOFF_MS = 300L

        /** A battery drains over minutes; twice a minute keeps it current without crowding the link. */
        private const val BATTERY_POLL_MS = 30_000L
        private val EXPOSURE_PROPS = listOf(EosPtp.Prop.APERTURE, EosPtp.Prop.SHUTTER, EosPtp.Prop.ISO, EosPtp.Prop.EXPOSURE_COMP)

        private fun Set<Int>.hex() = sorted().joinToString(" ") { "%04x".format(it) }
        private const val MAX_FRAME = 16 * 1024 * 1024
    }
}
