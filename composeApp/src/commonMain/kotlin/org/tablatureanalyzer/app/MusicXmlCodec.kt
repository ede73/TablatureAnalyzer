package org.tablatureanalyzer.app

/** Small, dependency-free MusicXML 4.0 partwise interchange for this app's guitar-tab model. */
object MusicXmlCodec {
    private const val OUTPUT_DIVISIONS = 480
    private const val DEFAULT_DURATION = OUTPUT_DIVISIONS

    fun encode(document: TabDocument): String {
        val divisions = document.divisions.coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
        sb.append("<score-partwise version=\"4.0\">\n")
        sb.append("  <work><work-title>Tablature Analyzer</work-title></work>\n")
        sb.append("  <identification><encoding><software>Tablature Analyzer</software></encoding></identification>\n")
        sb.append("  <part-list><score-part id=\"P1\"><part-name>Guitar</part-name></score-part></part-list>\n")
        sb.append("  <part id=\"P1\">\n")

        val measures = mutableListOf<Pair<Int, List<Pair<Int, TabEvent>>>>()
        var pending = mutableListOf<Pair<Int, TabEvent>>()
        document.events.forEachIndexed { index, event ->
            pending += index to event
            if (event.barAfter) {
                measures += measures.size + 1 to pending
                pending = mutableListOf()
            }
        }
        if (pending.isNotEmpty() || measures.isEmpty()) measures += measures.size + 1 to pending

        val tempos = document.tempoChanges.associateBy { it.eventIndex }
        val signatures = document.timeSignatures.associateBy { it.eventIndex }
        var currentSignature = document.timeSignatures.minByOrNull { it.eventIndex } ?: TabTimeSignatureChange(0, 4, 4)
        var currentTempo = document.tempoChanges.minByOrNull { it.eventIndex }?.beatsPerMinute ?: 120

        measures.forEachIndexed { measureIndex, (measureNumber, rows) ->
            val firstEventIndex = rows.firstOrNull()?.first ?: 0
            sb.append("    <measure number=\"").append(measureNumber).append("\">\n")
            if (measureIndex == 0 || signatures.containsKey(firstEventIndex)) {
                signatures[firstEventIndex]?.let { currentSignature = it }
                sb.append("      <attributes>\n")
                sb.append("        <divisions>").append(divisions).append("</divisions>\n")
                sb.append("        <time><beats>").append(currentSignature.beats.coerceAtLeast(1))
                    .append("</beats><beat-type>").append(currentSignature.beatType.coerceAtLeast(1)).append("</beat-type></time>\n")
                sb.append("        <clef><sign>TAB</sign></clef>\n")
                if (measureIndex == 0) {
                    sb.append("        <staff-details><staff-type>alternate</staff-type><staff-lines>").append(document.tuning.size).append("</staff-lines>\n")
                    document.tuning.forEachIndexed { stringIndex, pitch ->
                        val (step, alter, octave) = pitchName(pitch)
                        val line = document.tuning.size - stringIndex
                        sb.append("          <staff-tuning line=\"").append(line).append("\"><tuning-step>")
                            .append(step).append("</tuning-step>")
                        if (alter != 0) sb.append("<tuning-alter>").append(alter).append("</tuning-alter>")
                        sb.append("<tuning-octave>").append(octave).append("</tuning-octave></staff-tuning>\n")
                    }
                    sb.append("        </staff-details>\n")
                }
                sb.append("      </attributes>\n")
            }

            rows.forEach { (eventIndex, event) ->
                signatures[eventIndex]?.let { signature ->
                    if (eventIndex != firstEventIndex) {
                        currentSignature = signature
                        sb.append("      <attributes><time><beats>").append(signature.beats.coerceAtLeast(1))
                            .append("</beats><beat-type>").append(signature.beatType.coerceAtLeast(1)).append("</beat-type></time></attributes>\n")
                    }
                }
                tempos[eventIndex]?.let { tempo ->
                    currentTempo = tempo.beatsPerMinute.coerceIn(1, 1000)
                    sb.append("      <direction placement=\"above\"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>")
                        .append(currentTempo).append("</per-minute></metronome></direction-type><sound tempo=\"")
                        .append(currentTempo).append("\"/></direction>\n")
                }

                val noteStrings = event.frets.indices.filter { event.frets[it] != null }
                if (noteStrings.isEmpty()) {
                    val restDuration = event.restDurationUnits ?: event.durationUnits.filterNotNull().maxOrNull() ?: DEFAULT_DURATION
                    appendRest(sb, restDuration.coerceAtLeast(1), divisions)
                } else {
                    val notes = noteStrings.map { string ->
                        val code = event.lengthCodes.getOrNull(string)
                        val duration = event.durationUnits.getOrNull(string) ?: code?.let(::legacyLengthUnits) ?: DEFAULT_DURATION
                        Triple(string, duration.coerceAtLeast(1), code)
                    }.sortedWith(compareByDescending<Triple<Int, Int, Int?>> { it.second }.thenBy { it.first })
                    notes.forEachIndexed { noteIndex, (string, duration, lengthCode) ->
                        val fret = event.frets[string] ?: return@forEachIndexed
                        val midiPitch = document.tuning[string] + fret
                        appendNote(sb, document, event, string, midiPitch, duration, divisions, noteIndex > 0, lengthCode)
                    }
                }
            }
            if (rows.lastOrNull()?.second?.barAfter == true) {
                sb.append("      <barline location=\"right\"><bar-style>regular</bar-style></barline>\n")
            }
            sb.append("    </measure>\n")
        }
        sb.append("  </part>\n</score-partwise>\n")
        return sb.toString()
    }

