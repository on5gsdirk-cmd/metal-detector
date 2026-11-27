package be.on5gs.pidetector.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.on5gs.pidetector.ConnState
import be.on5gs.pidetector.R
import be.on5gs.pidetector.bt.PairedDevice
import be.on5gs.pidetector.data.AppPrefs
import be.on5gs.pidetector.ui.theme.LocalNeon

@Composable
fun BluetoothScreen(
    prefs: AppPrefs,
    conn: ConnState,
    hasPermission: Boolean,
    btAvailable: Boolean,
    btEnabled: Boolean,
    linkError: String?,
    devices: () -> List<PairedDevice>,
    onRequestPermission: () -> Unit,
    onSelect: (PairedDevice) -> Unit,
    onSimulation: (Boolean) -> Unit,
) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val list = remember(refresh, hasPermission, btEnabled) { if (hasPermission) devices() else emptyList() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NeonCard {
            SectionTitle(stringResource(R.string.bluetooth))
            Hint(stringResource(R.string.bt_pairing_hint))
            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.open_bt_settings)) }
        }

        NeonCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.paired_devices).uppercase(), color = neon.accent, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh), tint = neon.text) }
            }
            when {
                !btAvailable -> Hint(stringResource(R.string.bt_not_available))
                !hasPermission -> {
                    Hint(stringResource(R.string.bt_permission_needed))
                    OutlinedButton(onClick = onRequestPermission) { Text(stringResource(R.string.grant_permission)) }
                }
                !btEnabled -> Hint(stringResource(R.string.bt_disabled))
                list.isEmpty() -> Hint(stringResource(R.string.no_paired))
                else -> list.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = neon.cardBorder.copy(alpha = 0.3f))
                    val remembered = d.address == prefs.deviceAddress
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(d) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Bluetooth, null, tint = if (remembered) neon.accent else neon.textDim)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(d.name, color = neon.text, fontWeight = FontWeight.SemiBold)
                            Hint(d.address)
                        }
                        if (remembered) Icon(Icons.Filled.CheckCircle, stringResource(R.string.remembered), tint = neon.good)
                    }
                }
            }
            if (linkError != null && conn != ConnState.CONNECTED && conn != ConnState.SIMULATION) {
                Text(stringResource(R.string.last_error, linkError), color = neon.warn)
            }
        }

        NeonCard {
            SwitchRow(stringResource(R.string.simulation_mode), prefs.simulation, stringResource(R.string.simulation_hint), onSimulation)
        }
    }
}
