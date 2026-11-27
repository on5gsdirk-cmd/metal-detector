package be.on5gs.pidetector.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.on5gs.pidetector.ConnState
import be.on5gs.pidetector.LiveState
import be.on5gs.pidetector.R
import be.on5gs.pidetector.data.AppPrefs
import be.on5gs.pidetector.ui.theme.LocalNeon
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun MainScreen(
    prefs: AppPrefs,
    conn: ConnState,
    live: LiveState,
    onConnect: () -> Unit,
    onBalance: () -> Unit,
    onSensitivity: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onToggleGraph: () -> Unit,
    onToggleAutoBalance: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusBar(prefs, conn, live, onConnect, onToggleMute)
        MeterCard(live)
        TargetCard(live)
        GraphCard(prefs.showGraph, live, onToggleGraph)
        ControlsCard(prefs, live, onBalance, onSensitivity, onToggleAutoBalance)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusBar(prefs: AppPrefs, conn: ConnState, live: LiveState, onConnect: () -> Unit, onMute: () -> Unit) {
    val neon = LocalNeon.current
    NeonCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // batterij
            val v = live.vbat
            val vColor = when {
                live.lowBattery -> neon.bad
                v == null -> neon.textDim
                v < 10.5f -> neon.warn
                else -> neon.good
            }
            Icon(if (live.lowBattery) Icons.Filled.BatteryAlert else Icons.Filled.BatteryFull, null, tint = vColor)
            Text(
                if (v != null) "%.1f V".format(v) else "-- V",
                color = vColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp),
            )
            HSpace(12)
            // verbindingsstatus
            val (icon, label, color) = when (conn) {
                ConnState.CONNECTED -> Triple(Icons.Filled.BluetoothConnected, stringResource(R.string.status_connected), neon.good)
                ConnState.CONNECTING -> Triple(Icons.AutoMirrored.Filled.BluetoothSearching, stringResource(R.string.status_connecting), neon.warn)
                ConnState.SIMULATION -> Triple(Icons.Filled.Science, stringResource(R.string.status_simulation), neon.accent2)
                ConnState.DISCONNECTED -> Triple(Icons.Filled.Bluetooth, stringResource(R.string.status_disconnected), neon.textDim)
            }
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Text(label, color = color, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp).weight(1f), maxLines = 1)
            IconButton(onClick = onMute) {
                Icon(
                    if (prefs.audio.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    stringResource(R.string.mute), tint = if (prefs.audio.muted) neon.bad else neon.text,
                )
            }
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = neon.accent),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(
                    if (conn == ConnState.DISCONNECTED) stringResource(R.string.connect) else stringResource(R.string.disconnect),
                    fontSize = 13.sp,
                )
            }
        }
        if (live.lowBattery) {
            Text(stringResource(R.string.low_battery_stopped), color = neon.bad, fontSize = 13.sp)
        }
        if (conn == ConnState.DISCONNECTED && prefs.deviceAddress == null && !prefs.simulation) {
            Hint(stringResource(R.string.hint_choose_device))
        }
    }
}

