package be.on5gs.pidetector.core

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class ToneMode { VCO, BEEP }

@Serializable
data class AudioSettings(
    val mode: ToneMode = ToneMode.VCO,
    val minHz: Float = 200f,
    val maxHz: Float = 2000f,
    /** Lage brom in rust zodat je hoort dat de detector werkt. */
    val thresholdHum: Boolean = true,
    /** Piepmodus: drempel op het meterniveau (0..1). */
    val beepThreshold: Float = 0.10f,
    /** Toonhoogte volgt de doel-indicator i.p.v. de signaalsterkte. */
    val pitchFromTarget: Boolean = false,
    val volume: Float = 0.7f,
    val muted: Boolean = false,
    /** Muntenshuppers: ook de gewone detectortoon laten horen naast walvis + munten. */
    val funKeepDetectorTone: Boolean = false,
) {
    companion object {
        val HZ_RANGE = 50f..4000f
    }
}

/** Wat de synthesizer moet weergeven; wordt vanuit de UI/meetlus gezet. */
data class SoundControl(
    val active: Boolean = false,
    val level: Float = 0f,
    val ratio: Float? = null,
    val targetPresent: Boolean = false,
    /** Muntenshuppers-thema: walvisgeluid + rinkelende munten. */
    val funMode: Boolean = false,
)

/** Berekening van toonhoogte en piepsnelheid (los getest). */
object ToneMapping {
    fun vcoHz(level: Float, s: AudioSettings): Float {
        val l = level.coerceIn(0f, 1f).pow(0.7f)
        return s.minHz + (s.maxHz - s.minHz) * l
    }

    /** Laag = traag/groot, hoog = snel/klein. */
    fun targetHz(ratio: Float, s: AudioSettings): Float =
        s.maxHz - (s.maxHz - s.minHz) * ratio.coerceIn(0f, 1f)

    /** Piepjes per seconde: 2 net boven de drempel tot 20 bij vol signaal. */
    fun beepRate(level: Float, s: AudioSettings): Float {
        if (level < s.beepThreshold) return 0f
        val x = ((level - s.beepThreshold) / max(1e-3f, 1f - s.beepThreshold)).coerceIn(0f, 1f)
        return 2f + 18f * x
    }
}

/**
 * Continu gegenereerd geluid (geen afspeelbestanden), fase-continu zodat
 * toonwissels niet klikken. Niet thread-safe behalve [control] en [settings],
 * die als geheel vervangen worden (volatile).
 */
class SoundGenerator(private val sampleRate: Int = 44100, seed: Int = 1) {
    @Volatile var settings = AudioSettings()
    @Volatile var control = SoundControl()

    private val dt = 1f / sampleRate
    private var phase = 0.0
    private var curHz = 200f
    private var curAmp = 0f
    private var beepPhase = 0f
    private var master = 0f

    private val whale = WhaleSynth(sampleRate, Random(seed))
    private val coins = CoinSynth(sampleRate, Random(seed + 7))
    private var wasPresent = false
    private var coinCooldown = 0f

    /** Vult [out] met 16-bit mono samples. */
    fun render(out: ShortArray, n: Int = out.size) {
        val s = settings
        val c = control
        val fun_ = c.funMode
        val toneOn = !fun_ || s.funKeepDetectorTone

        // doelwaarden
        var targetHz: Float
        var targetAmp: Float
        val level = c.level.coerceIn(0f, 1f)
        when (s.mode) {
            ToneMode.VCO -> {
                targetHz = if (s.pitchFromTarget && c.ratio != null) ToneMapping.targetHz(c.ratio, s)
                           else ToneMapping.vcoHz(level, s)
                targetAmp = if (s.thresholdHum) 0.12f + 0.88f * level else min(1f, level * 3f)
                if (s.thresholdHum && level < 0.02f) targetHz = s.minHz * 0.5f // brom
            }
            ToneMode.BEEP -> {
                targetHz = if (s.pitchFromTarget && c.ratio != null) ToneMapping.targetHz(c.ratio, s)
                           else 0.5f * (s.minHz + s.maxHz)
                targetAmp = 1f
            }
        }
        if (!c.active || !toneOn) targetAmp = 0f
        val beepRate = ToneMapping.beepRate(level, s)

        // munten bij een nieuw doel (stijgende flank), met afkoeltijd
        if (fun_ && c.active && c.targetPresent && !wasPresent && coinCooldown <= 0f) {
            coins.trigger(level)
            coinCooldown = 1.2f
        }
        wasPresent = c.targetPresent
        coinCooldown -= n * dt

        val masterTarget = if (s.muted) 0f else s.volume.coerceIn(0f, 1f)
        val smooth = 1f - exp(-dt / 0.010f)      // 10 ms voor toonhoogte/amplitude
        val beepEdge = 0.15f                       // fractie van de pieppriode voor flanken

        for (i in 0 until n) {
            curHz += (targetHz - curHz) * smooth
            curAmp += (targetAmp - curAmp) * smooth
            master += (masterTarget - master) * smooth

            phase += 2.0 * PI * curHz * dt
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            // iets rijker dan een pure sinus, goed hoorbaar op een telefoonspeaker
            var tone = (sin(phase) * 0.8 + sin(2 * phase) * 0.15 + sin(3 * phase) * 0.05).toFloat()

            if (s.mode == ToneMode.BEEP) {
                var gate = 0f
                if (beepRate > 0f) {
                    beepPhase += beepRate * dt
                    if (beepPhase >= 1f) beepPhase -= 1f
                    // piep in de eerste helft van de periode, met zachte flanken
                    val p = beepPhase / 0.5f
                    gate = when {
                        p >= 1f -> 0f
                        p < beepEdge -> p / beepEdge
                        p > 1f - beepEdge -> (1f - p) / beepEdge
                        else -> 1f
                    }
                } else beepPhase = 0f
                tone *= gate
            }

            var mix = tone * curAmp * 0.6f
            if (fun_) {
                val duck = 1f - 0.6f * coins.envelope
                mix += whale.next(c.active, level) * 0.55f * duck
                mix += coins.next() * 0.7f
            }
            val v = (mix * master).coerceIn(-1f, 1f)
            out[i] = (v * 32000f).toInt().toShort()
        }
    }
}

