package org.tablatureanalyzer.app

/** One simultaneous position in the tab. Legacy length codes are 1..12; null means default/unspecified. */
data class TabEvent(
    val frets: List<Int?>,
    val actions: List<Char?> = List(frets.size) { null },
    val barAfter: Boolean = false,
    val lengthCodes: List<Int?> = List(frets.size) { null },
    /** MusicXML duration units, using the containing document's divisions per quarter. */
    val durationUnits: List<Int?> = List(frets.size) { null },
    val restDurationUnits: Int? = null
)
data class TabTempoChange(val eventIndex: Int, val beatsPerMinute: Int)
data class TabTimeSignatureChange(val eventIndex: Int, val beats: Int, val beatType: Int)
data class TabDocument(
    val tuning: List<Int>,
    val events: List<TabEvent>,
    val divisions: Int = 480,
    val tempoChanges: List<TabTempoChange> = listOf(TabTempoChange(0, 120)),
    val timeSignatures: List<TabTimeSignatureChange> = listOf(TabTimeSignatureChange(0, 4, 4))
)

object TabCodec {
    private val defaultTuning = listOf(64, 59, 55, 50, 45, 40)

    fun parse(source: String): TabDocument =
        if (source.contains("<score-partwise") || source.contains(":score-partwise")) MusicXmlCodec.parse(source)
        else if (source.lineSequence().firstOrNull()?.trim() == "#Internalstatesavefile 0.1") parseInternal(source)
        else parseAsciiTab(source)

