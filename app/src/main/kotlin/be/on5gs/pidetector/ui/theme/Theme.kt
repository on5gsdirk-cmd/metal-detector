package be.on5gs.pidetector.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import be.on5gs.pidetector.data.AppTheme

/** Kleuren die per thema verschillen (bovenop het Material-schema). */
@Immutable
data class Neon(
    val accent: Color,
    val accent2: Color,
    val card: Color,
    val cardBorder: Color,
    val text: Color,
    val textDim: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val graph1: Color,
    val graph2: Color,
    val isFun: Boolean,
)

val StandardNeon = Neon(
    accent = Color(0xFFFF2D55),
    accent2 = Color(0xFFFF7A45),
    card = Color(0xE6121318),
    cardBorder = Color(0x66FF2D55),
    text = Color(0xFFF2F2F5),
    textDim = Color(0xFF9A9CA8),
    good = Color(0xFF39E58C),
    warn = Color(0xFFFFC23D),
    bad = Color(0xFFFF3B3B),
    graph1 = Color(0xFFFF2D55),
    graph2 = Color(0xFF4FC3F7),
    isFun = false,
)

val CoinHuntersNeon = Neon(
    accent = Color(0xFFFF3DF2),
    accent2 = Color(0xFF00F5D4),
    card = Color(0xB3120024),
    cardBorder = Color(0xAAFFE600),
    text = Color(0xFFFFFFFF),
    textDim = Color(0xFFE0C8FF),
    good = Color(0xFF7CFF4F),
    warn = Color(0xFFFFE600),
    bad = Color(0xFFFF4F4F),
    graph1 = Color(0xFFFFE600),
    graph2 = Color(0xFF00F5D4),
    isFun = true,
)

val LocalNeon = staticCompositionLocalOf { StandardNeon }

@Composable
fun PIDetectorTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val neon = if (theme == AppTheme.COIN_HUNTERS) CoinHuntersNeon else StandardNeon
    val scheme = darkColorScheme(
        primary = neon.accent,
        onPrimary = Color.White,
        secondary = neon.accent2,
        background = Color(0xFF07080B),
        surface = Color(0xFF121318),
        surfaceVariant = Color(0xFF1C1D24),
        onSurface = neon.text,
        onBackground = neon.text,
        onSurfaceVariant = neon.textDim,
        outline = neon.cardBorder,
        error = neon.bad,
    )
    CompositionLocalProvider(LocalNeon provides neon) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