/**
 * Synthetisch "walvisgezang": lange, glijdende tonen met vibrato, boventonen,
 * een trage galm (feedback-delay) en zacht zeeruis-bed.
 */
internal class WhaleSynth(private val sr: Int, private val rnd: Random) {
    private val dt = 1f / sr
    private var phase = 0.0
    private var hz = 220f
    private var fromHz = 220f
    private var toHz = 300f
    private var noteT = 0f
    private var noteLen = 3f
    private var gapLen = 1f
    private var vibPhase = 0.0
    private var amp = 0f
    private var fade = 0f
    private val delay = FloatArray((sr * 0.37f).toInt())
    private val delay2 = FloatArray((sr * 0.53f).toInt())
    private var di = 0
    private var di2 = 0
    private var noiseLp = 0f
    private var noiseLp2 = 0f

    init { newNote() }

    private fun newNote() {
        fromHz = toHz
        // walvissen zingen grofweg 100..800 Hz; kies een glijdende frase
        toHz = 120f + rnd.nextFloat() * 520f
        noteLen = 1.6f + rnd.nextFloat() * 3.2f
        gapLen = 0.3f + rnd.nextFloat() * 1.6f
        noteT = 0f
    }

    fun next(active: Boolean, level: Float): Float {
        fade += ((if (active) 1f else 0f) - fade) * (dt / 0.8f)
        noteT += dt
        val inNote = noteT < noteLen
        if (noteT > noteLen + gapLen) newNote()

        val x = (noteT / noteLen).coerceIn(0f, 1f)
        // S-curve glide met een "kreun" omhoog in het midden
        val glide = x * x * (3f - 2f * x)
        hz = fromHz + (toHz - fromHz) * glide + 40f * sin(PI * x).toFloat()
        hz *= 1f + 0.25f * level           // walvis "reageert" op het signaal
        vibPhase += 2.0 * PI * 5.5 * dt
        val f = hz * (1f + 0.012f * sin(vibPhase).toFloat())
        phase += 2.0 * PI * f * dt
        if (phase > 2.0 * PI) phase -= 2.0 * PI

        val envTarget = if (inNote) sin(PI * x).toFloat().pow(0.6f) else 0f
        amp += (envTarget - amp) * (dt / 0.05f)

        var v = (sin(phase) * 0.7 + sin(2 * phase + 0.3) * 0.2 + sin(3.01 * phase) * 0.1).toFloat() * amp

        // zeeruis: dubbel laagdoorlaat-gefilterde ruis
        val white = rnd.nextFloat() * 2f - 1f
        noiseLp += (white - noiseLp) * 0.02f
        noiseLp2 += (noiseLp - noiseLp2) * 0.02f
        v += noiseLp2 * 1.2f

        // twee feedback-delays = onderwater-galm
        val d1 = delay[di]; val d2 = delay2[di2]
        delay[di] = v + d2 * 0.45f
        delay2[di2] = v + d1 * 0.4f
        di = (di + 1) % delay.size
        di2 = (di2 + 1) % delay2.size
        return (v * 0.6f + (d1 + d2) * 0.35f) * fade
    }
}

/** Rinkelende muntjes: korte metalen "tinks" met inharmonische boventonen. */
internal class CoinSynth(private val sr: Int, private val rnd: Random) {
    private class Partial(var freq: Float, var amp: Float, var decay: Float, var phase: Double = 0.0, var delaySamples: Int)

    private val partials = ArrayList<Partial>()
    var envelope = 0f; private set

    fun trigger(strength: Float) {
        val clinks = 3 + rnd.nextInt(4) + (strength * 3).toInt()
        var t = 0f
        repeat(clinks) {
            val base = 1900f + rnd.nextFloat() * 2600f
            val a = 0.35f + rnd.nextFloat() * 0.45f
            // verhoudingen typisch voor een vrij trillende metalen schijf
            for ((r, rel) in listOf(1f to 1f, 2.32f to 0.6f, 3.86f to 0.4f, 5.24f to 0.25f, 6.8f to 0.15f)) {
                partials += Partial(
                    freq = base * r * (1f + (rnd.nextFloat() - 0.5f) * 0.01f),
                    amp = a * rel / 2.2f,
                    decay = 0.04f + rnd.nextFloat() * 0.18f / r,
                    delaySamples = (t * sr).toInt(),
                )
            }
            t += 0.04f + rnd.nextFloat() * 0.13f
        }
        if (partials.size > 60) partials.subList(0, partials.size - 60).clear()
    }

    fun next(): Float {
        if (partials.isEmpty()) { envelope *= 0.999f; return 0f }
        var v = 0f
        var env = 0f
        val it = partials.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.delaySamples > 0) { p.delaySamples--; continue }
            p.phase += 2.0 * PI * p.freq / sr
            v += sin(p.phase).toFloat() * p.amp
            env += p.amp
            p.amp *= exp(-1f / (p.decay * sr))
            if (p.amp < 1e-4f) it.remove()
        }
        envelope = min(1f, env * 2f)
        return v
    }
}
