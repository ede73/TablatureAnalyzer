package org.tablatureanalyzer.app

/** Minimal, dependency-free reader for the original Power-Tab v4 binary container. */
internal object PowerTabV4Codec {
    private data class Guitar(val name: String, val tuning: List<Int>, val capo: Int, val midiProgram: Int)
    private data class Position(val offset: Int, val length: Int, val dots: Int, val notes: Map<Int, Int>)
    private data class Section(val beats: Int, val beatType: Int, val staffs: List<Pair<Int, List<Position>>>, val bars: List<Int>)
    private data class InstrumentBlock(val guitar: Guitar?, val sections: List<Section>)

    fun parse(bytes: ByteArray): TabDocument {
        val input = Reader(bytes)
        input.expectBytes("ptab".encodeToByteArray())
        val version = input.u16()
        require(version in 1..4) { "Unsupported Power-Tab version $version." }
        val classification = input.u8()
        require(classification == 0 || classification == 1) { "Unsupported Power-Tab document type." }
        if (classification == 0) {
            input.u8() // content type
            input.string(); input.string()
            when (input.u8()) {
                0 -> { input.u8(); input.string(); input.u16(); input.u8() }
                1 -> { input.string(); input.u8() }
                2 -> { input.string(); input.u16(); input.u16(); input.u16() }
                3 -> Unit
                else -> error("Unsupported Power-Tab release metadata.")
            }
            input.u8()
            repeat(9) { input.string() }
        } else {
            input.string(); input.string(); input.u16(); input.u8(); input.string(); input.string(); input.string()
        }

        val instrumentBlocks = mutableListOf<InstrumentBlock>()
        repeat(2) { instrumentIndex ->
            var guitar: Guitar? = null
            val sections = mutableListOf<Section>()
            input.items("CGuitar") {
                val index = input.u8()
                val name = input.string()
                val midiProgram = input.u8()
                repeat(6) { input.u8() } // mix, effects
                val capo = input.u8()
                val type = input.string()
                input.u8() // half-up tuning
                val stringCount = input.u8().coerceIn(1, 12)
                val tuning = List(stringCount) { input.u8() }
                if (index == 0 && guitar == null) {
                    guitar = Guitar(name.ifBlank { if (instrumentIndex == 0) "Guitar" else "Bass" }, tuning, capo, midiProgram.coerceIn(0, 127))
                }
            }
            input.items("CChordDiagram") {
                input.bytes(6)
                input.u8() // displayed fret span
                val count = input.u8().coerceAtMost(12)
                input.bytes(count)
            }
            input.items("CFloatingText") { input.string(); input.bytes(16 + 1); input.font() }
            input.items("CGuitarIn") { input.u8(); input.expect(0); input.u8(); input.u8(); input.u8(); input.u8() }
            input.items("CTempoMarker") { input.u8(); input.expect(0); input.u8(); input.u8(); input.expect(0); input.u16(); input.string() }
            input.items("CDynamic") { input.u16(); input.u8(); input.u8(); input.u16() }
            input.items("CSectionSymbol") { input.u16(); input.u8(); input.u32() }
            val parsedSections = input.items("CSection") {
                input.expect(0x32); input.bytes(11); input.u16(); input.bytes(2)
                input.u8() // ending/repeat flags
                input.u8() // position width
                input.bytes(5)
                input.u8() // key
                input.bytes(1)
                input.u16() // meter flags
                val beatInfo = input.u8()
                input.u8() // metronome pulses
                input.u8() // section letter
                input.string()
                input.items("CDirection") { input.bytes(1); val count = input.u8(); input.bytes(2 * count) }
                input.items("CChordText") { input.bytes(7) }
                input.items("CRhythmSlash") { input.bytes(6) }
                val staffs = input.items("CStaff") {
                    val properties = input.u8()
                    input.bytes(4)
                    val firstVoice = input.positions()
                    val nextHeader = input.peekU16()
                    val secondVoice = if (nextHeader and 0x8000 == 0) input.positions() else emptyList()
                    properties to (firstVoice + secondVoice)
                }
                val bars = input.items("CMusicBar") {
                    val offset = input.u8()
                    input.u8(); input.bytes(6); input.u8(); input.string()
                    offset
                }
                val beats = (beatInfo and 0x07).takeIf { it in 1..7 } ?: 4
                val beatType = (beatInfo ushr 3).takeIf { it in 1..32 } ?: 4
                Section(beats, beatType, staffs, bars)
            }
            sections += parsedSections
            instrumentBlocks += InstrumentBlock(guitar, sections)
        }
        repeat(3) { input.font() }
        input.u32(); input.u32(); input.u32()

        val parts = instrumentBlocks.mapIndexedNotNull { instrumentIndex, block ->
            val guitar = block.guitar
            val tuning = guitar?.let { notes ->
                // Power-Tab stores open strings high-to-low as MIDI pitches.
                notes.tuning.map { it + notes.capo }
            } ?: if (instrumentIndex == 0) listOf(64, 59, 55, 50, 45, 40) else listOf(43, 38, 33, 28)
            val usableSections = block.sections.filter { section ->
                section.staffs.any { (properties, positions) -> properties and 0xF0 == 0 && positions.isNotEmpty() }
            }
            val events = mutableListOf<TabEvent>()
            val signatures = mutableListOf<TabTimeSignatureChange>()
            for (section in usableSections) {
                val tabPositions = section.staffs.filter { (properties, _) -> properties and 0xF0 == 0 }.flatMap { it.second }
                val positionsByOffset = tabPositions.groupBy { it.offset }
                val offsets = (positionsByOffset.keys + section.bars).distinct().sorted()
                val sectionStart = events.size
                offsets.forEachIndexed { localIndex, offset ->
                    val frets = MutableList<Int?>(tuning.size) { null }
                    val lengths = MutableList<Int?>(tuning.size) { null }
                    var restLength: Int? = null
                    positionsByOffset[offset].orEmpty().forEach { position ->
                        if (position.dots and 0x04 != 0) restLength = powerTabLengthCode(position.length, position.dots)?.let { durationUnitsForLengthCode(it, 480) }
                        position.notes.forEach { (string, fret) ->
                            if (string in frets.indices) {
                                frets[string] = fret
                                lengths[string] = powerTabLengthCode(position.length, position.dots)
                            }
                        }
                    }
                    val barAfter = offset in section.bars
                    events += TabEvent(frets, barAfter = barAfter, lengthCodes = lengths, restDurationUnits = restLength)
                    if (localIndex == 0) signatures += TabTimeSignatureChange(sectionStart, section.beats, section.beatType)
                }
            }
            if (events.none { it.frets.any { fret -> fret != null } }) return@mapIndexedNotNull null
            TabPart(
                id = "powertab-instrument-${instrumentIndex + 1}",
                name = guitar?.name?.ifBlank { null } ?: if (instrumentIndex == 0) "Guitar" else "Bass",
                tuning = tuning,
                events = events,
                midiProgram = guitar?.midiProgram ?: if (instrumentIndex == 0) 24 else 32,
                divisions = 480,
                timeSignatures = signatures.ifEmpty { listOf(TabTimeSignatureChange(0, 4, 4)) }
            )
        }
        require(parts.isNotEmpty()) { "This Power-Tab file contains no tablature notes." }
        return TabDocument.fromParts(parts)
    }

