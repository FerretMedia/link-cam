package cool.linker.cam.camera

/**
 * Canon EOS remote control over Wi-Fi: PTP/IP (CIPA DC-005) with Canon's
 * vendor operations, the protocol the camera's "Remote control (EOS Utility)"
 * mode speaks. Unlike Camera Control API it needs no activation, only a
 * one-time OK on the camera's screen.
 *
 * This holds the byte layouts and the meaning of Canon's values; the Android
 * app owns the sockets. Everything is little-endian.
 *
 * A session goes: InitCommandRequest on one TCP connection, InitEventRequest
 * on a second, OpenSession, SetRemoteMode and SetEventMode, then GetEventData
 * a few times a second. The first GetEventData answers with every property;
 * later ones carry only what changed.
 */
object EosPtp {
    const val PORT = 15740

    object Packet {
        const val INIT_COMMAND_REQUEST = 1
        const val INIT_COMMAND_ACK = 2
        const val INIT_EVENT_REQUEST = 3
        const val INIT_EVENT_ACK = 4
        const val INIT_FAIL = 5
        const val OPERATION_REQUEST = 6
        const val OPERATION_RESPONSE = 7
        const val EVENT = 8
        const val START_DATA = 9
        const val DATA = 10
        const val CANCEL = 11
        const val END_DATA = 12
        const val PROBE_REQUEST = 13
        const val PROBE_RESPONSE = 14
    }

    object Op {
        const val GET_DEVICE_INFO = 0x1001
        const val OPEN_SESSION = 0x1002
        const val CLOSE_SESSION = 0x1003
        const val GET_DEVICE_PROP_DESC = 0x1014
        const val GET_DEVICE_PROP_VALUE = 0x1015
        const val SET_REMOTE_MODE = 0x9114
        const val SET_EVENT_MODE = 0x9115
        const val GET_EVENT_DATA = 0x9116
    }

    object Response {
        const val OK = 0x2001
        const val DEVICE_BUSY = 0x2019
        const val SESSION_ALREADY_OPEN = 0x201E
    }

    object Prop {
        const val APERTURE = 0xD101
        const val SHUTTER = 0xD102
        const val ISO = 0xD103
        const val EXPOSURE_COMP = 0xD104

        /** The standard PTP battery level. Canon answers it when asked, not in its change events. */
        const val BATTERY_LEVEL = 0x5001
    }

    private const val EVENT_PROP_CHANGED = 0xC189

    /** PTP/IP 1.0, major version in the high half. */
    private const val PROTOCOL_VERSION = 0x00010000

    // --- building packets ---------------------------------------------------

    fun initCommandRequest(guid: ByteArray, name: String): ByteArray {
        require(guid.size == 16) { "a PTP/IP GUID is 16 bytes" }
        return packet(Packet.INIT_COMMAND_REQUEST, guid + utf16z(name) + u32(PROTOCOL_VERSION))
    }

    fun initEventRequest(connection: Int): ByteArray = packet(Packet.INIT_EVENT_REQUEST, u32(connection))

    /** An operation with no data to send. Data the camera returns comes back before the response. */
    fun operation(code: Int, transaction: Int, vararg params: Int): ByteArray {
        val body = u32(1) + u16(code) + u32(transaction) + params.fold(ByteArray(0)) { acc, p -> acc + u32(p) }
        return packet(Packet.OPERATION_REQUEST, body)
    }

    fun probeResponse(): ByteArray = packet(Packet.PROBE_RESPONSE, ByteArray(0))

    private fun packet(type: Int, payload: ByteArray): ByteArray = u32(8 + payload.size) + u32(type) + payload

    // --- reading packets ----------------------------------------------------

    /** A packet's type and what follows the 8-byte header. */
    class Frame(val type: Int, val payload: ByteArray)

    /** Total frame length from the first four bytes of a header. */
    fun frameLength(header: ByteArray): Int = readU32(header, 0)

    fun frameType(header: ByteArray): Int = readU32(header, 4)

    class InitAck(val connection: Int, val cameraName: String)

    fun parseInitAck(payload: ByteArray): InitAck {
        val connection = readU32(payload, 0)
        val name = readUtf16z(payload, 4 + 16)
        return InitAck(connection, name)
    }

    /** The reason code in an InitFail. */
    fun parseInitFail(payload: ByteArray): Int = if (payload.size >= 4) readU32(payload, 0) else 0

    class OperationResult(val code: Int, val transaction: Int)

    fun parseResponse(payload: ByteArray): OperationResult = OperationResult(readU16(payload, 0), readU32(payload, 2))

    /** The data in a Data or EndData packet, after its transaction id. */
    fun dataOf(payload: ByteArray): ByteArray = payload.copyOfRange(4, payload.size)

    /** What a camera says it can do, from GetDeviceInfo. Which operations it lists depends on its Wi-Fi mode. */
    class DeviceInfo(
        val model: String,
        val operations: Set<Int>,
        val events: Set<Int>,
        val properties: Set<Int>,
        /** The body's serial number: what tells two cameras of the same model apart. Blank if it gives none. */
        val serial: String = "",
    )

