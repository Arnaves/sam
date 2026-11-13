package com.sam.twintracker.voice

import com.sam.twintracker.data.Baby
import com.sam.twintracker.data.Entry
import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.MedicineSchedule
import com.sam.twintracker.data.VaccineDose
import com.sam.twintracker.data.formatValue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Everything needed to answer a question, read from the database at the moment it is asked. */
data class Snapshot(
    val babies: List<Baby>,
    /** Recent entries (e.g. the last 30 days), any order. */
    val entries: List<Entry>,
    val schedules: List<MedicineSchedule>,
    val doses: List<VaccineDose>,
    val now: Long,
)

/** Builds short spoken answers. Uses names, never pronouns. */
object Answerer {

    fun answer(q: Question, s: Snapshot): String {
        val ids = q.babies.ifEmpty { s.babies.map { it.id }.ifEmpty { listOf(1, 2) } }
        return when (q.kind) {
            QuestionKind.WHICH_TO_FEED -> whichToFeed(ids, s)
            QuestionKind.LAST -> ids.joinToString(" ") { last(it, q.topic ?: EntryType.FEED, q.dirtyOnly, s) }
            QuestionKind.NEXT_MEDICINE -> nextMedicine(ids, s)
            QuestionKind.NEXT_VACCINE -> ids.joinToString(" ") { nextVaccine(it, s) }
            QuestionKind.TODAY -> ids.joinToString(" ") { today(it, s) }
            QuestionKind.UNKNOWN ->
                "Sorry, I didn't understand. You can ask: when did baby 1 last eat, which baby to feed now, or when is the next medicine."
        }
    }

    private fun name(id: Int, s: Snapshot) = s.babies.firstOrNull { it.id == id }?.name ?: "Baby $id"

    private fun feeds(id: Int, s: Snapshot) = s.entries.filter { it.baby == id && it.type == EntryType.FEED }.sortedBy { it.timestamp }

    /** Typical minutes between feeds over the snapshot, ignoring overnight gaps where logging stopped. */
    fun typicalGap(id: Int, s: Snapshot): Int? {
        val gaps = feeds(id, s).zipWithNext { a, b -> (b.timestamp - a.timestamp) / 60_000 }.filter { it in 30..(8 * 60) }
        return if (gaps.size < 2) null else gaps.sorted()[gaps.size / 2].toInt()
    }

    private fun whichToFeed(ids: List<Int>, s: Snapshot): String {
        data class F(val id: Int, val last: Entry?, val since: Long?, val gap: Int?)
        val info = ids.map { id ->
            val last = feeds(id, s).lastOrNull()
            F(id, last, last?.let { (s.now - it.timestamp) / 60_000 }, typicalGap(id, s))
        }
        val medicine = nextMedicine(ids, s, onlyDueWithinMin = 30)

        if (info.size == 1) {
            val f = info.single()
            val n = name(f.id, s)
            if (f.last == null) return "No feed is logged for $n yet." + medicine
            val base = "$n was last fed ${spoken(f.since!!)} ago${feedDetail(f.last)}."
            val due = when {
                f.gap == null -> ""
                f.since >= f.gap -> " $n usually feeds every ${spoken(f.gap.toLong())}, so $n is due now."
                else -> " $n usually feeds every ${spoken(f.gap.toLong())}, so the next feed is in about ${spoken(f.gap - f.since)}."
            }
            return base + due + medicine
        }

        val never = info.filter { it.last == null }
        if (never.size == info.size) return "No feeds are logged yet for either baby." + medicine
        if (never.isNotEmpty()) {
            return "Feed ${never.joinToString(" and ") { name(it.id, s) }} — no feed logged yet. " +
                info.filter { it.last != null }.joinToString(" ") { "${name(it.id, s)} was fed ${spoken(it.since!!)} ago." } + medicine
        }
        // Rank by how overdue each baby is relative to their own rhythm (fallback: time since last feed).
        val ranked = info.sortedByDescending { f -> if (f.gap != null) f.since!!.toDouble() / f.gap else f.since!!.toDouble() / 180 }
        val first = ranked[0]
        val second = ranked[1]
        val details = ranked.joinToString(", ") { "${name(it.id, s)} ${spoken(it.since!!)} ago" }
        if (kotlin.math.abs(first.since!! - second.since!!) <= 15) {
            return "Both were fed around the same time: $details. Feed both together." + medicine
        }
        val dueText = first.gap?.let { if (first.since >= it) ", and is due" else "" } ?: ""
        return "Feed ${name(first.id, s)} first — last fed ${spoken(first.since)} ago$dueText. " +
            "${name(second.id, s)} was fed ${spoken(second.since)} ago." + medicine
    }