    fun parse(source: String): TabDocument {
        val root = XmlTreeParser(source).parse()
        require(root.localName() == "score-partwise") { "Expected a MusicXML score-partwise document" }
        val part = root.children("part").firstOrNull() ?: error("MusicXML document has no part")
        var divisions = OUTPUT_DIVISIONS
        var sourceTime = TabTimeSignatureChange(0, 4, 4)
        var tuning = listOf(64, 59, 55, 50, 45, 40)
        var runningUnits = 0
        val events = mutableListOf<TabEvent>()
        val tempos = mutableListOf<TabTempoChange>()
        val signatures = mutableListOf<TabTimeSignatureChange>()

        part.children("measure").forEach { measure ->
            measure.child("attributes")?.let { attributes ->
                attributes.child("divisions")?.intText()?.takeIf { it > 0 }?.let { divisions = it }
                attributes.child("time")?.let { time ->
                    val beats = time.child("beats")?.intText()?.coerceAtLeast(1) ?: sourceTime.beats
                    val beatType = time.child("beat-type")?.intText()?.coerceAtLeast(1) ?: sourceTime.beatType
                    sourceTime = TabTimeSignatureChange(events.size, beats, beatType)
                    signatures += sourceTime
                }
                attributes.child("staff-details")?.let { details ->
                    val count = details.child("staff-lines")?.intText()?.takeIf { it in 1..12 } ?: tuning.size
                    val parsed = MutableList(count) { tuning.getOrElse(it) { 0 } }
                    details.children("staff-tuning").forEach { staffTuning ->
                        val line = staffTuning.attributes["line"]?.toIntOrNull() ?: return@forEach
                        val appString = count - line
                        if (appString in parsed.indices) {
                            val step = staffTuning.child("tuning-step")?.textValue()?.uppercase() ?: return@forEach
                            val alter = staffTuning.child("tuning-alter")?.intText() ?: 0
                            val octave = staffTuning.child("tuning-octave")?.intText() ?: 4
                            parsed[appString] = pitchValue(step, alter, octave)
                        }
                    }
                    if (parsed.any { it != 0 }) tuning = parsed
                }
            }

            val measureStart = runningUnits
            var cursor = 0
            var lastOnset = 0
            val drafts = mutableMapOf<Int, MutableEvent>()
            for (child in measure.children) {
                when (child.localName()) {
                    "direction" -> child.descendants("sound").firstOrNull()?.attributes?.get("tempo")?.toDoubleOrNull()?.let { bpm ->
                        tempos += TabTempoChange(events.size + drafts.size, bpm.toInt().coerceIn(1, 1000))
                    }
                    "note" -> {
                        val durationSource = child.child("duration")?.intText()?.coerceAtLeast(1) ?: divisions
                        val durationUnits = (durationSource.toLong() * OUTPUT_DIVISIONS / divisions).toInt().coerceAtLeast(1)
                        val isChordTone = child.child("chord") != null
                        val onset = if (isChordTone) lastOnset else cursor.also { lastOnset = it }
                        if (!isChordTone) cursor += durationSource
                        val absoluteOnset = measureStart + (onset.toLong() * OUTPUT_DIVISIONS / divisions).toInt()
                        val draft = drafts.getOrPut(absoluteOnset) { MutableEvent(tuning.size) }
                        if (child.child("rest") != null) {
                            draft.restDuration = maxOf(draft.restDuration ?: 0, durationUnits)
                        } else {
                            val midiPitch = child.child("pitch")?.let(::midiPitch)
                            val technical = child.descendants("technical").firstOrNull()
                            val stringFromXml = technical?.child("string")?.intText()?.minus(1)
                            val fretFromXml = technical?.child("fret")?.intText()
                            val string = stringFromXml?.takeIf { it in tuning.indices }
                                ?: midiPitch?.let { pitch -> tuning.indices.firstOrNull { index -> pitch - tuning[index] in 0..36 && draft.frets[index] == null } }
                            if (string != null) {
                                val fret = fretFromXml?.takeIf { it in 0..36 }
                                    ?: midiPitch?.minus(tuning[string])?.takeIf { it in 0..36 }
                                if (fret != null) {
                                    draft.frets[string] = fret
                                    draft.actions[string] = readAction(child)
                                    draft.durationUnits[string] = durationUnits
                                    draft.lengthCodes[string] = codeForDuration(durationUnits)
                                }
                            }
                        }
                    }
                    "forward" -> child.child("duration")?.intText()?.let { cursor += it }
                    "backup" -> child.child("duration")?.intText()?.let { cursor = (cursor - it).coerceAtLeast(0) }
                }
            }

            val expectedMeasure = (sourceTime.beats.toLong() * 4L * divisions / sourceTime.beatType.coerceAtLeast(1)).toInt()
            val measureUnits = (maxOf(cursor, expectedMeasure).toLong() * OUTPUT_DIVISIONS / divisions).toInt()
            val orderedDrafts = drafts.entries.sortedBy { it.key }
            val firstNewEvent = events.size
            orderedDrafts.forEach { (_, draft) ->
                events += TabEvent(draft.frets, draft.actions, false, draft.lengthCodes, draft.durationUnits, draft.restDuration)
            }
            if (events.size > firstNewEvent) events[events.lastIndex] = events.last().copy(barAfter = true)
            runningUnits += measureUnits
        }

        val normalizedSignatures = signatures.distinctBy { it.eventIndex }.ifEmpty { listOf(TabTimeSignatureChange(0, 4, 4)) }
        val normalizedTempos = tempos.distinctBy { it.eventIndex }.ifEmpty { listOf(TabTempoChange(0, 120)) }
        return TabDocument(tuning, events, OUTPUT_DIVISIONS, normalizedTempos, normalizedSignatures)
    }

