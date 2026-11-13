package com.sam.twintracker.voice

import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.FeedKind
import com.sam.twintracker.data.OutputKind
import com.sam.twintracker.data.VaccineCatalog
import java.util.Calendar
import kotlin.math.roundToInt

data class ParsedCommand(
    val baby: Int,
    val type: EntryType,
    val subtype: String? = null,
    val amountMl: Double? = null,
    val durationMin: Int? = null,
    val side: String? = null,
    val note: String? = null,
    val timestamp: Long,
    val vaccineKeys: List<String> = emptyList(),
    val raw: String,
)

/** One trigger phrase found in an utterance plus the words spoken after it. */
data class TriggerSegment(val babies: List<Int>, val text: String)

data class ProcessResult(
    /** Fully parsed entries, ready to save. */
    val commands: List<ParsedCommand>,
    /** Babies whose trigger was said with nothing after it — wait for the next utterance. */
    val armedFor: List<Int>,
) {
    val triggered get() = commands.isNotEmpty() || armedFor.isNotEmpty()
}

/**
 * Turns recognised speech into log entries.
 *
 * Trigger phrases: "entry for baby 1", "entry for baby 2", "entry for both babies"
 * (plus the babies' own names, e.g. "entry for Aria"). Everything said after the trigger is the
 * command, e.g.:
 *
 *  - "entry for baby 1 fed 120 ml formula"
 *  - "entry for baby 2 breastfed 15 minutes left side"
 *  - "entry for baby 1 wet diaper" / "pooped" / "wet and dirty"
 *  - "entry for baby 2 slept 2 hours"
 *  - "entry for baby 1 vaccine BCG and hepatitis B"
 *  - "entry for both babies fed 90 ml at 3:30 pm"
 *  - "entry for baby 2 note slight fever" (anything unrecognised is kept as a note)
 */
class CommandParser(babyNames: Map<String, Int> = emptyMap()) {

    private val names: Map<String, Int> = babyNames
        .mapKeys { normalize(it.key) }
        .filterKeys { it.isNotBlank() && it != "baby 1" && it != "baby 2" }

