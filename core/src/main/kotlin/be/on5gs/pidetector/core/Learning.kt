package be.on5gs.pidetector.core

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Een aangeleerd voorwerp: piekniveau (ADC-tellen na balance) en s2/s1-verhouding. */
@Serializable
data class LearnedTarget(
    val id: String,
    val name: String,
    val peak: Float,
    val ratio: Float,
    val samples: Int,
    /** Profiel (pw/d1/d2) waarmee geleerd werd; verhoudingen zijn alleen vergelijkbaar binnen dezelfde instellingen. */
    val params: DetectorParams? = null,
)

/**
 * Leermodus: verzamelt readings terwijl je het voorwerp over de spoel beweegt.
 * De verhouding wordt gemiddeld over de samples rond de piek (boven 50 % van de piek).
 */
class LearnSession {
    private val points = ArrayList<Pair<Float, Float>>() // d1, d2
    var peak = 0f; private set

    val sampleCount: Int get() = points.size

    fun add(r: Reading) {
        if (!r.balanced || r.d1 < SignalProcessor.MIN_RATIO_COUNTS) return
        points += r.d1 to r.d2
        peak = max(peak, r.d1)
    }

    /** Null als er te weinig bruikbare samples waren. */
    fun result(id: String, name: String, params: DetectorParams?): LearnedTarget? {
        val strong = points.filter { it.first >= peak * 0.5f }
        if (strong.size < 3) return null
        // gewogen gemiddelde: sterke samples tellen zwaarder (minder ruis)
        var num = 0.0; var den = 0.0
        for ((a, b) in strong) { num += b.toDouble(); den += a.toDouble() }
        val ratio = (num / den).toFloat().coerceIn(0f, 1f)
        return LearnedTarget(id, name, peak, ratio, strong.size, params)
    }
}

data class TargetMatch(val target: LearnedTarget, val percent: Int)

object TargetMatcher {
    /** Breedte (σ) van de verhoudingsvergelijking. */
    const val SIGMA_RATIO = 0.07f
    /** Amplitude telt maar licht mee: diepte en positie veranderen die sterk. */
    const val AMPLITUDE_WEIGHT = 0.15f

    fun score(target: LearnedTarget, ratio: Float, peak: Float?): Float {
        val dr = (ratio - target.ratio) / SIGMA_RATIO
        var s = exp(-0.5f * dr * dr)
        if (peak != null && peak > 0f && target.peak > 0f) {
            val dl = ln(peak / target.peak) / ln(4f) // factor 4 verschil = 1 σ
            val ampScore = exp(-0.5f * dl * dl)
            s *= (1f - AMPLITUDE_WEIGHT) + AMPLITUDE_WEIGHT * ampScore
        }
        return s
    }

    fun best(targets: List<LearnedTarget>, ratio: Float?, peak: Float?, minPercent: Int = 20): TargetMatch? {
        if (ratio == null || targets.isEmpty()) return null
        val best = targets.maxByOrNull { score(it, ratio, peak) } ?: return null
        val pct = (score(best, ratio, peak) * 100f).toInt()
        return if (pct >= minPercent) TargetMatch(best, pct) else null
    }
}
