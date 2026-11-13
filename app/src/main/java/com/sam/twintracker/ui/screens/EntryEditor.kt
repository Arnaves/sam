package com.sam.twintracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sam.twintracker.data.Baby
import com.sam.twintracker.data.Entry
import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.FeedKind
import com.sam.twintracker.data.OutputKind
import com.sam.twintracker.data.VaccineCatalog
import com.sam.twintracker.ui.Fmt
import java.util.Calendar
import java.util.TimeZone

/**
 * Add / edit dialog. [initial] with id == 0 means a new entry.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryEditorDialog(
    initial: Entry,
    babies: List<Baby>,
    onDismiss: () -> Unit,
    onSave: (Entry) -> Unit,
    onDelete: ((Entry) -> Unit)? = null,
    defaultTempUnit: String = "°F",
    medicineSuggestions: List<String> = emptyList(),
) {
    var baby by remember { mutableStateOf(initial.baby) }
    var bothBabies by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(initial.type) }
    var subtype by remember { mutableStateOf(initial.subtype) }
    var amount by remember { mutableStateOf(initial.amountMl?.let { Fmt.num(it) } ?: "") }
    var duration by remember { mutableStateOf(initial.durationMin?.toString() ?: "") }
    var side by remember { mutableStateOf(initial.side) }
    var note by remember { mutableStateOf(initial.note ?: "") }
    var timestamp by remember { mutableLongStateOf(initial.timestamp) }
    var medName by remember { mutableStateOf(if (initial.type == EntryType.MEDICINE) initial.subtype.orEmpty() else "") }
    var value by remember { mutableStateOf(initial.value?.let { Fmt.num(it, 2) } ?: "") }
    var unit by remember { mutableStateOf(initial.unit) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val isNew = initial.id == 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New entry" else "Edit entry") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val options = babies.map { it.id to it.name } + if (isNew) listOf(0 to "Both") else emptyList()
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (id, name) ->
                        SegmentedButton(
                            selected = if (id == 0) bothBabies else !bothBabies && baby == id,
                            onClick = { if (id == 0) bothBabies = true else { bothBabies = false; baby = id } },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(name, maxLines = 1) }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EntryType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = {
                            if (type != t) {
                                type = t; subtype = null; side = null
                                unit = when (t) {
                                    EntryType.TEMPERATURE -> defaultTempUnit
                                    EntryType.WEIGHT -> "kg"
                                    EntryType.HEIGHT -> "cm"
                                    EntryType.MEDICINE -> "ml"
                                    else -> null
                                }
                            }
                        }, label = { Text(t.label) })
                    }
                }

                val subtypes = when (type) {
                    EntryType.FEED -> FeedKind.all
                    EntryType.OUTPUT -> OutputKind.all
                    EntryType.VACCINE -> VaccineCatalog.vaccines.map { it.name }
                    else -> emptyList()
                }
                if (subtypes.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        subtypes.forEach { s ->
                            FilterChip(selected = subtype == s, onClick = { subtype = if (subtype == s) null else s }, label = { Text(s) })
                        }
                    }
                }

                if (type == EntryType.FEED) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f),
                            label = { Text("Amount (ml)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        OutlinedTextField(
                            duration, { duration = it.filter(Char::isDigit) }, Modifier.weight(1f),
                            label = { Text("Minutes") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    if (subtype == FeedKind.BREAST) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Left", "Right", "Both").forEach { s ->
                                FilterChip(selected = side == s, onClick = { side = if (side == s) null else s }, label = { Text(s) })
                            }
                        }
                    }
                }
                if (type == EntryType.SLEEP) {
                    OutlinedTextField(
                        duration, { duration = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(),
                        label = { Text("Duration (minutes)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                if (type == EntryType.MEDICINE) {
                    OutlinedTextField(medName, { medName = it }, Modifier.fillMaxWidth(), label = { Text("Medicine name") }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (medicineSuggestions + listOf("Paracetamol", "Vitamin D", "Iron", "Gripe water", "ORS", "Saline drops"))
                            .distinct().take(8).forEach { m ->
                                FilterChip(selected = medName.equals(m, true), onClick = { medName = m }, label = { Text(m) })
                            }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value, { value = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f),
                            label = { Text("Dose") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ml", "drops", "mg", "tablet", "puffs").forEach { u ->
                            FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) })
                        }
                    }
                }
                if (type == EntryType.TEMPERATURE || type == EntryType.WEIGHT || type == EntryType.HEIGHT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value, { value = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f),
                            label = { Text(when (type) { EntryType.TEMPERATURE -> "Temperature"; EntryType.WEIGHT -> "Weight (kg)"; else -> "Height (cm)" }) },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        if (type == EntryType.TEMPERATURE) {
                            listOf("°F", "°C").forEach { u ->
                                FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) })
                            }
                        }
                    }
                }

                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note") }, minLines = 2)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showDate = true }) {
                        Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(6.dp)); Text(Fmt.shortDate(timestamp))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { showTime = true }) {
                        Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(6.dp)); Text(Fmt.time(timestamp))
                    }
                }
                if (initial.source == "voice" && !initial.rawText.isNullOrBlank()) {
                    Text("Heard: \"${initial.rawText}\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!isNew && onDelete != null) {
                    TextButton(onClick = { onDelete(initial); onDismiss() }) {
                        Text("Delete entry", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hasValue = type in setOf(EntryType.MEDICINE, EntryType.TEMPERATURE, EntryType.WEIGHT, EntryType.HEIGHT)
                val base = initial.copy(
                    type = type,
                    subtype = if (type == EntryType.MEDICINE) medName.trim().ifBlank { "Medicine" } else subtype,
                    value = if (hasValue) value.toDoubleOrNull() else null,
                    unit = if (hasValue && value.toDoubleOrNull() != null) unit else null,
                    amountMl = if (type == EntryType.FEED) amount.toDoubleOrNull() else null,
                    durationMin = if (type == EntryType.FEED || type == EntryType.SLEEP) duration.toIntOrNull() else null,
                    side = if (type == EntryType.FEED) side else null,
                    note = note.ifBlank { null },
                    timestamp = timestamp,
                )
                if (bothBabies) {
                    onSave(base.copy(baby = 1)); onSave(base.copy(baby = 2))
                } else onSave(base.copy(baby = baby))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toUtcDate(timestamp))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { timestamp = withDate(timestamp, it) }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (showTime) {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val state = rememberTimePickerState(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    timestamp = Calendar.getInstance().apply {
                        timeInMillis = timestamp
                        set(Calendar.HOUR_OF_DAY, state.hour); set(Calendar.MINUTE, state.minute)
                    }.timeInMillis
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

/** Material's DatePicker works in UTC midnight millis. */
fun toUtcDate(localMillis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Local midnight for a UTC date returned by DatePicker. */
fun fromUtcDate(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun withDate(original: Long, utcDate: Long): Long {
    val day = Calendar.getInstance().apply { timeInMillis = fromUtcDate(utcDate) }
    return Calendar.getInstance().apply {
        timeInMillis = original
        set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
