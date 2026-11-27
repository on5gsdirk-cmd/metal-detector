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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.on5gs.pidetector.LearnState
import be.on5gs.pidetector.LiveState
import be.on5gs.pidetector.R
import be.on5gs.pidetector.core.BuiltInProfiles
import be.on5gs.pidetector.core.DetectorParams
import be.on5gs.pidetector.core.LearnedTarget
import be.on5gs.pidetector.core.Profile
import be.on5gs.pidetector.data.AppPrefs
import be.on5gs.pidetector.ui.theme.LocalNeon
import kotlin.math.roundToInt

/** Ingebouwde profielen tonen we vertaald zolang de gebruiker ze niet hernoemde. */
@Composable
fun profileLabel(p: Profile): String {
    val orig = BuiltInProfiles.ALL.firstOrNull { it.id == p.id }
    if (orig == null || orig.name != p.name) return p.name
    return when (p.id) {
        BuiltInProfiles.SMALL_GOLD.id -> stringResource(R.string.profile_small)
        BuiltInProfiles.UNIVERSAL.id -> stringResource(R.string.profile_universal)
        BuiltInProfiles.LARGE_DEEP.id -> stringResource(R.string.profile_large)
        else -> p.name
    }
}

@Composable
fun ProfilesScreen(
    prefs: AppPrefs,
    live: LiveState,
    learn: LearnState?,
    onLoad: (Profile) -> Unit,
    onUpdate: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
    onRestore: (Profile) -> Unit,
    onStartLearn: (String) -> Unit,
    onFinishLearn: () -> Unit,
    onCancelLearn: () -> Unit,
    onDeleteLearned: (String) -> Unit,
) {
    val neon = LocalNeon.current
    var editing by remember { mutableStateOf<Profile?>(null) }
    var askLearnName by remember { mutableStateOf(false) }
    val cur = prefs.params

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NeonCard {
            SectionTitle(stringResource(R.string.profiles))
            Hint(stringResource(R.string.profiles_hint))
            prefs.profiles.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = neon.cardBorder.copy(alpha = 0.3f))
                val active = p.params.pw == cur.pw && p.params.d1 == cur.d1 && p.params.d2 == cur.d2 && p.params.f == cur.f
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(profileLabel(p), color = if (active) neon.accent else neon.text, fontWeight = FontWeight.Bold)
                        Hint("pw ${p.params.pw} · d1 ${p.params.d1} · d2 ${p.params.d2} · f ${p.params.f}")
                    }
                    IconButton(onClick = { editing = p }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit), tint = neon.textDim) }
                    if (p.builtIn) {
                        IconButton(onClick = { onRestore(p) }) { Icon(Icons.Filled.Restore, stringResource(R.string.restore), tint = neon.textDim) }
                    } else {
                        IconButton(onClick = { onDelete(p) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = neon.textDim) }
                    }
                    Button(onClick = { onLoad(p) }, colors = ButtonDefaults.buttonColors(containerColor = neon.accent)) {
                        Text(stringResource(R.string.load), fontSize = 13.sp)
                    }
                }
            }
        }

        // ---- leermodus ----
        NeonCard {
            SectionTitle(stringResource(R.string.learn_mode))
            if (learn == null) {
                Hint(stringResource(R.string.learn_hint))
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { askLearnName = true },
                    enabled = live.reading?.balanced == true && !live.legacy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = neon.accent),
                ) { Text(stringResource(R.string.learn_object)) }
                if (live.legacy) Hint(stringResource(R.string.target_legacy))
            } else {
                Text(stringResource(R.string.learning_now, learn.name), color = neon.accent2, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Hint(stringResource(R.string.learning_instructions))
                Text(stringResource(R.string.learning_progress, learn.samples, learn.peak.roundToInt()), color = neon.text)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onFinishLearn, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = neon.accent)) { Text(stringResource(R.string.done)) }
                    OutlinedButton(onClick = onCancelLearn, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                }
            }
        }

        NeonCard {
            SectionTitle(stringResource(R.string.learned_objects))
            if (prefs.learned.isEmpty()) Hint(stringResource(R.string.no_learned))
            prefs.learned.forEach { t -> LearnedRow(t, cur, onDeleteLearned) }
        }
    }

    editing?.let { p ->
        ProfileEditDialog(p, onDismiss = { editing = null }) { editing = null; onUpdate(it) }
    }
    if (askLearnName) {
        NameDialog(stringResource(R.string.learn_object), "", onDismiss = { askLearnName = false }) {
            askLearnName = false; onStartLearn(it)
        }
    }
}

@Composable
private fun LearnedRow(t: LearnedTarget, cur: DetectorParams, onDelete: (String) -> Unit) {
    val neon = LocalNeon.current
    // geleerd met andere pw/d1/d2? dan telt het voorwerp nu niet mee bij het vergelijken
    val other = t.params?.takeIf { it.pw != cur.pw || it.d1 != cur.d1 || it.d2 != cur.d2 }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.name, color = neon.text, fontWeight = FontWeight.SemiBold)
            Hint(stringResource(R.string.learned_values, "%.2f".format(t.ratio), t.peak.roundToInt()))
            if (other != null) {
                val p = other
                Text(stringResource(R.string.learned_other_settings, p.pw, p.d1, p.d2), color = neon.warn, fontSize = 12.sp)
            }
        }
        IconButton(onClick = { onDelete(t.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = neon.textDim) }
    }
}

@Composable
private fun ProfileEditDialog(p: Profile, onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var pw by remember { mutableStateOf(p.params.pw.toString()) }
    var d1 by remember { mutableStateOf(p.params.d1.toString()) }
    var d2 by remember { mutableStateOf(p.params.d2.toString()) }
    var f by remember { mutableStateOf(p.params.f.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("pw µs", pw, Modifier.weight(1f)) { pw = it }
                    NumField("f Hz", f, Modifier.weight(1f)) { f = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("d1 µs", d1, Modifier.weight(1f)) { d1 = it }
                    NumField("d2 µs", d2, Modifier.weight(1f)) { d2 = it }
                }
                Hint(stringResource(R.string.edit_profile_hint))
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && listOf(pw, d1, d2, f).all { it.toIntOrNull() != null },
                onClick = {
                    onSave(
                        p.copy(
                            name = name.trim(),
                            params = p.params.copy(pw = pw.toInt(), d1 = d1.toInt(), d2 = d2.toInt(), f = f.toInt()).sanitized(),
                        )
                    )
                },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(4)) }, label = { Text(label) },
        singleLine = true, modifier = modifier,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
    )
}