    private fun feedDetail(e: Entry): String {
        val parts = listOfNotNull(
            e.amountMl?.let { "${fmt(it)} ml" },
            e.subtype?.lowercase(),
            e.durationMin?.let { spoken(it.toLong()) },
            e.side?.let { "$it side".lowercase() },
        )
        return " at ${clock(e.timestamp)}" + if (parts.isEmpty()) "" else ", " + parts.joinToString(" ")
    }

    private fun last(id: Int, topic: EntryType, dirtyOnly: Boolean, s: Snapshot): String {
        val n = name(id, s)
        val list = s.entries.filter { it.baby == id && it.type == topic && (!dirtyOnly || it.isDirty) }.sortedBy { it.timestamp }
        val e = list.lastOrNull()
        if (topic == EntryType.VACCINE) {
            val given = s.doses.filter { it.baby == id && it.givenAt != null }.maxByOrNull { it.givenAt!! }
            return if (given == null) "No vaccine has been marked as given for $n." else "$n's last vaccine was ${given.name} on ${day(given.givenAt!!, s.now)}."
        }
        if (e == null) {
            val what = when (topic) {
                EntryType.FEED -> "feed"
                EntryType.OUTPUT -> if (dirtyOnly) "dirty diaper" else "diaper"
                EntryType.SLEEP -> "sleep"
                EntryType.MEDICINE -> "medicine"
                EntryType.TEMPERATURE -> "temperature"
                EntryType.WEIGHT -> "weight"
                EntryType.HEIGHT -> "height"
                else -> "entry"
            }
            return "No $what is logged for $n recently."
        }
        val ago = spoken((s.now - e.timestamp) / 60_000)
        return when (topic) {
            EntryType.FEED -> "$n was last fed $ago ago${feedDetail(e)}." + todayCount(id, s)
            EntryType.OUTPUT -> "$n's last ${if (dirtyOnly) "dirty diaper" else "diaper"} was $ago ago at ${clock(e.timestamp)}, ${e.subtype?.lowercase() ?: "changed"}."
            EntryType.SLEEP -> "$n last slept $ago ago at ${clock(e.timestamp)}" + (e.durationMin?.let { " for ${spoken(it.toLong())}" } ?: "") + "."
            EntryType.MEDICINE -> "$n last had ${e.subtype ?: "medicine"}" + (e.value?.let { " ${formatValue(it, e.unit)}" } ?: "") +
                " $ago ago at ${clock(e.timestamp)}." + nextMedicine(listOf(id), s, onlyDueWithinMin = null, prefix = " ")
            EntryType.TEMPERATURE -> "$n's temperature was ${formatValue(e.value ?: 0.0, e.unit)} $ago ago" +
                (if (e.isFever) ", which is a fever." else ", which is normal.")
            EntryType.WEIGHT -> "$n weighed ${formatValue(e.value ?: 0.0, e.unit)} on ${day(e.timestamp, s.now)}." + change(list)
            EntryType.HEIGHT -> "$n measured ${formatValue(e.value ?: 0.0, e.unit)} on ${day(e.timestamp, s.now)}." + change(list)
            else -> "$n's last entry was $ago ago."
        }
    }

    private fun change(list: List<Entry>): String {
        if (list.size < 2) return ""
        val a = list[list.size - 2]
        val b = list.last()
        val before = a.value ?: return ""
        val after = b.value ?: return ""
        val d = after - before
        val unit = b.unit ?: ""
        return " That's ${if (d >= 0) "up" else "down"} ${formatValue(kotlin.math.abs(d), unit)} from ${formatValue(before, unit)}."
    }