    private fun powerTabLengthCode(length: Int, dots: Int): Int? {
        val base = when (length) {
            1 -> 1
            2 -> 3
            4 -> 5
            8 -> 7
            16 -> 9
            32 -> 11
            else -> return null
        }
        return (base + if (dots and 0x01 != 0) 1 else 0).takeIf { it in 1..12 }
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0
        private fun need(count: Int) { require(count >= 0 && position + count <= data.size) { "Truncated Power-Tab file at byte $position." } }
        fun u8(): Int { need(1); return data[position++].toInt() and 0xff }
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        fun peekU16(): Int { need(2); return (data[position].toInt() and 0xff) or ((data[position + 1].toInt() and 0xff) shl 8) }
        fun bytes(count: Int) { need(count); position += count }
        fun expect(value: Int) { val actual = u8(); require(actual == value) { "Invalid Power-Tab data at byte ${position - 1}." } }
        fun expectBytes(expected: ByteArray) { expected.forEach { expect(it.toInt() and 0xff) } }
        fun string(): String {
            val shortLength = u8()
            val length = if (shortLength == 0xff) u16() else shortLength
            need(length)
            val result = data.copyOfRange(position, position + length).decodeToString()
            position += length
            return result
        }
        fun rawString(length: Int): String {
            need(length)
            val result = data.copyOfRange(position, position + length).decodeToString()
            position += length
            return result
        }
        fun font() { string(); u32(); u32(); bytes(3 + 4) }

        fun positions(): List<Position> = items("CPosition") {
            val offset = u8()
            u16() // beaming/grouping
            val dots = u8()
            u8() // articulation
            u8() // fermata / triplet feel
            val length = u8()
            val additionalCount = u8()
            bytes(additionalCount * 4)
            val notes = items("CLineData") {
                val packed = u8()
                val properties = u8()
                u8() // octave transposition
                val bends = u8()
                bytes(bends * 4)
                (packed ushr 5) to (packed and 0x1f)
            }.toMap()
            Position(offset, length, dots, notes)
        }

        fun <T> items(expectedType: String, readItem: Reader.() -> T): List<T> {
            val recordOffset = position
            val count = u16()
            if (count == 0) return emptyList()
            val classIndex = u16()
            if (classIndex == 0xffff) {
                require(u16() == 1) { "Invalid Power-Tab record descriptor." }
                val type = rawString(u16())
                val aliasMatch = type == expectedType || (expectedType == "CSection" && type == "CSection2")
                require(aliasMatch) { "Unsupported Power-Tab record '$type' (expected $expectedType)." }
            } else {
                require(classIndex and 0x8000 != 0) {
                    "Invalid Power-Tab record reference for $expectedType at byte $recordOffset (0x${classIndex.toString(16)})."
                }
            }
            return List(count) { index ->
                val item = readItem()
                if (index != count - 1) {
                    val linkOffset = position
                    val next = u16()
                    require(next and 0x8000 != 0) {
                        "Invalid Power-Tab item link for $expectedType after item $index at byte $linkOffset (0x${next.toString(16)})."
                    }
                }
                item
            }
        }
    }
}
