package org.tablatureanalyzer.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteEntryTest {
    private val tuning = listOf(64, 59, 55, 50, 45, 40)

    @Test
    fun clickEntryCanStartAnEmptyTabAtChosenStringAndFret() {
        val blank = TabDocument(tuning, emptyList())

        val recorded = addNoteAt(blank, eventIndex = 0, stringIndex = 2, fret = 7, lengthCode = 5)

        assertEquals(1, recorded.events.size)
        assertEquals(7, recorded.events.single().frets[2])
        assertEquals(5, recorded.events.single().lengthCodes[2])
    }

    @Test
    fun clickEntryAtLaterTimeCreatesEmptyPositionsBetweenNotes() {
        val first = addNoteAt(TabDocument(tuning, emptyList()), 0, stringIndex = 0, fret = 3)

        val later = addNoteAt(first, eventIndex = 3, stringIndex = 1, fret = 5)

        assertEquals(4, later.events.size)
        assertEquals(3, later.events[0].frets[0])
        assertNull(later.events[1].frets[1])
        assertNull(later.events[2].frets[1])
        assertEquals(5, later.events[3].frets[1])
    }
}
