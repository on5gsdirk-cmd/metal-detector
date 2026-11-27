package be.on5gs.pidetector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.on5gs.pidetector.ConnState
import be.on5gs.pidetector.DetectorInfo
import be.on5gs.pidetector.R
import be.on5gs.pidetector.core.AudioSettings
import be.on5gs.pidetector.core.DetectorParams
import be.on5gs.pidetector.core.Polarity
import be.on5gs.pidetector.core.ToneMode
import be.on5gs.pidetector.data.AppPrefs
import be.on5gs.pidetector.data.AppTheme
import be.on5gs.pidetector.ui.theme.LocalNeon
import kotlin.math.roundToInt

@Composable
fun DetectorSettingsScreen(
    prefs: AppPrefs,
    conn: ConnState,
    detector: DetectorInfo,
    onApply: (DetectorParams) -> Unit,
    onSave: () -> Unit,
    onDefaults: () -> Unit,
    onSaveAsProfile: (String, DetectorParams) -> Unit,
) {
    val neon = LocalNeon.current
    var draft by remember { mutableStateOf(prefs.params) }
    // als de detector nieuwe waarden meldt (CFG), het concept bijwerken
    LaunchedEffect(prefs.params) { draft = prefs.params }
    var confirmDefaults by remember { mutableStateOf(false) }
    var askName by remember { mutableStateOf(false) }
    val connected = conn == ConnState.CONNECTED

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NeonCard {
            SectionTitle(stringResource(R.string.detector_settings))
            val fw = detector.firmware
            Hint(
                when {
                    conn == ConnState.SIMULATION -> stringResource(R.string.settings_sim_note)
                    !connected -> stringResource(R.string.settings_offline_note)
                    fw != null -> stringResource(R.string.firmware_version, fw, detector.name ?: "")
                    else -> stringResource(R.string.firmware_unknown)
                }
            )
            detector.info["t1_us"]?.let { t1 ->
                Hint(stringResource(R.string.measured_timing, detector.info["pw_us"] ?: "?", t1, detector.info["t2_us"] ?: "?", detector.info["adc_us"] ?: "?"))
            }
        }
        NeonCard {
            IntParam(stringResource(R.string.p_pw), "µs", draft.pw, DetectorParams.PW, stringResource(R.string.p_pw_hint)) {
                draft = draft.copy(pw = it)
            }
            IntParam(stringResource(R.string.p_d1), "µs", draft.d1, DetectorParams.D1, stringResource(R.string.p_d1_hint)) {
                draft = draft.copy(d1 = it, d2 = maxOf(draft.d2, it + DetectorParams.D2_GAP))
            }
            IntParam(stringResource(R.string.p_d2), "µs", draft.d2, (draft.d1 + DetectorParams.D2_GAP)..DetectorParams.D2_MAX,
                stringResource(R.string.p_d2_hint)) { draft = draft.copy(d2 = it) }
            IntParam(stringResource(R.string.p_f), "Hz", draft.f, DetectorParams.F, stringResource(R.string.p_f_hint)) {
                draft = draft.copy(f = it)
            }
            IntParam(stringResource(R.string.p_avg), "×", draft.avg, DetectorParams.AVG, stringResource(R.string.p_avg_hint)) {
                draft = draft.copy(avg = it)
            }
            FloatParam(
                stringResource(R.string.p_vcal), "%.3f".format(draft.vcal), draft.vcal,
                DetectorParams.VCAL_MIN..DetectorParams.VCAL_MAX, stringResource(R.string.p_vcal_hint), steps = 79,
            ) { draft = draft.copy(vcal = (it * 200).roundToInt() / 200f) }
        }
        NeonCard {
            Button(
                onClick = { onApply(draft) }, modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = neon.accent),
            ) { Text(stringResource(R.string.apply), fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSave, enabled = connected, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.save_to_detector), fontSize = 13.sp)
                }
                OutlinedButton(onClick = { confirmDefaults = true }, enabled = connected, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.factory_defaults), fontSize = 13.sp)
                }
            }
            OutlinedButton(onClick = { askName = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.save_as_profile))
            }
            Hint(stringResource(R.string.apply_hint))
        }
        NeonCard {
            SectionTitle(stringResource(R.string.hw_tips_title))
            Hint(stringResource(R.string.hw_tips))
        }
    }

    if (confirmDefaults) {
        AlertDialog(
            onDismissRequest = { confirmDefaults = false },
            title = { Text(stringResource(R.string.factory_defaults)) },
            text = { Text(stringResource(R.string.factory_defaults_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDefaults = false; onDefaults() }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { confirmDefaults = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (askName) {
        NameDialog(stringResource(R.string.save_as_profile), "", onDismiss = { askName = false }) {
            askName = false; onSaveAsProfile(it, draft)
        }
    }
}

@Composable
fun SoundDisplayScreen(prefs: AppPrefs, onUpdate: ((AppPrefs) -> AppPrefs) -> Unit) {
    val neon = LocalNeon.current
    val a = prefs.audio
    fun audio(t: (AudioSettings) -> AudioSettings) = onUpdate { it.copy(audio = t(it.audio)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- weergave / thema ----
        NeonCard {
            SectionTitle(stringResource(R.string.display))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip(stringResource(R.string.theme_standard), prefs.theme == AppTheme.STANDARD) {
                    onUpdate { it.copy(theme = AppTheme.STANDARD) }
                }
                ThemeChip(stringResource(R.string.theme_coin_hunters), prefs.theme == AppTheme.COIN_HUNTERS) {
                    onUpdate { it.copy(theme = AppTheme.COIN_HUNTERS) }
                }
            }
            Hint(stringResource(if (prefs.theme == AppTheme.COIN_HUNTERS) R.string.theme_coin_hunters_hint else R.string.theme_standard_hint))
            if (prefs.theme == AppTheme.COIN_HUNTERS) {
                SwitchRow(stringResource(R.string.fun_keep_tone), a.funKeepDetectorTone, stringResource(R.string.fun_keep_tone_hint)) { v ->
                    audio { it.copy(funKeepDetectorTone = v) }
                }
            }
            SwitchRow(stringResource(R.string.show_graph), prefs.showGraph) { v -> onUpdate { it.copy(showGraph = v) } }
        }

        // ---- geluid ----
        NeonCard {
            SectionTitle(stringResource(R.string.sound))
            SwitchRow(stringResource(R.string.mute), a.muted) { v -> audio { it.copy(muted = v) } }
            FloatParam(stringResource(R.string.volume), "${(a.volume * 100).roundToInt()} %", a.volume, 0f..1f) { v ->
                audio { it.copy(volume = v) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip(stringResource(R.string.mode_vco), a.mode == ToneMode.VCO) { audio { it.copy(mode = ToneMode.VCO) } }
                ThemeChip(stringResource(R.string.mode_beep), a.mode == ToneMode.BEEP) { audio { it.copy(mode = ToneMode.BEEP) } }
            }
            Hint(stringResource(if (a.mode == ToneMode.VCO) R.string.mode_vco_hint else R.string.mode_beep_hint))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.pitch_range, a.minHz.roundToInt(), a.maxHz.roundToInt()), color = neon.text, fontWeight = FontWeight.SemiBold)
            RangeSlider(
                value = a.minHz..a.maxHz,
                onValueChange = { r -> audio { it.copy(minHz = (r.start / 10).roundToInt() * 10f, maxHz = maxOf(r.endInclusive, r.start + 100f).let { e -> (e / 10).roundToInt() * 10f }) } },
                valueRange = 50f..4000f,
                colors = SliderDefaults.colors(thumbColor = neon.accent, activeTrackColor = neon.accent),
            )
            if (a.mode == ToneMode.VCO) {
                SwitchRow(stringResource(R.string.threshold_hum), a.thresholdHum, stringResource(R.string.threshold_hum_hint)) { v ->
                    audio { it.copy(thresholdHum = v) }
                }
            } else {
                FloatParam(stringResource(R.string.beep_threshold), "${(a.beepThreshold * 100).roundToInt()} %", a.beepThreshold, 0.02f..0.8f) { v ->
                    audio { it.copy(beepThreshold = v) }
                }
            }
            SwitchRow(stringResource(R.string.pitch_from_target), a.pitchFromTarget, stringResource(R.string.pitch_from_target_hint)) { v ->
                audio { it.copy(pitchFromTarget = v) }
            }
        }

        // ---- signaalverwerking ----
        NeonCard {
            val p = prefs.processor
            SectionTitle(stringResource(R.string.processing))
            FloatParam(stringResource(R.string.detect_threshold), "${(p.threshold * 100).roundToInt()} %", p.threshold, 0.02f..0.5f,
                stringResource(R.string.detect_threshold_hint)) { v -> onUpdate { it.copy(processor = it.processor.copy(threshold = v)) } }
            SwitchRow(stringResource(R.string.auto_balance), p.autoBalance, stringResource(R.string.auto_balance_hint)) { v ->
                onUpdate { it.copy(processor = it.processor.copy(autoBalance = v)) }
            }
            if (p.autoBalance) {
                FloatParam(stringResource(R.string.auto_balance_speed), "%.0f s".format(p.autoBalanceTauS), p.autoBalanceTauS, 1f..30f,
                    stringResource(R.string.auto_balance_speed_hint)) { v -> onUpdate { it.copy(processor = it.processor.copy(autoBalanceTauS = v)) } }
            }
            Text(stringResource(R.string.polarity), color = neon.text, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((pol, label) in listOf(
                    Polarity.ABSOLUTE to stringResource(R.string.polarity_abs),
                    Polarity.NORMAL to stringResource(R.string.polarity_normal),
                    Polarity.INVERTED to stringResource(R.string.polarity_inverted),
                )) ThemeChip(label, p.polarity == pol) { onUpdate { it.copy(processor = it.processor.copy(polarity = pol)) } }
            }
            Hint(stringResource(R.string.polarity_hint))
        }
    }
}

@Composable
fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = neon.accent, selectedLabelColor = neon.text),
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                label = { Text(stringResource(R.string.name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
