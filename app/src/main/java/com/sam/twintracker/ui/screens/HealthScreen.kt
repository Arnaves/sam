package com.sam.twintracker.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sam.twintracker.TwinApp
import com.sam.twintracker.data.Entry
import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.MedicineSchedule
import com.sam.twintracker.data.formatMinutes
import com.sam.twintracker.data.formatValue
import com.sam.twintracker.ui.Fmt
import com.sam.twintracker.ui.MainViewModel
import com.sam.twintracker.ui.components.TimeLineChart
import com.sam.twintracker.ui.theme.StatusColors
import com.sam.twintracker.ui.theme.babyColor

@Composable
fun HealthScreen(vm: MainViewModel) {
    val babies by vm.babies.collectAsStateWithLifecycle()
    val baby by vm.healthBaby.collectAsStateWithLifecycle()
    val entries by vm.healthEntries.collectAsStateWithLifecycle()
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val tempUnit = vm.prefs.getString(TwinApp.PREF_TEMP_UNIT, "°F") ?: "°F"
    var editor by remember { mutableStateOf<Entry?>(null) }
    var addTimer by remember { mutableStateOf(false) }
    var removeTimer by remember { mutableStateOf<MedicineSchedule?>(null) }

    val mine = schedules.filter { it.baby == baby }
    val color = babyColor(baby)
    val babyName = babies.firstOrNull { it.id == baby }?.name ?: "Baby $baby"
    val medicineNames = entries.filter { it.type == EntryType.MEDICINE }.mapNotNull { it.subtype } + schedules.map { it.name }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = (baby - 1).coerceIn(0, 1)) {
            babies.forEach { b ->
                Tab(selected = baby == b.id, onClick = { vm.healthBaby.value = b.id },
                    text = { Text(b.name, color = if (baby == b.id) babyColor(b.id) else Color.Unspecified) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---------------- medicine timers
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Medicine timers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { addTimer = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Timer") }
                }
            }
            if (!vm.canExactAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Allow exact alarms so medicine reminders ring on time.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                            }) { Text("Allow") }
                        }
                    }
                }
            }
            if (mine.isEmpty()) {
                item {
                    Text(
                        "No timers. Say \"baby tracker, $babyName paracetamol 2.5 ml every 6 hours for 3 days\" or tap Timer.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(mine, key = { "s${it.id}" }) { s ->
                TimerCard(s, now, onGiven = { vm.giveDose(s) }, onStop = { vm.stopSchedule(s) }, onDelete = { removeTimer = s })
            }

            // ---------------- recent doses
            val doses = entries.filter { it.type == EntryType.MEDICINE }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Medicine given", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { editor = Entry(baby = baby, type = EntryType.MEDICINE, unit = "ml") }) { Text("Log dose") }
                }
            }
            if (doses.isEmpty()) item { Text("No medicine logged.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(doses.take(6), key = { "d${it.id}" }) { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.subtype ?: "Medicine", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(Fmt.dateTime(e.timestamp) + (e.value?.let { " · ${formatValue(it, e.unit)}" } ?: ""),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { editor = e }) { Text("Edit") }
                }
            }

            // ---------------- measurements
            item {
                MeasureCard(
                    title = "Temperature", type = EntryType.TEMPERATURE, entries = entries, color = color,
                    convert = { e -> toUnit(e, tempUnit) }, unit = tempUnit,
                    threshold = if (tempUnit == "°F") 100.4 else 38.0, thresholdColor = StatusColors.critical,
                    status = { e -> if (e.isFever) "Fever" to StatusColors.critical else "Normal" to StatusColors.good },
                    onAdd = { editor = Entry(baby = baby, type = EntryType.TEMPERATURE, unit = tempUnit) },
                    onEdit = { editor = it },
                )
            }
            item {
                MeasureCard(
                    title = "Weight", type = EntryType.WEIGHT, entries = entries, color = color, convert = { it.value }, unit = "kg",
                    onAdd = { editor = Entry(baby = baby, type = EntryType.WEIGHT, unit = "kg") }, onEdit = { editor = it },
                )
            }
            item {
                MeasureCard(
                    title = "Height", type = EntryType.HEIGHT, entries = entries, color = color, convert = { it.value }, unit = "cm",
                    onAdd = { editor = Entry(baby = baby, type = EntryType.HEIGHT, unit = "cm") }, onEdit = { editor = it },
                )
            }
        }
    }

    editor?.let { e ->
        EntryEditorDialog(
            e, babies, onDismiss = { editor = null },
            onSave = { if (it.id == 0L) vm.addEntry(it) else vm.updateEntry(it) },
            onDelete = { vm.deleteEntry(it) },
            defaultTempUnit = tempUnit,
            medicineSuggestions = medicineNames.distinct(),
        )
    }
    if (addTimer) AddTimerDialog(babyName, medicineNames.distinct(), onDismiss = { addTimer = false }) { s, logNow ->
        vm.addSchedule(s.copy(baby = baby), logNow)
    }
    removeTimer?.let { s ->
        AlertDialog(
            onDismissRequest = { removeTimer = null },
            title = { Text("Delete ${s.name} timer?") },
            text = { Text("Doses already logged stay in the log.") },
            confirmButton = { TextButton(onClick = { vm.deleteSchedule(s); removeTimer = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { removeTimer = null }) { Text("Cancel") } },
        )
    }
}