    /**
     * Reads the standard PTP DeviceInfo dataset: versions and the vendor
     * description, then the operation, event and property code arrays, then
     * formats and the manufacturer, model, version and serial number strings.
     */
    fun parseDeviceInfo(data: ByteArray): DeviceInfo {
        var at = 2 + 4 + 2 // standard version, vendor extension id and version
        fun string(): String {
            // A body that stops early, as some do after the model, leaves the rest blank.
            if (at >= data.size) return ""
            val count = data[at].toInt() and 0xFF
            at += 1
            val s = readUtf16z(data, at)
            at += count * 2
            return s
        }
        fun codes(): Set<Int> {
            val count = readU32(data, at)
            at += 4
            return (0 until count).map { readU16(data, at + it * 2) }.toSet().also { at += count * 2 }
        }
        string() // vendor extension description
        at += 2 // functional mode
        val operations = codes()
        val events = codes()
        val properties = codes()
        codes() // capture formats
        codes() // image formats
        string() // manufacturer
        val model = string()
        string() // device version
        val serial = string()
        return DeviceInfo(model, operations, events, properties, serial)
    }

    /** A property value from GetDevicePropValue: Canon's exposure properties fit in the low 16 or 32 bits. */
    fun propValue(data: ByteArray): Int? = when {
        data.size >= 4 -> readU32(data, 0)
        data.size >= 2 -> readU16(data, 0)
        data.size == 1 -> data[0].toInt() and 0xFF
        else -> null
    }

    /** What a property's values run between. */
    class PropRange(val min: Int, val max: Int)

    /** A property's value now, and its range where it gives one. */
    class PropDesc(val current: Int, val range: PropRange?)

    /**
     * Reads GetDevicePropDesc: the property's code, type, whether it can be
     * set, its default and current values, then a range or a list of the
     * values it takes. Canon bodies answer this where they refuse
     * GetDevicePropValue, so it's also how to read a value.
     */
    fun parsePropDesc(data: ByteArray): PropDesc? {
        val size = when (if (data.size >= 4) readU16(data, 2) else return null) {
            0x0001, 0x0002 -> 1
            0x0003, 0x0004 -> 2
            0x0005, 0x0006 -> 4
            else -> return null
        }
        fun value(at: Int): Int = when (size) {
            1 -> data[at].toInt() and 0xFF
            2 -> readU16(data, at)
            else -> readU32(data, at)
        }
        val form = 5 + size * 2
        if (form >= data.size) return null
        val range = when (data[form].toInt()) {
            1 -> if (form + 1 + size * 2 <= data.size) PropRange(value(form + 1), value(form + 1 + size)) else null
            2 -> {
                if (form + 3 > data.size) return null
                val count = readU16(data, form + 1)
                val values = (0 until count).map { form + 3 + it * size }.filter { it + size <= data.size }.map(::value)
                if (values.isEmpty()) null else PropRange(values.min(), values.max())
            }
            else -> null
        }
        return PropDesc(value(5 + size), range)
    }

    /**
     * The battery level as a percentage. Bodies differ in how they count it:
     * 0 to 100, or a few steps, which [range] says; without one, the value is
     * taken as a percentage.
     */
    fun batteryPercent(value: Int, range: PropRange?): Int {
        if (range == null || range.max <= range.min) return value.coerceIn(0, 100)
        return ((value - range.min) * 100 / (range.max - range.min)).coerceIn(0, 100)
    }

    // --- events -------------------------------------------------------------

    class PropChange(val prop: Int, val value: Int)

    /**
     * The property changes in GetEventData's answer: a run of records, each
     * `[length][type][...]`, ended by a record of length 8 and type 0.
     */
    fun propChanges(data: ByteArray): List<PropChange> {
        val out = mutableListOf<PropChange>()
        var at = 0
        while (at + 8 <= data.size) {
            val length = readU32(data, at)
            val type = readU32(data, at + 4)
            if (length < 8 || at + length > data.size || type == 0) break
            if (type == EVENT_PROP_CHANGED && length >= 16) {
                out += PropChange(readU32(data, at + 8), readU32(data, at + 12))
            }
            at += length
        }
        return out
    }

    /** [current] with [changes] applied, in the camera's own words as [CameraSettings] keeps them. */
    fun apply(changes: List<PropChange>, current: CameraSettings): CameraSettings {
        var s = current
        for (c in changes) {
            s = when (c.prop) {
                Prop.APERTURE -> s.copy(aperture = EosValues.aperture(c.value))
                Prop.SHUTTER -> s.copy(shutter = EosValues.shutter(c.value))
                Prop.ISO -> s.copy(iso = EosValues.iso(c.value))
                Prop.EXPOSURE_COMP -> s.copy(exposureComp = EosValues.exposureComp(c.value))
                else -> s
            }
        }
        return s
    }

    // --- little-endian ------------------------------------------------------