    /** Imports the original application's lossless, editable text format. */
    fun parseInternal(source: String): TabDocument {
        var strings = 6
        var tuning = defaultTuning
        val events = mutableListOf<TabEvent>()
        var barPending = false
        var headerComplete = false
        for (raw in source.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() || line == "#Internalstatesavefile 0.1" -> Unit
                line.startsWith("#strings") -> {
                    strings = line.substringAfter("#strings").trim().toIntOrNull()?.coerceIn(1, 12) ?: strings
                    if (tuning.size != strings) tuning = List(strings) { tuning.getOrElse(it) { 0 } }
                }
                line.startsWith("#tuning") -> {
                    val parsed = line.substringAfter("#tuning").trim().split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (parsed.isNotEmpty()) {
                        tuning = parsed
                        strings = parsed.size
                    }
                    headerComplete = true
                }
                line == "#bar" -> {
                    if (events.isNotEmpty()) events[events.lastIndex] = events.last().copy(barAfter = true)
                    else barPending = true
                }
                line.startsWith("#") -> Unit // Preserve compatibility with legacy remarks/directives.
                !headerComplete -> Unit
                else -> {
                    val frets = MutableList<Int?>(strings) { null }
                    val actions = MutableList<Char?>(strings) { null }
                    val lengths = MutableList<Int?>(strings) { null }
                    line.split('+').filter(String::isNotBlank).forEach { token ->
                        val fields = token.split(',')
                        if (fields.size >= 4) {
                            val fret = fields[0].trim().toIntOrNull()
                            val string = fields[1].trim().toIntOrNull()
                            val length = fields[2].trim().toIntOrNull()
                            if (fret != null && string != null && string in 0 until strings) {
                                frets[string] = fret
                                lengths[string] = length?.takeIf { it in 1..12 }
                                actions[string] = fields[3].trim().firstOrNull()?.takeUnless { it == '-' }
                            }
                        }
                    }
                    events += TabEvent(frets, actions, barPending, lengths)
                    barPending = false
                }
            }
        }
        if (barPending && events.isNotEmpty()) events[events.lastIndex] = events.last().copy(barAfter = true)
        return TabDocument(tuning, events)
    }

    private fun parseAsciiTab(source: String): TabDocument {
        val lines = source.lines()
        var strings = 6
        var tuning = defaultTuning
        var transpose = 0
        val groups = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var pendingBar = false
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.startsWith("#strings ")) {
                strings = line.substringAfter(' ').toIntOrNull()?.coerceIn(1, 12) ?: strings
                continue
            }
            if (line.startsWith("#tuning")) {
                val values = line.substringAfter("#tuning").trim().split(',', ' ', '\t').filter(String::isNotBlank).mapNotNull(String::toIntOrNull)
                if (values.isNotEmpty()) { tuning = values; strings = values.size }
                continue
            }
            if (line.startsWith("#transpose ")) { transpose = line.substringAfter(' ').toIntOrNull() ?: 0; continue }
            if (line.startsWith("#bar")) { pendingBar = true; continue }
            if (line.startsWith('#') || line.isBlank()) continue
            if ('-' !in line && '|' !in line && line.none(Char::isDigit)) continue
            current += line
            if (current.size == strings) {
                groups += current.toList()
                current = mutableListOf()
            }
        }
        if (current.size == strings) groups += current.toList()
        val events = mutableListOf<TabEvent>()
        for (group in groups) {
            val width = group.maxOfOrNull(String::length) ?: 0
            var col = 0
            val pendingActions = MutableList<Char?>(strings) { null }
            while (col < width) {
                val frets = MutableList<Int?>(strings) { null }
                val actions = MutableList<Char?>(strings) { null }
                var bar = pendingBar
                pendingBar = false
                for (s in 0 until strings) {
                    val line = group.getOrElse(s) { "" }
                    if (col >= line.length) continue
                    if (line[col] == '|') bar = true
                    if (line[col].isAction() && (col == 0 || line[col - 1] == '-' || line[col - 1] == '.')) {
                        pendingActions[s] = line[col]
                    }
                    if (line[col].isDigit()) {
                        var end = col + 1
                        while (end < line.length && line[end].isDigit()) end++
                        frets[s] = line.substring(col, end).toIntOrNull()?.plus(transpose)
                        actions[s] = if (end < line.length && line[end].isAction()) line[end] else pendingActions[s]
                        pendingActions[s] = null
                        col = maxOf(col, end - 1)
                    }
                }
                if (frets.any { it != null } || bar) events += TabEvent(frets, actions, bar)
                col++
            }
        }
        return TabDocument(tuning, events)
    }

    /** Writes the original editable format so fret, action, length, tuning and bars round-trip. */
    fun encodeInternal(document: TabDocument): String = buildString {
        appendLine("#Internalstatesavefile 0.1")
        appendLine("#strings ${document.tuning.size}")
        appendLine("#tuning ${document.tuning.joinToString(",")}")
        document.events.forEach { event ->
            val notes = event.frets.mapIndexedNotNull { string, fret ->
                fret?.let {
                    val action = event.actions.getOrNull(string)?.toString() ?: "-"
                    "${it},${string},${event.lengthCodes.getOrNull(string) ?: -1234},$action"
                }
            }
            appendLine(notes.joinToString("+"))
            if (event.barAfter) appendLine("#bar")
        }
    }

    fun encode(document: TabDocument): String {
        val rows = MutableList(document.tuning.size) { StringBuilder() }
        document.events.forEach { event ->
            val tokens = event.frets.mapIndexed { string, fret -> fret?.toString()?.plus(event.actions.getOrNull(string) ?: "") ?: "-" }
            val tokenWidth = tokens.maxOfOrNull(String::length) ?: 1
            tokens.forEachIndexed { string, token -> rows[string].append(token.padEnd(tokenWidth, '-')) }
            if (event.barAfter) rows.forEach { it.append('|') }
        }
        return "#strings ${document.tuning.size}\n#tuning ${document.tuning.joinToString(",")}\n" + rows.mapIndexed { i, row ->
            val label = listOf("e", "B", "G", "D", "A", "E").getOrElse(i) { "S${i + 1}" }
            "$label|$row|"
        }.joinToString("\n")
    }
}

private fun Char.isAction(): Boolean = this in "sS/\\hHpPtT^~bB)vVrR"

