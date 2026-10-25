package cool.linker.cam

import cool.linker.cam.camera.WifiScreen
import cool.linker.cam.camera.WifiScreen.Credentials
import cool.linker.cam.camera.WifiScreen.Line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WifiScreenTest {
    // The R10's "Waiting to connect" screen, as recognition splits it: labels and values apart.
    private val screen = listOf(
        Line("Waiting to connect", 100f, 150f, 520f),
        Line("Connect device to camera using", 200f, 250f, 520f),
        Line("network settings below and then", 290f, 340f, 520f),
        Line("start Canon app/software", 375f, 425f, 520f),
        Line("SSID", 465f, 515f, 520f),
        Line("EOSR10_A-cam-333_Canon0A", 550f, 600f, 770f),
        Line("Password", 635f, 685f, 520f),
        Line("Q7mTxw2K", 637f, 687f, 1240f),
        Line("Cancel", 750f, 800f, 600f),
        Line("Switch network", 752f, 802f, 1090f),
    )

    @Test fun rowsJoinLabelsWithTheirValues() {
        val rows = WifiScreen.rows(screen.shuffled())
        assertEquals("Password Q7mTxw2K", rows[6])
        assertEquals("Cancel Switch network", rows[7])
    }

    @Test fun readsTheNameAndPassword() {
        assertEquals(Credentials("EOSR10_A-cam-333_Canon0A", "Q7mTxw2K"), WifiScreen.read(WifiScreen.rows(screen)))
    }

    @Test fun readsAScreenInAnotherLanguage() {
        val rows = listOf("Wachten op verbinding", "SSID", "EOSR10_A-cam-333_Canon0A", "Wachtwoord Q7mTxw2K")
        assertEquals("Q7mTxw2K", WifiScreen.read(rows)?.password)
    }

    @Test fun fixesALetterOInTheName() {
        val rows = listOf("SSID EOSR10_A-cam-333_CanonOA", "Password Q7mTxw2K")
        assertEquals("EOSR10_A-cam-333_Canon0A", WifiScreen.read(rows)?.ssid)
    }

    @Test fun takesAPasswordOnItsOwnRowButNotALabel() {
        assertEquals("Q7mTxw2K", WifiScreen.read(listOf("EOSR10_A-cam-333_Canon0A", "Password", "Q7mTxw2K"))?.password)
        assertNull(WifiScreen.read(listOf("EOSR10_A-cam-333_Canon0A", "Password")))
    }

    @Test fun ignoresOtherScreens() {
        assertNull(WifiScreen.read(listOf("Wi-Fi function", "Remote control (EOS Utility)", "Connect to smartphone")))
        assertNull(WifiScreen.read(emptyList()))
    }
}
