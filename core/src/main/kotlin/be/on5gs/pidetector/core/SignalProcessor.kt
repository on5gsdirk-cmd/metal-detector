package be.on5gs.pidetector.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Polarity { ABSOLUTE, NORMAL, INVERTED }

@kotlinx.serialization.Serializable
data class ProcessorSettings(
    /** Gevoeligheid 1..20: bij 1 is ~400 ADC-tellen volle schaal, bij 10 ~40. */
    val sensitivity: Int = 8,
    /** Genormaliseerd niveau (0..1) waarboven we van een "doel" spreken. */
    val threshold: Float = 0.08f,
    val autoBalance: Boolean = false,
    /** Tijdconstante van de automatische drift-correctie in seconden. */
    val autoBalanceTauS: Float = 8f,
    val polarity: Polarity = Polarity.ABSOLUTE,
    val balanceDurationMs: Long = 1000,
) {
    companion object {
        val SENSITIVITY = 1..20
        const val FULL_SCALE_COUNTS = 400f
    }
}

/** Resultaat per ontvangen sample. */
data class Reading(
    val timeMs: Long,
    val raw1: Int,
    val raw2: Int,
    /** Signaal na balance (ADC-tellen, met polariteit toegepast). */
    val d1: Float,
    val d2: Float,
    /** Meterwaarde 0..1 (met gevoeligheid). */
    val level: Float,
    /** Verhouding d2/d1 (0 = snelle afval, 1 = trage afval), null als het signaal te zwak is. */
    val ratio: Float?,
    val targetPresent: Boolean,
    val balanced: Boolean,
    /** 0..1 tijdens het balanceren, anders null. */
    val balanceProgress: Float?,
)

/**
 * Balance (nulpunt voor s1 en s2), optionele auto-balance, gevoeligheid en
 * de s2/s1-verhouding voor de doel-indicator. Puur rekenwerk, testbaar zonder Android.
 */
class SignalProcessor(var settings: ProcessorSettings = ProcessorSettings()) {

    var zero1: Float? = null; private set
    var zero2: Float? = null; private set

    private var balancing = false
    private var balanceStart = 0L
    private var sum1 = 0.0
    private var sum2 = 0.0
    private var count = 0
    private var lastTime: Long? = null
    private var ratioSmooth: Float? = null

    val isBalanced: Boolean get() = zero1 != null

    /** Start een balance: het gemiddelde van de komende ~1 s wordt het nulpunt. */
    fun startBalance() {
        balancing = true
        balanceStart = -1
        sum1 = 0.0; sum2 = 0.0; count = 0
    }

    fun setZero(z1: Float, z2: Float) { zero1 = z1; zero2 = z2; balancing = false }
    fun clearBalance() { zero1 = null; zero2 = null; balancing = false }

    fun process(raw1: Int, raw2: Int, timeMs: Long): Reading {
        var progress: Float? = null
        if (balancing) {
            if (balanceStart < 0) balanceStart = timeMs
            sum1 += raw1; sum2 += raw2; count++
            val elapsed = timeMs - balanceStart
            progress = min(1f, elapsed.toFloat() / settings.balanceDurationMs)
            if (elapsed >= settings.balanceDurationMs && count > 0) {
                zero1 = (sum1 / count).toFloat()
                zero2 = (sum2 / count).toFloat()
                balancing = false
                progress = null
                ratioSmooth = null
            }
        }

        val z1 = zero1
        val z2 = zero2
        if (z1 == null || z2 == null) {
            lastTime = timeMs
            return Reading(timeMs, raw1, raw2, 0f, 0f, 0f, null, false, false, progress)
        }

        val d1 = applyPolarity(raw1 - z1)
        val d2 = applyPolarity(raw2 - z2)
        val level = levelOf(d1)
        val present = level >= settings.threshold

        // Auto-balance: nulpunt volgt langzaam de drift (niet tijdens het balanceren).
        val prev = lastTime
        if (settings.autoBalance && !balancing && prev != null && timeMs > prev) {
            val dt = (timeMs - prev) / 1000f
            val k = min(1f, dt / max(0.5f, settings.autoBalanceTauS))
            zero1 = z1 + (raw1 - z1) * k
            zero2 = z2 + (raw2 - z2) * k
        }
        lastTime = timeMs

        val r = ratioOf(d1, d2, present)
        ratioSmooth = when {
            r == null -> null
            ratioSmooth == null -> r
            else -> ratioSmooth!! * 0.7f + r * 0.3f
        }

        return Reading(timeMs, raw1, raw2, d1, d2, level, ratioSmooth, present, true, progress)
    }

    fun levelOf(d1: Float): Float {
        val fullScale = ProcessorSettings.FULL_SCALE_COUNTS / settings.sensitivity.coerceIn(ProcessorSettings.SENSITIVITY)
        return (max(0f, d1) / fullScale).coerceIn(0f, 1f)
    }

    private fun applyPolarity(v: Float): Float = when (settings.polarity) {
        Polarity.ABSOLUTE -> abs(v)
        Polarity.NORMAL -> v
        Polarity.INVERTED -> -v
    }

    companion object {
        /** Minimaal d1 (ADC-tellen) voor een zinvolle verhouding. */
        const val MIN_RATIO_COUNTS = 6f

        /**
         * Verhouding van het late t.o.v. het vroege signaal, 0..1.
         * Klein/dun/folie/goud: snelle afval -> laag. Groot/koper/zilver: trage afval -> hoog.
         */
        fun ratioOf(d1: Float, d2: Float, present: Boolean = true): Float? {
            if (!present || d1 < MIN_RATIO_COUNTS) return null
            return (d2 / d1).coerceIn(0f, 1f)
        }
    }
}
