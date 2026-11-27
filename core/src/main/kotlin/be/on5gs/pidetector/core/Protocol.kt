package be.on5gs.pidetector.core

/**
 * Tekstprotocol van firmware Spirit PI v3 (zie firmware/spirit_pi_v3/pi_protocol.h).
 *
 * ESP -> app:  D,<s1>,<s2>,<vbat>   CFG,k=v,...   INFO,k=v,...   OK[,..]   ERR,<reden>
 * Oud formaat (N.E.C.O-firmware 2.x of legacy=1):  <signaal/freq/duty/volt>
 */
sealed interface Message {
    /** Ruwe 12-bit ADC-waarden (gemiddelde over avg pulsen) en batterijspanning. */
    data class Data(val s1: Int, val s2: Int, val vbat: Float) : Message

    data class Config(val values: Map<String, String>) : Message {
        fun int(key: String): Int? = values[key]?.toIntOrNull()
        fun float(key: String): Float? = values[key]?.toFloatOrNull()
        val firmware: String? get() = values["fw"]
        fun toParams(): DetectorParams? {
            val p = DetectorParams(
                pw = int("pw") ?: return null,
                d1 = int("d1") ?: return null,
                d2 = int("d2") ?: return null,
                f = int("f") ?: return null,
                avg = int("avg") ?: DetectorParams.DEFAULT.avg,
                vcal = float("vcal") ?: DetectorParams.DEFAULT.vcal,
            )
            return p
        }
    }

    data class Info(val values: Map<String, String>) : Message
    data class Ok(val detail: String? = null) : Message
    data class Error(val reason: String) : Message

    /** Oud formaat: signaal = late sample / 10, duty = pw / 10. */
    data class Legacy(val signal: Int, val freq: Int, val duty: Int, val volt: Float) : Message
}

object Protocol {

    /** Parseert één ontvangen regel; geeft null bij onbekende of beschadigde regels. */
    fun parse(rawLine: String): Message? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        if (line.startsWith("<")) return parseLegacy(line)
        val parts = line.split(',')
        return when (parts[0]) {
            "D" -> {
                if (parts.size < 4) return null
                val s1 = parts[1].trim().toIntOrNull() ?: return null
                val s2 = parts[2].trim().toIntOrNull() ?: return null
                val v = parts[3].trim().toFloatOrNull() ?: return null
                if (s1 !in 0..4095 || s2 !in 0..4095) return null
                Message.Data(s1, s2, v)
            }
            "CFG" -> Message.Config(keyValues(parts))
            "INFO" -> Message.Info(keyValues(parts))
            "OK" -> Message.Ok(parts.getOrNull(1))
            "ERR" -> Message.Error(parts.drop(1).joinToString(",").ifEmpty { "?" })
            else -> null
        }
    }

    private fun keyValues(parts: List<String>): Map<String, String> =
        parts.drop(1).mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
        }.toMap()

    private fun parseLegacy(line: String): Message? {
        if (!line.endsWith(">")) return null
        val p = line.substring(1, line.length - 1).split('/')
        if (p.size != 4) return null
        return Message.Legacy(
            signal = p[0].trim().toIntOrNull() ?: return null,
            freq = p[1].trim().toIntOrNull() ?: return null,
            duty = p[2].trim().toIntOrNull() ?: return null,
            volt = p[3].trim().toFloatOrNull() ?: return null,
        )
    }

    // ---- commando's app -> ESP ----
    const val GET = "GET"
    const val SAVE = "SAVE"
    const val DEFAULTS = "DEFAULTS"
    const val PING = "PING"

    fun set(key: String, value: Number): String = when (value) {
        is Float, is Double -> "SET $key ${"%.3f".format(java.util.Locale.ROOT, value.toDouble())}"
        else -> "SET $key $value"
    }

    /**
     * SET-commando's voor een volledige parameterset. d1 gaat vóór d2: de firmware
     * schuift d2 zelf mee omhoog als d1 te groot wordt, zodat de volgorde altijd lukt.
     */
    fun setAll(p: DetectorParams): List<String> = listOf(
        set("pw", p.pw), set("d1", p.d1), set("d2", p.d2),
        set("f", p.f), set("avg", p.avg), set("vcal", p.vcal),
    )
}

/** Splitst een bytestroom in regels (tolerant voor \r\n en onvolledige stukken). */
class LineSplitter(private val maxLen: Int = 256) {
    private val sb = StringBuilder()

    fun feed(chunk: String): List<String> {
        val out = ArrayList<String>(2)
        for (c in chunk) {
            when (c) {
                '\n' -> { out += sb.toString().trimEnd('\r'); sb.setLength(0) }
                else -> if (sb.length < maxLen) sb.append(c) else sb.setLength(0)
            }
            // Oud formaat eindigt soms niet netjes op \n: sluit af op '>'
            if (c == '>' && sb.startsWith("<")) { out += sb.toString(); sb.setLength(0) }
        }
        return out.filter { it.isNotBlank() }
    }
}