/** Add a note to a vacant string at an existing event; if occupied, insert a new event before it. */
fun addNoteAt(document: TabDocument, eventIndex: Int, stringIndex: Int, fret: Int, lengthCode: Int? = null): TabDocument {
    if (stringIndex !in document.tuning.indices || fret !in 0..36) return document
    val events = document.events.toMutableList()
    val index = eventIndex.coerceIn(0, events.size)
    val existing = events.getOrNull(index)
    if (existing != null && existing.frets.getOrNull(stringIndex) == null) {
        val frets = existing.frets.toMutableList()
        val actions = existing.actions.toMutableList()
        val lengths = existing.lengthCodes.toMutableList()
        val durations = existing.durationUnits.toMutableList()
        frets[stringIndex] = fret
        actions[stringIndex] = null
        lengths[stringIndex] = lengthCode
        durations[stringIndex] = null
        events[index] = existing.copy(frets = frets, actions = actions, lengthCodes = lengths, durationUnits = durations)
    } else {
        val frets = MutableList<Int?>(document.tuning.size) { null }.also { it[stringIndex] = fret }
        val lengths = MutableList<Int?>(document.tuning.size) { null }.also { it[stringIndex] = lengthCode }
        events.add(index, TabEvent(frets, lengthCodes = lengths))
    }
    return document.copy(events = events)
}

/** Set the legacy duration code for a selected note. */
fun setNoteLengthAt(document: TabDocument, eventIndex: Int, stringIndex: Int, lengthCode: Int): TabDocument {
    val event = document.events.getOrNull(eventIndex) ?: return document
    if (stringIndex !in event.frets.indices || event.frets[stringIndex] == null || lengthCode !in 1..12) return document
    val lengths = event.lengthCodes.toMutableList()
    val durations = event.durationUnits.toMutableList()
    lengths[stringIndex] = lengthCode
    durations[stringIndex] = null // The original code is authoritative; MusicXML duration is derived on export.
    return document.copy(events = document.events.toMutableList().also {
        it[eventIndex] = event.copy(lengthCodes = lengths, durationUnits = durations)
    })
}

/** Append an empty event position to the tab so the user can add notes to it. */
fun addTabPosition(document: TabDocument): TabDocument =
    document.copy(events = document.events + TabEvent(List(document.tuning.size) { null }))

fun removeMeasureBar(document: TabDocument, eventIndex: Int): TabDocument {
    val event = document.events.getOrNull(eventIndex) ?: return document
    if (!event.barAfter) return document
    return document.copy(events = document.events.toMutableList().also { it[eventIndex] = event.copy(barAfter = false) })
}

fun addMeasureBar(document: TabDocument, eventIndex: Int): TabDocument {
    val event = document.events.getOrNull(eventIndex) ?: return document
    if (event.barAfter) return document
    return document.copy(events = document.events.toMutableList().also { it[eventIndex] = event.copy(barAfter = true) })
}

fun moveMeasureBar(document: TabDocument, fromEvent: Int, toEvent: Int): TabDocument {
    val source = document.events.getOrNull(fromEvent) ?: return document
    val target = document.events.getOrNull(toEvent) ?: return document
    if (!source.barAfter || fromEvent == toEvent || target.barAfter) return document
    val events = document.events.toMutableList()
    events[fromEvent] = source.copy(barAfter = false)
    events[toEvent] = target.copy(barAfter = true)
    return document.copy(events = events)
}

/** Delete the selected string's note; remove its event only when it becomes entirely empty. */
fun deleteNoteAt(document: TabDocument, eventIndex: Int, stringIndex: Int): TabDocument {
    val event = document.events.getOrNull(eventIndex) ?: return document
    if (stringIndex !in event.frets.indices || event.frets[stringIndex] == null) return document
    val frets = event.frets.toMutableList()
    val actions = event.actions.toMutableList()
    val lengths = event.lengthCodes.toMutableList()
    val durations = event.durationUnits.toMutableList()
    frets[stringIndex] = null
    actions[stringIndex] = null
    lengths[stringIndex] = null
    durations[stringIndex] = null
    val events = document.events.toMutableList()
    if (frets.none { it != null } && !event.barAfter) events.removeAt(eventIndex)
    else events[eventIndex] = event.copy(frets = frets, actions = actions, lengthCodes = lengths, durationUnits = durations)
    return document.copy(events = events)
}

