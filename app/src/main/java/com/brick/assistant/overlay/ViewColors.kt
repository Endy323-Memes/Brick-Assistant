package com.brick.assistant.overlay

import android.graphics.Color
import com.brick.assistant.data.Palettes

/** The active palette as plain ARGB ints for Brick's drawn views (read when a view is created). */
object ViewColors {
    fun mix(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    fun alpha(c: Int, a: Int): Int = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    val brick get() = Palettes.current.brick
    val unit get() = Palettes.current.unit
    val alert get() = Palettes.current.alert
    val ember get() = mix(brick, alert, 0.4f)
    val ice get() = mix(unit, Color.WHITE, 0.55f)
    val teal get() = mix(unit, Color.BLACK, 0.4f)
    val background get() = Palettes.current.background
    val panel get() = Palettes.current.panel
    val line get() = Palettes.current.line
    val text get() = Palettes.current.text
    val dim get() = Palettes.current.dim
}
