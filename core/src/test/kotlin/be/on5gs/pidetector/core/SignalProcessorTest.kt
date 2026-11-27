package be.on5gs.pidetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalProcessorTest {

    private fun balanced(settings: ProcessorSettings = ProcessorSettings()): SignalProcessor {
        val sp = SignalProcessor(settings)
        sp.startBalance()
        var t = 0L
        while (!sp.isBalanced) { sp.process(1001, 500, t); t += 40 }
        return sp
    }

    @Test fun balanceAveragesOneSecond() {
        val sp = SignalProcessor()
        sp.startBalance()
        var t = 0L
        var r: Reading
        do { r = sp.process(1000 + (t / 40 % 3).toInt(), 500, t); t += 40 } while (!r.balanced)
        assertEquals(1001f, sp.zero1!!, 0.5f)
        assertEquals(500f, sp.zero2!!, 0.01f)
        assertTrue(t >= 1000)
    }

    @Test fun progressDuringBalance() {
        val sp = SignalProcessor()
        sp.startBalance()
        sp.process(100, 100, 0)
        val r = sp.process(100, 100, 500)
        assertEquals(0.5f, r.balanceProgress!!, 0.01f)
        assertFalse(r.balanced)
    }

    @Test fun signalAfterBalanceAndLevel() {
        val sp = balanced(ProcessorSettings(sensitivity = 10))
        val r = sp.process(1041, 520, 2000)
        assertEquals(40f, r.d1, 0.6f)
        assertEquals(20f, r.d2, 0.01f)
        assertEquals(1f, r.level, 0.02f)       // 40 tellen = volle schaal bij gevoeligheid 10
        assertTrue(r.targetPresent)
        assertEquals(0.5f, r.ratio!!, 0.02f)
    }

    @Test fun polarity() {
        val abs = balanced(ProcessorSettings(polarity = Polarity.ABSOLUTE))
        assertEquals(30f, abs.process(971, 500, 2000).d1, 0.6f)
        val norm = balanced(ProcessorSettings(polarity = Polarity.NORMAL))
        assertEquals(-30f, norm.process(971, 500, 2000).d1, 0.6f)
        assertEquals(0f, norm.process(971, 500, 2040).level, 0f)
        val inv = balanced(ProcessorSettings(polarity = Polarity.INVERTED))
        assertEquals(30f, inv.process(971, 500, 2000).d1, 0.6f)
    }

    @Test fun ratioNeedsSignal() {
        assertNull(SignalProcessor.ratioOf(3f, 1f))
        assertNull(SignalProcessor.ratioOf(50f, 10f, present = false))
        assertEquals(0.2f, SignalProcessor.ratioOf(50f, 10f)!!, 1e-4f)
        assertEquals(1f, SignalProcessor.ratioOf(50f, 80f)!!, 0f)   // geklemd
        assertEquals(0f, SignalProcessor.ratioOf(50f, -5f)!!, 0f)
    }

    @Test fun autoBalanceFollowsDrift() {
        val sp = balanced(ProcessorSettings(autoBalance = true, autoBalanceTauS = 2f))
        var t = 2000L
        repeat(400) { sp.process(1100, 550, t); t += 40 }   // 16 s ~ 8 tijdconstanten
        assertEquals(1100f, sp.zero1!!, 1f)
        assertEquals(550f, sp.zero2!!, 1f)
    }

    @Test fun noAutoBalanceKeepsZero() {
        val sp = balanced()
        var t = 2000L
        repeat(100) { sp.process(1100, 550, t); t += 40 }
        assertEquals(1001f, sp.zero1!!, 0.5f)
    }

    @Test fun unbalancedGivesZero() {
        val r = SignalProcessor().process(2000, 1000, 0)
        assertFalse(r.balanced)
        assertEquals(0f, r.level, 0f)
    }

    @Test fun learnAndMatch() {
        val sp = balanced(ProcessorSettings(sensitivity = 10))
        val learn = LearnSession()
        var t = 2000L
        for (a in listOf(0, 10, 40, 90, 120, 90, 40, 10, 0)) {
            learn.add(sp.process(1001 + a, 500 + (a * 0.3f).toInt(), t)); t += 40
        }
        val target = learn.result("x", "koperen munt", null)
        assertNotNull(target)
        assertEquals(120f, target!!.peak, 1f)
        assertEquals(0.3f, target.ratio, 0.02f)

        val foil = LearnedTarget("f", "folie", 80f, 0.1f, 5)
        val m = TargetMatcher.best(listOf(foil, target), ratio = 0.31f, peak = 100f)!!
        assertEquals("koperen munt", m.target.name)
        assertTrue(m.percent > 85)
        assertNull(TargetMatcher.best(listOf(foil), ratio = 0.9f, peak = 100f))
        assertNull(TargetMatcher.best(listOf(foil), ratio = null, peak = 100f))
    }

    @Test fun learnNeedsEnoughSamples() {
        val l = LearnSession()
        assertNull(l.result("a", "b", null))
    }
}
