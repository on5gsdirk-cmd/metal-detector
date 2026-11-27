package be.on5gs.pidetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test fun parsesData() {
        assertEquals(Message.Data(1234, 567, 12.35f), Protocol.parse("D,1234,567,12.35"))
        assertEquals(Message.Data(1, 2, 0f), Protocol.parse("  D,1,2,0.00\r"))
    }

    @Test fun rejectsBrokenData() {
        assertNull(Protocol.parse("D,12"))
        assertNull(Protocol.parse("D,a,2,3"))
        assertNull(Protocol.parse("D,5000,2,3"))
        assertNull(Protocol.parse("garbage"))
        assertNull(Protocol.parse(""))
    }

    @Test fun parsesConfig() {
        val m = Protocol.parse("CFG,pw=130,d1=20,d2=60,f=60,avg=4,vcal=1.000,lowbat=9.5,legacy=0,name=ESP32_Spirit_PI-3,fw=3.0")
        assertTrue(m is Message.Config)
        m as Message.Config
        assertEquals("3.0", m.firmware)
        assertEquals(DetectorParams(130, 20, 60, 60, 4, 1.0f), m.toParams())
        assertEquals("ESP32_Spirit_PI-3", m.values["name"])
    }

    @Test fun parsesStatus() {
        assertEquals(Message.Ok(null), Protocol.parse("OK"))
        assertEquals(Message.Ok("reboot"), Protocol.parse("OK,reboot"))
        assertEquals(Message.Error("lowbat"), Protocol.parse("ERR,lowbat"))
        val info = Protocol.parse("INFO,adc_us=9.8,pw_us=130.2,t1_us=20.1,t2_us=60.0") as Message.Info
        assertEquals("9.8", info.values["adc_us"])
    }

    @Test fun parsesLegacy() {
        assertEquals(Message.Legacy(56, 60, 13, 12.0f), Protocol.parse("<56/60/13/12.00>"))
        assertNull(Protocol.parse("<56/60/13>"))
        assertNull(Protocol.parse("<56/60/13/12.00"))
    }

    @Test fun buildsCommands() {
        assertEquals("SET pw 80", Protocol.set("pw", 80))
        assertEquals("SET vcal 1.020", Protocol.set("vcal", 1.02f))
        val all = Protocol.setAll(DetectorParams(80, 15, 40, 100, 4, 1f))
        assertEquals(listOf("SET pw 80", "SET d1 15", "SET d2 40", "SET f 100", "SET avg 4", "SET vcal 1.000"), all)
    }

    @Test fun splitsLines() {
        val sp = LineSplitter()
        assertEquals(emptyList<String>(), sp.feed("D,1,2,"))
        assertEquals(listOf("D,1,2,3.0", "OK"), sp.feed("3.0\r\nOK\nCF"))
        assertEquals(listOf("CFG,pw=1"), sp.feed("G,pw=1\n"))
        assertEquals(listOf("<1/2/3/4.0>"), sp.feed("<1/2/3/4.0>\r\n"))
    }

    @Test fun sanitizesParams() {
        val p = DetectorParams(pw = 5, d1 = 200, d2 = 10, f = 0, avg = 99, vcal = 3f).sanitized()
        assertEquals(DetectorParams(20, 150, 160, 20, 32, 1.2f), p)
    }
}