fun moveNote(document: TabDocument, eventIndex: Int, fromString: Int, toString: Int): TabDocument {
    if (fromString !in document.tuning.indices || toString !in document.tuning.indices || fromString == toString) return document
    val event = document.events.getOrNull(eventIndex) ?: return document
    val oldFret = event.frets.getOrNull(fromString) ?: return document
    val absolutePitch = document.tuning[fromString] + oldFret
    val newFret = absolutePitch - document.tuning[toString]
    if (newFret !in 0..36 || event.frets[toString] != null) return document
    val changed = event.frets.toMutableList()
    val actions = event.actions.toMutableList()
    val lengths = event.lengthCodes.toMutableList()
    val durations = event.durationUnits.toMutableList()
    changed[fromString] = null
    changed[toString] = newFret
    actions[toString] = actions.getOrNull(fromString)
    actions[fromString] = null
    lengths[toString] = lengths.getOrNull(fromString)
    lengths[fromString] = null
    durations[toString] = durations.getOrNull(fromString)
    durations[fromString] = null
    return document.copy(events = document.events.toMutableList().also { it[eventIndex] = event.copy(frets = changed, actions = actions, lengthCodes = lengths, durationUnits = durations) })
}

/** Move to the nearest playable empty string in the requested direction, preserving pitch. */
fun moveNoteInDirection(document: TabDocument, eventIndex: Int, fromString: Int, direction: Int): TabDocument {
    if (direction == 0 || fromString !in document.tuning.indices) return document
    for (distance in 1 until document.tuning.size) {
        val target = (fromString + distance * direction).mod(document.tuning.size)
        val moved = moveNote(document, eventIndex, fromString, target)
        if (moved !== document) return moved
    }
    return document
}

data class BatchNoteMoveResult(
    val document: TabDocument,
    val movedSelection: Set<NotePosition>,
    val applied: Boolean
)

/** Shift a rectangle selection together by one string, preserving every selected note's pitch. */
fun moveNotesInDirection(document: TabDocument, selection: Set<NotePosition>, direction: Int): BatchNoteMoveResult {
    if (selection.isEmpty() || direction !in -1..1 || direction == 0) return BatchNoteMoveResult(document, selection, false)
    val byEvent = selection.groupBy { it.eventIndex }
    data class Move(val from: Int, val to: Int, val fret: Int, val action: Char?, val length: Int?, val duration: Int?)
    val movesByEvent = mutableMapOf<Int, List<Move>>()

    for ((eventIndex, positions) in byEvent) {
        val event = document.events.getOrNull(eventIndex) ?: return BatchNoteMoveResult(document, selection, false)
        val sources = positions.map { it.stringIndex }.toSet()
        val moves = positions.map { position ->
            val from = position.stringIndex
            val to = from + direction
            if (from !in event.frets.indices || to !in document.tuning.indices) return BatchNoteMoveResult(document, selection, false)
            val oldFret = event.frets[from] ?: return BatchNoteMoveResult(document, selection, false)
            if (event.frets[to] != null && to !in sources) return BatchNoteMoveResult(document, selection, false)
            val newFret = document.tuning[from] + oldFret - document.tuning[to]
            if (newFret !in 0..36) return BatchNoteMoveResult(document, selection, false)
            Move(from, to, newFret, event.actions.getOrNull(from), event.lengthCodes.getOrNull(from), event.durationUnits.getOrNull(from))
        }
        movesByEvent[eventIndex] = moves
    }

    val events = document.events.toMutableList()
    for ((eventIndex, moves) in movesByEvent) {
        val event = events[eventIndex]
        val frets = event.frets.toMutableList()
        val actions = event.actions.toMutableList()
        val lengths = event.lengthCodes.toMutableList()
        val durations = event.durationUnits.toMutableList()
        moves.forEach { move ->
            frets[move.from] = null
            actions[move.from] = null
            lengths[move.from] = null
            durations[move.from] = null
        }
        moves.forEach { move ->
            frets[move.to] = move.fret
            actions[move.to] = move.action
            lengths[move.to] = move.length
            durations[move.to] = move.duration
        }
        events[eventIndex] = event.copy(frets = frets, actions = actions, lengthCodes = lengths, durationUnits = durations)
    }
    val movedSelection = selection.mapTo(mutableSetOf()) { it.copy(stringIndex = it.stringIndex + direction) }
    return BatchNoteMoveResult(document.copy(events = events), movedSelection, true)
}

