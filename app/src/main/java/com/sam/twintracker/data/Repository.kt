package com.sam.twintracker.data

import com.sam.twintracker.voice.ParsedCommand
import com.sam.twintracker.voice.Snapshot
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

/** Told whenever a medicine timer changes so the alarm can be (re)scheduled or cancelled. */
interface ScheduleListener {
    fun onScheduleChanged(schedule: MedicineSchedule)
    fun onScheduleRemoved(id: Long)
}

/** What a voice/typed command stored, for the spoken confirmation. */
data class SaveResult(val entry: Entry?, val schedule: MedicineSchedule?)

class Repository(private val db: AppDatabase) {

    var scheduleListener: ScheduleListener? = null

    val babies: Flow<List<Baby>> = db.babies().observeAll()
    val vaccineDoses: Flow<List<VaccineDose>> = db.vaccines().observeAll()
    val medicineSchedules: Flow<List<MedicineSchedule>> = db.medicines().observeAll()

    suspend fun ensureDefaults() {
        db.babies().insertIfMissing(Baby(1, "Baby 1"))
        db.babies().insertIfMissing(Baby(2, "Baby 2"))
        for (b in 1..2) {
            if (db.vaccines().countFor(b) == 0) {
                db.vaccines().insertAll(VaccineCatalog.defaultSchedule.map {
                    VaccineDose(baby = b, vaccineKey = it.key, name = it.dose, dueAgeDays = it.ageDays, ageLabel = it.ageLabel)
                })
            }
        }
    }

    suspend fun saveBaby(baby: Baby) = db.babies().upsert(baby)
    suspend fun babyList() = db.babies().all()

    /** Entries for one calendar day (DST-safe). */
    fun searchDay(dayStart: Long, baby: Int, type: EntryType?, q: String): Flow<List<Entry>> =
        searchRange(dayStart, nextDay(dayStart), baby, type, q)

    fun searchRange(from: Long, to: Long, baby: Int, type: EntryType?, q: String): Flow<List<Entry>> =
        db.entries().search(from, to, baby, type?.name, q.trim())

    fun entriesSince(from: Long): Flow<List<Entry>> = db.entries().since(from)

    fun entriesOfType(baby: Int, type: EntryType): Flow<List<Entry>> =
        db.entries().search(0, Long.MAX_VALUE, baby, type.name, "")

    suspend fun addEntry(entry: Entry): Long {
        val id = db.entries().insert(entry)
        when (entry.type) {
            EntryType.VACCINE -> markVaccinesFromText(entry.baby, entry.subtype.orEmpty() + " " + entry.note.orEmpty(), entry.timestamp)
            EntryType.MEDICINE -> advanceMatchingSchedule(entry.baby, entry.subtype, entry.timestamp)
            else -> {}
        }
        return id
    }

    suspend fun updateEntry(entry: Entry) = db.entries().update(entry)
    suspend fun deleteEntry(entry: Entry) = db.entries().delete(entry)
    suspend fun allEntries() = db.entries().all()

    /** Saves a parsed voice/typed command (entry and/or medicine timer). */
    suspend fun saveCommand(cmd: ParsedCommand): SaveResult {
        var entry: Entry? = null
        if (cmd.logEntry) {
            val e = Entry(
                baby = cmd.baby,
                type = cmd.type,
                subtype = cmd.subtype,
                amountMl = cmd.amountMl,
                durationMin = cmd.durationMin,
                side = cmd.side,
                note = cmd.note,
                timestamp = cmd.timestamp,
                source = "voice",
                rawText = cmd.raw,
                value = cmd.value,
                unit = cmd.unit,
            )
            entry = e.copy(id = db.entries().insert(e))
            if (cmd.type == EntryType.VACCINE) cmd.vaccineKeys.forEach { markNextDose(cmd.baby, it, cmd.timestamp) }
        }
        var schedule: MedicineSchedule? = null
        if (cmd.type == EntryType.MEDICINE) {
            val req = cmd.schedule
            schedule = if (req != null) {
                val start = if (cmd.logEntry) cmd.timestamp else System.currentTimeMillis()
                // Replace an existing timer for the same medicine rather than stacking two.
                db.medicines().all().filter { it.baby == cmd.baby && it.active && it.matches(cmd.subtype) }
                    .forEach { stopSchedule(it) }
                addSchedule(
                    MedicineSchedule(
                        baby = cmd.baby,
                        name = cmd.subtype ?: "Medicine",
                        dose = cmd.value?.let { formatValue(it, cmd.unit) },
                        intervalMinutes = req.intervalMinutes.coerceAtLeast(1),
                        nextDueAt = start + req.intervalMinutes * 60_000L,
                        endAt = req.days?.let { start + it * DAY_MS },
                        oneShot = req.oneShot,
                    )
                )
            } else if (cmd.logEntry) {
                advanceMatchingSchedule(cmd.baby, cmd.subtype, cmd.timestamp)
            } else null
        }
        return SaveResult(entry, schedule)
    }

