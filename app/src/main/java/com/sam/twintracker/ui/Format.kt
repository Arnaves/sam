package com.sam.twintracker.ui

import com.sam.twintracker.data.Repository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {
    fun time(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    fun date(ms: Long): String = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(ms))
    fun shortDate(ms: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))
    fun dayLetter(ms: Long): String = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
    fun dateTime(ms: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(ms))

    fun ago(from: Long, now: Long): String {
        val min = ((now - from) / 60_000).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "${min}m ago"
            min < 48 * 60 -> "${min / 60}h ${min % 60}m ago"
            else -> "${min / 1440}d ago"
        }
    }

    fun age(birth: Long?, now: Long): String {
        if (birth == null) return "Birth date not set"
        val days = ((Repository.startOfDay(now) - birth) / Repository.DAY_MS).toInt()
        if (days < 0) return "Due ${shortDate(birth)}"
        val b = Calendar.getInstance().apply { timeInMillis = birth }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        var months = (n.get(Calendar.YEAR) - b.get(Calendar.YEAR)) * 12 + n.get(Calendar.MONTH) - b.get(Calendar.MONTH)
        if (n.get(Calendar.DAY_OF_MONTH) < b.get(Calendar.DAY_OF_MONTH)) months--
        return when {
            days < 14 -> "$days days old"
            months < 3 -> "${days / 7} weeks ${days % 7} days old"
            months < 24 -> "$months months old"
            else -> "${months / 12} years ${months % 12} months old"
        }
    }

    fun num(v: Double, decimals: Int = 1): String =
        if (v.isNaN()) "–" else if (v % 1.0 == 0.0) v.toInt().toString() else "%.${decimals}f".format(v)
}
