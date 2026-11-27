package be.on5gs.pidetector.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import be.on5gs.pidetector.core.AudioSettings
import be.on5gs.pidetector.core.BuiltInProfiles
import be.on5gs.pidetector.core.DetectorParams
import be.on5gs.pidetector.core.LearnedTarget
import be.on5gs.pidetector.core.ProcessorSettings
import be.on5gs.pidetector.core.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class AppTheme { STANDARD, COIN_HUNTERS }

/** Alles wat de app onthoudt, als één JSON-document in DataStore. */
@Serializable
data class AppPrefs(
    val theme: AppTheme = AppTheme.STANDARD,
    val processor: ProcessorSettings = ProcessorSettings(),
    val audio: AudioSettings = AudioSettings(),
    val showGraph: Boolean = true,
    val simulation: Boolean = false,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    /** Laatst gebruikte parameters (ook bruikbaar in simulatie). */
    val params: DetectorParams = DetectorParams.DEFAULT,
    val profiles: List<Profile> = BuiltInProfiles.ALL,
    val learned: List<LearnedTarget> = emptyList(),
)

private val Context.dataStore by preferencesDataStore(name = "pidetector")

class AppStore(private val context: Context) {
    private val key = stringPreferencesKey("prefs_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val prefs: Flow<AppPrefs> = context.dataStore.data.map { p ->
        p[key]?.let { runCatching { json.decodeFromString(AppPrefs.serializer(), it) }.getOrNull() } ?: AppPrefs()
    }

    suspend fun load(): AppPrefs = prefs.first()

    suspend fun update(transform: (AppPrefs) -> AppPrefs) {
        context.dataStore.edit { p ->
            val cur = p[key]?.let { runCatching { json.decodeFromString(AppPrefs.serializer(), it) }.getOrNull() } ?: AppPrefs()
            p[key] = json.encodeToString(AppPrefs.serializer(), transform(cur))
        }
    }
}
