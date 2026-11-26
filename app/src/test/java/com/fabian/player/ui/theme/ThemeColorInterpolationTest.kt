package com.fabian.player.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemeColorInterpolationTest {

    @Test
    fun `lerpColorScheme returns endpoints cleanly at boundaries`() {
        val from = lightColorScheme(primary = Color(0xFF000000), primaryFixed = Color(0xFF102030))
        val to = darkColorScheme(primary = Color(0xFFFFFFFF), primaryFixed = Color(0xFF405060))

        assertThat(lerpColorScheme(from, to, 0f)).isSameInstanceAs(from)
        assertThat(lerpColorScheme(from, to, 1f)).isSameInstanceAs(to)
    }

    @Test
    fun `lerpColorScheme interpolates standard and fixed color roles`() {
        val from = lightColorScheme(
            primary = Color(0xFF000000),
            background = Color(0xFF000000),
            surfaceContainer = Color(0xFF202020),
            primaryFixed = Color(0xFF000000)
        )
        val to = lightColorScheme(
            primary = Color(0xFFFFFFFF),
            background = Color(0xFFFFFFFF),
            surfaceContainer = Color(0xFF808080),
            primaryFixed = Color(0xFFFFFFFF)
        )

        val mid = lerpColorScheme(from, to, 0.5f)

        assertThat(mid.primary.red).isWithin(0.02f).of(0.5f)
        assertThat(mid.background.green).isWithin(0.02f).of(0.5f)
        assertThat(mid.surfaceContainer.red).isWithin(0.02f).of(0.3137f)
        assertThat(mid.primaryFixed.blue).isWithin(0.02f).of(0.5f)
    }
}