@Composable
private fun MeterCard(live: LiveState) {
    val neon = LocalNeon.current
    val r = live.reading
    val level by animateFloatAsState(r?.level ?: 0f, tween(90), label = "lvl")
    NeonCard {
        Box(Modifier.fillMaxWidth().aspectRatio(1.75f), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.width * 0.075f
                val radius = minOf(size.width / 2, size.height) - stroke
                val center = Offset(size.width / 2, size.height - stroke * 0.3f)
                val tl = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2, radius * 2)
                // achtergrondboog + schaalstreepjes
                drawArc(Color.White.copy(alpha = 0.08f), 180f, 180f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                for (i in 0..10) {
                    val a = Math.toRadians(180.0 + i * 18.0)
                    val o = Offset(center.x + cos(a).toFloat() * (radius - stroke), center.y + sin(a).toFloat() * (radius - stroke))
                    val inner = Offset(center.x + cos(a).toFloat() * (radius - stroke * 1.5f), center.y + sin(a).toFloat() * (radius - stroke * 1.5f))
                    drawLine(neon.textDim.copy(alpha = 0.5f), inner, o, 2f)
                }
                val brush = Brush.sweepGradient(
                    0.5f to neon.accent2, 0.75f to neon.accent, 1f to neon.accent, center = center,
                )
                // gloed + waarde
                drawArc(neon.accent.copy(alpha = 0.25f), 180f, 180f * level, false, tl, arcSize, style = Stroke(stroke * 2f, cap = StrokeCap.Round))
                drawArc(brush, 180f, 180f * level, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                // piekmarkering
                if (live.peakLevel > 0.01f) {
                    val a = Math.toRadians(180.0 + 180.0 * live.peakLevel)
                    val p1 = Offset(center.x + cos(a).toFloat() * (radius - stroke / 2), center.y + sin(a).toFloat() * (radius - stroke / 2))
                    val p2 = Offset(center.x + cos(a).toFloat() * (radius + stroke / 2), center.y + sin(a).toFloat() * (radius + stroke / 2))
                    drawLine(neon.text, p1, p2, 4f, cap = StrokeCap.Round)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 8.dp)) {
                val progress = r?.balanceProgress
                when {
                    r == null -> Text("—", color = neon.textDim, fontSize = 52.sp, fontWeight = FontWeight.Black)
                    progress != null || !r.balanced -> {
                        Text(stringResource(R.string.balancing), color = neon.warn, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("${((progress ?: 0f) * 100).roundToInt()} %", color = neon.textDim)
                    }
                    else -> {
                        Text(r.d1.roundToInt().toString(), color = if (r.targetPresent) neon.accent else neon.text,
                            fontSize = 56.sp, fontWeight = FontWeight.Black)
                        Text(stringResource(R.string.signal_after_balance), color = neon.textDim, fontSize = 12.sp)
                    }
                }
            }
        }
        if (r != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("s1 ${r.raw1}", color = neon.graph1, fontSize = 12.sp)
                Text("s2 ${r.raw2}", color = neon.graph2, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TargetCard(live: LiveState) {
    val neon = LocalNeon.current
    val ratio = live.reading?.ratio
    val pos by animateFloatAsState(ratio ?: 0.5f, tween(150), label = "ratio")
    NeonCard {
        SectionTitle(stringResource(R.string.target_indicator))
        Box(
            Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFFFFD54F), Color(0xFF7CFF4F), Color(0xFF4FC3F7), Color(0xFFB388FF)))),
        ) {
            if (ratio != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = 10f + (size.width - 20f) * pos
                    drawCircle(Color.Black.copy(alpha = 0.5f), radius = size.height * 0.62f, center = Offset(x, size.height / 2))
                    drawCircle(Color.White, radius = size.height * 0.42f, center = Offset(x, size.height / 2))
                    drawCircle(neon.accent, radius = size.height * 0.25f, center = Offset(x, size.height / 2))
                }
            } else {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.target_fast), color = neon.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.target_slow), color = neon.textDim, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        val m = live.match
        when {
            live.legacy -> Hint(stringResource(R.string.target_legacy))
            m != null -> Text(
                stringResource(R.string.target_match, m.target.name, m.percent),
                color = neon.accent2, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp),
            )
            ratio != null -> Text(stringResource(R.string.target_ratio, ratio), color = neon.text, modifier = Modifier.padding(top = 6.dp))
        }
        Hint(stringResource(R.string.target_disclaimer))
    }
}

@Composable
private fun GraphCard(show: Boolean, live: LiveState, onToggle: () -> Unit) {
    val neon = LocalNeon.current
    NeonCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.live_graph).uppercase(), color = neon.accent, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
            Text("s1", color = neon.graph1, fontSize = 12.sp); HSpace(6)
            Text("s2", color = neon.graph2, fontSize = 12.sp)
            IconButton(onClick = onToggle) {
                Icon(if (show) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, stringResource(R.string.toggle_graph), tint = neon.text)
            }
        }
        if (show) {
            val pts = live.history
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                for (i in 1..3) drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4))
                if (pts.size < 2) return@Canvas
                var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                for ((a, b) in pts) { lo = minOf(lo, a, b); hi = maxOf(hi, a, b) }
                if (hi - lo < 20f) { val mid = (hi + lo) / 2; lo = mid - 10f; hi = mid + 10f }
                fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height
                val dx = size.width / (be.on5gs.pidetector.DetectorViewModel.HISTORY - 1)
                val x0 = size.width - dx * (pts.size - 1)
                for (ch in 0..1) {
                    val path = Path()
                    pts.forEachIndexed { i, p ->
                        val v = if (ch == 0) p.first else p.second
                        if (i == 0) path.moveTo(x0, y(v)) else path.lineTo(x0 + i * dx, y(v))
                    }
                    drawPath(path, if (ch == 0) neon.graph1 else neon.graph2, style = Stroke(3f))
                }
            }
        }
    }
}

@Composable
private fun ControlsCard(
    prefs: AppPrefs, live: LiveState,
    onBalance: () -> Unit, onSensitivity: (Int) -> Unit, onAuto: (Boolean) -> Unit,
) {
    val neon = LocalNeon.current
    NeonCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onBalance,
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = neon.accent),
                enabled = live.reading != null,
            ) {
                Text(stringResource(R.string.balance), fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            HSpace(12)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.sensitivity), color = neon.textDim, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { onSensitivity(-1) }, shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = neon.cardBorder)) {
                        Icon(Icons.Filled.Remove, stringResource(R.string.less))
                    }
                    Text(prefs.processor.sensitivity.toString(), color = neon.text, fontSize = 26.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                    FilledTonalIconButton(onClick = { onSensitivity(1) }, shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = neon.cardBorder)) {
                        Icon(Icons.Filled.Add, stringResource(R.string.more))
                    }
                }
            }
        }
        SwitchRow(stringResource(R.string.auto_balance), prefs.processor.autoBalance, stringResource(R.string.auto_balance_hint), onAuto)
    }
}
