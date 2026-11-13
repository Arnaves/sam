package com.sam.twintracker.voice

import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.FeedKind
import com.sam.twintracker.data.OutputKind
import com.sam.twintracker.data.VaccineCatalog
import java.util.Calendar
import kotlin.math.roundToInt

/** A request to start a medicine timer ("every 6 hours for 3 days", "remind me in 4 hours"). */
data class ScheduleRequest(
    val intervalMinutes: Int,
    val days: Int? = null,
    val oneShot: Boolean = false,
)

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
    val value: Double? = null,
    val unit: String? = null,
    val schedule: ScheduleRequest? = null,
    /** False for "set a paracetamol timer" (no dose given now — only the timer). */
    val logEntry: Boolean = true,
)

enum class QuestionKind { WHICH_TO_FEED, LAST, NEXT_MEDICINE, NEXT_VACCINE, TODAY, UNKNOWN }

data class Question(
    /** Empty = both babies. */
    val babies: List<Int>,
    val kind: QuestionKind,
    val topic: EntryType? = null,
    /** "when did baby 1 last poop" → only dirty diapers. */
    val dirtyOnly: Boolean = false,
    val text: String,
)

data class ProcessResult(
    val commands: List<ParsedCommand> = emptyList(),
    val questions: List<Question> = emptyList(),
    /** A command was said without saying which baby: keep the text and ask "which baby?". */
    val needsBaby: String? = null,
    /** Only the wake word was said — wait for the next utterance. */
    val wakeOnly: Boolean = false,
    /** The wake word was heard at all. */
    val awake: Boolean = false,
)

/**
 * Turns recognised speech into log entries and questions.
 *
 * Every request starts with the wake word (default "baby tracker"), then names a baby by
 * "baby 1" / "baby 2", by any of its names or nicknames, or "both babies":
 *
 *  - "baby tracker, Chintu fed 120 ml formula"
 *  - "baby tracker, entry for baby 2 pooped"
 *  - "baby tracker, Munna medicine paracetamol 2.5 ml every 6 hours for 3 days"
 *  - "baby tracker, Aarav temperature 99.8" / "weight 4.2 kg" / "height 55 cm"
 *  - "baby tracker, when did Chintu last eat?"
 *  - "baby tracker, which baby should I feed now?"
 *
 * @param babyNames baby id → every name it may be called (name + aliases)
 */
