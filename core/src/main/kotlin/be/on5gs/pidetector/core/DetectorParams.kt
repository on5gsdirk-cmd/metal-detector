package be.on5gs.pidetector.core

import kotlinx.serialization.Serializable

/** Meetparameters van de detector; grenzen gelijk aan de firmware. */
@Serializable
data class DetectorParams(
    val pw: Int = 130,
    val d1: Int = 20,
    val d2: Int = 60,
    val f: Int = 60,
    val avg: Int = 4,
    val vcal: Float = 1.0f,
) {
    /** Brengt alle waarden binnen de firmwaregrenzen (incl. d2 >= d1 + 10). */
    fun sanitized(): DetectorParams {
        val nd1 = d1.coerceIn(D1)
        return copy(
            pw = pw.coerceIn(PW),
            d1 = nd1,
            d2 = d2.coerceIn(nd1 + D2_GAP, D2_MAX),
            f = f.coerceIn(F),
            avg = avg.coerceIn(AVG),
            vcal = vcal.coerceIn(VCAL_MIN, VCAL_MAX),
        )
    }

    companion object {
        val PW = 20..300
        val D1 = 8..150
        const val D2_GAP = 10
        const val D2_MAX = 300
        val F = 20..200
        val AVG = 1..32
        const val VCAL_MIN = 0.8f
        const val VCAL_MAX = 1.2f
        val DEFAULT = DetectorParams()
    }
}

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val params: DetectorParams,
    val builtIn: Boolean = false,
)

object BuiltInProfiles {
    val SMALL_GOLD = Profile("builtin_small", "Klein/goud", DetectorParams(pw = 80, d1 = 15, d2 = 40, f = 100), true)
    val UNIVERSAL = Profile("builtin_universal", "Universeel", DetectorParams(pw = 130, d1 = 20, d2 = 60, f = 60), true)
    val LARGE_DEEP = Profile("builtin_large", "Groot/diep", DetectorParams(pw = 220, d1 = 40, d2 = 120, f = 40), true)
    val ALL = listOf(SMALL_GOLD, UNIVERSAL, LARGE_DEEP)
}