enum class GroupFretOperation { RAISE_MINIMUM, LOWER_MAXIMUM, TRANSPOSE }

data class NotePosition(val eventIndex: Int, val stringIndex: Int)

data class GroupFretRequest(
    val operation: GroupFretOperation,
    val minimumFret: Int,
    val maximumFret: Int,
    val semitones: Int = 0,
    val selectedNotes: Set<NotePosition> = emptySet()
)

data class GroupFretResult(val document: TabDocument, val applied: Boolean, val message: String)

/** Experimental port of the legacy fret-bound and transpose controls. */
fun applyGroupFretOperation(document: TabDocument, request: GroupFretRequest): GroupFretResult {
    val minFret = request.minimumFret
    val maxFret = request.maximumFret
    val validRange = when (request.operation) {
        GroupFretOperation.RAISE_MINIMUM -> minFret in 0..36
        GroupFretOperation.LOWER_MAXIMUM -> maxFret in 0..36
        GroupFretOperation.TRANSPOSE -> minFret in 0..36 && maxFret in minFret..36
    }
    if (!validRange) {
        return GroupFretResult(document, false, "Fret range must be between 0 and 36, with minimum ≤ maximum.")
    }
    if (request.operation == GroupFretOperation.TRANSPOSE && request.semitones !in -48..48) {
        return GroupFretResult(document, false, "Transpose amount must be between −48 and 48 semitones.")
    }
    if (request.operation != GroupFretOperation.TRANSPOSE && request.selectedNotes.isEmpty()) {
        return GroupFretResult(document, false, "Rectangle-select notes first; each selection adds to the current selection.")
    }
    var movedNotes = 0
    val transformed = mutableListOf<TabEvent>()
    for ((eventIndex, event) in document.events.withIndex()) {
        val notes = event.frets.mapIndexedNotNull { string, fret ->
            fret?.let { NoteToPlace(string, document.tuning[string] + it + if (request.operation == GroupFretOperation.TRANSPOSE) request.semitones else 0, event.actions.getOrNull(string), event.lengthCodes.getOrNull(string), event.durationUnits.getOrNull(string), request.operation == GroupFretOperation.TRANSPOSE || NotePosition(eventIndex, string) in request.selectedNotes) }
        }
        if (notes.isEmpty()) {
            transformed += event
            continue
        }

        val options = notes.map { note ->
            document.tuning.indices.mapNotNull { target ->
                val fret = note.pitch - document.tuning[target]
                if (!note.selected) {
                    return@mapNotNull if (target == note.sourceString && fret in 0..36) NotePlacement(target, fret, 0) else null
                }
                val directionAllowed = when (request.operation) {
                    GroupFretOperation.RAISE_MINIMUM -> target >= note.sourceString
                    GroupFretOperation.LOWER_MAXIMUM -> target <= note.sourceString
                    GroupFretOperation.TRANSPOSE -> true
                }
                val fretAllowed = when (request.operation) {
                    GroupFretOperation.RAISE_MINIMUM -> fret in minFret..36
                    GroupFretOperation.LOWER_MAXIMUM -> fret in 0..maxFret
                    GroupFretOperation.TRANSPOSE -> fret in minFret..maxFret
                }
                if (directionAllowed && fretAllowed) NotePlacement(target, fret, kotlin.math.abs(target - note.sourceString)) else null
            }.sortedWith(compareBy<NotePlacement> { it.stringDistance }.thenBy { it.fret })
        }
        val order = notes.indices.sortedBy { options[it].size }
        var bestCost = Int.MAX_VALUE
        var best: List<NotePlacement?>? = null
        val current = MutableList<NotePlacement?>(notes.size) { null }
        val occupied = mutableSetOf<Int>()
        fun search(depth: Int, cost: Int) {
            if (cost >= bestCost) return
            if (depth == order.size) {
                bestCost = cost
                best = current.toList()
                return
            }
            val noteIndex = order[depth]
            for (placement in options[noteIndex]) {
                if (!occupied.add(placement.string)) continue
                current[noteIndex] = placement
                search(depth + 1, cost + placement.stringDistance * 100)
                current[noteIndex] = null
                occupied.remove(placement.string)
            }
        }
        search(0, 0)
        val assignment = best ?: return GroupFretResult(document, false, "Could not fit all notes in event ${eventIndex + 1} without a string collision; no changes were applied.")
        val frets = MutableList<Int?>(document.tuning.size) { null }
        val actions = MutableList<Char?>(document.tuning.size) { null }
        val lengths = MutableList<Int?>(document.tuning.size) { null }
        val durations = MutableList<Int?>(document.tuning.size) { null }
        notes.forEachIndexed { index, note ->
            val placement = assignment[index]!!
            frets[placement.string] = placement.fret
            actions[placement.string] = note.action
            lengths[placement.string] = note.lengthCode
            durations[placement.string] = note.durationUnits
            if (placement.string != note.sourceString) movedNotes++
        }
        transformed += event.copy(frets = frets, actions = actions, lengthCodes = lengths, durationUnits = durations)
    }

    val operationName = when (request.operation) {
        GroupFretOperation.RAISE_MINIMUM -> "Apply lower limit to selected notes"
        GroupFretOperation.LOWER_MAXIMUM -> "Apply upper limit to selected notes"
        GroupFretOperation.TRANSPOSE -> "Transpose entire song by ${request.semitones} semitones"
    }
    val message = if (movedNotes == 0) "$operationName: no notes needed to move." else "$operationName: repositioned $movedNotes note${if (movedNotes == 1) "" else "s"}."
    return GroupFretResult(document.copy(events = transformed), true, message)
}

