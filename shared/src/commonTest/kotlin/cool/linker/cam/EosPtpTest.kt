package cool.linker.cam

import cool.linker.cam.camera.CameraSettings
import cool.linker.cam.camera.EosPtp
import cool.linker.cam.camera.EosValues
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EosPtpTest {
    @Test
    fun initCommandRequestIsLaidOutAsTheSpecSays() {
        val guid = ByteArray(16) { it.toByte() }
        val p = EosPtp.initCommandRequest(guid, "LC")
        // length, type, GUID, "LC" + NUL in UTF-16LE, version 1.0
        assertEquals(8 + 16 + 6 + 4, p.size)
        assertEquals(p.size, EosPtp.frameLength(p))
        assertEquals(EosPtp.Packet.INIT_COMMAND_REQUEST, EosPtp.frameType(p))
        assertContentEquals(guid, p.copyOfRange(8, 24))
        assertContentEquals(byteArrayOf('L'.code.toByte(), 0, 'C'.code.toByte(), 0, 0, 0), p.copyOfRange(24, 30))
        assertContentEquals(byteArrayOf(0, 0, 1, 0), p.copyOfRange(30, 34))
    }

    @Test
    fun operationCarriesCodeTransactionAndParams() {
        val p = EosPtp.operation(EosPtp.Op.OPEN_SESSION, transaction = 7, 1)
        assertEquals(8 + 4 + 2 + 4 + 4, p.size)
        assertEquals(EosPtp.Packet.OPERATION_REQUEST, EosPtp.frameType(p))
        assertEquals(1, EosPtp.readU32(p, 8)) // no data phase
        assertEquals(0x1002, EosPtp.readU16(p, 12))
        assertEquals(7, EosPtp.readU32(p, 14))
        assertEquals(1, EosPtp.readU32(p, 18))
    }

    @Test
    fun initAckGivesTheConnectionAndTheCamerasName() {
        val name = "EOS R10".flatMap { listOf(it.code.toByte(), 0) }.toByteArray() + byteArrayOf(0, 0)
        val payload = byteArrayOf(3, 0, 0, 0) + ByteArray(16) + name + byteArrayOf(0, 0, 1, 0)
        val ack = EosPtp.parseInitAck(payload)
        assertEquals(3, ack.connection)
        assertEquals("EOS R10", ack.cameraName)
    }

    @Test
    fun eventDataYieldsPropertyChangesAndStopsAtTheTerminator() {
        fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun prop(code: Int, value: Int) = u32(16) + u32(0xC189) + u32(code) + u32(value)
        val other = u32(12) + u32(0xC18A) + u32(0)
        val data = prop(0xD103, 0x60) + other + prop(0xD102, 0x65) + u32(8) + u32(0) + prop(0xD101, 0x30)
        val changes = EosPtp.propChanges(data)
        assertEquals(listOf(0xD103, 0xD102), changes.map { it.prop })

        val s = EosPtp.apply(changes, CameraSettings())
        assertEquals(listOf("1/50", "ISO 800"), s.readout())
    }

    @Test
    fun deviceInfoListsWhatTheCameraCanDo() {
        fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun u32(v: Int) = u16(v) + u16(v shr 16)
        fun str(s: String) = if (s.isEmpty()) byteArrayOf(0) else byteArrayOf((s.length + 1).toByte()) + s.flatMap { listOf(it.code.toByte(), 0) }.toByteArray() + byteArrayOf(0, 0)
        fun arr(vararg c: Int) = u32(c.size) + c.fold(ByteArray(0)) { a, v -> a + u16(v) }
        val data = u16(100) + u32(11) + u16(100) + str("canon.com: 1.0") + u16(0) +
            arr(0x1001, 0x1002, 0x9116) + arr(0xC189) + arr(0xD101, 0xD103) + arr() + arr(0x3801) +
            str("Canon Inc.") + str("Canon EOS R10") + str("1.5.0")
        val info = EosPtp.parseDeviceInfo(data + str("0123456789ab"))
        assertEquals("Canon EOS R10", info.model)
        assertEquals("0123456789ab", info.serial)
        assertEquals("", EosPtp.parseDeviceInfo(data).serial)
        assertEquals(setOf(0x1001, 0x1002, 0x9116), info.operations)
        assertEquals(setOf(0xD101, 0xD103), info.properties)
        assertEquals(0x30, EosPtp.propValue(u32(0x30)))
    }

    @Test
    fun batteryLevelReadsAsAPercentage() {
        fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        // UINT8, read-only, default 100, current 64, range 0..100 step 1.
        val percent = u16(0x5001) + u16(0x0002) + byteArrayOf(0, 100, 64, 1, 0, 100, 1)
        val desc = EosPtp.parsePropDesc(percent)!!
        assertEquals(64, desc.current)
        assertEquals(64, EosPtp.batteryPercent(desc.current, desc.range))
        // A body that counts in steps: 0 to 3, listed.
        val steps = u16(0x5001) + u16(0x0002) + byteArrayOf(0, 3, 2, 0x02) + u16(4) + byteArrayOf(0, 1, 2, 3)
        val stepped = EosPtp.parsePropDesc(steps)!!
        assertEquals(66, EosPtp.batteryPercent(stepped.current, stepped.range))
        assertEquals(100, EosPtp.batteryPercent(140, null))
        assertNull(EosPtp.parsePropDesc(byteArrayOf(1)))
    }

    @Test
    fun valuesReadLikeTheCamerasDisplay() {
        assertEquals("f5.6", EosValues.aperture(0x30))
        assertEquals("f2.8", EosValues.aperture(0x20))
        assertEquals("f7.1", EosValues.aperture(0x35))
        assertEquals("f6.7", EosValues.aperture(0x34)) // a half stop
        assertEquals("1/50", EosValues.shutter(0x65))
        assertEquals("1/125", EosValues.shutter(0x70))
        assertEquals("30\"", EosValues.shutter(0x10))
        assertEquals("0.5″", CameraSettings.shutterLabel(EosValues.shutter(0x40)!!))
        assertEquals("BULB", CameraSettings.shutterLabel(EosValues.shutter(0x0C)!!))
        assertEquals("100", EosValues.iso(0x48))
        assertEquals("1250", EosValues.iso(0x65))
        assertEquals("auto", EosValues.iso(0))
        assertNull(EosValues.aperture(0x00))
    }

    @Test
    fun exposureCompensationIsSignedEighths() {
        assertEquals("±0", CameraSettings.exposureLabel(EosValues.exposureComp(0)!!))
        assertEquals("+1 1/3", CameraSettings.exposureLabel(EosValues.exposureComp(0x0B)!!))
        assertEquals("-2/3", CameraSettings.exposureLabel(EosValues.exposureComp(0xFB)!!))
        assertEquals("-1", CameraSettings.exposureLabel(EosValues.exposureComp(0xFFFFFFF8.toInt())!!))
    }
}