    private data class MutableEvent(val frets: MutableList<Int?>, val actions: MutableList<Char?> = MutableList(frets.size) { null }, val lengthCodes: MutableList<Int?> = MutableList(frets.size) { null }, val durationUnits: MutableList<Int?> = MutableList(frets.size) { null }, var restDuration: Int? = null) {
        constructor(strings: Int) : this(MutableList(strings) { null })
    }

    private fun appendNote(sb: StringBuilder, document: TabDocument, event: TabEvent, string: Int, midi: Int, duration: Int, divisions: Int, chord: Boolean, lengthCode: Int?) {
        val (step, alter, octave) = pitchName(midi)
        sb.append("      <note>")
        if (chord) sb.append("<chord/>")
        sb.append("<pitch><step>").append(step).append("</step>")
        if (alter != 0) sb.append("<alter>").append(alter).append("</alter>")
        sb.append("<octave>").append(octave).append("</octave></pitch>")
        val (noteType, dotted) = notationForDuration(duration, divisions)
        sb.append("<duration>").append(duration).append("</duration><voice>1</voice><type>")
            .append(noteType).append("</type>")
        if (dotted) sb.append("<dot/>")
        sb.append("<staff>1</staff><notations><technical>")
            .append("<string>").append(string + 1).append("</string>")
            .append("<fret>").append(event.frets[string] ?: 0).append("</fret>")
        event.actions.getOrNull(string)?.let { action ->
            sb.append("<other-technical type=\"tabanalyzer-action\">").append(xmlEscape(action.toString())).append("</other-technical>")
            when (action.lowercaseChar()) {
                'h' -> sb.append("<hammer-on type=\"start\">H</hammer-on>")
                'p' -> sb.append("<pull-off type=\"start\">P</pull-off>")
            }
        }
        lengthCode?.let { sb.append("<other-technical type=\"tabanalyzer-length-code\">").append(it).append("</other-technical>") }
        sb.append("</technical>")
        if (event.actions.getOrNull(string)?.lowercaseChar() == 'v') sb.append("<ornaments><wavy-line type=\"start\" number=\"1\"/></ornaments>")
        sb.append("</notations></note>\n")
    }

