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

    @Test
    fun newlyAddedInstrumentSurvivesEditableAndMusicXmlRoundTrips() {
        val original = TabDocument(
            tuning = listOf(64, 59, 55, 50, 45, 40),
            events = listOf(
                TabEvent(listOf(0, null, null, null, null, null)),
                TabEvent(listOf(null, 1, null, null, null, null), barAfter = true)
            )
        ).addInstrument(TabInstrumentPreset.BASS)

        assertEquals("Bass", original.activePart.name)
        assertEquals(2, original.activePart.events.size)
        assertTrue(original.activePart.events.all { it.frets.all { fret -> fret == null } })

        val editableRoundTrip = TabCodec.parseInternal(TabCodec.encodeInternal(original))
        val musicXmlRoundTrip = MusicXmlCodec.parse(MusicXmlCodec.encode(original))
        assertEquals("Bass", editableRoundTrip.activePart.name)
        listOf(editableRoundTrip, musicXmlRoundTrip).forEach { parsed ->
            assertEquals(listOf("Guitar", "Bass"), parsed.parts.map { it.name })
            assertEquals(33, parsed.parts[1].midiProgram)
            assertEquals(2, parsed.parts[1].events.size)
            assertTrue(parsed.parts[1].events[1].barAfter)
        }
    }

    @Test
    fun changingMidiInstrumentAffectsOnlyTheSelectedPartAndSurvivesExport() {
        val original = score().updatePart("bass") { it.copy(midiProgram = 80) }

        assertEquals(listOf(24, 80), original.parts.map { it.midiProgram })
        assertEquals(listOf(24, 80), TabCodec.parseInternal(TabCodec.encodeInternal(original)).parts.map { it.midiProgram })
        assertEquals(listOf(24, 80), MusicXmlCodec.parse(MusicXmlCodec.encode(original)).parts.map { it.midiProgram })
    }
}