    // ------------------------------------------------------------- medicine timers

    suspend fun addSchedule(s: MedicineSchedule): MedicineSchedule {
        val saved = s.copy(id = db.medicines().insert(s))
        scheduleListener?.onScheduleChanged(saved)
        return saved
    }

    suspend fun updateSchedule(s: MedicineSchedule) {
        db.medicines().update(s)
        scheduleListener?.onScheduleChanged(s)
    }

    suspend fun stopSchedule(s: MedicineSchedule) = updateSchedule(s.copy(active = false, nextDueAt = null))

    suspend fun deleteSchedule(s: MedicineSchedule) {
        db.medicines().delete(s)
        scheduleListener?.onScheduleRemoved(s.id)
    }

    suspend fun schedule(id: Long) = db.medicines().get(id)
    suspend fun allSchedules() = db.medicines().all()

    /** "Given" from the reminder notification or the Health screen: logs the dose and moves the timer on. */
    suspend fun giveScheduledDose(id: Long, at: Long = System.currentTimeMillis()): MedicineSchedule? {
        val s = db.medicines().get(id) ?: return null
        val (value, unit) = splitDose(s.dose)
        db.entries().insert(
            Entry(baby = s.baby, type = EntryType.MEDICINE, subtype = s.name, value = value, unit = unit, timestamp = at, source = "manual")
        )
        return advance(s, at)
    }

    private suspend fun advanceMatchingSchedule(baby: Int, medicine: String?, at: Long): MedicineSchedule? {
        val s = db.medicines().all().firstOrNull { it.baby == baby && it.active && it.matches(medicine) } ?: return null
        return advance(s, at)
    }

    private suspend fun advance(s: MedicineSchedule, givenAt: Long): MedicineSchedule {
        val next = givenAt + s.intervalMinutes * 60_000L
        val finished = s.oneShot || (s.endAt != null && next > s.endAt)
        val updated = if (finished) s.copy(active = false, nextDueAt = null) else s.copy(nextDueAt = next)
        updateSchedule(updated)
        return updated
    }

    private fun splitDose(dose: String?): Pair<Double?, String?> {
        val m = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*(.*)$").find(dose.orEmpty()) ?: return null to dose
        return m.groupValues[1].toDouble() to m.groupValues[2].ifBlank { null }
    }

    // ------------------------------------------------------------- vaccines

    private suspend fun markVaccinesFromText(baby: Int, text: String, at: Long) {
        VaccineCatalog.match(text).forEach { markNextDose(baby, it, at) }
    }

    private suspend fun markNextDose(baby: Int, key: String, at: Long) {
        db.vaccines().nextPending(baby, key)?.let { db.vaccines().update(it.copy(givenAt = at)) }
    }

    /** Marks a dose given from the vaccine screen and logs it in the daily log too. */
    suspend fun setDoseGiven(dose: VaccineDose, givenAt: Long?) {
        db.vaccines().update(dose.copy(givenAt = givenAt))
        if (givenAt != null) {
            db.entries().insert(
                Entry(baby = dose.baby, type = EntryType.VACCINE, subtype = dose.name, timestamp = givenAt, source = "manual")
            )
        }
    }

    suspend fun addDose(dose: VaccineDose) = db.vaccines().insert(dose)
    suspend fun deleteDose(dose: VaccineDose) = db.vaccines().delete(dose)
    suspend fun allDoses() = db.vaccines().all()

    /** Data for answering questions. Growth entries are kept for all time; everything else 30 days. */
    suspend fun snapshot(now: Long = System.currentTimeMillis()): Snapshot {
        val from = now - 30 * DAY_MS
        val entries = db.entries().all().filter {
            it.timestamp >= from || it.type == EntryType.WEIGHT || it.type == EntryType.HEIGHT
        }
        return Snapshot(babyList(), entries, allSchedules(), allDoses(), now)
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun nextDay(dayStart: Long): Long = Calendar.getInstance().apply {
            timeInMillis = dayStart; add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

        fun addDays(dayStart: Long, days: Int): Long = Calendar.getInstance().apply {
            timeInMillis = dayStart; add(Calendar.DAY_OF_YEAR, days)
        }.timeInMillis
    }
}
