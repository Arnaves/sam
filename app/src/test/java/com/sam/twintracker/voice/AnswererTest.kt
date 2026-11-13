package com.sam.twintracker.voice

import com.sam.twintracker.data.Baby
import com.sam.twintracker.data.Entry
import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.MedicineSchedule
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswererTest {

    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val babies = listOf(Baby(1, "Aarav", aliases = "Chintu"), Baby(2, "Anaya"))

    private fun feed(baby: Int, minsAgo: Long, ml: Double = 90.0) =
        Entry(baby = baby, type = EntryType.FEED, amountMl = ml, timestamp = now - minsAgo * min)

    private fun snap(entries: List<Entry>, schedules: List<MedicineSchedule> = emptyList()) =
        Snapshot(babies, entries, schedules, emptyList(), now)

    @Test fun picksBabyFedLongestAgo() {
        val s = snap(listOf(feed(1, 200), feed(2, 60)))
        val a = Answerer.answer(Question(emptyList(), QuestionKind.WHICH_TO_FEED, text = ""), s)
        assertTrue(a, a.startsWith("Feed Aarav first"))
        assertTrue(a, a.contains("3 hours 20 minutes"))
    }

    @Test fun usesEachBabysRhythm() {
        // Anaya feeds every 2h and is 2h 10m in; Aarav feeds every 4h and is 3h in.
        val s = snap(
            listOf(feed(1, 180), feed(1, 420), feed(1, 660), feed(2, 130), feed(2, 250), feed(2, 370)),
        )
        val a = Answerer.answer(Question(emptyList(), QuestionKind.WHICH_TO_FEED, text = ""), s)
        assertTrue(a, a.startsWith("Feed Anaya first"))
        assertTrue(a, a.contains("is due"))
    }

    @Test fun sameTimeFeedsBoth() {
        val a = Answerer.answer(Question(emptyList(), QuestionKind.WHICH_TO_FEED, text = ""), snap(listOf(feed(1, 120), feed(2, 110))))
        assertTrue(a, a.contains("Feed both"))
    }

    @Test fun neverFed() {
        val a = Answerer.answer(Question(emptyList(), QuestionKind.WHICH_TO_FEED, text = ""), snap(listOf(feed(1, 30))))
        assertTrue(a, a.startsWith("Feed Anaya"))
    }

    @Test fun lastFeedForOneBaby() {
        val a = Answerer.answer(Question(listOf(1), QuestionKind.LAST, EntryType.FEED, text = ""), snap(listOf(feed(1, 75, 120.0))))
        assertTrue(a, a.startsWith("Aarav was last fed 1 hour 15 minutes ago"))
        assertTrue(a, a.contains("120 ml"))
    }

    @Test fun nextMedicine() {
        val s = snap(emptyList(), listOf(MedicineSchedule(1, 2, "Paracetamol", "2.5 ml", 360, now + 90 * min)))
        val a = Answerer.answer(Question(emptyList(), QuestionKind.NEXT_MEDICINE, text = ""), s)
        assertTrue(a, a.contains("Anaya's Paracetamol (2.5 ml) is due in 1 hour 30 minutes"))
    }

    @Test fun feverFlagged() {
        val t = Entry(baby = 1, type = EntryType.TEMPERATURE, value = 101.0, unit = "°F", timestamp = now - 10 * min)
        val a = Answerer.answer(Question(listOf(1), QuestionKind.LAST, EntryType.TEMPERATURE, text = ""), snap(listOf(t)))
        assertTrue(a, a.contains("fever"))
    }
}
