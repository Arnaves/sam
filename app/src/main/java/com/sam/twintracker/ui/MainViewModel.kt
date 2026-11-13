package com.sam.twintracker.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sam.twintracker.TwinApp
import com.sam.twintracker.data.Analytics
import com.sam.twintracker.data.Baby
import com.sam.twintracker.data.BabyStats
import com.sam.twintracker.data.Entry
import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.MedicineSchedule
import com.sam.twintracker.data.Repository
import com.sam.twintracker.data.VaccineDose
import com.sam.twintracker.voice.Assistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogFilter(
    val day: Long = Repository.startOfDay(System.currentTimeMillis()),
    val baby: Int = 0,
    val type: EntryType? = null,
    val query: String = "",
    val allDates: Boolean = false,
)

val HEALTH_TYPES = setOf(EntryType.MEDICINE, EntryType.TEMPERATURE, EntryType.WEIGHT, EntryType.HEIGHT)

data class Dashboard(val rangeDays: Int, val dayStarts: List<Long>, val stats: Map<Int, BabyStats>)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as TwinApp).repository
    val prefs = app.getSharedPreferences(TwinApp.PREFS, Context.MODE_PRIVATE)

    val babies: StateFlow<List<Baby>> = repo.babies
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(Baby(1, "Baby 1"), Baby(2, "Baby 2")))

    val doses: StateFlow<List<VaccineDose>> = repo.vaccineDoses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val schedules: StateFlow<List<MedicineSchedule>> = repo.medicineSchedules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ------------------------------------------------------------- ask (typed questions / commands)

    private val assistant = Assistant(repo, prefs)
    val askReply = MutableStateFlow<String?>(null)
    private var pendingBabyFor: String? = null

    fun ask(text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        val pending = pendingBabyFor
        val reply = if (pending != null && assistant.parser().babiesIn(text).isNotEmpty()) assistant.onBabyAnswer(text, pending)
        else assistant.onRequest(text)
        pendingBabyFor = reply.awaitingBabyFor
        askReply.value = if (reply.awaitingRequest) "Type a question or an entry, e.g. \"which baby to feed now\"" else reply.text
    }

    // ------------------------------------------------------------- health

    val healthBaby = MutableStateFlow(1)

    /** Medicine doses and measurements for the selected baby, newest first. */
    val healthEntries: StateFlow<List<Entry>> = healthBaby.flatMapLatest { b ->
        repo.searchRange(0, Long.MAX_VALUE, b, null, "").map { list -> list.filter { it.type in HEALTH_TYPES } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun giveDose(s: MedicineSchedule) = viewModelScope.launch { repo.giveScheduledDose(s.id) }
    fun stopSchedule(s: MedicineSchedule) = viewModelScope.launch { repo.stopSchedule(s) }
    fun deleteSchedule(s: MedicineSchedule) = viewModelScope.launch { repo.deleteSchedule(s) }
    fun addSchedule(s: MedicineSchedule, logDoseNow: Boolean) = viewModelScope.launch {
        val saved = repo.addSchedule(s)
        if (logDoseNow) repo.giveScheduledDose(saved.id)
    }

    val canExactAlarms get() = getApplication<TwinApp>().medicineAlarms.canExact()

    /** Ticks every 30 s so "time since last feed" stays fresh. */
    val now: StateFlow<Long> = flow {
        while (true) { emit(System.currentTimeMillis()); delay(30_000) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    // ------------------------------------------------------------- log

    val logFilter = MutableStateFlow(LogFilter())

    val logEntries: StateFlow<List<Entry>> = logFilter.flatMapLatest { f ->
        if (f.allDates) repo.searchRange(0, Long.MAX_VALUE, f.baby, f.type, f.query)
        else repo.searchDay(f.day, f.baby, f.type, f.query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateFilter(block: (LogFilter) -> LogFilter) { logFilter.value = block(logFilter.value) }

    // ------------------------------------------------------------- dashboard

    val rangeDays = MutableStateFlow(7)

    val dashboard: StateFlow<Dashboard?> = combine(rangeDays, now.map { Repository.startOfDay(it) }) { r, today -> r to today }
        .flatMapLatest { (range, today) ->
            val dayStarts = (range - 1 downTo 0).map { Repository.addDays(today, -it) }
            repo.entriesSince(dayStarts.first()).map { entries ->
                Dashboard(range, dayStarts, mapOf(1 to Analytics.compute(entries, dayStarts, 1), 2 to Analytics.compute(entries, dayStarts, 2)))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ------------------------------------------------------------- actions

    fun addEntry(e: Entry) = viewModelScope.launch { repo.addEntry(e) }
    fun updateEntry(e: Entry) = viewModelScope.launch { repo.updateEntry(e) }
    fun deleteEntry(e: Entry) = viewModelScope.launch { repo.deleteEntry(e) }
    fun saveBaby(b: Baby) = viewModelScope.launch { repo.saveBaby(b) }
    fun setDoseGiven(d: VaccineDose, at: Long?) = viewModelScope.launch { repo.setDoseGiven(d, at) }
    fun addDose(d: VaccineDose) = viewModelScope.launch { repo.addDose(d) }
    fun deleteDose(d: VaccineDose) = viewModelScope.launch { repo.deleteDose(d) }

    fun exportCsv(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val names = repo.babyList().associate { it.id to it.name }
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                fun esc(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.bufferedWriter().use { w ->
                    w.appendLine("time,baby,type,subtype,amount_ml,duration_min,side,value,unit,note,source,voice_text")
                    repo.allEntries().forEach { e ->
                        w.appendLine(
                            listOf(
                                fmt.format(Date(e.timestamp)), esc(names[e.baby]), e.type.label, esc(e.subtype),
                                e.amountMl?.toString() ?: "", e.durationMin?.toString() ?: "", esc(e.side),
                                e.value?.toString() ?: "", esc(e.unit), esc(e.note),
                                e.source, esc(e.rawText),
                            ).joinToString(",")
                        )
                    }
                    w.appendLine()
                    w.appendLine("vaccine_baby,dose,due_age,given")
                    repo.allDoses().forEach { d ->
                        w.appendLine("${esc(names[d.baby])},${esc(d.name)},${esc(d.ageLabel)},${d.givenAt?.let { fmt.format(Date(it)) } ?: ""}")
                    }
                }
            }.isSuccess
        }
        onDone(ok)
    }
}
