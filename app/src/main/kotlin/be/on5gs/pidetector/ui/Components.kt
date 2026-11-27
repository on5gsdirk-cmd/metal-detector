package be.on5gs.pidetector.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.on5gs.pidetector.ui.theme.LocalNeon
import kotlin.math.roundToInt

@Composable
fun NeonCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val neon = LocalNeon.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(22.dp), ambientColor = neon.accent, spotColor = neon.accent),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = neon.card),
        border = BorderStroke(1.dp, neon.cardBorder),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String) {
    val neon = LocalNeon.current
    Text(text.uppercase(), color = neon.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.padding(top = 6.dp))
}

@Composable
fun Hint(text: String) {
    Text(text, color = LocalNeon.current.textDim, fontSize = 13.sp, lineHeight = 17.sp)
}

@Composable
fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    val neon = LocalNeon.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = neon.text)
            hint?.let { Hint(it) }
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = neon.accent, checkedThumbColor = neon.text),
        )
    }
}

/** Slider + numeriek invoerveld voor een geheel getal. */
@Composable
fun IntParam(
    label: String, unit: String, value: Int, range: IntRange, hint: String,
    onChange: (Int) -> Unit,
) {
    val neon = LocalNeon.current
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = neon.text, fontWeight = FontWeight.SemiBold)
                Hint("${range.first}–${range.last} $unit")
            }
            OutlinedTextField(
                value = text,
                onValueChange = { t ->
                    text = t.filter { it.isDigit() }.take(4)
                    text.toIntOrNull()?.let { if (it in range) onChange(it) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(96.dp),
                suffix = { Text(unit, color = neon.textDim, fontSize = 12.sp) },
                isError = text.toIntOrNull()?.let { it !in range } ?: true,
            )
        }
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = neon.accent, activeTrackColor = neon.accent),
        )
        Hint(hint)
    }
}

/** Slider met tekstweergave voor een kommagetal. */
@Composable
fun FloatParam(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    hint: String? = null, steps: Int = 0, onChange: (Float) -> Unit,
) {
    val neon = LocalNeon.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = neon.text, fontWeight = FontWeight.SemiBold)
            Text(valueText, color = neon.accent, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange,
            valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = neon.accent, activeTrackColor = neon.accent),
        )
        hint?.let { Hint(it) }
    }
}

@Composable
fun HSpace(w: Int = 8) = Spacer(Modifier.width(w.dp))