    private fun appendRest(sb: StringBuilder, duration: Int, divisions: Int) {
        val (noteType, dotted) = notationForDuration(duration, divisions)
        sb.append("      <note><rest/><duration>").append(duration).append("</duration><voice>1</voice><type>")
            .append(noteType).append("</type>")
        if (dotted) sb.append("<dot/>")
        sb.append("<staff>1</staff></note>\n")
    }

    private fun legacyLengthUnits(code: Int): Int = when (code) {
        1 -> OUTPUT_DIVISIONS * 4
        2 -> OUTPUT_DIVISIONS * 6
        3 -> OUTPUT_DIVISIONS * 2
        4 -> OUTPUT_DIVISIONS * 3
        5 -> OUTPUT_DIVISIONS
        6 -> OUTPUT_DIVISIONS * 3 / 2
        7 -> OUTPUT_DIVISIONS / 2
        8 -> OUTPUT_DIVISIONS * 3 / 4
        9 -> OUTPUT_DIVISIONS / 4
        10 -> OUTPUT_DIVISIONS * 3 / 8
        11 -> OUTPUT_DIVISIONS / 8
        12 -> OUTPUT_DIVISIONS * 3 / 16
        else -> DEFAULT_DURATION
    }

    private fun codeForDuration(duration: Int): Int? = (1..12).firstOrNull { legacyLengthUnits(it) == duration }

    private fun notationForDuration(duration: Int, divisions: Int): Pair<String, Boolean> {
        val choices = listOf(
            "whole" to divisions * 4, "half" to divisions * 2, "quarter" to divisions,
            "eighth" to divisions / 2, "16th" to divisions / 4, "32nd" to divisions / 8,
            "64th" to divisions / 16
        ).filter { it.second > 0 }
        choices.firstOrNull { it.second == duration }?.let { return it.first to false }
        choices.firstOrNull { it.second * 3 / 2 == duration }?.let { return it.first to true }
        return (choices.minByOrNull { kotlin.math.abs(it.second - duration) }?.first ?: "quarter") to false
    }

    private fun pitchName(midi: Int): Triple<String, Int, Int> {
        val names = listOf("C", "C", "D", "D", "E", "F", "F", "G", "G", "A", "A", "B")
        val alters = listOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        val pitchClass = ((midi % 12) + 12) % 12
        return Triple(names[pitchClass], alters[pitchClass], midi / 12 - 1)
    }

    private fun pitchValue(step: String, alter: Int, octave: Int): Int {
        val pitchClass = when (step) { "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9; "B" -> 11; else -> 0 }
        return (octave + 1) * 12 + pitchClass + alter
    }

    private fun midiPitch(pitch: XmlNode): Int? {
        val step = pitch.child("step")?.textValue()?.uppercase() ?: return null
        val octave = pitch.child("octave")?.intText() ?: return null
        val alter = pitch.child("alter")?.intText() ?: 0
        return pitchValue(step, alter, octave)
    }

    private fun readAction(note: XmlNode): Char? {
        val technical = note.descendants("technical").firstOrNull() ?: return null
        return technical.children("other-technical").firstOrNull { it.attributes["type"] == "tabanalyzer-action" }
            ?.textValue()?.firstOrNull()
            ?: when {
                technical.child("hammer-on") != null -> 'h'
                technical.child("pull-off") != null -> 'p'
                note.descendants("wavy-line").isNotEmpty() -> 'v'
                else -> null
            }
    }

    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}

private data class XmlNode(val name: String, val attributes: Map<String, String>, val children: MutableList<XmlNode> = mutableListOf(), val text: StringBuilder = StringBuilder()) {
    fun localName(): String = name.substringAfterLast(':')
    fun child(name: String): XmlNode? = children.firstOrNull { it.localName() == name }
    fun children(name: String): List<XmlNode> = children.filter { it.localName() == name }
    fun descendants(name: String): List<XmlNode> = buildList {
        children.forEach { child ->
            if (child.localName() == name) add(child)
            addAll(child.descendants(name))
        }
    }
    fun textValue(): String = text.toString().trim()
    fun intText(): Int? = textValue().toIntOrNull()
}

/** A small XML tree reader for MusicXML input; it resolves entities and never loads external DTDs. */
private class XmlTreeParser(private val source: String) {
    private var cursor = 0

