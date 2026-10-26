package org.tablatureanalyzer.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChordAnalysisTest {
    private val standardTuning = listOf(64, 59, 55, 50, 45, 40)

    @Test
    fun parsesDmAsDMinor() {
        val chord = assertNotNull(parseChordSymbol("Dm"))

        assertEquals(2, chord.root)
        assertEquals("m", chord.quality.suffix)
        assertEquals(setOf(2, 5, 9), chord.pitchClasses)
    }

    @Test
    fun dmAlwaysHasChordToneFretPatterns() {
        val chord = assertNotNull(parseChordSymbol("Dm"))
        val voicings = ChordDatabase.BuiltIn.find(standardTuning, chord)

        assertTrue(voicings.isNotEmpty(), "Dm must produce the original analyzer's fret patterns")
        assertTrue(voicings.all { voicing ->
            val soundingNotes = voicing.frets.mapIndexedNotNull { string, fret ->
                fret?.let { ((standardTuning[string] + it) % 12 + 12) % 12 }
            }.toSet()
            soundingNotes == chord.pitchClasses
        }, "Every returned Dm shape must contain D, F, and A only")
        assertTrue(voicings.all { voicing -> voicing.frets.size == 6 && voicing.fingers.size == 6 })
    }

    @Test
    fun commonMajorAndMinorChordsHaveFretPatterns() {
        val commonChords = listOf(
            "C" to setOf(0, 4, 7),
            "D" to setOf(2, 6, 9),
            "Cm" to setOf(0, 3, 7),
            "Dm" to setOf(2, 5, 9)
        )

        commonChords.forEach { (name, expectedNotes) ->
            val chord = assertNotNull(parseChordSymbol(name))
            assertEquals(expectedNotes, chord.pitchClasses, "$name pitch classes")
            val voicings = ChordDatabase.BuiltIn.find(standardTuning, chord)
            assertTrue(voicings.isNotEmpty(), "$name must have fret patterns")
            assertTrue(voicings.all { voicing ->
                voicing.frets.mapIndexedNotNull { string, fret ->
                    fret?.let { ((standardTuning[string] + it) % 12 + 12) % 12 }
                }.toSet() == expectedNotes
            }, "$name voicings must use only its chord tones")
        }
    }

    @Test
    fun dmDoesNotParseAsDMajor() {
        val minor = assertNotNull(parseChordSymbol("Dm"))
        val major = assertNotNull(parseChordSymbol("D"))
        val explicitMajor = assertNotNull(parseChordSymbol("DM"))

        assertTrue(minor.pitchClasses != major.pitchClasses)
        assertEquals(setOf(2, 5, 9), minor.pitchClasses)
        assertEquals(setOf(2, 6, 9), major.pitchClasses)
        assertEquals("", explicitMajor.quality.suffix)
        assertEquals(setOf(2, 6, 9), explicitMajor.pitchClasses)
    }

    @Test
    fun dMinorSus4UsesSuspendedNotes() {
        val dmSus4 = assertNotNull(parseChordSymbol("Dmsus4"))
        val parenthesizedDmSus4 = assertNotNull(parseChordSymbol("Dm(sus4)"))
        val dSus4 = assertNotNull(parseChordSymbol("Dsus4"))

        assertEquals("msus4", dmSus4.quality.suffix)
        assertEquals("Dmsus4", dmSus4.name)
        assertEquals(setOf(2, 7, 9), dmSus4.pitchClasses)
        assertEquals(dmSus4.pitchClasses, parenthesizedDmSus4.pitchClasses)
        assertEquals(setOf(2, 7, 9), dSus4.pitchClasses)

        val voicings = ChordDatabase.BuiltIn.find(standardTuning, dmSus4)
        assertTrue(voicings.isNotEmpty(), "Dmsus4 must produce fret patterns")
        assertTrue(voicings.all { voicing ->
            voicing.frets.mapIndexedNotNull { string, fret ->
                fret?.let { ((standardTuning[string] + it) % 12 + 12) % 12 }
            }.toSet() == dmSus4.pitchClasses
        })
    }
}
