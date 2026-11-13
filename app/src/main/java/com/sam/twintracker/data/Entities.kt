package com.sam.twintracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class EntryType(val label: String) {
    FEED("Feed"),
    OUTPUT("Diaper"),
    SLEEP("Sleep"),
    VACCINE("Vaccine"),
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
) {
    val isWet get() = type == EntryType.OUTPUT && (subtype == OutputKind.WET || subtype == OutputKind.MIXED)
    val isDirty get() = type == EntryType.OUTPUT && (subtype == OutputKind.DIRTY || subtype == OutputKind.MIXED)

    fun summary(): String = buildString {
        append(type.label)
        subtype?.let { append(" · ").append(it) }
        amountMl?.let { append(" · ").append(formatMl(it)) }
        durationMin?.let { append(" · ").append(formatMinutes(it)) }
        side?.let { append(" · ").append(it) }
    }
}

@Entity(tableName = "babies")
data class Baby(
    @PrimaryKey val id: Int,
    val name: String,
    /** epoch millis of birth date (local midnight), null if unknown */
    val birthDate: Long? = null,
)

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

fun formatMinutes(min: Int): String =
    if (min >= 60) "${min / 60}h ${min % 60}m" else "$min min"
