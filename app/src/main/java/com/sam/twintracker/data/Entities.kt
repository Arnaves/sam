package com.sam.twintracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class EntryType(val label: String) {
    FEED("Feed"),
    OUTPUT("Diaper"),
    SLEEP("Sleep"),
    VACCINE("Vaccine"),
    MEDICINE("Medicine"),
    TEMPERATURE("Temperature"),
    WEIGHT("Weight"),
    HEIGHT("Height"),
    NOTE("Note"),
}

/** Sub-types. Stored as their name in [Entry.subtype]. */
object FeedKind {
    const val BREAST = "Breast"
    const val FORMULA = "Formula"
    const val EXPRESSED = "Expressed milk"
    const val SOLID = "Solids"
    const val WATER = "Water"
    val all = listOf(BREAST, FORMULA, EXPRESSED, SOLID, WATER)
}

object OutputKind {
    const val WET = "Wet"
    const val DIRTY = "Dirty"
    const val MIXED = "Wet + Dirty"
    val all = listOf(WET, DIRTY, MIXED)
}

@Entity(tableName = "entries", indices = [Index("timestamp"), Index("baby")])
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 1 or 2 */
    val baby: Int,
    val type: EntryType,
    val subtype: String? = null,
    val amountMl: Double? = null,
    val durationMin: Int? = null,
    /** Left / Right / Both for breast feeds */
    val side: String? = null,
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /** "voice" or "manual" */
    val source: String = "manual",
    val rawText: String? = null,
    /** Measurement or medicine dose: temperature (°F/°C), weight (kg), height (cm), dose (ml, drops…). */
    val value: Double? = null,
    val unit: String? = null,
) {
    val isWet get() = type == EntryType.OUTPUT && (subtype == OutputKind.WET || subtype == OutputKind.MIXED)
    val isDirty get() = type == EntryType.OUTPUT && (subtype == OutputKind.DIRTY || subtype == OutputKind.MIXED)

    fun summary(): String = buildString {
        append(type.label)
        subtype?.let { append(" · ").append(it) }
        amountMl?.let { append(" · ").append(formatMl(it)) }
        durationMin?.let { append(" · ").append(formatMinutes(it)) }
        side?.let { append(" · ").append(it) }
        value?.let { append(" · ").append(formatValue(it, unit)) }
    }

    /** Temperature normalised to °C, or null. */
    val celsius: Double?
        get() = if (type != EntryType.TEMPERATURE || value == null) null
        else if (unit == "°F") (value - 32) * 5 / 9 else value

    val isFever get() = (celsius ?: 0.0) >= 38.0
}

@Entity(tableName = "babies")
data class Baby(
    @PrimaryKey val id: Int,
    val name: String,
    /** epoch millis of birth date (local midnight), null if unknown */
    val birthDate: Long? = null,
    /** Comma separated nicknames, e.g. "Chintu, Munna, Aarav". */
    @ColumnInfo(defaultValue = "")
    val aliases: String = "",
) {
    val aliasList: List<String>
        get() = aliases.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /** Every name the baby can be called by voice. */
    val allNames: List<String> get() = (listOf(name) + aliasList).distinct()
}

@Entity(tableName = "medicine_schedules")
data class MedicineSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val baby: Int,
    val name: String,
    /** e.g. "2.5 ml" */
    val dose: String? = null,
    val intervalMinutes: Int,
    /** When the next dose is due; null when finished. */
    val nextDueAt: Long?,
    /** Stop reminding after this time (null = until turned off). */
    val endAt: Long? = null,
    val active: Boolean = true,
    /** One-off timer ("remind me in 4 hours") rather than a repeating course. */
    val oneShot: Boolean = false,
) {
    fun matches(medicine: String?): Boolean {
        val a = medicine?.lowercase()?.trim().orEmpty()
        val b = name.lowercase().trim()
        return a.isNotEmpty() && (a.contains(b) || b.contains(a))
    }
}

@Entity(tableName = "vaccine_doses", indices = [Index("baby")])
data class VaccineDose(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val baby: Int,
    /** key from [VaccineCatalog], or "custom" */
    val vaccineKey: String,
    val name: String,
    val dueAgeDays: Int,
    val ageLabel: String,
    val givenAt: Long? = null,
    val note: String? = null,
)

fun formatMl(ml: Double): String =
    if (ml % 1.0 == 0.0) "${ml.toInt()} ml" else "%.1f ml".format(ml)

fun formatValue(v: Double, unit: String?): String {
    val n = if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
    return if (unit.isNullOrBlank()) n else if (unit.startsWith("°")) "$n $unit" else "$n $unit"
}

fun formatMinutes(min: Int): String =
    if (min >= 60) "${min / 60}h ${min % 60}m" else "$min min"
