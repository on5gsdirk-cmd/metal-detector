package be.on5gs.pidetector.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Nepdata voor de simulatiemodus: ruis, trage drift en om de paar seconden een
 * "voorwerp" dat over de spoel zweeft, afwisselend snel (folie/goud) en traag (koper).
 */
class Simulator(seed: Int = 42) {
    private val rnd = Random(seed)
    private val base1 = 1800f
    private val base2 = 900f
    private var nextTargetAt = 3000L
    private var targetStart = -1L
    private var targetAmp = 0f
    private var targetRatio = 0.5f
    private val sweepMs = 1400L

    fun sample(timeMs: Long, params: DetectorParams = DetectorParams.DEFAULT): Message.Data {
        val drift = 25f * sin(2 * PI * timeMs / 60000.0).toFloat()
        var t1 = 0f
        var t2 = 0f
        if (targetStart < 0 && timeMs >= nextTargetAt) {
            targetStart = timeMs
            targetAmp = 60f + rnd.nextFloat() * 400f
            targetRatio = listOf(0.15f, 0.35f, 0.55f, 0.8f).random(rnd) + (rnd.nextFloat() - 0.5f) * 0.06f
        }
        if (targetStart >= 0) {
            val x = (timeMs - targetStart).toFloat() / sweepMs   // 0..1
            if (x >= 1f) {
                targetStart = -1
                nextTargetAt = timeMs + 2500 + rnd.nextLong(4000)
            } else {
                val g = exp(-((x - 0.5f) * 5f).let { it * it })      // gaussische zwaai
                // langere pulsbreedte -> iets sterker signaal (grof model)
                val pwGain = (params.pw / 130f).coerceIn(0.4f, 2f)
                t1 = targetAmp * g * pwGain
                t2 = t1 * targetRatio
            }
        }
        val n1 = (rnd.nextFloat() - 0.5f) * 8f
        val n2 = (rnd.nextFloat() - 0.5f) * 6f
        return Message.Data(
            s1 = (base1 + drift + t1 + n1).toInt().coerceIn(0, 4095),
            s2 = (base2 + drift * 0.5f + t2 + n2).toInt().coerceIn(0, 4095),
            vbat = 11.9f + (rnd.nextFloat() - 0.5f) * 0.05f,
        )
    }
}