private fun toUnit(e: Entry, unit: String): Double? {
    val c = e.celsius ?: return null
    return if (unit == "°F") c * 9 / 5 + 32 else c
}

@Composable
private fun TimerCard(s: MedicineSchedule, now: Long, onGiven: () -> Unit, onStop: () -> Unit, onDelete: () -> Unit) {
    val due = s.nextDueAt
    val mins = due?.let { (it - now) / 60_000 }
    val overdue = s.active && mins != null && mins <= 0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (overdue) Icons.Default.NotificationsActive else Icons.Default.AlarmOn, null,
                    tint = if (overdue) StatusColors.warning else if (s.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name + (s.dose?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        (if (s.oneShot) "One-time reminder" else "Every ${formatMinutes(s.intervalMinutes)}") +
                            (s.endAt?.let { " · until ${Fmt.shortDate(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete timer", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            when {
                !s.active -> Text("Finished", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                overdue -> Text(
                    "Due now" + if (mins!! < -1) " · ${formatMinutes((-mins).toInt())} late" else "",
                    style = MaterialTheme.typography.titleSmall, color = StatusColors.warning, fontWeight = FontWeight.SemiBold,
                )
                due != null -> Text(
                    "Next dose in ${formatMinutes(mins!!.toInt())} · ${Fmt.time(due)}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (s.active) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onGiven) { Text("Given now") }
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                }
            }
        }
    }
}

@Composable
private fun MeasureCard(
    title: String,
    type: EntryType,
    entries: List<Entry>,
    color: Color,
    convert: (Entry) -> Double?,
    unit: String,
    onAdd: () -> Unit,
    onEdit: (Entry) -> Unit,
    threshold: Double? = null,
    thresholdColor: Color = color,
    status: ((Entry) -> Pair<String, Color>)? = null,
) {
    val list = entries.filter { it.type == type && it.value != null }.sortedBy { it.timestamp }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onAdd) { Icon(Icons.Default.Add, null); Text("Add") }
            }
            val last = list.lastOrNull()
            if (last == null) {
                Text("Nothing recorded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatValue(convert(last) ?: 0.0, unit), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                status?.invoke(last)?.let { (label, c) ->
                    Text(label, style = MaterialTheme.typography.labelLarge, color = c, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                }
                Text(Fmt.dateTime(last.timestamp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { onEdit(last) }) { Text("Edit") }
            }
            if (list.size >= 2) {
                val prev = convert(list[list.size - 2])
                val cur = convert(last)
                if (prev != null && cur != null && type != EntryType.TEMPERATURE) {
                    val d = cur - prev
                    Text("${if (d >= 0) "+" else "−"}${formatValue(kotlin.math.abs(d), unit)} since ${Fmt.shortDate(list[list.size - 2].timestamp)}",
                        style = MaterialTheme.typography.bodySmall)
                }
                val pts = list.takeLast(30).mapNotNull { e -> convert(e)?.let { e.timestamp to it } }
                TimeLineChart(
                    pts, color, valueFormat = { formatValue((it * 100).toLong() / 100.0, unit) },
                    dateFormat = { Fmt.dateTime(it) }, threshold = threshold, thresholdColor = thresholdColor,
                )
            }
        }
    }
}

@Composable
private fun AddTimerDialog(
    babyName: String,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onAdd: (MedicineSchedule, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("6") }
    var days by remember { mutableStateOf("") }
    var givenNow by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Medicine timer for $babyName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Medicine") }, singleLine = true)
                if (suggestions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        suggestions.take(3).forEach { s -> TextButton(onClick = { name = s }) { Text(s) } }
                    }
                }
                OutlinedTextField(dose, { dose = it }, label = { Text("Dose (e.g. 2.5 ml)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(hours, { hours = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f),
                        label = { Text("Every (hours)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(days, { days = it.filter(Char::isDigit) }, Modifier.weight(1f),
                        label = { Text("For (days)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(givenNow, { givenNow = it })
                    Text("A dose was just given (log it now)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            val h = hours.toDoubleOrNull()
            Button(enabled = name.isNotBlank() && h != null && h > 0, onClick = {
                val now = System.currentTimeMillis()
                val interval = (h!! * 60).toInt()
                onAdd(
                    MedicineSchedule(
                        baby = 0, name = name.trim(), dose = dose.trim().ifBlank { null }, intervalMinutes = interval,
                        // If a dose is logged now, giveScheduledDose moves this on from "now".
                        nextDueAt = now + interval * 60_000L,
                        endAt = days.toIntOrNull()?.let { now + it * 86_400_000L },
                    ),
                    givenNow,
                )
                onDismiss()
            }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