private data class NoteToPlace(val sourceString: Int, val pitch: Int, val action: Char?, val lengthCode: Int?, val durationUnits: Int?, val selected: Boolean)
private data class NotePlacement(val string: Int, val fret: Int, val stringDistance: Int)

data class ScaleDefinition(val name: String, val steps: List<Int>)
val scales = listOf(
    ScaleDefinition("Major", listOf(0, 2, 4, 5, 7, 9, 11)),
    ScaleDefinition("Natural minor", listOf(0, 2, 3, 5, 7, 8, 10)),
    ScaleDefinition("Major pentatonic", listOf(0, 2, 4, 7, 9)),
    ScaleDefinition("Minor pentatonic", listOf(0, 3, 5, 7, 10)),
    ScaleDefinition("Blues", listOf(0, 3, 5, 6, 7, 10)),
    ScaleDefinition("Dorian", listOf(0, 2, 3, 5, 7, 9, 10)),
    ScaleDefinition("Phrygian", listOf(0, 1, 3, 5, 7, 8, 10)),
    ScaleDefinition("Whole tone", listOf(0, 2, 4, 6, 8, 10))
)
val noteNames = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

enum class ChordNamingSystem(val label: String, val shortLabel: String) {
    US("US (B)", "US · B"), GERMAN("German / Finnish (H)", "H · German")
}

fun noteName(pitchClass: Int, system: ChordNamingSystem = ChordNamingSystem.US): String {
    val pitch = ((pitchClass % 12) + 12) % 12
    return when {
        system == ChordNamingSystem.GERMAN && pitch == 10 -> "B"
        system == ChordNamingSystem.GERMAN && pitch == 11 -> "H"
        else -> noteNames[pitch]
    }
}

data class ChordQuality(val suffix: String, val intervals: List<Int>, val aliases: List<String> = emptyList())
data class ChordSymbol(val root: Int, val quality: ChordQuality) {
    val name: String get() = noteNames[root] + quality.suffix
    fun name(system: ChordNamingSystem): String = noteName(root, system) + quality.suffix
    val pitchClasses: Set<Int> get() = quality.intervals.map { (root + it) % 12 }.toSet()
}

