package cool.linker.cam

import cool.linker.cam.camera.CameraBook
import cool.linker.cam.camera.CameraIdentity
import cool.linker.cam.camera.CameraWifi
import cool.linker.cam.camera.SavedCamera
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraBookTest {
    private val r10 = CameraIdentity("Canon EOS R10", serial = "0123456789ab", nickname = "A-cam")
    private val first = CameraWifi("EOSR10_A-cam-333_Canon0A", "Q7mTxw2K")

    @Test fun aFirstConnectionAddsTheCamera() {
        val (cameras, camera) = CameraBook.connected(emptyList(), r10, first, "eos:192.168.1.2", now = 10)
        assertEquals(listOf(camera), cameras)
        assertEquals("serial:0123456789ab", camera.id)
        assertEquals("Canon EOS R10 “A-cam”", camera.name)
        // As the R10 itself names it: its model, then the owner's name.
        assertEquals("Canon EOS R10 “A-cam”", camera.copy(nickname = "EOSR10_A-cam").name)
        assertEquals("Canon EOS R10", camera.copy(nickname = "EOSR10").name)
        assertTrue(camera.reconnectsQuietly)
    }

    @Test fun pairingTheSameCameraAgainUpdatesItsEntry() {
        val (once, _) = CameraBook.connected(emptyList(), r10, first, "eos:192.168.1.2", now = 10)
        val again = CameraWifi("EOSR10_A-cam-512_Canon0A", "Zz9yXw8V")
        val (twice, camera) = CameraBook.connected(once, r10, again, "eos:192.168.1.2", now = 20)
        assertEquals(1, twice.size)
        assertEquals(again, camera.wifi)
        assertEquals(20, camera.lastConnected)
    }

    @Test fun reconnectingWithoutWifiDetailsKeepsThem() {
        val (once, _) = CameraBook.connected(emptyList(), r10, first, "eos:192.168.1.2", now = 10)
        assertEquals(first, CameraBook.connected(once, r10, null, "eos:192.168.1.3", now = 20).second.wifi)
        // A typed password with no name keeps the name it already had.
        assertEquals(first, CameraBook.connected(once, r10, CameraWifi(null, first.password), "eos:192.168.1.2", now = 20).second.wifi)
    }

    @Test fun anEntryFromBeforeTheSerialWasKnownIsTakenOver() {
        val old = SavedCamera("name:Canon EOS R10/A-cam", "Canon EOS R10", "A-cam", first, "eos:192.168.1.2", 5)
        val (cameras, camera) = CameraBook.connected(listOf(old), r10, first, "eos:192.168.1.2", now = 10)
        assertEquals(listOf(camera), cameras)
        assertEquals("serial:0123456789ab", camera.id)
    }

    @Test fun twoCamerasAreKeptApartAndTriedNewestFirst() {
        val r6 = CameraIdentity("Canon EOS R6", serial = "ffff", nickname = "B-cam")
        val (one, _) = CameraBook.connected(emptyList(), r10, first, "eos:192.168.1.2", now = 10)
        val (two, _) = CameraBook.connected(one, r6, CameraWifi("EOSR6_B-cam-101_Canon0A", "Aa1Bb2Cc"), "eos:192.168.1.2", now = 20)
        assertEquals(listOf("Canon EOS R6 “B-cam”", "Canon EOS R10 “A-cam”"), CameraBook.ordered(two).map { it.name })
        val typedOnly = SavedCamera("name:x/", "Canon EOS R7", wifi = CameraWifi(null, "Pp0Oo9Ii"), lastConnected = 30)
        // Known only by password: Android would ask the user, so it isn't tried with nobody looking.
        assertEquals(listOf("serial:ffff", "serial:0123456789ab"), CameraBook.quietOrder(two + typedOnly).map { it.id })
        assertEquals(listOf("serial:ffff"), CameraBook.forget(two, "serial:0123456789ab").map { it.id })
    }

    @Test fun findsTheCameraAWifiBelongsTo() {
        val (cameras, camera) = CameraBook.connected(emptyList(), r10, first, "eos:192.168.1.2", now = 10)
        assertEquals(camera, CameraBook.withWifi(cameras, first))
        assertEquals(camera, CameraBook.withWifi(cameras, CameraWifi(null, first.password)))
        assertNull(CameraBook.withWifi(cameras, CameraWifi(first.ssid, "different")))
    }

    @Test fun survivesBeingSaved() {
        val (cameras, _) = CameraBook.connected(emptyList(), r10, first, "eos:192.168.1.2", now = 10)
        val json = Json.encodeToString(cameras)
        assertEquals(cameras, Json.decodeFromString<List<SavedCamera>>(json))
        // Fields added later read back as their defaults.
        assertEquals("Canon EOS R10", Json { ignoreUnknownKeys = true }.decodeFromString<SavedCamera>("""{"id":"a","model":"Canon EOS R10","extra":1}""").model)
    }
}
