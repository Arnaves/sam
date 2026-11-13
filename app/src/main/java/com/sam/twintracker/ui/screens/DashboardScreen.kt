package com.sam.twintracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sam.twintracker.TwinApp
import com.sam.twintracker.data.Baby
import com.sam.twintracker.data.BabyStats
import com.sam.twintracker.data.Entry
import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.OutputKind
import com.sam.twintracker.data.Repository
import com.sam.twintracker.data.VaccineDose
import com.sam.twintracker.data.formatMinutes
import com.sam.twintracker.data.formatValue
import com.sam.twintracker.data.MedicineSchedule
import com.sam.twintracker.ui.Fmt
import com.sam.twintracker.ui.MainViewModel
import com.sam.twintracker.ui.components.GroupedBarChart
import com.sam.twintracker.ui.components.Legend
import com.sam.twintracker.ui.components.Meter
import com.sam.twintracker.ui.components.Series
import com.sam.twintracker.ui.theme.StatusColors
import com.sam.twintracker.ui.theme.babyColor
import com.sam.twintracker.voice.VoiceListenerService

@Composable
fun DashboardScreen(vm: MainViewModel, onToggleVoice: () -> Unit) {
    val dash by vm.dashboard.collectAsStateWithLifecycle()
    val babies by vm.babies.collectAsStateWithLifecycle()
    val doses by vm.doses.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val voice by VoiceListenerService.state.collectAsStateWithLifecycle()
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val askReply by vm.askReply.collectAsStateWithLifecycle()
    val wakeWord = vm.prefs.getString(TwinApp.PREF_WAKE_WORD, null)?.takeIf { it.isNotBlank() } ?: "baby tracker"
    var editor by remember { mutableStateOf<Entry?>(null) }
    val alertHours = vm.prefs.getInt(TwinApp.PREF_FEED_ALERT_HOURS, 3)

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        VoiceCard(voice, wakeWord, onToggleVoice)
        AskCard(askReply, vm::ask)

        val d = dash ?: return@Column
        val b1 = babies.firstOrNull { it.id == 1 } ?: Baby(1, "Baby 1")
        val b2 = babies.firstOrNull { it.id == 2 } ?: Baby(2, "Baby 2")

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(b1, b2).forEach { baby ->
                BabyTodayCard(
                    baby, d.stats.getValue(baby.id), doses.filter { it.baby == baby.id },
                    schedules.filter { it.baby == baby.id && it.active && it.nextDueAt != null }.minByOrNull { it.nextDueAt!! },
                    now, alertHours,
                    onQuick = { editor = it },
                    onQuickSave = { vm.addEntry(it) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Trends", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            listOf(7, 14, 30).forEach { r ->
                FilterChip(selected = d.rangeDays == r, onClick = { vm.rangeDays.value = r }, label = { Text("${r}d") })
            }
        }

        val s1 = d.stats.getValue(1)
        val s2 = d.stats.getValue(2)
        val c1 = babyColor(1)
        val c2 = babyColor(2)
        val labels = d.dayStarts.map { if (d.rangeDays <= 7) Fmt.dayLetter(it).take(2) else Fmt.shortDate(it).substringBefore(' ') }
        val tips = d.dayStarts.map { Fmt.shortDate(it) }

        fun series(sel: (com.sam.twintracker.data.DayStat) -> Double) = listOf(
            Series(b1.name, c1, s1.days.map(sel)),
            Series(b2.name, c2, s2.days.map(sel)),
        )

        ChartCard("Milk intake per day", series { it.feedMl }) {
            GroupedBarChart(labels, tips, it, { v -> "${Fmt.num(v, 0)} ml" })
        }
        ChartCard("Feeds per day", series { it.feeds.toDouble() }) {
            GroupedBarChart(labels, tips, it, { v -> Fmt.num(v, 0) })
        }
        ChartCard("Diapers per day (wet + dirty)", series { it.diapers.toDouble() }) {
            GroupedBarChart(labels, tips, it, { v -> Fmt.num(v, 0) })
        }
        ChartCard("Sleep per day (hours)", series { it.sleepMin / 60.0 }) {
            GroupedBarChart(labels, tips, it, { v -> "${Fmt.num(v)} h" })
        }

        ComparisonTable(b1, b2, s1, s2, d.rangeDays)
        Spacer(Modifier.height(24.dp))
    }

    editor?.let { e ->
        EntryEditorDialog(e, babies, onDismiss = { editor = null }, onSave = { vm.addEntry(it) })
    }
}

