package com.sam.twintracker.voice

import com.sam.twintracker.data.EntryType
import com.sam.twintracker.data.FeedKind
import com.sam.twintracker.data.OutputKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CommandParserTest {

    private val p = CommandParser()
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 13, 18, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun one(text: String): ParsedCommand {
        val r = p.process(text, now)
        assertEquals("commands for '$text': ${r.commands}", 1, r.commands.size)
        return r.commands.single()
    }

    @Test fun formulaFeedBaby1() {
        val c = one("Entry for baby 1 fed 120 ml formula")
        assertEquals(1, c.baby)
        assertEquals(EntryType.FEED, c.type)
        assertEquals(FeedKind.FORMULA, c.subtype)
        assertEquals(120.0, c.amountMl!!, 0.01)
    }

    @Test fun spelledNumbersAndBabyTwo() {
        val c = one("entry for baby two drank one hundred and twenty milliliters of milk")
        assertEquals(2, c.baby)
        assertEquals(EntryType.FEED, c.type)
        assertEquals(120.0, c.amountMl!!, 0.01)
    }

    @Test fun misheardTo() {
        assertEquals(2, one("entry for baby to wet diaper").baby)
    }

    @Test fun breastFeedWithSideAndDuration() {
        val c = one("entry for baby 2 breastfed 15 minutes left side")
        assertEquals(FeedKind.BREAST, c.subtype)
        assertEquals(15, c.durationMin)
        assertEquals("Left", c.side)
    }

    @Test fun ouncesConverted() {
        val c = one("entry for baby 1 bottle 4 oz")
        assertEquals(118.0, c.amountMl!!, 0.5)
    }

    @Test fun diapers() {
        assertEquals(OutputKind.WET, one("entry for baby 1 wet diaper").subtype)
        assertEquals(OutputKind.DIRTY, one("entry for baby 2 pooped").subtype)
        assertEquals(OutputKind.MIXED, one("entry for baby 1 pee and poop").subtype)
        assertEquals(EntryType.OUTPUT, one("entry for baby 1 had a wet diaper").type)
    }

    @Test fun sleep() {
        val c = one("entry for baby 2 slept 2 hours")
        assertEquals(EntryType.SLEEP, c.type)
        assertEquals(120, c.durationMin)
        assertEquals(30, one("entry for baby 1 nap half an hour").durationMin)
    }

    @Test fun vaccineMatchesCatalog() {
        val c = one("entry for baby 1 vaccine BCG and hepatitis B")
        assertEquals(EntryType.VACCINE, c.type)
        assertEquals(listOf("bcg", "hepb"), c.vaccineKeys.sorted())
    }

    @Test fun hexavalentCoversComponents() {
        val c = one("entry for baby 2 got the hexavalent shot")
        assertTrue(c.vaccineKeys.containsAll(listOf("dtap", "ipv", "hib", "hepb")))
    }

    @Test fun bothBabies() {
        val r = p.process("entry for both babies fed 90 ml", now)
        assertEquals(listOf(1, 2), r.commands.map { it.baby })
        assertTrue(r.commands.all { it.amountMl == 90.0 })
    }

    @Test fun twoTriggersInOneUtterance() {
        val r = p.process("entry for baby 1 fed 100 ml entry for baby 2 fed 80 ml", now)
        assertEquals(2, r.commands.size)
        assertEquals(100.0, r.commands[0].amountMl!!, 0.01)
        assertEquals(2, r.commands[1].baby)
        assertEquals(80.0, r.commands[1].amountMl!!, 0.01)
    }

    @Test fun feedAndDiaperInOneCommand() {
        val r = p.process("entry for baby 1 fed 100 ml formula and pooped", now)
        assertEquals(setOf(EntryType.FEED, EntryType.OUTPUT), r.commands.map { it.type }.toSet())
    }

    @Test fun triggerOnlyArms() {
        val r = p.process("Entry for baby one", now)
        assertTrue(r.commands.isEmpty())
        assertEquals(listOf(1), r.armedFor)
    }

    @Test fun noTriggerIgnored() {
        val r = p.process("the baby fed well today", now)
        assertTrue(!r.triggered)
    }

    @Test fun relativeTime() {
        val c = one("entry for baby 1 fed 60 ml 20 minutes ago")
        assertEquals(now - 20 * 60_000, c.timestamp)
        assertEquals(60.0, c.amountMl!!, 0.01)
        assertNull(c.durationMin)
    }

    @Test fun clockTime() {
        val c = one("entry for baby 2 fed 90 ml at 3:30 p.m.")
        val cal = Calendar.getInstance().apply { timeInMillis = c.timestamp }
        assertEquals(15, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        val spoken = one("entry for baby 2 fed 90 ml at three thirty pm")
        assertEquals(c.timestamp, spoken.timestamp)
    }

    @Test fun futureClockTimeMeansYesterday() {
        val c = one("entry for baby 1 fed 90 ml at 11 pm")
        assertTrue(c.timestamp < now)
    }

    @Test fun noteFallback() {
        val c = one("entry for baby 2 note slight fever")
        assertEquals(EntryType.NOTE, c.type)
        assertEquals("slight fever", c.note)
    }

    @Test fun customNames() {
        val named = CommandParser(mapOf("Aria" to 1, "Dev" to 2))
        val r = named.process("entry for Dev wet diaper", now)
        assertEquals(2, r.commands.single().baby)
    }

    @Test fun wordsToNumbers() {
        assertEquals("120 ml", CommandParser.wordsToNumbers("one hundred twenty ml"))
        assertEquals("3 30 pm", CommandParser.wordsToNumbers("three thirty pm"))
        assertEquals("25 minutes", CommandParser.wordsToNumbers("twenty five minutes"))
    }
}
