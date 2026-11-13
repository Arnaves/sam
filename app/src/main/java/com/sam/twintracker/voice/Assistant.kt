package com.sam.twintracker.voice

import android.content.SharedPreferences
import com.sam.twintracker.TwinApp
import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.Repository
import com.sam.twintracker.data.SaveResult
import com.sam.twintracker.data.formatMinutes
import com.sam.twintracker.data.formatMl
import com.sam.twintracker.data.formatValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The reply to one utterance or typed request. */
data class Reply(
    val text: String,
    /** A command was given without naming a baby: ask "which baby?" and keep this text. */
    val awaitingBabyFor: String? = null,
    /** Only the wake word was heard: listen for the request next. */
    val awaitingRequest: Boolean = false,
    val handled: Boolean = true,
)

/**
 * Turns a [ProcessResult] into saved entries and spoken answers. Shared by the background voice
 * service and the "Ask" box in the app, so both behave the same.
 */
class Assistant(private val repo: Repository, private val prefs: SharedPreferences) {

    val wakeWord: String
        get() = prefs.getString(TwinApp.PREF_WAKE_WORD, null)?.takeIf { it.isNotBlank() } ?: CommandParser.DEFAULT_WAKE_WORD

    suspend fun parser(): CommandParser =
        CommandParser(repo.babyList().associate { it.id to it.allNames }, wakeWord)

    /** Voice: requires the wake word. */
    suspend fun onUtterance(alternatives: List<String>, now: Long = System.currentTimeMillis()): Reply? {
        val p = parser()
        val heard = alternatives.firstOrNull { p.hasWakeWord(it) } ?: return null
        return run(p.process(heard, now), now)
    }

    /** The utterance after "Yes?", or a typed request in the app — no wake word needed. */
    suspend fun onRequest(text: String, now: Long = System.currentTimeMillis()): Reply {
        val p = parser()
        val result = if (p.hasWakeWord(text)) p.process(text, now) else p.processRequest(text, text, now)
        return run(result, now)
    }

    /** Reply to "which baby?" for a pending command. */
    suspend fun onBabyAnswer(answer: String, pendingCommand: String, now: Long = System.currentTimeMillis()): Reply {
        val p = parser()
        val babies = p.babiesIn(answer)
        if (babies.isEmpty()) return Reply("Sorry, I didn't catch which baby. Nothing was saved.")
        return run(ProcessResult(commands = p.parseCommand(babies, pendingCommand, pendingCommand, now), awake = true), now)
    }

    private suspend fun run(result: ProcessResult, now: Long): Reply {
        if (result.wakeOnly) return Reply("Yes?", awaitingRequest = true)
        val parts = mutableListOf<String>()

        if (result.commands.isNotEmpty()) {
            val names = repo.babyList().associate { it.id to it.name }
            val saved = result.commands.map { it to repo.saveCommand(it) }
            parts += "Saved. " + saved.groupBy { it.first.baby }.entries.joinToString(". ") { (baby, list) ->
                "${names[baby] ?: "Baby $baby"}: " + list.joinToString(", ") { (cmd, res) -> describe(cmd, res) }
            } + "."
            saved.filter { it.first.type == EntryType.TEMPERATURE && isFever(it.first) }.forEach {
                parts += "That is a fever for ${names[it.first.baby] ?: "Baby ${it.first.baby}"}."
            }
        }
        if (result.questions.isNotEmpty()) {
            val snap = repo.snapshot(now)
            result.questions.forEach { parts += Answerer.answer(it, snap) }
        }
        if (result.needsBaby != null && parts.isEmpty()) {
            return Reply("For which baby?", awaitingBabyFor = result.needsBaby)
        }
        if (parts.isEmpty()) return Reply("Sorry, I didn't get that.", handled = false)
        return Reply(parts.joinToString(" "))
    }

    private fun isFever(c: ParsedCommand): Boolean {
        val v = c.value ?: return false
        return if (c.unit == "°F") v >= 100.4 else v >= 38.0
    }

    private fun describe(c: ParsedCommand, r: SaveResult): String = buildString {
        when (c.type) {
            EntryType.MEDICINE -> {
                if (c.logEntry) append(c.subtype ?: "Medicine") else append("${c.subtype ?: "Medicine"} timer")
                c.value?.let { append(" ").append(formatValue(it, c.unit)) }
                r.schedule?.let { s ->
                    if (s.active && s.nextDueAt != null) {
                        if (c.schedule != null && !s.oneShot) append(", every ${spokenInterval(s.intervalMinutes)}")
                        append(", next dose at ${clock(s.nextDueAt)}")
                    } else if (c.schedule == null) append(", course finished")
                }
            }
            EntryType.TEMPERATURE, EntryType.WEIGHT, EntryType.HEIGHT ->
                append("${c.type.label.lowercase()} ${formatValue(c.value ?: 0.0, c.unit)}")
            EntryType.NOTE -> append("note: ${c.note.orEmpty()}")
            EntryType.VACCINE -> append(c.subtype ?: "vaccine")
            else -> {
                append(c.subtype ?: c.type.label)
                if (c.subtype != null && c.type.label != c.subtype) append(" ${c.type.label.lowercase()}")
                c.amountMl?.let { append(" ").append(formatMl(it)) }
                c.durationMin?.let { append(" ").append(formatMinutes(it)) }
                c.side?.let { append(" $it side") }
            }
        }
    }

    private fun spokenInterval(min: Int) = if (min % 60 == 0) "${min / 60} hours" else Answerer.spoken(min.toLong())
    private fun clock(ms: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
}
