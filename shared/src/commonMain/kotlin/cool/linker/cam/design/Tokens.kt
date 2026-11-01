package cool.linker.cam.design

/**
 * Link Cam's design tokens, taken from Linker's.
 *
 * The chrome is black, white and the greys between, and that is a monitoring
 * requirement as much as a look: a neutral surround keeps the UI from tinting
 * how the picture is judged. Ferret Media's two brand colours are rationed. The
 * full-strength pair only appears as the drifting squares on the arrival
 * screen; on the monitor itself there is one blue accent for an active toggle,
 * and the bright crimson is kept for REC and LIVE.
 *
 * Colours are 0xAARRGGBB.
 */
object Tokens {
    object Color {
        /** Around the picture. Pure black, as on any reference monitor. */
        const val SURROUND = 0xFF000000
        const val INK = 0xFF09090B
        const val INK_RAISED = 0xFF131317
        const val INK_LINE = 0xFF26262C
        const val PAPER = 0xFFFAFAFA
        const val MUTED = 0xFFA1A1AA
        const val FAINT = 0xFF71717A

        const val BRAND = 0xFF4545E6
        const val CRIMSON = 0xFF9F1D8E

        /** The brand hues lifted for small marks and type on ink. */
        const val BRAND_BRIGHT = 0xFF6D6DFF
        const val CRIMSON_BRIGHT = 0xFFF52BD0
    }

    object Size {
        const val RADIUS = 10f
        const val RADIUS_CHIP = 6f
        const val HIT_TARGET = 48f
        const val RAIL = 64f

        /**
         * How tall a tool on the rail is. Below the 48 of a hit target: nine
         * tools have to fit down a phone held sideways, about 410 dp, without
         * scrolling.
         */
        const val RAIL_ITEM = 44f
    }

    object Motion {
        /** The brand squares' drift periods, each its own so they never move in step. */
        val DRIFT_PERIODS_MS = listOf(3000, 4000, 3000, 2000)
        val DRIFT_DELAYS_MS = listOf(1500, 1000, 1000, 2000)
        const val DRIFT_X = 10f
        const val DRIFT_Y = -20f
    }
}
