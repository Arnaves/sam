package com.sam.twintracker.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sam.twintracker.data.Baby
import com.sam.twintracker.data.Repository
import com.sam.twintracker.data.VaccineDose
import com.sam.twintracker.ui.Fmt
import com.sam.twintracker.ui.MainViewModel
import com.sam.twintracker.ui.theme.StatusColors
import com.sam.twintracker.ui.theme.babyColor

private enum class DoseStatus(val label: String) { GIVEN("Given"), OVERDUE("Overdue"), DUE("Due soon"), UPCOMING("Upcoming"), UNKNOWN("Set birth date") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaccineScreen(vm: MainViewModel) {
    val babies by vm.babies.collectAsStateWithLifecycle()
    val doses by vm.doses.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var markDose by remember { mutableStateOf<VaccineDose?>(null) }
    var pickDob by remember { mutableStateOf<Baby?>(null) }
    var addDose by remember { mutableStateOf(false) }
    var removeDose by remember { mutableStateOf<VaccineDose?>(null) }

    val baby = babies.getOrNull(tab) ?: return
    val today = Repository.startOfDay(now)
    val mine = doses.filter { it.baby == baby.id }

    fun dueOf(d: VaccineDose) = baby.birthDate?.let { Repository.addDays(it, d.dueAgeDays) }
    fun statusOf(d: VaccineDose): DoseStatus {
        if (d.givenAt != null) return DoseStatus.GIVEN
        val due = dueOf(d) ?: return DoseStatus.UNKNOWN
        return when {
            due < today -> DoseStatus.OVERDUE
            due <= Repository.addDays(today, 7) -> DoseStatus.DUE
            else -> DoseStatus.UPCOMING
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            babies.forEachIndexed { i, b ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(b.name, color = if (tab == i) babyColor(b.id) else Color.Unspecified) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val given = mine.count { it.givenAt != null }
                        val overdue = mine.count { statusOf(it) == DoseStatus.OVERDUE }
                        Text("${baby.name} · ${Fmt.age(baby.birthDate, now)}", style = MaterialTheme.typography.titleMedium)
                        Text("$given of ${mine.size} doses given" + if (overdue > 0) " · $overdue overdue" else "",
                            style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickDob = baby }) { Text(if (baby.birthDate == null) "Set birth date" else "Birth date: ${Fmt.shortDate(baby.birthDate)}") }
                            OutlinedButton(onClick = { addDose = true }) { Icon(Icons.Default.Add, null); Text("Dose") }
                        }
                        Text(
                            "Default schedule follows common WHO/IAP timelines. Confirm with your paediatrician and edit to match. " +
                                "Voice: \"entry for baby 1 vaccine BCG\".",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val groups = mine.groupBy { it.ageLabel }
            groups.forEach { (label, list) ->
                item(key = "h-$label") {
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                }
                items(list, key = { it.id }) { d ->
                    DoseRow(d, statusOf(d), dueOf(d),
                        onToggle = { if (d.givenAt == null) markDose = d else vm.setDoseGiven(d, null) },
                        onDelete = { removeDose = d })
                }
            }
        }
    }

    markDose?.let { d ->
        val state = rememberDatePickerState(initialSelectedDateMillis = toUtcDate(System.currentTimeMillis()))
        DatePickerDialog(
            onDismissRequest = { markDose = null },
            confirmButton = {
                TextButton(onClick = {
                    val day = state.selectedDateMillis?.let { fromUtcDate(it) } ?: today
                    val at = if (day == today) System.currentTimeMillis() else day + 10 * 3_600_000L
                    vm.setDoseGiven(d, at); markDose = null
                }) { Text("Mark given") }
            },
            dismissButton = { TextButton(onClick = { markDose = null }) { Text("Cancel") } },
        ) {
            Text("When was ${d.name} given?", Modifier.padding(start = 24.dp, top = 16.dp), style = MaterialTheme.typography.titleMedium)
            DatePicker(state)
        }
    }

    pickDob?.let { b ->
        val state = rememberDatePickerState(initialSelectedDateMillis = b.birthDate?.let { toUtcDate(it) } ?: toUtcDate(System.currentTimeMillis()))
        DatePickerDialog(
            onDismissRequest = { pickDob = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { sel ->
                        val dob = fromUtcDate(sel)
                        vm.saveBaby(b.copy(birthDate = dob))
                        // Twins: fill in the sibling's birth date too if it isn't set yet.
                        babies.firstOrNull { it.id != b.id && it.birthDate == null }?.let { vm.saveBaby(it.copy(birthDate = dob)) }
                    }
                    pickDob = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDob = null }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    removeDose?.let { d ->
        AlertDialog(
            onDismissRequest = { removeDose = null },
            title = { Text("Remove ${d.name}?") },
            text = { Text("This removes the dose from ${baby.name}'s schedule.") },
            confirmButton = { TextButton(onClick = { vm.deleteDose(d); removeDose = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeDose = null }) { Text("Cancel") } },
        )
    }

    if (addDose) {
        var name by remember { mutableStateOf("") }
        var weeks by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDose = false },
            title = { Text("Add dose for ${baby.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Vaccine / dose name") }, singleLine = true)
                    OutlinedTextField(weeks, { weeks = it.filter(Char::isDigit) }, label = { Text("Due at age (weeks)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                Button(enabled = name.isNotBlank() && weeks.isNotBlank(), onClick = {
                    val w = weeks.toInt()
                    val key = com.sam.twintracker.data.VaccineCatalog.match(name).firstOrNull() ?: "custom"
                    vm.addDose(VaccineDose(baby = baby.id, vaccineKey = key, name = name.trim(), dueAgeDays = w * 7,
                        ageLabel = if (w == 0) "Birth" else if (w < 26) "$w weeks" else "${(w * 7 / 30.4).toInt()} months"))
                    addDose = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addDose = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DoseRow(d: VaccineDose, status: DoseStatus, due: Long?, onToggle: () -> Unit, onDelete: () -> Unit) {
    val (icon, tint) = when (status) {
        DoseStatus.GIVEN -> Icons.Default.CheckCircle to StatusColors.good
        DoseStatus.OVERDUE -> Icons.Default.ErrorOutline to StatusColors.critical
        DoseStatus.DUE -> Icons.Default.Schedule to StatusColors.warning
        else -> Icons.Outlined.Circle to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle) { Icon(icon, if (d.givenAt == null) "Mark given" else "Mark not given", tint = tint) }
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val detail = when {
                    d.givenAt != null -> "Given ${Fmt.shortDate(d.givenAt)}"
                    due != null -> "Due ${Fmt.date(due)}"
                    else -> "Due at ${d.ageLabel}"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status.label, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove dose", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
