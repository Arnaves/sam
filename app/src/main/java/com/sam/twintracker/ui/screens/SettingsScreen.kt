package com.sam.twintracker.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sam.twintracker.TwinApp
import com.sam.twintracker.data.Baby
import com.sam.twintracker.ui.Fmt
import com.sam.twintracker.ui.MainViewModel
import com.sam.twintracker.ui.theme.babyColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val babies by vm.babies.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var pickDob by remember { mutableStateOf<Baby?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri) { ok ->
            Toast.makeText(ctx, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Babies", style = MaterialTheme.typography.titleMedium)
        babies.forEach { b ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var name by remember(b.id, b.name) { mutableStateOf(b.name) }
                    OutlinedTextField(
                        name, { name = it }, Modifier.fillMaxWidth(),
                        label = { Text("Baby ${b.id} name", color = babyColor(b.id)) }, singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickDob = b }) {
                            Text(b.birthDate?.let { "Born ${Fmt.shortDate(it)}" } ?: "Set birth date")
                        }
                        Text(Fmt.age(b.birthDate, now), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(enabled = name.isNotBlank() && name != b.name, onClick = { vm.saveBaby(b.copy(name = name.trim())) }) { Text("Save") }
                    }
                    Text(
                        "Voice trigger: \"entry for baby ${b.id}\"" + if (b.name != "Baby ${b.id}") " or \"entry for ${b.name}\"" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text("Voice logging", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PrefSwitch(vm, TwinApp.PREF_TTS, true, "Spoken confirmation", "Reads back what was saved")
                PrefSwitch(vm, TwinApp.PREF_MUTE_BEEP, true, "Silence listening beep", "Mutes media volume while the mic restarts (unmuted for confirmations)")
                PrefSwitch(vm, TwinApp.PREF_OFFLINE, false, "Prefer offline recognition", "Needs the offline speech pack for your language")
                Spacer(Modifier.height(8.dp))
                var hours by remember { mutableFloatStateOf(vm.prefs.getInt(TwinApp.PREF_FEED_ALERT_HOURS, 3).toFloat()) }
                Text("Highlight a baby when not fed for ${hours.toInt()} h", style = MaterialTheme.typography.bodyMedium)
                Slider(hours, { hours = it }, valueRange = 1f..6f, steps = 4,
                    onValueChangeFinished = { vm.prefs.edit { putInt(TwinApp.PREF_FEED_ALERT_HOURS, hours.toInt()) } })
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("Battery optimisation settings") }
                Text(
                    "To keep listening reliably with the screen off, set Twin Tracker to \"Unrestricted\" / \"Don't optimise\".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("Voice commands", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "entry for baby 1 fed 120 ml formula",
                    "entry for baby 2 breastfed 15 minutes left side",
                    "entry for baby 1 wet diaper",
                    "entry for baby 2 pooped",
                    "entry for baby 1 wet and dirty diaper",
                    "entry for baby 2 slept 2 hours",
                    "entry for baby 1 vaccine BCG and hepatitis B",
                    "entry for both babies fed 90 ml at 3:30 pm",
                    "entry for baby 2 fed 4 oz 20 minutes ago",
                    "entry for baby 1 note slight fever",
                ).forEach { Text("• \"$it\"", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(4.dp))
                Text(
                    "You can also say just \"entry for baby 1\", wait for \"go ahead\", then say the details.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("Data", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { exporter.launch("twin-tracker-${System.currentTimeMillis() / 1000}.csv") }) {
            Text("Export all logs to CSV")
        }
        Spacer(Modifier.height(24.dp))
    }

    pickDob?.let { b ->
        val state = rememberDatePickerState(initialSelectedDateMillis = toUtcDate(b.birthDate ?: System.currentTimeMillis()))
        DatePickerDialog(
            onDismissRequest = { pickDob = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.saveBaby(b.copy(birthDate = fromUtcDate(it))) }
                    pickDob = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDob = null }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun PrefSwitch(vm: MainViewModel, key: String, default: Boolean, title: String, subtitle: String) {
    var on by remember { mutableStateOf(vm.prefs.getBoolean(key, default)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, { on = it; vm.prefs.edit { putBoolean(key, it) } })
    }
}
