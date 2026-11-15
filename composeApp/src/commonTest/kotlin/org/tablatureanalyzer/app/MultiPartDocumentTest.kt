package org.tablatureanalyzer.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiPartDocumentTest {
    private fun score() = TabDocument.fromParts(
        listOf(
            TabPart(
                id = "gtr", name = "Guitar", tuning = listOf(64, 59, 55, 50, 45, 40),
                events = listOf(TabEvent(listOf(0, null, null, null, null, null)), TabEvent(listOf(null, 1, null, null, null, null))),
                midiProgram = 24,
                tempoChanges = listOf(TabTempoChange(0, 132)),
                timeSignatures = listOf(TabTimeSignatureChange(0, 3, 4))
            ),
            TabPart(
                id = "bass", name = "Bass & Keys", tuning = listOf(43, 38, 33, 28),
                events = listOf(TabEvent(listOf(3, null, null, null))), midiProgram = 32
            )
        )
    )

    @Test
    fun editableProjectRoundTripsAllPartsAndActivePart() {
        val original = score().selectPart("bass")
        val parsed = TabCodec.parseInternal(TabCodec.encodeInternal(original))

        assertEquals(listOf("Guitar", "Bass & Keys"), parsed.parts.map { it.name })
        assertEquals("bass", parsed.activePart.id)
        assertEquals(32, parsed.activePart.midiProgram)
        assertEquals(listOf(43, 38, 33, 28), parsed.activePart.tuning)
        assertEquals(132, parsed.parts.first().tempoChanges.first().beatsPerMinute)
    }

    @Test
    fun musicXmlRoundTripsIndependentPartsAndPrograms() {
        val original = score()
        val parsed = MusicXmlCodec.parse(MusicXmlCodec.encode(original))

        assertEquals(listOf("Guitar", "Bass & Keys"), parsed.parts.map { it.name })
        assertEquals(listOf(24, 32), parsed.parts.map { it.midiProgram })
        assertEquals(original.parts.map { it.tuning }, parsed.parts.map { it.tuning })
        assertEquals(original.parts.map { part -> part.events.map { it.frets } }, parsed.parts.map { part -> part.events.map { it.frets } })
        assertTrue(parsed.parts.all { it.events.isNotEmpty() })
    }

    @Test
    fun choosingAVisiblePartDoesNotReorderTheScore() {
        val original = score()

        assertEquals(listOf("gtr", "bass"), original.selectPart("bass").parts.map { it.id })
        assertEquals(listOf("gtr", "bass"), original.selectPart("bass").selectPart("gtr").parts.map { it.id })
    }
}
