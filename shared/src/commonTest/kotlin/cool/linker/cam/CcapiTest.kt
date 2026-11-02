package cool.linker.cam

import cool.linker.cam.camera.CameraSettings
import cool.linker.cam.camera.Ccapi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CcapiTest {
    @Test
    fun baseUrlAcceptsWhatTheCameraShowsOrJustTheAddress() {
        assertEquals("http://192.168.1.2:8080/ccapi", Ccapi.baseUrl("http://192.168.1.2:8080/ccapi"))
        assertEquals("http://192.168.1.2:8080/ccapi", Ccapi.baseUrl(" 192.168.1.2 "))
        assertEquals("http://10.0.0.5:9000/ccapi", Ccapi.baseUrl("10.0.0.5:9000/"))
        assertNull(Ccapi.baseUrl("  "))
    }

    @Test
    fun indexTakesTheNewestVersionOfEachApi() {
        val body = """
            {
              "ver100": [
                {"path": "http://192.168.1.2:8080/ccapi/ver100/shooting/settings", "get": true, "put": false},
                {"path": "http://192.168.1.2:8080/ccapi/ver100/event/polling", "get": true, "delete": true}
              ],
              "ver110": [
                {"path": "/ccapi/ver110/event/polling", "get": true}
              ]
            }
        """
        val index = Ccapi.Index.parse(body, "http://192.168.1.2:8080/ccapi")
        assertEquals(100, index[Ccapi.SETTINGS]?.version)
        assertEquals("http://192.168.1.2:8080/ccapi/ver110/event/polling", index[Ccapi.POLLING]?.url)
        assertEquals("?timeout=long", Ccapi.pollingQuery(index[Ccapi.POLLING]!!.version))
        assertEquals("?continue=on", Ccapi.pollingQuery(100))
    }

    @Test
    fun settingsAndEventsReadTheSameWay() {
        val all = """{"iso": {"value": "800", "ability": ["auto", "100"]}, "av": {"value": "f5.6"}, "tv": {"value": "1/50"}, "exposure": {"value": "+0_1/3"}}"""
        val s = Ccapi.apply(all, CameraSettings())
        assertEquals(CameraSettings("800", "f5.6", "1/50", "+0_1/3"), s)

        // An event carries only what changed; the rest stays.
        val changed = Ccapi.apply("""{"iso": {"value": "auto"}, "recordable": "on"}""", s)
        assertEquals("auto", changed.iso)
        assertEquals("f5.6", changed.aperture)

        // A setting present with no value has gone away, like the aperture of a manual lens.
        assertNull(Ccapi.apply("""{"av": {"value": ""}}""", s).aperture)
        assertEquals("1600", Ccapi.value("""{"value": "1600", "ability": []}"""))
    }

    @Test
    fun aProbeKnowsACameraFromAnythingElse() {
        assertEquals(true, Ccapi.isIndex("""{"ver100": [], "ver110": []}"""))
        assertEquals(false, Ccapi.isIndex("<html>router login</html>"))
        assertEquals(false, Ccapi.isIndex("""{"status": "ok"}"""))
    }

    @Test
    fun ssdpReplyGivesTheCamerasHost() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.2:49152/upnp/CameraDevDesc.xml\r\nST: ${Ccapi.SSDP_TARGET}\r\n\r\n"
        assertEquals("192.168.1.2", Ccapi.ssdpHost(reply))
        assertEquals("http://192.168.1.2:8080/ccapi", Ccapi.baseUrlFor("192.168.1.2"))
        assertNull(Ccapi.ssdpHost("HTTP/1.1 200 OK\r\n\r\n"))
    }

    @Test
    fun readoutReadsLikeTheCamerasInfoBar() {
        val s = CameraSettings(iso = "auto", aperture = "f2.8", shutter = "0\"5", exposureComp = "+0.0")
        assertEquals(listOf("0.5″", "f/2.8", "ISO AUTO"), s.readout())
        assertEquals("1/4000", CameraSettings.shutterLabel("1/4000"))
        assertEquals("30″", CameraSettings.shutterLabel("30\""))
        assertEquals("+1 1/3", CameraSettings.exposureLabel("+1_1/3"))
    }
}
