package com.sam.twintracker.voice

import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.FeedKind
import com.sam.twintracker.data.OutputKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CommandParserTest {

    private val p = CommandParser(
        mapOf(
            1 to listOf("Aarav", "Chintu", "Munna"),
            2 to listOf("Anaya", "Gudiya", "Pari"),
        )
    )
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 13, 18, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun one(text: String): ParsedCommand {
        val r = p.process(text, now)
        assertEquals("commands for '$text': $r", 1, r.commands.size)
        return r.commands.single()
    }

    private fun question(text: String): Question {
        val r = p.process(text, now)
        assertEquals("questions for '$text': $r", 1, r.questions.size)
        return r.questions.single()
    }

    // ------------------------------------------------------------- wake word & babies

    @Test fun nothingWithoutWakeWord() {
        val r = p.process("Chintu fed 120 ml formula", now)
        assertFalse(r.awake)
        assertTrue(r.commands.isEmpty())
    }

    @Test fun wakeWordOnly() {
        val r = p.process("Baby tracker", now)
        assertTrue(r.wakeOnly)
    }

    @Test fun wakeWordMishearings() {
        assertEquals(1, one("baby tractor chintu wet diaper").baby)
        assertEquals(2, one("hey baby trackers Pari pooped").baby)
    }

    @Test fun customWakeWord() {
        val custom = CommandParser(mapOf(1 to listOf("Aarav")), wakeWord = "hello nanny")
        assertEquals(1, custom.process("hello nanny aarav fed 60 ml", now).commands.size)
        assertTrue(custom.process("baby tracker aarav fed 60 ml", now).commands.isEmpty())
    }

    @Test fun aliasesMapToBabies() {
        assertEquals(1, one("baby tracker Munna fed 90 ml").baby)
        assertEquals(2, one("baby tracker gudiya fed 90 ml").baby)
        assertEquals(2, one("baby tracker, Anaya had a wet diaper").baby)
    }

    @Test fun numberedBabyStillWorks() {
        val c = one("baby tracker entry for baby 1 fed 120 ml formula")
        assertEquals(1, c.baby)
        assertEquals(FeedKind.FORMULA, c.subtype)
        assertEquals(120.0, c.amountMl!!, 0.01)
        assertEquals(2, one("baby tracker baby two drank one hundred and twenty milliliters of milk").baby)
    }

    @Test fun nameAfterCommand() {
        val c = one("baby tracker fed 100 ml for Chintu")
        assertEquals(1, c.baby)
        assertEquals(100.0, c.amountMl!!, 0.01)
    }

    @Test fun twoBabiesTwoCommands() {
        val r = p.process("baby tracker chintu fed 100 ml gudiya fed 80 ml", now)
        assertEquals(listOf(1, 2), r.commands.map { it.baby })
        assertEquals(listOf(100.0, 80.0), r.commands.map { it.amountMl })
    }

    @Test fun twoBabiesSameCommand() {
        val r = p.process("baby tracker Chintu and Pari fed 90 ml", now)
        assertEquals(listOf(1, 2), r.commands.map { it.baby })
        val both = p.process("baby tracker both babies pooped", now)
        assertEquals(listOf(1, 2), both.commands.map { it.baby })
    }

    @Test fun missingBabyAsks() {
        val r = p.process("baby tracker fed 100 ml", now)
        assertTrue(r.commands.isEmpty())
        assertNotNull(r.needsBaby)
        assertEquals(listOf(2), p.babiesIn("for Pari"))
        assertEquals(100.0, p.parseCommand(listOf(2), r.needsBaby!!, now = now).single().amountMl!!, 0.01)
    }

    // ------------------------------------------------------------- feeds, diapers, sleep

    @Test fun breastFeedWithSideAndDuration() {
        val c = one("baby tracker Pari breastfed 15 minutes left side")
        assertEquals(FeedKind.BREAST, c.subtype)
        assertEquals(15, c.durationMin)
        assertEquals("Left", c.side)
    }

    @Test fun diapers() {
        assertEquals(OutputKind.WET, one("baby tracker baby 1 wet diaper").subtype)
        assertEquals(OutputKind.DIRTY, one("baby tracker baby 2 pooped").subtype)
        assertEquals(OutputKind.MIXED, one("baby tracker chintu pee and poop").subtype)
    }

    @Test fun sleep() {
        val c = one("baby tracker pari slept 2 hours")
        assertEquals(EntryType.SLEEP, c.type)
        assertEquals(120, c.durationMin)
    }

    @Test fun clockAndRelativeTime() {
        val c = one("baby tracker pari fed 90 ml at 3:30 p.m.")
        val cal = Calendar.getInstance().apply { timeInMillis = c.timestamp }
        assertEquals(15, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        assertEquals(now - 20 * 60_000, one("baby tracker chintu fed 60 ml 20 minutes ago").timestamp)
    }

    @Test fun vaccine() {
        val c = one("baby tracker chintu vaccine BCG and hepatitis B")
        assertEquals(EntryType.VACCINE, c.type)
        assertEquals(listOf("bcg", "hepb"), c.vaccineKeys.sorted())
    }

    // ------------------------------------------------------------- medicine

    @Test fun medicineDose() {
        val c = one("baby tracker Chintu paracetamol 2.5 ml")
        assertEquals(EntryType.MEDICINE, c.type)
        assertEquals("Paracetamol", c.subtype)
        assertEquals(2.5, c.value!!, 0.001)
        assertEquals("ml", c.unit)
        assertNull(c.amountMl)
        assertTrue(c.logEntry)
        assertNull(c.schedule)
    }

    @Test fun medicineWithTimer() {
        val c = one("baby tracker gave Munna calpol 2.5 ml every 6 hours for 3 days")
        assertEquals("Calpol", c.subtype)
        assertEquals(360, c.schedule!!.intervalMinutes)
        assertEquals(3, c.schedule!!.days)
        assertTrue(c.logEntry)
        assertNull(c.durationMin)
    }

    @Test fun timerOnly() {
        val c = one("baby tracker set a medicine timer for pari in 4 hours")
        assertEquals(EntryType.MEDICINE, c.type)
        assertEquals(240, c.schedule!!.intervalMinutes)
        assertTrue(c.schedule!!.oneShot)
        assertFalse(c.logEntry)
    }

    @Test fun timesPerDay() {
        val c = one("baby tracker pari iron drops 5 drops twice a day")
        assertEquals("Iron", c.subtype)
        assertEquals(5.0, c.value!!, 0.01)
        assertEquals("drops", c.unit)
        assertEquals(720, c.schedule!!.intervalMinutes)
    }

    @Test fun unknownMedicineName() {
        val c = one("baby tracker chintu medicine zyrtec 2 ml")
        assertEquals("Zyrtec", c.subtype)
    }

    // ------------------------------------------------------------- measurements

    @Test fun temperature() {
        val f = one("baby tracker chintu temperature 100.2")
        assertEquals(EntryType.TEMPERATURE, f.type)
        assertEquals(100.2, f.value!!, 0.001)
        assertEquals("°F", f.unit)
        val c = one("baby tracker pari temp thirty eight point five degrees celsius")
        assertEquals(38.5, c.value!!, 0.001)
        assertEquals("°C", c.unit)
    }

    @Test fun weight() {
        assertEquals(4.2, one("baby tracker chintu weight 4.2 kg").value!!, 0.001)
        assertEquals(4.25, one("baby tracker pari weighs 4 kg 250 grams").value!!, 0.001)
        assertEquals(3.5, one("baby tracker pari weight 3500 grams").value!!, 0.001)
        val lb = one("baby tracker chintu weight 9 pounds 4 ounces")
        assertEquals(EntryType.WEIGHT, lb.type)
        assertEquals(4.196, lb.value!!, 0.01)
    }

    @Test fun height() {
        val h = one("baby tracker pari height 55 cm")
        assertEquals(EntryType.HEIGHT, h.type)
        assertEquals(55.0, h.value!!, 0.001)
        assertEquals(55.9, one("baby tracker chintu length 22 inches").value!!, 0.05)
    }

    // ------------------------------------------------------------- questions

    @Test fun whichToFeed() {
        assertEquals(QuestionKind.WHICH_TO_FEED, question("baby tracker which baby should I feed now").kind)
        assertEquals(QuestionKind.WHICH_TO_FEED, question("baby tracker which baby to feed").kind)
        assertEquals(QuestionKind.WHICH_TO_FEED, question("baby tracker who is hungry?").kind)
        assertTrue(question("baby tracker which baby to feed").babies.isEmpty())
    }

    @Test fun lastFeed() {
        val q = question("baby tracker when did Chintu last eat")
        assertEquals(QuestionKind.LAST, q.kind)
        assertEquals(EntryType.FEED, q.topic)
        assertEquals(listOf(1), q.babies)
        val q2 = question("baby tracker when was the last food taken by pari")
        assertEquals(EntryType.FEED, q2.topic)
        assertEquals(listOf(2), q2.babies)
    }

    @Test fun otherQuestions() {
        val poop = question("baby tracker when did gudiya last poop")
        assertEquals(EntryType.OUTPUT, poop.topic)
        assertTrue(poop.dirtyOnly)
        assertEquals(QuestionKind.NEXT_MEDICINE, question("baby tracker when is the next medicine").kind)
        assertEquals(EntryType.TEMPERATURE, question("baby tracker what was chintu's last temperature").topic)
        assertEquals(QuestionKind.TODAY, question("baby tracker how much did pari eat today").kind)
        assertEquals(QuestionKind.NEXT_VACCINE, question("baby tracker when is the next vaccine").kind)
        assertEquals(EntryType.WEIGHT, question("baby tracker what is munna's weight").topic)
        assertEquals(EntryType.MEDICINE, question("baby tracker when did chintu last have medicine").topic)
    }

    @Test fun wordsToNumbers() {
        assertEquals("120 ml", CommandParser.wordsToNumbers("one hundred twenty ml"))
        assertEquals("3 30 pm", CommandParser.wordsToNumbers("three thirty pm"))
        assertEquals("99.5", CommandParser.wordsToNumbers("ninety nine point five"))
    }
}