val chordQualities = listOf(
    ChordQuality("", listOf(0, 4, 7), listOf("M", "maj", "major")),
    ChordQuality("m", listOf(0, 3, 7), listOf("min", "minor")),
    ChordQuality("dim", listOf(0, 3, 6), listOf("o", "°", "diminished")),
    ChordQuality("aug", listOf(0, 4, 8), listOf("+", "augmented")),
    ChordQuality("7", listOf(0, 4, 7, 10), listOf("dom7", "7th", "seventh", "dominant7", "dominant 7", "dominant 7th", "dominant seventh")),
    ChordQuality("maj7", listOf(0, 4, 7, 11), listOf("major7", "major 7", "major 7th", "major seventh")),
    ChordQuality("m7", listOf(0, 3, 7, 10), listOf("min7", "minor7", "minor 7", "minor 7th", "minor seventh")),
    ChordQuality("m7♭5", listOf(0, 3, 6, 10), listOf("m7b5", "ø", "halfdiminished")),
    ChordQuality("m(maj7)", listOf(0, 3, 7, 11), listOf("mmaj7", "minor(maj7)")),
    ChordQuality("6", listOf(0, 4, 7, 9), listOf("sixth", "6th")),
    ChordQuality("m6", listOf(0, 3, 7, 9), listOf("min6", "minor6", "minor 6", "minor 6th", "minor sixth")),
    ChordQuality("sus2", listOf(0, 2, 7), listOf("suspended2", "suspended 2")),
    ChordQuality("sus4", listOf(0, 5, 7), listOf("sus", "suspended4", "suspended 4")),
    ChordQuality("msus4", listOf(0, 5, 7), listOf("m(sus4)", "min(sus4)", "minor sus4", "minor suspended 4")),
    ChordQuality("add9", listOf(0, 2, 4, 7)),
    ChordQuality("9", listOf(0, 2, 4, 7, 10), listOf("9th", "ninth")),
    ChordQuality("m9", listOf(0, 2, 3, 7, 10), listOf("min9", "minor9", "minor 9", "minor 9th", "minor ninth")),
    ChordQuality("maj9", listOf(0, 2, 4, 7, 11), listOf("M9", "major9", "major 9", "major 9th", "major ninth")),
    // Wider chord vocabulary ported from the original analyzer's interval masks.
    // Existing qualities stay first so the current analyzer keeps its established preference.
    ChordQuality("5", listOf(0, 7), listOf("fifth", "5th", "(no3rd)", "no3rd", "(no 3rd)")),
    ChordQuality("7sus2", listOf(0, 2, 7, 10), listOf("7suspended2")),
    ChordQuality("7sus4", listOf(0, 5, 7, 10), listOf("7suspended4")),
    ChordQuality("11", listOf(0, 2, 5, 7, 10), listOf("11th", "eleventh")),
    ChordQuality("m11", listOf(0, 3, 5, 10), listOf("min11", "minor11", "minor 11", "minor 11th", "minor eleventh")),
    ChordQuality("13", listOf(0, 4, 7, 9, 10), listOf("13th", "thirteenth")),
    ChordQuality("6+9", listOf(0, 2, 4, 7, 9), listOf("6add9", "6thadd9", "sixthadd9", "sixth add ninth")),
    ChordQuality("7+9", listOf(0, 3, 4, 7, 10), listOf("7aug9", "7thaugmented9th", "seventhaugmentedninth")),
    ChordQuality("m7+5", listOf(0, 3, 8, 10), listOf("min7+5", "m7aug5", "minor7augmented5th")),
    ChordQuality("11+", listOf(0, 2, 6, 10), listOf("aug11", "augmented11", "augmentedeleventh")),
    ChordQuality("dim7", listOf(0, 3, 6, 9), listOf("o7", "°7", "ø7", "diminished7", "diminished7th", "diminishedseventh")),
    ChordQuality("7-9", listOf(0, 3, 6), listOf("7dim9", "7thdiminished9th")),
    ChordQuality("m7-5", listOf(0, 3, 6, 9), listOf("min7-5", "m7dim5", "minor7-5")),
    ChordQuality("m7(add3)", listOf(0, 3, 4, 7, 10), listOf("min7(add3)", "m7add3", "min7(add 3)")),
    ChordQuality("maj9(drop3)", listOf(0, 2, 7, 11), listOf("major9(drop3)", "M9(drop3)")),
    ChordQuality("7(drop5)", listOf(0, 4, 10)),
    ChordQuality("m7(drop5)", listOf(0, 3, 10), listOf("min7(drop5)")),
    ChordQuality("6(drop5)", listOf(0, 4, 9)),
    ChordQuality("6+9(drop5)", listOf(0, 2, 4, 9)),
    ChordQuality("7-9(drop5)", listOf(0, 1, 4, 10)),
    ChordQuality("7+9(drop5)", listOf(0, 3, 4, 10)),
    ChordQuality("9(drop5)", listOf(0, 2, 4, 10)),
    ChordQuality("maj9(drop5)", listOf(0, 2, 4, 11), listOf("major9(drop5)", "M9(drop5)")),
    ChordQuality("m9(drop5)", listOf(0, 2, 3, 10), listOf("min9(drop5)")),
    ChordQuality("13(drop5)", listOf(0, 4, 9, 10)),
    ChordQuality("m11(drop9)", listOf(0, 2, 3, 5, 10), listOf("min11(drop9)"))
)

