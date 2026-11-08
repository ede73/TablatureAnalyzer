package org.tablatureanalyzer.app

/** Turns pitch frames into tab events using audio time, including repeated plucks. */
internal class SingleNoteRecorder {
    private var candidate: Int? = null
    private var candidateSince = 0L
    private var recorded: Int? = null
    private var lastEvent = -1_000L
    private var lastLevel = 0f
    private var attackAt = -1_000L
    private var quietSince: Long? = null

    fun update(note: Int?, level: Float, threshold: Float, millis: Long): Int? {
        if (level < threshold) {
            if (quietSince == null) quietSince = millis
            if (millis - quietSince!! >= 60) recorded = null
        } else {
            quietSince = null
            if (level > maxOf(threshold, lastLevel * 1.6f)) attackAt = millis
        }
        lastLevel = level
        if (note != candidate) {
            candidate = note
            candidateSince = millis
        }
        if (note == null || level < threshold || millis - candidateSince < 60 || millis - lastEvent < 90) return null
        if (note == recorded && millis - attackAt > 100) return null
        recorded = note
        lastEvent = millis
        attackAt = -1_000L
        return note
    }
}
