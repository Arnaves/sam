package com.sam.twintracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sam.twintracker.data.Entry
import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.Repository
import com.sam.twintracker.ui.Fmt
import com.sam.twintracker.ui.MainViewModel
import com.sam.twintracker.ui.theme.babyColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(vm: MainViewModel) {
    val f by vm.logFilter.collectAsStateWithLifecycle()
    val entries by vm.logEntries.collectAsStateWithLifecycle()
    val babies by vm.babies.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<Entry?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    val names = babies.associate { it.id to it.name }
    val today = Repository.startOfDay(System.currentTimeMillis())

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- date navigation
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !f.allDates, onClick = { vm.updateFilter { it.copy(day = Repository.addDays(it.day, -1)) } }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day")
                }
                TextButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f), enabled = !f.allDates) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (f.allDates) "All dates" else if (f.day == today) "Today · ${Fmt.shortDate(f.day)}" else Fmt.date(f.day))
                }
                IconButton(enabled = !f.allDates && f.day < today, onClick = { vm.updateFilter { it.copy(day = Repository.addDays(it.day, 1)) } }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day")
                }
            }

            // ---- search
            OutlinedTextField(
                value = f.query,
                onValueChange = { q -> vm.updateFilter { it.copy(query = q) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search: formula, poop, BCG, fever…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (f.query.isNotEmpty()) IconButton(onClick = { vm.updateFilter { it.copy(query = "") } }) { Icon(Icons.Default.Clear, "Clear") }
                },
                singleLine = true,
            )

            // ---- filters
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = f.allDates, onClick = { vm.updateFilter { it.copy(allDates = !it.allDates) } }, label = { Text("All dates") })
                FilterChip(selected = f.baby == 0, onClick = { vm.updateFilter { it.copy(baby = 0) } }, label = { Text("Both") })
                babies.forEach { b ->
                    FilterChip(selected = f.baby == b.id, onClick = { vm.updateFilter { it.copy(baby = b.id) } }, label = { Text(b.name) },
                        leadingIcon = { Box(Modifier.size(8.dp).background(babyColor(b.id), CircleShape)) })
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = f.type == null, onClick = { vm.updateFilter { it.copy(type = null) } }, label = { Text("All types") })
                EntryType.entries.forEach { t ->
                    FilterChip(selected = f.type == t, onClick = { vm.updateFilter { it.copy(type = if (it.type == t) null else t) } }, label = { Text(t.label) })
                }
            }

            // ---- day summary
            val feeds = entries.filter { it.type == EntryType.FEED }
            Text(
                "${entries.size} entries · ${feeds.size} feeds · ${Fmt.num(feeds.sumOf { it.amountMl ?: 0.0 }, 0)} ml · " +
                    "${entries.count { it.isWet }} wet · ${entries.count { it.isDirty }} dirty",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            if (entries.isEmpty()) {
                Text(
                    if (f.query.isBlank()) "Nothing logged yet. Say \"entry for baby 1 …\" or tap +." else "No entries match \"${f.query}\".",
                    modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                items(entries, key = { it.id }) { e ->
                    EntryRow(e, names[e.baby] ?: "Baby ${e.baby}", showDate = f.allDates) { editor = e }
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
            }
        }

        FloatingActionButton(
            onClick = {
                val ts = if (f.allDates || f.day == today) System.currentTimeMillis() else f.day + 12 * 3_600_000L
                editor = Entry(baby = if (f.baby == 0) 1 else f.baby, type = f.type ?: EntryType.FEED, timestamp = ts)
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add entry") }
    }

    editor?.let { e ->
        EntryEditorDialog(
            e, babies, onDismiss = { editor = null },
            onSave = { if (it.id == 0L) vm.addEntry(it) else vm.updateEntry(it) },
            onDelete = { vm.deleteEntry(it) },
        )
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toUtcDate(f.day))
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { sel -> vm.updateFilter { it.copy(day = fromUtcDate(sel)) } }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

fun iconFor(type: EntryType): ImageVector = when (type) {
    EntryType.FEED -> Icons.Default.LocalDrink
    EntryType.OUTPUT -> Icons.Default.BabyChangingStation
    EntryType.SLEEP -> Icons.Default.Bedtime
    EntryType.VACCINE -> Icons.Default.Vaccines
    EntryType.NOTE -> Icons.Default.Description
}

@Composable
private fun EntryRow(e: Entry, babyName: String, showDate: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(babyColor(e.baby).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(iconFor(e.type), e.type.label, tint = babyColor(e.baby))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(babyName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(if (showDate) Fmt.dateTime(e.timestamp) else Fmt.time(e.timestamp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.source == "voice") {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Mic, "Logged by voice", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(e.summary(), style = MaterialTheme.typography.bodyMedium)
            e.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
