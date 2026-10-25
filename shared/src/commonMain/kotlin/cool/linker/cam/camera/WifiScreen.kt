package cool.linker.cam.camera

/**
 * Reads a Canon camera's Wi-Fi name and password off its "Waiting to
 * connect" screen, from text recognised in the HDMI picture. The camera
 * shows them while it waits to be paired:
 *
 *     SSID
 *                       EOSR10_A-cam-333_Canon0A
 *     Password                         Ab3dEf7h
 *
 * Only the layout is relied on, not the labels, which follow the camera's
 * language: the network name ends in Canon0A or similar, and the password is
 * the last word on the row after it.
 */
object WifiScreen {
    /** One line of recognised text and where it sits in the picture. */
    data class Line(val text: String, val top: Float, val bottom: Float, val left: Float)

    data class Credentials(val ssid: String, val password: String)

    private val SSID = Regex("""\S*Canon[0O][0-9A-Z]\b""")
    private val PASSWORD = Regex("""[A-Za-z0-9]{8,63}""")

    /** A single capitalised word, as a label is; a password on its own row never looks like one. */
    private val WORD = Regex("""[A-Z]?[a-z]+""")

    /**
     * Puts lines that sit side by side back into one row, left to right:
     * recognition often splits a label from its value across the screen.
     */
    fun rows(lines: List<Line>): List<String> {
        val rows = mutableListOf<MutableList<Line>>()
        for (line in lines.sortedBy { (it.top + it.bottom) / 2 }) {
            val centre = (line.top + line.bottom) / 2
            val row = rows.lastOrNull()?.takeIf { centre in it.first().top..it.first().bottom }
            if (row != null) row += line else rows += mutableListOf(line)
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    fun read(rows: List<String>): Credentials? {
        val at = rows.indexOfFirst { SSID.containsMatchIn(it) }
        if (at < 0) return null
        // Recognition reads Canon's zero as a letter O now and then; the camera always writes a zero.
        val ssid = SSID.find(rows[at])!!.value.replace("CanonO", "Canon0")
        for (row in rows.drop(at + 1).take(2)) {
            val words = row.split(Regex("""\s+""")).filter { it.isNotEmpty() }
            val last = words.lastOrNull() ?: continue
            if (!PASSWORD.matches(last) || "Canon" in last) continue
            if (words.size == 1 && WORD.matches(last)) continue
            return Credentials(ssid, last)
        }
        return null
    }
}