    private fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    private fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun utf16z(s: String): ByteArray {
        val out = ByteArray((s.length + 1) * 2)
        s.forEachIndexed { i, ch ->
            out[i * 2] = ch.code.toByte()
            out[i * 2 + 1] = (ch.code shr 8).toByte()
        }
        return out
    }

    fun readU16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    fun readU32(b: ByteArray, at: Int): Int = readU16(b, at) or (readU16(b, at + 2) shl 16)

    private fun readUtf16z(b: ByteArray, from: Int): String {
        val sb = StringBuilder()
        var at = from
        while (at + 1 < b.size) {
            val c = readU16(b, at)
            if (c == 0) break
            sb.append(c.toChar())
            at += 2
        }
        return sb.toString()
    }
}

/**
 * What Canon's property codes mean. Aperture, shutter and ISO all count in
 * eighths of a stop: a whole stop is 8, a third 3 or 5 past it, a half 4. The
 * labels are the nominal values a camera displays, written the way Camera
 * Control API writes them, so [CameraSettings] formats both the same way.
 */
object EosValues {
    private val thirdOffsets = intArrayOf(0, 3, 5)

    /** Builds code → label from labels in third stops and labels in half stops, from [firstCode]. */
    private fun table(firstCode: Int, thirds: List<String>, halves: List<String>): Map<Int, String> {
        val m = mutableMapOf<Int, String>()
        thirds.forEachIndexed { i, label -> m[firstCode + 8 * (i / 3) + thirdOffsets[i % 3]] = label }
        halves.forEachIndexed { i, label -> m[firstCode + 8 * i + 4] = label }
        return m
    }

    private val apertures = table(
        0x08,
        thirds = listOf(
            "1.0", "1.1", "1.2", "1.4", "1.6", "1.8", "2.0", "2.2", "2.5", "2.8", "3.2", "3.5",
            "4.0", "4.5", "5.0", "5.6", "6.3", "7.1", "8.0", "9.0", "10", "11", "13", "14",
            "16", "18", "20", "22", "25", "29", "32", "36", "40", "45", "51", "57", "64", "72", "81", "91",
        ),
        halves = listOf("1.2", "1.8", "2.5", "3.5", "4.5", "6.7", "9.5", "13", "19", "27", "38", "54", "76"),
    )

    // Seconds are written as CCAPI does: 30" for thirty, 0"5 for half a second.
    private val shutters = table(
        0x10,
        thirds = listOf(
            "30\"", "25\"", "20\"", "15\"", "13\"", "10\"", "8\"", "6\"", "5\"", "4\"", "3\"2", "2\"5",
            "2\"", "1\"6", "1\"3", "1\"", "0\"8", "0\"6", "0\"5", "0\"4", "0\"3",
            "1/4", "1/5", "1/6", "1/8", "1/10", "1/13", "1/15", "1/20", "1/25", "1/30", "1/40", "1/50",
            "1/60", "1/80", "1/100", "1/125", "1/160", "1/200", "1/250", "1/320", "1/400",
            "1/500", "1/640", "1/800", "1/1000", "1/1250", "1/1600", "1/2000", "1/2500", "1/3200",
            "1/4000", "1/5000", "1/6400", "1/8000", "1/10000", "1/12800", "1/16000", "1/20000", "1/25000", "1/32000",
        ),
        halves = listOf(
            "20\"", "10\"", "6\"", "3\"", "1\"5", "0\"7", "0\"3", "1/6", "1/10", "1/20", "1/45", "1/90",
            "1/180", "1/350", "1/750", "1/1500", "1/3000", "1/6000", "1/12000",
        ),
    )

    private val isos = table(
        0x40,
        thirds = listOf(
            "50", "64", "80", "100", "125", "160", "200", "250", "320", "400", "500", "640",
            "800", "1000", "1250", "1600", "2000", "2500", "3200", "4000", "5000", "6400", "8000", "10000",
            "12800", "16000", "20000", "25600", "32000", "40000", "51200", "64000", "80000", "102400",
        ),
        halves = emptyList(),
    )

    fun aperture(code: Int): String? = apertures[code]?.let { "f$it" }

    fun shutter(code: Int): String? = when (code) {
        0x0C -> "BULB"
        else -> shutters[code]
    }

    fun iso(code: Int): String? = if (code == 0) "auto" else isos[code]

    /** Exposure compensation: a signed byte in eighths of a stop, written "+1_1/3" as CCAPI does. */
    fun exposureComp(code: Int): String? {
        val v = code.toByte().toInt()
        if (v == 0) return "+0.0"
        val sign = if (v < 0) "-" else "+"
        val a = kotlin.math.abs(v)
        val fraction = when (a % 8) {
            0 -> ""
            3 -> "1/3"
            4 -> "1/2"
            5 -> "2/3"
            else -> return null
        }
        val whole = a / 8
        return when {
            fraction.isEmpty() -> "$sign$whole"
            whole == 0 -> "$sign$fraction"
            else -> "$sign${whole}_$fraction"
        }
    }
}
