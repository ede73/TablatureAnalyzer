package org.tablatureanalyzer.app

enum class VoicingKind { OPEN, BARRE, OTHER }

data class ChordVoicing(
    /** Absolute fret numbers, in the app's high-e to low-E string order. */
    val frets: List<Int?>,
    val fingers: List<Int>,
    val baseFret: Int,
    val barreFret: Int?,
    val kind: VoicingKind,
    val label: String
) {
    val fingerCount: Int get() = fingers.filter { it > 0 }.distinct().size
}

/** Ports the original analyzer's fret-pattern search; it stores no external chord dataset. */
class ChordDatabase private constructor() {
    fun find(tuning: List<Int>, chord: ChordSymbol): List<ChordVoicing> =
        findOriginalFretPatterns(tuning, chord)

    companion object {
        val BuiltIn = ChordDatabase()
    }
}

/**
 * Port of the original generateHold algorithm: try every 0–3 fret pattern in each
 * of the twelve positions, then apply its chord-tone, root-count, barre, and
 * adjacent-unison filters. The result is calculated from the chord and tuning.
 */
private fun findOriginalFretPatterns(tuning: List<Int>, chord: ChordSymbol): List<ChordVoicing> {
    if (tuning.size != 6) return emptyList()
    val chordTones = chord.pitchClasses.map { ((it % 12) + 12) % 12 }.toSet()
    if (chordTones.isEmpty()) return emptyList()

    val results = mutableListOf<ChordVoicing>()
    val seen = mutableSetOf<List<Int>>()
    for (position in 0..11) {
        for (encodedPattern in 0 until 4095) {
            val relativeFrets = decodeFretPattern(encodedPattern, tuning.size)
            val pitches = relativeFrets.mapIndexed { string, fret ->
                ((position + fret + tuning[string]) % 12 + 12) % 12
            }
            if (pitches.any { it !in chordTones } || !chordTones.all { it in pitches }) continue

            val rootCount = pitches.count { it == chord.root }
            if (rootCount < 2) continue

            val fretCounts = IntArray(4)
            relativeFrets.forEach { fretCounts[it]++ }
            val lowestFret = relativeFrets.minOf { position + it }
            var barreCandidate = lowestFret
            relativeFrets.forEach { fret ->
                if (fretCounts[fret] >= tuning.size / 2) barreCandidate = position + fret
            }

            // These match the original's checks for notes below the barre,
            // repeated pitches on adjacent strings, and excessive finger reach.
            if (lowestFret < barreCandidate) continue
            if (pitches.zipWithNext().any { (left, right) -> left == right }) continue
            val fretsAboveLowest = relativeFrets.count { position + it != lowestFret }
            if (barreCandidate == lowestFret && lowestFret != 0 && fretsAboveLowest > 3) continue
            if (!seen.add(relativeFrets.map { position + it })) continue

            val absoluteFrets = relativeFrets.map { position + it }
            val hasBarre = barreCandidate > 0 && relativeFrets.count { position + it == barreCandidate } >= tuning.size / 2
            val barreFret = if (hasBarre) barreCandidate else null
            val kind = when {
                barreFret != null -> VoicingKind.BARRE
                absoluteFrets.any { it == 0 } -> VoicingKind.OPEN
                else -> VoicingKind.OTHER
            }
            val baseFret = lowestFret.coerceAtLeast(1)
            val label = when (kind) {
                VoicingKind.OPEN -> "Open position"
                VoicingKind.BARRE -> "Barre · fret $barreCandidate"
                VoicingKind.OTHER -> "Fretted position · fret $baseFret"
            }
            results += ChordVoicing(
                frets = absoluteFrets,
                fingers = assignFingers(absoluteFrets, barreFret),
                baseFret = baseFret,
                barreFret = barreFret,
                kind = kind,
                label = label
            )
            if (results.size == 20) return results
        }
    }
    return results
}

private fun decodeFretPattern(encoded: Int, stringCount: Int): List<Int> {
    var remaining = encoded
    return IntArray(stringCount) { 0 }.also { frets ->
        for (string in stringCount - 1 downTo 0) {
            frets[string] = remaining % 4
            remaining /= 4
        }
    }.toList()
}

private fun assignFingers(frets: List<Int>, barreFret: Int?): List<Int> {
    val fingerByFret = mutableMapOf<Int, Int>()
    barreFret?.let { fingerByFret[it] = 1 }
    var nextFinger = if (barreFret == null) 1 else 2
    frets.asSequence().filter { it > 0 && it != barreFret }.distinct().sorted().forEach { fret ->
        fingerByFret[fret] = nextFinger.coerceAtMost(4)
        nextFinger++
    }
    return frets.map { fret -> if (fret == 0) 0 else fingerByFret[fret] ?: 4 }
}
