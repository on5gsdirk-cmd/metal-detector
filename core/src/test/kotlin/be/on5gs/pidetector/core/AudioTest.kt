package be.on5gs.pidetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioTest {
    private val s = AudioSettings(minHz = 200f, maxHz = 2000f, beepThreshold = 0.1f)

    @Test fun vcoRange() {
        assertEquals(200f, ToneMapping.vcoHz(0f, s), 0.01f)
        assertEquals(2000f, ToneMapping.vcoHz(1f, s), 0.01f)
        assertEquals(2000f, ToneMapping.vcoHz(5f, s), 0.01f)
        assertTrue(ToneMapping.vcoHz(0.5f, s) in 200f..2000f)
    }

    @Test fun targetPitchLowForSlow() {
        assertEquals(2000f, ToneMapping.targetHz(0f, s), 0.01f)   // snel/klein -> hoog
        assertEquals(200f, ToneMapping.targetHz(1f, s), 0.01f)    // traag/groot -> laag
    }

    @Test fun beepRate() {
        assertEquals(0f, ToneMapping.beepRate(0.05f, s), 0f)
        assertEquals(2f, ToneMapping.beepRate(0.1f, s), 0.01f)
        assertEquals(20f, ToneMapping.beepRate(1f, s), 0.01f)
    }

    /** Geen klikken: tussen opeenvolgende samples geen grote sprongen, ook niet bij een toonwissel. */
    @Test fun phaseContinuous() {
        val g = SoundGenerator(44100)
        g.settings = AudioSettings(volume = 1f)
        g.control = SoundControl(active = true, level = 0.1f)
        val a = ShortArray(4410); g.render(a)
        g.control = SoundControl(active = true, level = 0.9f)
        val b = ShortArray(4410); g.render(b)
        val all = a + b
        var maxStep = 0
        for (i in 1 until all.size) maxStep = maxOf(maxStep, abs(all[i] - all[i - 1]))
        // 2 kHz bij volle schaal geeft max ~ 2*pi*2000/44100*32000 ≈ 9100 per sample
        assertTrue("sprong $maxStep", maxStep < 12000)
        assertTrue(all.any { abs(it.toInt()) > 1000 })
    }

    @Test fun mutedIsSilentAfterRamp() {
        val g = SoundGenerator(44100)
        g.settings = AudioSettings(muted = true)
        g.control = SoundControl(active = true, level = 1f)
        val buf = ShortArray(44100); g.render(buf)
        assertTrue(buf.drop(4410).all { it.toInt() == 0 })
    }

    @Test fun funModeMakesSoundAndCoinsOnTarget() {
        val g = SoundGenerator(44100)
        g.settings = AudioSettings(volume = 1f)
        g.control = SoundControl(active = true, funMode = true)
        val quiet = ShortArray(44100 * 3); g.render(quiet)
        assertTrue("walvis hoorbaar", quiet.any { abs(it.toInt()) > 500 })
        g.control = SoundControl(active = true, level = 0.8f, targetPresent = true, funMode = true)
        val coin = ShortArray(8820); g.render(coin)
        assertTrue(coin.any { abs(it.toInt()) > 2000 })
    }

    @Test fun simulatorProducesTargets() {
        val sim = Simulator(1)
        val sp = SignalProcessor(ProcessorSettings(sensitivity = 8))
        sp.startBalance()
        var sawTarget = false
        var t = 0L
        while (t < 20000) {
            val d = sim.sample(t)
            val r = sp.process(d.s1, d.s2, t)
            if (r.targetPresent) sawTarget = true
            t += 36
        }
        assertTrue(sawTarget)
    }
}