    private val triggerRegex: Regex = run {
        val entry = "(?:entry|entries|entery|entri|andrey|andre|entree|enter|in tree|n tree|add entry|log)"
        val prep = "(?:\\s+(?:for|four|of|from|to|4|on))?"
        val number = "(?:1|one|won|first|2|two|to|too|tu|second)"
        val baby = "(?:the\\s+)?(?:baby|bebe|babe)\\s*(?:number\\s*)?($number)\\b"
        val both = "(both(?:\\s+(?:babies|baby|twins|of them))?|(?:the\\s+)?twins|all babies)"
        val nameAlt = if (names.isEmpty()) "" else "|(" + names.keys.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) } + ")\\b"
        Regex("\\b$entry$prep\\s+(?:$baby|$both$nameAlt)")
    }

    fun segments(raw: String): List<TriggerSegment> {
        val text = normalize(raw)
        val matches = triggerRegex.findAll(text).toList()
        return matches.mapIndexed { i, m ->
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            // group 1 = baby number, group 2 = "both", group 3 = a baby's name
            val g = m.groupValues
            val babies = when {
                g.getOrNull(1).orEmpty().isNotEmpty() -> listOf(babyNumber(g[1]))
                g.getOrNull(2).orEmpty().isNotEmpty() -> listOf(1, 2)
                else -> listOf(names[g.getOrNull(3)] ?: 1)
            }
            TriggerSegment(babies, text.substring(m.range.last + 1, end).trim())
        }
    }

    fun hasTrigger(raw: String) = triggerRegex.containsMatchIn(normalize(raw))

    /** Full pipeline for one utterance. */
    fun process(raw: String, now: Long = System.currentTimeMillis()): ProcessResult {
        val commands = mutableListOf<ParsedCommand>()
        val armed = mutableListOf<Int>()
        for (seg in segments(raw)) {
            val parsed = parseCommand(seg.babies, seg.text, raw, now)
            if (parsed.isEmpty()) armed += seg.babies else commands += parsed
        }
        return ProcessResult(commands, armed.distinct())
    }

    /**
     * Parses the command words (without trigger) for the given babies.
     * Returns an empty list when nothing meaningful was said.
     */
    fun parseCommand(babies: List<Int>, commandText: String, raw: String = commandText, now: Long = System.currentTimeMillis()): List<ParsedCommand> {
        var t = wordsToNumbers(normalize(commandText))
        t = t.replace(Regex("^(?:is|was|has|had|just|got|and|then|please)\\s+"), "").trim()
        if (t.isBlank() || t.matches(Regex("(?:ok|okay|um|uh|hmm|cancel|stop|nothing)"))) return emptyList()

        // --- when ---
        var timestamp = now
        Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(minutes?|mins?|hours?|hrs?)\\s+ago\\b").find(t)?.let { m ->
            val v = m.groupValues[1].toDouble()
            val ms = if (m.groupValues[2].startsWith("h")) v * 3_600_000 else v * 60_000
            timestamp = now - ms.toLong()
            t = t.removeRange(m.range).trim()
        }
        Regex("\\b(?:at|around)\\s+(\\d{1,2})(?::(\\d{2})|\\s+(\\d{2}))?\\s*(am|pm|in the morning|in the evening|at night)?\\b").find(t)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val minute = (m.groupValues[2].ifEmpty { m.groupValues[3] }).ifEmpty { "0" }.toInt()
            val mer = m.groupValues[4]
            if (hour in 0..23 && minute in 0..59) {
                if ((mer == "pm" || mer == "in the evening" || mer == "at night") && hour < 12) hour += 12
                if (mer == "am" && hour == 12) hour = 0
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                // No meridiem and the time is in the future: try the PM/AM twin earlier today, else yesterday.
                if (cal.timeInMillis > now + 5 * 60_000 && mer.isEmpty() && hour in 13..23) cal.add(Calendar.HOUR_OF_DAY, -12)
                if (cal.timeInMillis > now + 5 * 60_000) cal.add(Calendar.DAY_OF_YEAR, -1)
                if (cal.timeInMillis > now + 5 * 60_000) cal.timeInMillis = now
                timestamp = cal.timeInMillis
                t = t.removeRange(m.range).trim()
            }
        }

        // --- quantities ---
        var amountMl: Double? = null
        Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(ml|mls|milliliters?|millilitres?|cc|m l)\\b").find(t)?.let {
            amountMl = it.groupValues[1].toDouble(); t = t.removeRange(it.range).trim()
        } ?: Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(oz|ounces?)\\b").find(t)?.let {
            amountMl = (it.groupValues[1].toDouble() * 29.5735).roundToInt().toDouble(); t = t.removeRange(it.range).trim()
        }

        var durationMin: Int? = null
        Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(?:hours?|hrs?|hour)\\b(?:\\s*(?:and\\s*)?(\\d+)\\s*(?:minutes?|mins?))?").find(t)?.let {
            durationMin = (it.groupValues[1].toDouble() * 60).roundToInt() + (it.groupValues[2].toIntOrNull() ?: 0)
            t = t.removeRange(it.range).trim()
        } ?: Regex("\\b(\\d+)\\s*(?:minutes?|mins?|min)\\b").find(t)?.let {
            durationMin = it.groupValues[1].toInt(); t = t.removeRange(it.range).trim()
        }

        val w = " $t "
        fun has(vararg words: String) = words.any { w.contains(" $it ") }

        // --- what ---
        val vaccineKeys = VaccineCatalog.match(t)
        val isVaccine = vaccineKeys.isNotEmpty() || has(
            "vaccine", "vaccines", "vaccinated", "vaccination", "vaccinations", "shot", "shots", "jab", "jabs",
            "injection", "immunization", "immunisation", "immunized", "immunised", "dose",
        )

        val wet = has("pee", "peed", "peeing", "wee", "weed", "urine", "urinated", "wet", "pissed", "number 1", "susu", "sussu")
        val dirty = has(
            "poop", "pooped", "pooping", "poo", "pooed", "potty", "stool", "stools", "dirty", "bowel", "motion", "motions",
            "soiled", "number 2", "poopy", "pottied", "bm",
        )
        val diaperWord = has("diaper", "diapers", "nappy", "nappies", "output", "diaper change", "changed")
        val isOutput = wet || dirty || diaperWord

        val isSleep = has("sleep", "slept", "sleeping", "nap", "napped", "napping", "asleep", "woke", "woke up")

        val solids = has("solids", "solid", "puree", "cereal", "food", "meal", "porridge", "khichdi", "fruit", "banana", "rice", "dal", "mashed")
        val water = has("water") && !has("milk")
        val breast = has("breast", "breastfed", "breastfeed", "breastfeeding", "breast fed", "breast feeding", "nursed", "nursing", "nurse", "latched", "latch")
        val expressed = has("expressed", "pumped", "breast milk", "breastmilk", "mothers milk", "mother milk")
        val formula = has("formula")
        val feedWord = has(
            "feed", "fed", "feeding", "fid", "ate", "eat", "eaten", "eating", "drank", "drink", "drinking", "milk", "bottle",
            "had", "took", "finished", "feeds",
        )
        val isFeed = breast || expressed || formula || solids || water || feedWord ||
            (amountMl != null && !isOutput && !isVaccine)

        val side = when {
            has("both sides", "both side", "both breasts") -> "Both"
            has("left") -> "Left"
            has("right") -> "Right"
            else -> null
        }

        val noteText = Regex("\\b(?:note|notes|comment|remark)\\b\\s*(.*)$").find(t)?.groupValues?.get(1)?.trim()?.ifBlank { null }

        val out = mutableListOf<ParsedCommand>()
        for (baby in babies) {
            fun cmd(type: EntryType, subtype: String? = null, amount: Double? = null, dur: Int? = null, sd: String? = null, note: String? = noteText, keys: List<String> = emptyList()) =
                ParsedCommand(baby, type, subtype, amount, dur, sd, note, timestamp, keys, raw)

            if (isVaccine) {
                val name = if (vaccineKeys.isEmpty()) null else vaccineKeys.joinToString(", ") { VaccineCatalog.nameFor(it) }
                out += cmd(EntryType.VACCINE, subtype = name, keys = vaccineKeys, note = noteText ?: if (name == null) t else null)
                continue // a vaccine utterance rarely also describes a feed ("dose" etc.)
            }
            val sleepTakesDuration = isSleep && !breast
            if (isFeed && (feedWord || breast || expressed || formula || solids || water || amountMl != null)) {
                val kind = when {
                    expressed -> FeedKind.EXPRESSED
                    breast -> FeedKind.BREAST
                    formula -> FeedKind.FORMULA
                    solids -> FeedKind.SOLID
                    water -> FeedKind.WATER
                    else -> null
                }
                // Avoid "had a wet diaper" being read as a feed via the word "had".
                val onlyWeakFeedWord = kind == null && amountMl == null && !has("feed", "fed", "feeding", "fid", "ate", "eat", "eaten", "eating", "drank", "drink", "drinking", "milk", "bottle", "feeds")
                if (!(onlyWeakFeedWord && (isOutput || isSleep))) {
                    out += cmd(
                        EntryType.FEED, subtype = kind, amount = amountMl,
                        dur = if (sleepTakesDuration) null else durationMin,
                        sd = if (kind == FeedKind.BREAST || side != null) side else null,
                    )
                }
            }
            if (isOutput) {
                val kind = when {
                    wet && dirty -> OutputKind.MIXED
                    dirty -> OutputKind.DIRTY
                    else -> OutputKind.WET
                }
                out += cmd(EntryType.OUTPUT, subtype = kind, note = noteText ?: if (!wet && !dirty) "Diaper change" else null)
            }
            if (isSleep) {
                out += cmd(EntryType.SLEEP, dur = if (sleepTakesDuration || out.none { it.type == EntryType.FEED && it.baby == baby }) durationMin else null)
            }
            if (out.none { it.baby == baby }) {
                out += cmd(EntryType.NOTE, note = noteText ?: t)
            }
        }
        return out
    }

    companion object {
        fun normalize(s: String): String = s.lowercase()
            .replace(Regex("\\b([ap])\\.\\s?m\\.?"), "$1m")
            .replace(Regex("(\\d)\\s*(st|nd|rd|th)\\b"), "$1")
            .replace(Regex("[^a-z0-9:.\\s]"), " ")
            .replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
            .replace(Regex("(?<!\\d):|:(?!\\d)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun babyNumber(token: String) = when (token) {
            "1", "one", "won", "first" -> 1
            else -> 2
        }

        private val units = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
            "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
            "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        )
        private val tens = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
        )

        /** "one hundred and twenty ml" -> "120 ml", "half an hour" -> "30 minutes", "an hour" -> "1 hour". */
        fun wordsToNumbers(input: String): String {
            var s = " $input "
                .replace(" half an hour ", " 30 minutes ")
                .replace(" half hour ", " 30 minutes ")
                .replace(" an hour and a half ", " 90 minutes ")
                .replace(" one and a half hours ", " 90 minutes ")
                .replace(" an hour ", " 1 hour ")
                .replace(" a hundred ", " one hundred ")
            val tokens = s.trim().split(" ")
            val out = mutableListOf<String>()
            var i = 0
            while (i < tokens.size) {
                var total = 0
                var current = 0
                var consumed = 0
                var j = i
                while (j < tokens.size) {
                    val tk = tokens[j]
                    when {
                        // A unit/teen may start a group, follow "hundred", or follow a tens word ("twenty five").
                        units.containsKey(tk) && (current % 100 == 0 || (current % 100 >= 20 && current % 10 == 0 && units.getValue(tk) in 1..9)) ->
                            { current += units.getValue(tk); consumed = j - i + 1 }
                        tens.containsKey(tk) && current % 100 == 0 -> { current += tens.getValue(tk); consumed = j - i + 1 }
                        tk == "hundred" && consumed > 0 -> { current = (if (current == 0) 1 else current) * 100; consumed = j - i + 1 }
                        tk == "thousand" && consumed > 0 -> { total += (if (current == 0) 1 else current) * 1000; current = 0; consumed = j - i + 1 }
                        tk == "and" && consumed > 0 && j + 1 < tokens.size && (units.containsKey(tokens[j + 1]) || tens.containsKey(tokens[j + 1])) -> {}
                        else -> break
                    }
                    j++
                }
                if (consumed > 0) {
                    out += (total + current).toString()
                    i += consumed
                } else {
                    out += tokens[i]
                    i++
                }
            }
            s = out.joinToString(" ")
            return s
        }
    }
}