    private fun todayCount(id: Int, s: Snapshot): String {
        val start = startOfDay(s.now)
        val today = feeds(id, s).filter { it.timestamp >= start }
        if (today.isEmpty()) return ""
        val ml = today.sumOf { it.amountMl ?: 0.0 }
        return " ${today.size} feed${if (today.size == 1) "" else "s"} today" + (if (ml > 0) ", ${fmt(ml)} ml in total." else ".")
    }

    fun nextMedicine(ids: List<Int>, s: Snapshot, onlyDueWithinMin: Int? = null, prefix: String = " "): String {
        val active = s.schedules.filter { it.active && it.nextDueAt != null && it.baby in ids }.sortedBy { it.nextDueAt }
        val relevant = if (onlyDueWithinMin == null) active else active.filter { it.nextDueAt!! - s.now <= onlyDueWithinMin * 60_000L }
        if (relevant.isEmpty()) {
            return if (onlyDueWithinMin == null && prefix == " " && ids.size > 1) "No medicine timers are running." else ""
        }
        return prefix + relevant.joinToString(" ") { m ->
            val mins = (m.nextDueAt!! - s.now) / 60_000
            val what = "${name(m.baby, s)}'s ${m.name}" + (m.dose?.let { " ($it)" } ?: "")
            if (mins <= 0) "$what is due now" + (if (mins < -1) ", ${spoken(-mins)} late." else ".")
            else "$what is due in ${spoken(mins)}, at ${clock(m.nextDueAt)}."
        }
    }

    private fun nextVaccine(id: Int, s: Snapshot): String {
        val n = name(id, s)
        val dose = s.doses.filter { it.baby == id && it.givenAt == null }.minByOrNull { it.dueAgeDays }
            ?: return "All scheduled vaccines for $n are done."
        val dob = s.babies.firstOrNull { it.id == id }?.birthDate
            ?: return "$n's next vaccine is ${dose.name}, due at ${dose.ageLabel}. Set the birth date to get exact dates."
        val due = Calendar.getInstance().apply { timeInMillis = dob; add(Calendar.DAY_OF_YEAR, dose.dueAgeDays) }.timeInMillis
        val days = ((startOfDay(due) - startOfDay(s.now)) / 86_400_000L).toInt()
        val whenText = when {
            days < 0 -> "was due ${-days} day${if (days == -1) "" else "s"} ago, on ${day(due, s.now)}"
            days == 0 -> "is due today"
            days == 1 -> "is due tomorrow"
            else -> "is due in $days days, on ${day(due, s.now)}"
        }
        return "$n's next vaccine, ${dose.name}, $whenText."
    }

    private fun today(id: Int, s: Snapshot): String {
        val start = startOfDay(s.now)
        val d = s.entries.filter { it.baby == id && it.timestamp >= start }
        val feeds = d.filter { it.type == EntryType.FEED }
        val ml = feeds.sumOf { it.amountMl ?: 0.0 }
        val sleep = d.filter { it.type == EntryType.SLEEP }.sumOf { it.durationMin ?: 0 }
        val meds = d.count { it.type == EntryType.MEDICINE }
        return "Today ${name(id, s)} had ${feeds.size} feed${if (feeds.size == 1) "" else "s"}" +
            (if (ml > 0) " totalling ${fmt(ml)} ml" else "") +
            ", ${d.count { it.isWet }} wet and ${d.count { it.isDirty }} dirty diapers" +
            (if (sleep > 0) ", and slept ${spoken(sleep.toLong())}" else "") +
            (if (meds > 0) ", with $meds medicine dose${if (meds == 1) "" else "s"}" else "") + "."
    }

    // ------------------------------------------------------------------ formatting

    fun spoken(minutes: Long): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m < 1 -> "less than a minute"
            m < 60 -> "$m minute${if (m == 1L) "" else "s"}"
            m < 48 * 60 -> {
                val h = m / 60; val r = m % 60
                "$h hour${if (h == 1L) "" else "s"}" + if (r > 0) " $r minute${if (r == 1L) "" else "s"}" else ""
            }
            else -> "${m / 1440} days"
        }
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
    private fun clock(ms: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    private fun day(ms: Long, now: Long): String {
        val d = ((startOfDay(now) - startOfDay(ms)) / 86_400_000L).toInt()
        return when (d) {
            0 -> "today"
            1 -> "yesterday"
            else -> SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(ms))
        }
    }

    private fun startOfDay(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
