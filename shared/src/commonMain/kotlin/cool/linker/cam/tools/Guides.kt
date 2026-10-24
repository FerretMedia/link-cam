package cool.linker.cam.tools

import kotlinx.serialization.Serializable

/** A rectangle in view coordinates. */
data class Rect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /** The same rectangle shrunk about its centre to [fraction] of its size. */
    fun inset(fraction: Float): Rect {
        val w = width * fraction
        val h = height * fraction
        return Rect(left + (width - w) / 2f, top + (height - h) / 2f, w, h)
    }
}

@Serializable
enum class GuideAspect(val ratio: Float, val label: String) {
    SCOPE(2.39f, "2.39"),
    FLAT(1.85f, "1.85"),
    PORTRAIT_4_5(0.8f, "4:5"),
    VERTICAL(9f / 16f, "9:16"),
    SQUARE(1f, "1:1"),
}

object Guides {
    /** Action safe and title safe, as fractions of the picture (SMPTE ST 2046-1). */
    const val ACTION_SAFE = 0.93f
    const val TITLE_SAFE = 0.9f

    /** The largest rectangle of [aspect] centred in a [width]×[height] area: where the picture goes. */
    fun fit(width: Float, height: Float, aspect: Float): Rect {
        if (width <= 0f || height <= 0f || aspect <= 0f) return Rect(0f, 0f, 0f, 0f)
        return if (width / height > aspect) {
            val w = height * aspect
            Rect((width - w) / 2f, 0f, w, height)
        } else {
            val h = width / aspect
            Rect(0f, (height - h) / 2f, width, h)
        }
    }

    /** The frame line for [guide] inside the [picture] rectangle. */
    fun frameLine(picture: Rect, guide: GuideAspect): Rect {
        val inner = fit(picture.width, picture.height, guide.ratio)
        return Rect(picture.left + inner.left, picture.top + inner.top, inner.width, inner.height)
    }
}