class CommandParser(
    babyNames: Map<Int, List<String>> = emptyMap(),
    wakeWord: String = DEFAULT_WAKE_WORD,
) {

    private val nameToBaby: Map<String, Int> = buildMap {
        babyNames.forEach { (id, names) ->
            names.map { normalize(it) }
                .filter { it.isNotBlank() && !it.matches(Regex("baby \\d")) && it !in STOP_NAMES }
                .forEach { putIfAbsent(it, id) }
        }
    }

    private val wakeRegex: Regex = run {
        val w = normalize(wakeWord).ifBlank { DEFAULT_WAKE_WORD }
        val custom = w.split(" ").joinToString("\\s*") { Regex.escape(it) }
        val variants = if (w == DEFAULT_WAKE_WORD) {
            "|baby\\s*(?:tracker|trackers|track her|tractor|tractors|trekker|tracking|trucker|traker)|bebe\\s*tracker|baby\\s*track\\b"
        } else ""
        Regex("\\b(?:(?:hey|hi|ok|okay|o k)\\s+)?(?:$custom$variants)\\b")
    }

    private val babyRefRegex: Regex = run {
        val number = "(?:1|one|won|first|2|two|tu|second|to(?!\\s+(?:feed|eat|give|change|be|sleep|nap|burp|bath|bathe|put|drink))|too)"
        val numbered = "(?:(?:the\\s+)?(?:baby|bebe|babe)\\s*(?:number\\s*)?($number)\\b|\\b(first|second)\\s+(?:baby|one|child|twin)\\b)"
        val both = "\\b(both(?:\\s+(?:babies|baby|twins|of them|kids|children))?|(?:the\\s+)?twins|all babies|both kids)\\b"
        val names = if (nameToBaby.isEmpty()) "" else "|\\b(" + nameToBaby.keys.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) } + ")\\b"
        Regex("$numbered|$both$names")
    }

    private data class BabyRef(val babies: List<Int>, val range: IntRange)

    private fun babyRefs(text: String): List<BabyRef> = babyRefRegex.findAll(text).map { m ->
        val g = m.groupValues
        val babies = when {
            g[1].isNotEmpty() -> listOf(babyNumber(g[1]))
            g[2].isNotEmpty() -> listOf(babyNumber(g[2]))
            g[3].isNotEmpty() -> listOf(1, 2)
            else -> listOfNotNull(nameToBaby[g.getOrNull(4)])
        }
        BabyRef(babies, m.range)
    }.filter { it.babies.isNotEmpty() }.toList()

    /** Babies mentioned in a reply to "which baby?". */
    fun babiesIn(raw: String): List<Int> = babyRefs(normalize(raw)).flatMap { it.babies }.distinct()

    fun hasWakeWord(raw: String) = wakeRegex.containsMatchIn(normalize(raw))

    /** Full pipeline for one utterance; nothing happens unless the wake word is present. */
    fun process(raw: String, now: Long = System.currentTimeMillis()): ProcessResult {
        val text = normalize(raw)
        val wakes = wakeRegex.findAll(text).toList()
        if (wakes.isEmpty()) return ProcessResult()
        val segments = wakes.mapIndexed { i, m ->
            text.substring(m.range.last + 1, if (i + 1 < wakes.size) wakes[i + 1].range.first else text.length)
        }
        val parts = segments.map { processRequest(it, raw, now) }
        val anyContent = parts.any { it.commands.isNotEmpty() || it.questions.isNotEmpty() || it.needsBaby != null }
        return ProcessResult(
            commands = parts.flatMap { it.commands },
            questions = parts.flatMap { it.questions },
            needsBaby = parts.firstNotNullOfOrNull { it.needsBaby },
            wakeOnly = !anyContent,
            awake = true,
        )
    }

    /**
     * Handles what comes after the wake word (also used for typed requests in the app and for the
     * follow-up utterance after "Yes?").
     */
    fun processRequest(request: String, raw: String = request, now: Long = System.currentTimeMillis()): ProcessResult {
        val text = stripFillers(normalize(request))
        if (text.isBlank() || text.matches(Regex("(?:um|uh|hmm|cancel|stop|nothing|never mind)"))) return ProcessResult(awake = true)

        val refs = babyRefs(text)
        if (isQuestion(text, raw)) {
            return ProcessResult(questions = listOf(parseQuestion(refs.flatMap { it.babies }.distinct(), text)), awake = true)
        }

        // Group "<babies> <command>" runs: "chintu fed 100 ml munna fed 80 ml", "chintu and munna fed 90 ml".
        data class Group(val babies: MutableList<Int>, var text: String)
        val groups = mutableListOf<Group>()
        var leading = ""
        var cursor = 0
        for (ref in refs) {
            val between = stripFillers(text.substring(cursor, ref.range.first))
            if (between.isEmpty() && groups.isNotEmpty() && groups.last().text.isEmpty()) {
                groups.last().babies += ref.babies
            } else {
                if (groups.isEmpty()) leading = between else groups.last().text = between
                groups += Group(ref.babies.toMutableList(), "")
            }
            cursor = ref.range.last + 1
        }
        val trailing = stripFillers(text.substring(cursor))
        if (groups.isEmpty()) {
            return if (parseCommand(listOf(1), text, raw, now).isNotEmpty()) ProcessResult(needsBaby = text, awake = true)
            else ProcessResult(awake = true)
        }
        groups.last().text = trailing
        // "fed 100 ml for chintu": text before the first name belongs to it.
        if (leading.isNotEmpty() && groups.first().text.isEmpty()) groups.first().text = leading
        else if (leading.isNotEmpty()) groups.first().text = "$leading ${groups.first().text}"

        val commands = groups.flatMap { parseCommand(it.babies.distinct(), it.text, raw, now) }
        return ProcessResult(commands = commands, awake = true)
    }

    // ------------------------------------------------------------------ questions

    private fun isQuestion(text: String, raw: String): Boolean {
        if (raw.trim().endsWith("?")) return true
        val first = text.substringBefore(' ')
        if (first in QUESTION_STARTERS) return true
        if (first == "last" && !text.contains(Regex("\\d"))) return true
        return Regex("\\b(?:time since|how long since|how long ago|is it time|tell me|do i need to|should i)\\b").containsMatchIn(text)
    }

    private fun parseQuestion(babies: List<Int>, text: String): Question {
        val w = " $text "
        fun has(vararg words: String) = words.any { w.contains(" $it ") }
        val feed = has("feed", "fed", "feeding", "eat", "ate", "eaten", "food", "milk", "hungry", "drink", "drank", "bottle", "nurse", "nursed", "breastfeed", "breastfed", "formula")
        val diaper = has("diaper", "diapers", "nappy", "poop", "pooped", "poo", "potty", "stool", "pee", "peed", "wet", "dirty", "urine", "susu")
        val dirty = has("poop", "pooped", "poo", "potty", "stool", "dirty", "bowel", "motion")
        val sleep = has("sleep", "slept", "nap", "napped", "sleeping", "asleep")
        val medicine = MEDICINE_WORDS.any { w.contains(" $it ") } || has("medicine", "medicines", "medication", "med", "meds", "dose", "syrup", "drops")
        val temp = has("temperature", "temp", "fever", "feverish")
        val weight = has("weight", "weigh", "weighs", "weighed", "heavy")
        val height = has("height", "length", "tall")
        val vaccine = has("vaccine", "vaccines", "vaccination", "vaccinations", "shot", "shots", "jab", "immunization", "immunisation") ||
            VaccineCatalog.match(text).isNotEmpty()
        val last = has("last", "since", "ago", "previous", "recent", "latest") || w.startsWith(" when did ") || w.startsWith(" when was ")
        val next = has("next", "due", "upcoming", "pending", "remaining", "coming")

        val kind = when {
            !last && (has("which", "who", "whose") || has("next")) && feed -> QuestionKind.WHICH_TO_FEED
            has("feed now", "feed first", "feed next", "turn") && !last -> QuestionKind.WHICH_TO_FEED
            medicine && (next || has("time for")) && !last -> QuestionKind.NEXT_MEDICINE
            vaccine && !last -> QuestionKind.NEXT_VACCINE
            has("how much", "how many", "today", "summary", "status", "report", "total", "update") && !last -> QuestionKind.TODAY
            feed || diaper || sleep || medicine || temp || weight || height || vaccine -> QuestionKind.LAST
            has("which", "who") -> QuestionKind.WHICH_TO_FEED
            else -> QuestionKind.UNKNOWN
        }
        val topic = when {
            kind != QuestionKind.LAST -> null
            temp -> EntryType.TEMPERATURE
            weight -> EntryType.WEIGHT
            height -> EntryType.HEIGHT
            medicine -> EntryType.MEDICINE
            vaccine -> EntryType.VACCINE
            diaper -> EntryType.OUTPUT
            sleep -> EntryType.SLEEP
            else -> EntryType.FEED
        }
        return Question(babies, kind, topic, dirtyOnly = topic == EntryType.OUTPUT && dirty, text = text)
    }

    // ------------------------------------------------------------------ commands

    /**
     * Parses the command words (no wake word / baby reference) for the given babies.
     * Returns an empty list when nothing meaningful was said.
     */
    fun parseCommand(babies: List<Int>, commandText: String, raw: String = commandText, now: Long = System.currentTimeMillis()): List<ParsedCommand> {
        var t = stripFillers(wordsToNumbers(normalize(commandText)))
        t = t.replace(Regex("^(?:is|was|has|had|just|got|then|please)\\s+"), "").trim()
        if (t.isBlank() || t.matches(Regex("(?:ok|okay|um|uh|hmm|cancel|stop|nothing|yes|no)"))) return emptyList()

        // --- medicine timer phrases (before durations/ago so "every 6 hours" isn't read as sleep) ---
        var schedule: ScheduleRequest? = null
        var days: Int? = null
        Regex("\\bfor (\\d+) days?\\b").find(t)?.let { days = it.groupValues[1].toInt(); t = t.removeRange(it.range).trim() }
        Regex("\\bevery (\\d+(?:\\.\\d+)?)?\\s*(hours?|hrs?|minutes?|mins?)\\b").find(t)?.let {
            val n = it.groupValues[1].toDoubleOrNull() ?: 1.0
            schedule = ScheduleRequest(if (it.groupValues[2].startsWith("h")) (n * 60).roundToInt() else n.roundToInt())
            t = t.removeRange(it.range).trim()
        } ?: Regex("\\b(once|twice|thrice|\\d+ times)\\s+(?:a|per|in a)\\s+day\\b|\\b(daily|once daily)\\b").find(t)?.let {
            val times = when (val x = it.groupValues[1]) {
                "", "once" -> 1
                "twice" -> 2
                "thrice" -> 3
                else -> x.substringBefore(' ').toInt().coerceIn(1, 24)
            }
            schedule = ScheduleRequest(24 * 60 / times)
            t = t.removeRange(it.range).trim()
        }
        val timerWords = Regex("\\b(?:timer|remind|reminder|alarm|alert|schedule)\\b").containsMatchIn(t)
        if (schedule == null && timerWords) {
            Regex("\\b(?:in|after) (\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|minutes?|mins?)\\b").find(t)?.let {
                val n = it.groupValues[1].toDouble()
                schedule = ScheduleRequest(if (it.groupValues[2].startsWith("h")) (n * 60).roundToInt() else n.roundToInt(), oneShot = true)
                t = t.removeRange(it.range).trim()
            }
        }
        schedule = schedule?.copy(days = days)

        // --- when ---
        var timestamp = now
        Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(minutes?|mins?|hours?|hrs?)\\s+ago\\b").find(t)?.let { m ->
            val v = m.groupValues[1].toDouble()
            val ms = if (m.groupValues[2].startsWith("h")) v * 3_600_000 else v * 60_000
            timestamp = now - ms.toLong()
            t = t.removeRange(m.range).trim()
        }
        Regex("\\b(?:at|around)\\s+(\\d{1,2})(?::(\\d{2})|\\s+(\\d{2}))?\\s*(am|pm|in the morning|in the evening|at night)?(?!\\s*(?:degrees?|f|c|fahrenheit|celsius|kg|cm|ml))\\b").find(t)?.let { m ->
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
                if (cal.timeInMillis > now + 5 * 60_000 && mer.isEmpty() && hour in 13..23) cal.add(Calendar.HOUR_OF_DAY, -12)
                if (cal.timeInMillis > now + 5 * 60_000) cal.add(Calendar.DAY_OF_YEAR, -1)
                if (cal.timeInMillis > now + 5 * 60_000) cal.timeInMillis = now
                timestamp = cal.timeInMillis
                t = t.removeRange(m.range).trim()
            }
        }

        val w0 = " $t "
        fun has0(vararg words: String) = words.any { w0.contains(" $it ") }
        val noteText = Regex("\\b(?:note|notes|comment|remark)\\b\\s*(.*)$").find(t)?.groupValues?.get(1)?.trim()?.ifBlank { null }

        // --- measurements: standalone entries ---
        measurement(t)?.let { (type, value, unit) ->
            return babies.map { ParsedCommand(it, type, value = value, unit = unit, note = noteText, timestamp = timestamp, raw = raw) }
        }

        // --- vaccine (explicit vaccine words or a catalogue name) ---
        val vaccineKeys = VaccineCatalog.match(t)
        val vaccineWord = has0(
            "vaccine", "vaccines", "vaccinated", "vaccination", "vaccinations", "shot", "shots", "jab", "jabs",
            "injection", "immunization", "immunisation", "immunized", "immunised",
        )
        val medicineWord = has0(*MEDICINE_TRIGGER_WORDS.toTypedArray()) || MEDICINE_WORDS.any { w0.contains(" $it ") }
        val isVaccine = vaccineKeys.isNotEmpty() || (vaccineWord && !medicineWord)

        // --- medicine ---
        if (!isVaccine && (medicineWord || (schedule != null && timerWords))) {
            var m = t
            var doseValue: Double? = null
            var doseUnit: String? = null
            fun take(r: Regex, unit: String, conv: (MatchResult) -> Double) {
                if (doseValue != null) return
                r.find(m)?.let { doseValue = conv(it); doseUnit = unit; m = m.removeRange(it.range).trim() }
            }
            take(Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(?:ml|mls|milliliters?|millilitres?|cc|m l)\\b"), "ml") { it.groupValues[1].toDouble() }
            take(Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(?:mg|milligrams?)\\b"), "mg") { it.groupValues[1].toDouble() }
            take(Regex("\\b(\\d+)\\s*(?:drops?)\\b"), "drops") { it.groupValues[1].toDouble() }
            take(Regex("\\b(\\d+(?:\\.\\d+)?|half|a|one)\\s*(?:tablets?|tabs?|capsules?)\\b"), "tablet") {
                when (val x = it.groupValues[1]) { "half" -> 0.5; "a", "one" -> 1.0; else -> x.toDouble() }
            }
            take(Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(?:puffs?)\\b"), "puffs") { it.groupValues[1].toDouble() }
            val given = Regex("\\b(?:given|gave|took|taken|had|administered|fed|done)\\b").containsMatchIn(m)
            val name = medicineName(m)
            val logEntry = given || !timerWords
            return babies.map {
                ParsedCommand(
                    it, EntryType.MEDICINE, subtype = name, value = doseValue, unit = doseUnit, note = noteText,
                    timestamp = timestamp, raw = raw, schedule = schedule, logEntry = logEntry,
                )
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
        val strongFeed = arrayOf("feed", "fed", "feeding", "fid", "ate", "eat", "eaten", "eating", "drank", "drink", "drinking", "milk", "bottle", "feeds")
        val feedWord = has(*strongFeed) || has("had", "took", "finished")
        val isFeed = breast || expressed || formula || solids || water || feedWord ||
            (amountMl != null && !isOutput && !isVaccine)

        val side = when {
            has("both sides", "both side", "both breasts") -> "Both"
            has("left") -> "Left"
            has("right") -> "Right"
            else -> null
        }

        val out = mutableListOf<ParsedCommand>()
        for (baby in babies) {
            fun cmd(type: EntryType, subtype: String? = null, amount: Double? = null, dur: Int? = null, sd: String? = null, note: String? = noteText, keys: List<String> = emptyList()) =
                ParsedCommand(baby, type, subtype, amount, dur, sd, note, timestamp, keys, raw)

            if (isVaccine) {
                val name = if (vaccineKeys.isEmpty()) null else vaccineKeys.joinToString(", ") { VaccineCatalog.nameFor(it) }
                out += cmd(EntryType.VACCINE, subtype = name, keys = vaccineKeys, note = noteText ?: if (name == null) t else null)
                continue
            }
            val sleepTakesDuration = isSleep && !breast
            if (isFeed) {
                val kind = when {
                    expressed -> FeedKind.EXPRESSED
                    breast -> FeedKind.BREAST
                    formula -> FeedKind.FORMULA
                    solids -> FeedKind.SOLID
                    water -> FeedKind.WATER
                    else -> null
                }
                // Avoid "had a wet diaper" being read as a feed via the word "had".
                val onlyWeakFeedWord = kind == null && amountMl == null && !has(*strongFeed)
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

    /** Temperature / weight / height in a command. Returns (type, value, unit) or null. */
    private fun measurement(t: String): Triple<EntryType, Double, String>? {
        val w = " $t "
        fun has(vararg words: String) = words.any { w.contains(" $it ") }
        val num = "(\\d+(?:\\.\\d+)?)"

        if (has("temperature", "temp", "fever", "degrees", "degree", "fahrenheit", "celsius", "centigrade")) {
            val m = Regex("\\b$num\\s*(?:degrees?\\s*)?(fahrenheit|f|celsius|centigrade|c)?\\b").find(t) ?: return null
            val v = m.groupValues[1].toDouble()
            val unit = when (m.groupValues[2]) {
                "fahrenheit", "f" -> "°F"
                "celsius", "centigrade", "c" -> "°C"
                else -> if (v > 45) "°F" else "°C"
            }
            return Triple(EntryType.TEMPERATURE, v, unit)
        }
        val weightWord = has("weight", "weighs", "weighed", "weighing", "weigh")
        Regex("\\b$num\\s*(?:kg|kgs|kilo|kilos|kilograms?|kilogrammes?)\\b(?:\\s*(?:and\\s*)?(\\d+)\\s*(?:grams?|g|gm|gms|grammes?))?").find(t)?.let {
            return Triple(EntryType.WEIGHT, round3(it.groupValues[1].toDouble() + (it.groupValues[2].toDoubleOrNull() ?: 0.0) / 1000), "kg")
        }
        Regex("\\b$num\\s*(?:pounds?|lbs?)\\b(?:\\s*(?:and\\s*)?$num\\s*(?:ounces?|oz))?").find(t)?.let {
            val kg = it.groupValues[1].toDouble() * 0.45359237 + (it.groupValues[2].toDoubleOrNull() ?: 0.0) * 0.0283495
            return Triple(EntryType.WEIGHT, round3(kg), "kg")
        }
        if (weightWord) {
            Regex("\\b$num\\s*(?:grams?|gm|gms|g)\\b").find(t)?.let {
                return Triple(EntryType.WEIGHT, round3(it.groupValues[1].toDouble() / 1000), "kg")
            }
            Regex("\\b$num\\b").find(t)?.let {
                val v = it.groupValues[1].toDouble()
                return Triple(EntryType.WEIGHT, round3(if (v >= 500) v / 1000 else v), "kg")
            }
        }
        val heightWord = has("height", "length", "tall", "measured", "measures")
        Regex("\\b$num\\s*(?:cm|cms|centimeters?|centimetres?|centimeter|centi meter)\\b").find(t)?.let {
            if (heightWord || has("cm", "cms", "centimeters", "centimetres", "centimeter")) return Triple(EntryType.HEIGHT, it.groupValues[1].toDouble(), "cm")
        }
        Regex("\\b$num\\s*(?:inches|inch|in)\\b").find(t)?.let {
            if (heightWord || has("inches", "inch")) return Triple(EntryType.HEIGHT, round3(it.groupValues[1].toDouble() * 2.54).let { v -> (v * 10).roundToInt() / 10.0 }, "cm")
        }
        if (heightWord) Regex("\\b$num\\b").find(t)?.let { return Triple(EntryType.HEIGHT, it.groupValues[1].toDouble(), "cm") }
        return null
    }

    private fun medicineName(text: String): String {
        val w = " $text "
        MEDICINE_WORDS.sortedByDescending { it.length }.firstOrNull { w.contains(" $it ") }?.let { known ->
            // Keep a qualifier that directly precedes or follows ("cough syrup", "vitamin d drops").
            return titleCase(known)
        }
        val leftover = text.split(" ").filter {
            it.isNotBlank() && it !in MEDICINE_TRIGGER_WORDS && it !in NAME_FILLERS && !it.matches(Regex("\\d+(?:\\.\\d+)?"))
        }.take(4)
        return if (leftover.isEmpty()) "Medicine" else titleCase(leftover.joinToString(" "))
    }

    companion object {
        const val DEFAULT_WAKE_WORD = "baby tracker"

        private val QUESTION_STARTERS = setOf(
            "when", "which", "who", "whose", "how", "what", "whats", "did", "does", "do", "has", "have", "is", "was",
            "should", "tell", "any", "status", "summary", "report", "where", "kab", "kitna", "kaun", "kisko",
        )

        /** Words that make a command about medicine. */
        private val MEDICINE_TRIGGER_WORDS = listOf(
            "medicine", "medicines", "medication", "medications", "med", "meds", "syrup", "drops", "drop", "tablet", "tablets",
            "tab", "capsule", "dose", "dosage", "dawai", "dawa", "davai", "ointment", "cream", "nebulizer", "nebulisation",
            "nebulization", "nebuliser", "inhaler", "puffs", "puff", "suspension",
        )

        /** Common infant medicines (lower-case, normalised) recognised as names. */
        val MEDICINE_WORDS = listOf(
            "paracetamol", "calpol", "crocin", "dolo", "tylenol", "acetaminophen", "ibuprofen", "ibugesic", "meftal", "motrin",
            "vitamin d", "vitamin d3", "d3", "vitamin", "multivitamin", "iron", "calcium", "zinc", "probiotic", "enterogermina",
            "gripe water", "colicaid", "colic drops", "simethicone", "ors", "electral", "amoxicillin", "augmentin", "azithromycin",
            "cefixime", "antibiotic", "domperidone", "ondem", "ondansetron", "cetirizine", "levocetirizine", "montelukast",
            "salbutamol", "levolin", "budecort", "asthalin", "saline", "nasal drops", "nasivion", "otrivin", "sinarest", "t minic",
            "tminic", "eye drops", "ear drops", "cough syrup", "anti colic", "lactulose", "cyclopam", "ranitidine", "omeprazole",
            "albendazole", "nystatin", "candid", "mupirocin", "t bact", "soframycin",
        )

        private val NAME_FILLERS = setOf(
            "given", "gave", "give", "took", "taken", "had", "of", "the", "a", "an", "his", "her", "some", "for", "to", "and",
            "with", "administered", "fed", "done", "now", "just", "timer", "set", "start", "remind", "reminder", "alarm", "me",
            "in", "after", "schedule", "alert", "please",
        )

        private val FILLERS = setOf(
            "entry", "entries", "entery", "entri", "andrey", "entree", "log", "add", "record", "for", "of", "and", "the",
            "please", "ok", "okay", "hey", "with", "also", "then", "to", "that", "so", "um", "uh", "new",
        )

        /** Words that cannot be baby names (they would collide with commands). */
        private val STOP_NAMES = setOf("baby", "babies", "both", "twins", "fed", "feed", "milk", "poop", "pee", "wet", "medicine")

        private fun stripFillers(s: String): String {
            val words = s.trim().split(" ").filter { it.isNotEmpty() }.toMutableList()
            while (words.isNotEmpty() && words.first() in FILLERS) words.removeAt(0)
            while (words.isNotEmpty() && words.last() in FILLERS) words.removeAt(words.lastIndex)
            return words.joinToString(" ")
        }

        private fun titleCase(s: String) = s.split(" ").joinToString(" ") { w ->
            if (w.length <= 3 && w.any(Char::isDigit) || w in setOf("ors", "d3")) w.uppercase() else w.replaceFirstChar { it.uppercase() }
        }

        private fun round3(v: Double) = (v * 1000).roundToInt() / 1000.0

        fun normalize(s: String): String = s.lowercase()
            .replace(Regex("\\b([ap])\\.\\s?m\\.?"), "$1m")
            .replace(Regex("(\\d)\\s*(st|nd|rd|th)\\b"), "$1")
            .replace("°", " degrees ")
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

        /** "one hundred and twenty ml" -> "120 ml", "ninety nine point five" -> "99.5", "half an hour" -> "30 minutes". */
        fun wordsToNumbers(input: String): String {
            val s = " $input "
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
            return out.joinToString(" ")
                .replace(Regex("\\b(\\d+) point (\\d+)\\b"), "$1.$2")
        }
    }
}