fun parseChordSymbol(input: String, system: ChordNamingSystem = ChordNamingSystem.US): ChordSymbol? {
    val match = Regex("^\\s*([A-Ga-h])([#b♯♭]?)(.*)\\s*$", RegexOption.IGNORE_CASE).matchEntire(input) ?: return null
    val natural = when (match.groupValues[1].uppercase()) {
        "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9
        "B" -> if (system == ChordNamingSystem.GERMAN) 10 else 11
        "H" -> 11
        else -> return null
    }
    val accidental = when (match.groupValues[2]) { "#", "♯" -> 1; "b", "♭" -> -1; else -> 0 }
    val root = (natural + accidental + 12) % 12
    val suffix = match.groupValues[3].trim()
    val normalizedSuffix = normalizeChordSuffix(suffix)
    val quality = when (suffix) {
        "M" -> chordQualities.first()
        "m" -> chordQualities.first { it.suffix == "m" }
        "M7" -> chordQualities.first { it.suffix == "maj7" }
        "M9" -> chordQualities.first { it.suffix == "maj9" }
        else -> chordQualities.firstOrNull { candidate ->
            (listOf(candidate.suffix) + candidate.aliases).any { normalizeChordSuffix(it) == normalizedSuffix }
        }
    } ?: return null
    return ChordSymbol(root, quality)
}

private fun normalizeChordSuffix(value: String): String = value.lowercase()
    .replace(" ", "")
    .replace("♭", "b")
    .replace("♯", "#")

fun identifyChord(notes: Set<Int>): ChordSymbol? {
    val normalized = notes.map { ((it % 12) + 12) % 12 }.toSet()
    return (0..11).asSequence().flatMap { root -> chordQualities.asSequence().map { ChordSymbol(root, it) } }
        .firstOrNull { it.pitchClasses == normalized }
}

/** Returns all exact chord-name interpretations; identifyChord remains the preferred single result. */
fun identifyChordPossibilities(notes: Set<Int>): List<ChordSymbol> {
    val normalized = notes.map { ((it % 12) + 12) % 12 }.toSet()
    if (normalized.isEmpty()) return emptyList()
    return (0..11).asSequence().flatMap { root -> chordQualities.asSequence().map { ChordSymbol(root, it) } }
        .filter { it.pitchClasses == normalized }
        .distinctBy { it.name }
        .toList()
}

fun chordsInScale(key: Int, scale: ScaleDefinition): List<ChordSymbol> {
    val scaleNotes = scale.steps.map { (key + it) % 12 }.toSet()
    val triads = chordQualities.filter { it.intervals.size == 3 && it.suffix in listOf("", "m", "dim", "aug") }
    return scale.steps.map { (key + it) % 12 }.distinct().flatMap { root ->
        triads.map { ChordSymbol(root, it) }.filter { chord -> chord.pitchClasses.all { it in scaleNotes } }
    }
}