    fun parse(): XmlNode {
        skipMisc()
        val root = readElement()
        skipMisc()
        require(cursor == source.length) { "Unexpected text after XML document" }
        return root
    }

    private fun readElement(): XmlNode {
        require(consume('<') && !peek('/') && !peek('!') && !peek('?')) { "Expected XML element at character $cursor" }
        val name = readName()
        val attributes = linkedMapOf<String, String>()
        while (true) {
            skipWhitespace()
            when {
                startsWith("/>") -> { cursor += 2; return XmlNode(name, attributes) }
                peek('>') -> { cursor++; break }
                else -> {
                    val key = readName()
                    skipWhitespace(); require(consume('=')) { "Expected '=' after XML attribute" }; skipWhitespace()
                    val quote = source.getOrNull(cursor) ?: error("Missing XML attribute value")
                    require(quote == '\'' || quote == '"') { "Expected quoted XML attribute" }
                    cursor++
                    val start = cursor
                    while (cursor < source.length && source[cursor] != quote) cursor++
                    require(cursor < source.length) { "Unclosed XML attribute" }
                    attributes[key.substringAfterLast(':')] = decodeEntities(source.substring(start, cursor))
                    cursor++
                }
            }
        }
        val node = XmlNode(name, attributes)
        while (cursor < source.length) {
            when {
                startsWith("</") -> {
                    cursor += 2
                    val closing = readName()
                    skipWhitespace(); require(consume('>')) { "Malformed XML closing tag" }
                    require(closing == name) { "Mismatched XML tag: expected $name, found $closing" }
                    return node
                }
                startsWith("<!--") -> skipUntil("-->")
                startsWith("<?") -> skipUntil("?>")
                startsWith("<![CDATA[") -> {
                    cursor += 9
                    val end = source.indexOf("]]>", cursor)
                    require(end >= 0) { "Unclosed XML CDATA" }
                    node.text.append(source.substring(cursor, end))
                    cursor = end + 3
                }
                startsWith("<!") -> skipDeclaration()
                peek('<') -> node.children += readElement()
                else -> {
                    val start = cursor
                    while (cursor < source.length && source[cursor] != '<') cursor++
                    node.text.append(decodeEntities(source.substring(start, cursor)))
                }
            }
        }
        error("Unclosed XML element <$name>")
    }

    private fun skipMisc() {
        while (true) {
            skipWhitespace()
            when {
                startsWith("<?") -> skipUntil("?>")
                startsWith("<!--") -> skipUntil("-->")
                startsWith("<!DOCTYPE") || startsWith("<!doctype") -> skipDeclaration()
                else -> return
            }
        }
    }

    private fun skipUntil(end: String) {
        val index = source.indexOf(end, cursor)
        require(index >= 0) { "Unclosed XML declaration" }
        cursor = index + end.length
    }

    private fun skipDeclaration() {
        var bracketDepth = 0
        var quote: Char? = null
        while (cursor < source.length) {
            val char = source[cursor++]
            if (quote != null) {
                if (char == quote) quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '>' -> if (bracketDepth == 0) return
            }
        }
        error("Unclosed XML declaration")
    }

    private fun readName(): String {
        val start = cursor
        while (cursor < source.length && (source[cursor].isLetterOrDigit() || source[cursor] in "_:.-")) cursor++
        require(cursor > start) { "Expected XML name at character $cursor" }
        return source.substring(start, cursor)
    }

    private fun decodeEntities(value: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            if (value[index] != '&') { result.append(value[index++]); continue }
            val end = value.indexOf(';', index + 1)
            if (end < 0) { result.append(value[index++]); continue }
            val entity = value.substring(index + 1, end)
            val decoded = when (entity) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> if (entity.startsWith("#x")) entity.substring(2).toIntOrNull(16)?.let { it.toChar().toString() }
                    else if (entity.startsWith("#")) entity.substring(1).toIntOrNull()?.let { it.toChar().toString() }
                    else null
            }
            if (decoded == null) result.append(value, index, end + 1) else result.append(decoded)
            index = end + 1
        }
        return result.toString()
    }

    private fun skipWhitespace() { while (cursor < source.length && source[cursor].isWhitespace()) cursor++ }
    private fun consume(char: Char): Boolean = if (peek(char)) { cursor++; true } else false
    private fun peek(char: Char): Boolean = source.getOrNull(cursor) == char
    private fun startsWith(value: String): Boolean = source.startsWith(value, cursor)
}
