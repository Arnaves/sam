package com.sam.twintracker.data

import com.sam.twintracker.voice.ParsedCommand
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class Repository(private val db: AppDatabase) {

    val babies: Flow<List<Baby>> = db.babies().observeAll()
    val vaccineDoses: Flow<List<VaccineDose>> = db.vaccines().observeAll()

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

    suspend fun addEntry(entry: Entry): Long {
        val id = db.entries().insert(entry)
        if (entry.type == EntryType.VACCINE) markVaccinesFromText(entry.baby, entry.subtype.orEmpty() + " " + entry.note.orEmpty(), entry.timestamp)
        return id
    }

    suspend fun updateEntry(entry: Entry) = db.entries().update(entry)
    suspend fun deleteEntry(entry: Entry) = db.entries().delete(entry)
    suspend fun allEntries() = db.entries().all()

    /** Saves a parsed voice command. Returns the stored entry. */
    suspend fun saveVoice(cmd: ParsedCommand): Entry {
        val entry = Entry(
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
        )
        val id = db.entries().insert(entry)
        if (cmd.type == EntryType.VACCINE) {
            for (key in cmd.vaccineKeys) markNextDose(cmd.baby, key, cmd.timestamp)
        }
        return entry.copy(id = id)
    }

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