@Composable
private fun AskCard(reply: String?, onAsk: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ask or log: which baby to feed now?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onAsk(text); text = "" }),
                trailingIcon = {
                    IconButton(enabled = text.isNotBlank(), onClick = { onAsk(text); text = "" }) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                },
            )
            if (reply != null) Text(reply, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun VoiceCard(state: VoiceListenerService.State, wakeWord: String, onToggle: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        state.awaiting -> "Listening for your request…"
                        state.running -> "Voice logging is on"
                        else -> "Voice logging is off"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    state.error ?: if (state.running) "Say \"$wakeWord\", then an entry or a question" else "Tap Start to listen in the background",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.lastSaved.isNotBlank()) {
                    Text(state.lastSaved, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                } else if (state.lastHeard.isNotBlank()) {
                    Text("Heard: \"${state.lastHeard}\"", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            FilledTonalButton(onClick = onToggle) {
                Icon(if (state.running) Icons.Default.MicOff else Icons.Default.Mic, null)
                Spacer(Modifier.width(6.dp))
                Text(if (state.running) "Stop" else "Start")
            }
        }
    }
}

@Composable
private fun BabyTodayCard(
    baby: Baby,
    stats: BabyStats,
    doses: List<VaccineDose>,
    nextMedicine: MedicineSchedule?,
    now: Long,
    alertHours: Int,
    onQuick: (Entry) -> Unit,
    onQuickSave: (Entry) -> Unit,
    modifier: Modifier,
) {
    val color = babyColor(baby.id)
    val t = stats.today
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(baby.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Fmt.age(baby.birthDate, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val last = stats.lastFeed
            val overdue = last != null && now - last.timestamp > alertHours * 3_600_000L
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (overdue) {
                    Icon(Icons.Default.Warning, "Feed overdue", tint = StatusColors.warning, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (last == null) "No feeds logged" else "Fed ${Fmt.ago(last.timestamp, now)}",
                    style = MaterialTheme.typography.bodySmall, fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal,
                )
            }

            HorizontalDivider()
            Text("Today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatLine("Feeds", "${t.feeds}")
            StatLine("Milk", "${Fmt.num(t.feedMl, 0)} ml")
            Meter((if (stats.avgMl > 0) t.feedMl / stats.avgMl else 0.0).toFloat(), color)
            Text("vs ${Fmt.num(stats.avgMl, 0)} ml daily avg", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatLine("Wet / dirty", "${t.wet} / ${t.dirty}")
            StatLine("Sleep", if (t.sleepMin == 0) "–" else formatMinutes(t.sleepMin))
            stats.avgFeedIntervalMin?.let { StatLine("Feed gap", "~${formatMinutes(it)}") }

            stats.lastTemperature?.takeIf { now - it.timestamp < 24 * 3_600_000L }?.let { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.isFever) {
                        Icon(Icons.Default.Thermostat, "Fever", tint = StatusColors.critical, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "Temp ${formatValue(t.value ?: 0.0, t.unit)}" + if (t.isFever) " · fever" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (t.isFever) StatusColors.critical else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (t.isFever) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            nextMedicine?.let { m ->
                val mins = (m.nextDueAt!! - now) / 60_000
                Text(
                    "${m.name}: " + if (mins <= 0) "due now" else "in ${formatMinutes(mins.toInt())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mins <= 0) StatusColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (mins <= 0) FontWeight.SemiBold else FontWeight.Normal,
                )
            }

            val next = doses.filter { it.givenAt == null }.minByOrNull { it.dueAgeDays }
            if (next != null) {
                val due = baby.birthDate?.let { Repository.addDays(it, next.dueAgeDays) }
                val late = due != null && due < Repository.startOfDay(now)
                Text(
                    "Next vaccine: ${next.name}" + (due?.let { " · ${Fmt.shortDate(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (late) StatusColors.critical else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (late) Text("Overdue", style = MaterialTheme.typography.labelSmall, color = StatusColors.critical, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.weight(1f))
            HorizontalDivider()
            Text("Quick log", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistChip(onClick = { onQuick(Entry(baby = baby.id, type = EntryType.FEED)) }, label = { Text("Feed") })
                AssistChip(onClick = { onQuickSave(Entry(baby = baby.id, type = EntryType.OUTPUT, subtype = OutputKind.WET)) }, label = { Text("Wet") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistChip(onClick = { onQuickSave(Entry(baby = baby.id, type = EntryType.OUTPUT, subtype = OutputKind.DIRTY)) }, label = { Text("Dirty") })
                AssistChip(onClick = { onQuick(Entry(baby = baby.id, type = EntryType.SLEEP)) }, label = { Text("Sleep") })
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChartCard(title: String, series: List<Series>, chart: @Composable (List<Series>) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Legend(series)
            Spacer(Modifier.height(8.dp))
            chart(series)
        }
    }
}

@Composable
private fun ComparisonTable(b1: Baby, b2: Baby, s1: BabyStats, s2: BabyStats, range: Int) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Daily averages (last ${range - 1} full days)", style = MaterialTheme.typography.titleSmall)
            Row {
                Spacer(Modifier.weight(1.4f))
                Text(b1.name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(b2.name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider()
            fun row(label: String, a: String, b: String) = Triple(label, a, b)
            listOf(
                row("Feeds", Fmt.num(s1.avgFeeds), Fmt.num(s2.avgFeeds)),
                row("Milk (ml)", Fmt.num(s1.avgMl, 0), Fmt.num(s2.avgMl, 0)),
                row("Feed gap", s1.avgFeedIntervalMin?.let { formatMinutes(it) } ?: "–", s2.avgFeedIntervalMin?.let { formatMinutes(it) } ?: "–"),
                row("Wet diapers", Fmt.num(s1.avgWet), Fmt.num(s2.avgWet)),
                row("Dirty diapers", Fmt.num(s1.avgDirty), Fmt.num(s2.avgDirty)),
                row("Sleep (h)", Fmt.num(s1.avgSleepMin / 60), Fmt.num(s2.avgSleepMin / 60)),
            ).forEach { (l, a, b) ->
                Row {
                    Text(l, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(a, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(b, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
