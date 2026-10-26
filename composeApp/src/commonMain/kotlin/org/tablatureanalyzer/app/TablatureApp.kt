package org.tablatureanalyzer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val ink = Color(0xFF18211F)
private val green = Color(0xFF236957)
private val paper = Color(0xFFF5F4EF)

@Composable
fun TablatureApp(initialDocument: TabDocument? = null) {
    var document by remember { mutableStateOf(initialDocument ?: TabDocument(listOf(64, 59, 55, 50, 45, 40), emptyList())) }
    var selectedEvent by remember { mutableIntStateOf(-1) }
    var selectedString by remember { mutableIntStateOf(-1) }
    var page by remember { mutableIntStateOf(0) }
    var defaultNaming by remember {
        mutableStateOf(ChordNamingSystem.values().firstOrNull { it.name == PlatformPreferences.get("defaultNaming") } ?: ChordNamingSystem.US)
    }
    var tabNamingOverride by remember {
        mutableStateOf(ChordNamingSystem.values().firstOrNull { it.name == PlatformPreferences.get("tabNamingOverride") })
    }
    var chordNamingOverride by remember {
        mutableStateOf(ChordNamingSystem.values().firstOrNull { it.name == PlatformPreferences.get("chordNamingOverride") })
    }
    val tabNaming = tabNamingOverride ?: defaultNaming
    val chordNaming = chordNamingOverride ?: defaultNaming
    LaunchedEffect(defaultNaming, tabNamingOverride, chordNamingOverride) {
        PlatformPreferences.put("defaultNaming", defaultNaming.name)
        PlatformPreferences.put("tabNamingOverride", tabNamingOverride?.name ?: "DEFAULT")
        PlatformPreferences.put("chordNamingOverride", chordNamingOverride?.name ?: "DEFAULT")
    }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var playingEventIndex by remember { mutableIntStateOf(-1) }
    val chordDatabase = ChordDatabase.BuiltIn
    val chordDatabaseLoaded = true
    MaterialTheme(colorScheme = lightColorScheme(primary = green, background = paper, surface = paper)) {
        Column(Modifier.fillMaxSize().background(paper).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TabRow(selectedTabIndex = page, modifier = Modifier.weight(1f)) {
                    listOf("Tab editor", "Scales", "Chords").forEachIndexed { i, label ->
                        Tab(selected = page == i, onClick = { page = i }, text = { Text(label, maxLines = 1) })
                    }
                }
                NamingSelector("Default", defaultNaming, allowDefault = false) { defaultNaming = it ?: ChordNamingSystem.US }
            }
            if (page == 0) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlatformOpenTabButton(onText = { text -> document = TabCodec.parse(text); selectedEvent = -1; selectedString = -1 })
                    OutlinedButton(onClick = { showImport = true }) { Text("Open / paste tab") }
                    PlatformSaveMusicXmlButton(MusicXmlCodec.encode(document))
                    PlatformMidiPlayerButton(document) { eventIndex -> playingEventIndex = eventIndex ?: -1 }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (page == 0) {
                EditorPage(document, selectedEvent, selectedString, playingEventIndex, tabNaming, tabNamingOverride, { tabNamingOverride = it }, onSelect = { e, s -> selectedEvent = e; selectedString = s }, onMove = { target ->
                    document = moveNote(document, selectedEvent, selectedString, target)
                    selectedString = target
                }, onMoveDirection = { selection, direction ->
                    val notesToMove = selection.ifEmpty {
                        if (selectedEvent >= 0 && selectedString >= 0) setOf(NotePosition(selectedEvent, selectedString)) else emptySet()
                    }
                    val result = moveNotesInDirection(document, notesToMove, direction)
                    if (result.applied) {
                        document = result.document
                        result.movedSelection.singleOrNull()?.let { moved ->
                            selectedEvent = moved.eventIndex
                            selectedString = moved.stringIndex
                        }
                    }
                    if (result.applied) result.movedSelection else selection
                }, onAdd = { event, string, fret, lengthCode ->
                    document = addNoteAt(document, event, string, fret, lengthCode)
                    selectedEvent = event.coerceIn(0, document.events.lastIndex.coerceAtLeast(0))
                    selectedString = string
                }, onAddPosition = {
                    document = addTabPosition(document)
                    selectedEvent = document.events.lastIndex
                    selectedString = -1
                }, onSetLength = { code ->
                    document = setNoteLengthAt(document, selectedEvent, selectedString, code)
                }, onRemoveBar = { eventIndex ->
                    document = removeMeasureBar(document, eventIndex)
                }, onAddBar = { eventIndex ->
                    document = addMeasureBar(document, eventIndex)
                }, onMoveBar = { fromEvent, toEvent ->
                    val moved = moveMeasureBar(document, fromEvent, toEvent)
                    val didMove = moved != document
                    document = moved
                    didMove
                }, onSetTempo = { bpm ->
                    document = document.copy(tempoChanges = listOf(TabTempoChange(0, bpm)) + document.tempoChanges.filter { it.eventIndex > 0 })
                }, onDelete = {
                    document = deleteNoteAt(document, selectedEvent, selectedString)
                    selectedEvent = selectedEvent.coerceAtMost(document.events.lastIndex)
                    selectedString = -1
                }, onGroupTransform = { request ->
                    val result = applyGroupFretOperation(document, request)
                    if (result.applied) {
                        document = result.document
                        selectedEvent = -1
                        selectedString = -1
                    }
                    result
                })
            } else if (page == 1) {
                ScalePage(document.tuning, chordDatabase, chordDatabaseLoaded, defaultNaming)
            } else {
                ChordPage(document.tuning, chordDatabase, chordDatabaseLoaded, chordNaming, chordNamingOverride, { chordNamingOverride = it })
            }
        }
        if (showImport) {
            AlertDialog(onDismissRequest = { showImport = false }, title = { Text("Open MusicXML, tab, or project") },
                text = {
                    Column {
                        Text("Paste a MusicXML score, ASCII .tab file, or TabAnalyzer .internal project. MusicXML imports guitar string/fret data, timing, measures and supported techniques.", fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        BasicTextField(value = importText, onValueChange = { importText = it }, modifier = Modifier.fillMaxWidth().height(210.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp).verticalScroll(rememberScrollState()), textStyle = LocalTextStyle.current.copy(color = ink, fontSize = 13.sp))
                    }
                }, confirmButton = { Button(onClick = {
                    val parsed = TabCodec.parse(importText)
                    document = parsed
                    selectedEvent = -1; selectedString = -1
                    showImport = false
                }) { Text("Load") } }, dismissButton = { TextButton(onClick = { showImport = false }) { Text("Cancel") } })
        }
    }
}

@Composable
private fun NamingSelector(
    label: String,
    selected: ChordNamingSystem?,
    allowDefault: Boolean = true,
    inherited: ChordNamingSystem = ChordNamingSystem.US,
    onSelect: (ChordNamingSystem?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = selected ?: inherited
    Box {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
            Text("$label: ${shown.shortLabel} ▾", fontSize = 11.sp, maxLines = 1)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (allowDefault) DropdownMenuItem(text = { Text("Use default (${inherited.label})") }, onClick = { onSelect(null); expanded = false })
            ChordNamingSystem.values().forEach { system ->
                DropdownMenuItem(text = { Text(system.label) }, onClick = { onSelect(system); expanded = false })
            }
        }
    }
}

@Composable
private fun EditorPage(document: TabDocument, selectedEvent: Int, selectedString: Int, playingEventIndex: Int, naming: ChordNamingSystem, namingOverride: ChordNamingSystem?, onNamingOverride: (ChordNamingSystem?) -> Unit, onSelect: (Int, Int) -> Unit, onMove: (Int) -> Unit, onMoveDirection: (Set<NotePosition>, Int) -> Set<NotePosition>, onAdd: (Int, Int, Int, Int) -> Unit, onAddPosition: () -> Unit, onSetLength: (Int) -> Unit, onRemoveBar: (Int) -> Unit, onAddBar: (Int) -> Unit, onMoveBar: (Int, Int) -> Boolean, onSetTempo: (Int) -> Unit, onDelete: () -> Unit, onGroupTransform: (GroupFretRequest) -> GroupFretResult) {
    val textMeasurer = rememberTextMeasurer()
    var dragTargetString by remember { mutableIntStateOf(-1) }
    var minimumFret by remember { mutableStateOf("0") }
    var maximumFret by remember { mutableStateOf("12") }
    var transposeSemitones by remember { mutableStateOf("0") }
    var groupTransformMessage by remember { mutableStateOf("") }
    var selectedNotes by remember { mutableStateOf(emptySet<NotePosition>()) }
    var selectionRect by remember { mutableStateOf<Rect?>(null) }
    var selectedBarEvent by remember { mutableIntStateOf(-1) }
    var addBarMode by remember { mutableStateOf(false) }
    var tempoInput by remember(document.tempoChanges) { mutableStateOf((document.tempoChanges.firstOrNull { it.eventIndex == 0 }?.beatsPerMinute ?: 120).toString()) }
    var addNoteMode by remember { mutableStateOf(false) }
    var fretToAdd by remember { mutableStateOf("0") }
    var selectedLengthCode by remember { mutableIntStateOf(5) }
    var showLengthMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TAB VIEW", fontSize = 11.sp, color = green, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        if (document.events.isEmpty()) Text("Open a tab file to start", fontSize = 19.sp, color = ink, fontWeight = FontWeight.SemiBold)
                    }
                    NamingSelector("Tab names", namingOverride, inherited = naming, onSelect = onNamingOverride)
                    Text("Tap a note, then choose its new string", fontSize = 12.sp, color = Color.Gray)
                }
                Spacer(Modifier.height(12.dp))
                if (document.events.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(220.dp).background(paper, RoundedCornerShape(12.dp)), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("—  —  —  —  —  —", color = green, fontSize = 22.sp)
                            Spacer(Modifier.height(8.dp)); Text("Open or paste a .tab file to begin", color = ink)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { onAdd(0, 0, fretToAdd.toIntOrNull()?.coerceIn(0, 36) ?: 0, selectedLengthCode) }) { Text("Add first note") }
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val density = LocalDensity.current
                        val colGapPx = with(density) { 62.dp.toPx() }
                        val leftPx = with(density) { 38.dp.toPx() }
                        val columnsPerSystem = ((constraints.maxWidth.toFloat() - leftPx - with(density) { 8.dp.toPx() }) / colGapPx).toInt().coerceAtLeast(1)
                        val systems = (document.events.size + columnsPerSystem - 1) / columnsPerSystem
                        val systemHeight = maxOf(206.dp, 52.dp + 32.dp * (document.tuning.size - 1))
                        Canvas(Modifier.fillMaxWidth().height(systemHeight * systems).pointerInput(document, columnsPerSystem, addNoteMode, fretToAdd, selectedLengthCode) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val system = (down.position.y / systemHeight.toPx()).toInt().coerceIn(0, systems - 1)
                                    val localColumn = ((down.position.x - leftPx) / colGapPx).roundToInt() - 1
                                    val startEvent = (system * columnsPerSystem + localColumn).coerceIn(0, document.events.lastIndex)
                                    val localY = down.position.y - system * systemHeight.toPx()
                                    val startString = ((localY - 26.dp.toPx()) / (32.dp.toPx())).roundToInt().coerceIn(0, document.tuning.lastIndex)
                                    if (addBarMode) {
                                        onAddBar(startEvent)
                                        selectedBarEvent = startEvent
                                        selectedNotes = emptySet()
                                        onSelect(-1, -1)
                                        addBarMode = false
                                        return@awaitEachGesture
                                    }
                                    if (addNoteMode) {
                                        onAdd(startEvent, startString, fretToAdd.toIntOrNull()?.coerceIn(0, 36) ?: 0, selectedLengthCode)
                                        selectedNotes = emptySet()
                                        selectedBarEvent = -1
                                        addNoteMode = false
                                        return@awaitEachGesture
                                    }
                                    val clickedBar = document.events.indices.firstOrNull { eventIndex ->
                                        val event = document.events[eventIndex]
                                        if (!event.barAfter) false else {
                                            val eventSystem = eventIndex / columnsPerSystem
                                            val local = eventIndex % columnsPerSystem
                                            val barX = leftPx + colGapPx * (local + 1) + colGapPx / 2
                                            val localClickY = down.position.y - eventSystem * systemHeight.toPx()
                                            val lineBottom = minOf(214.dp.toPx(), systemHeight.toPx() - 4.dp.toPx())
                                            kotlin.math.abs(down.position.x - barX) <= 12.dp.toPx() && localClickY in 8.dp.toPx()..lineBottom
                                        }
                                    }
                                    if (clickedBar != null) {
                                        selectedBarEvent = clickedBar
                                        selectedNotes = emptySet()
                                        onSelect(-1, -1)
                                        var destinationEvent = clickedBar
                                        val pastSlop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                        if (pastSlop != null) {
                                            drag(down.id) { change ->
                                                val targetSystem = (change.position.y / systemHeight.toPx()).toInt().coerceIn(0, systems - 1)
                                                val targetLocal = ((change.position.x - leftPx) / colGapPx - 1.5f).roundToInt().coerceIn(0, columnsPerSystem - 1)
                                                destinationEvent = (targetSystem * columnsPerSystem + targetLocal).coerceIn(0, document.events.lastIndex)
                                                change.consume()
                                            }
                                            if (onMoveBar(clickedBar, destinationEvent)) selectedBarEvent = destinationEvent
                                        }
                                        return@awaitEachGesture
                                    }
                                    val hasNote = document.events[startEvent].frets.getOrNull(startString) != null
                                    var draggingNote = false
                                    var selectingRectangle = false
                                    var rectangleEnd = down.position
                                    val pastSlop = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                        if (hasNote) {
                                            draggingNote = true
                                            onSelect(startEvent, startString)
                                            dragTargetString = startString
                                        } else {
                                            selectingRectangle = true
                                            selectionRect = Rect(
                                                minOf(down.position.x, change.position.x), minOf(down.position.y, change.position.y),
                                                maxOf(down.position.x, change.position.x), maxOf(down.position.y, change.position.y)
                                            )
                                        }
                                        change.consume()
                                    }
                                    if (pastSlop == null) {
                                        if (hasNote && !draggingNote) {
                                            selectedBarEvent = -1
                                            onSelect(startEvent, startString)
                                            selectedNotes = setOf(NotePosition(startEvent, startString))
                                        } else {
                                            selectedBarEvent = -1
                                            onSelect(-1, -1)
                                            selectedNotes = emptySet()
                                        }
                                    } else if (draggingNote) {
                                        drag(down.id) { change ->
                                            val targetSystem = (change.position.y / systemHeight.toPx()).toInt().coerceIn(0, systems - 1)
                                            val targetLocalY = change.position.y - targetSystem * systemHeight.toPx()
                                            val target = ((targetLocalY - 26.dp.toPx()) / 32.dp.toPx()).roundToInt().coerceIn(0, document.tuning.lastIndex)
                                            dragTargetString = target
                                            change.consume()
                                        }
                                        val target = dragTargetString
                                        if (target in document.tuning.indices && target != startString) {
                                            onMove(target)
                                            selectedNotes = setOf(NotePosition(startEvent, target))
                                        }
                                        dragTargetString = -1
                                    } else if (selectingRectangle) {
                                        drag(down.id) { change ->
                                            rectangleEnd = change.position
                                            selectionRect = Rect(
                                                minOf(down.position.x, rectangleEnd.x), minOf(down.position.y, rectangleEnd.y),
                                                maxOf(down.position.x, rectangleEnd.x), maxOf(down.position.y, rectangleEnd.y)
                                            )
                                            change.consume()
                                        }
                                        val rect = selectionRect
                                        if (rect != null) {
                                            val added = document.events.flatMapIndexed { eventIndex, event ->
                                                val eventSystem = eventIndex / columnsPerSystem
                                                val local = eventIndex % columnsPerSystem
                                                val x = leftPx + colGapPx * (local + 1)
                                                event.frets.mapIndexedNotNull { stringIndex, fret ->
                                                    if (fret == null) null else {
                                                        val y = eventSystem * systemHeight.toPx() + 26.dp.toPx() + 32.dp.toPx() * stringIndex
                                                        NotePosition(eventIndex, stringIndex).takeIf { x in rect.left..rect.right && y in rect.top..rect.bottom }
                                                    }
                                                }
                                            }.toSet()
                                            selectedNotes = selectedNotes + added
                                        }
                                        selectionRect = null
                                    }
                                }
                            }) {
                            val systemHeightPx = systemHeight.toPx()
                            val rowGap = 32.dp.toPx()
                            val left = leftPx
                            repeat(systems) { system ->
                                val top = system * systemHeightPx
                                if (system > 0) drawLine(Color(0x33808080), Offset(0f, top), Offset(size.width, top), 1f)
                                for (s in document.tuning.indices) {
                                    val y = top + 26.dp.toPx() + rowGap * s
                                    drawLine(Color(0xFF76827E), Offset(left, y), Offset(size.width - 8.dp.toPx(), y), strokeWidth = 1.4f)
                                    drawText(textMeasurer, listOf("e", noteName(11, naming), "G", "D", "A", "E").getOrElse(s) { "S${s + 1}" }, Offset(5f, y - 8.dp.toPx()), style = TextStyle(color = ink, fontSize = 12.sp))
                                }
                            }
                            document.events.forEachIndexed { i, event ->
                                val system = i / columnsPerSystem
                                val local = i % columnsPerSystem
                                val top = system * systemHeightPx
                                val x = left + colGapPx * (local + 1)
                                if (event.barAfter) {
                                    val barColor = if (i == selectedBarEvent) Color(0xFFD98C35) else green
                                    val barBottom = minOf(214.dp.toPx(), systemHeightPx - 4.dp.toPx())
                                    drawLine(barColor, Offset(x + colGapPx / 2, top + 8.dp.toPx()), Offset(x + colGapPx / 2, top + barBottom), if (i == selectedBarEvent) 3.5f else 2f)
                                }
                                event.frets.forEachIndexed { s, fret ->
                                    if (fret != null) {
                                        val y = top + 26.dp.toPx() + rowGap * s
                                        val selected = NotePosition(i, s) in selectedNotes
                                        val chosen = i == selectedEvent && s == selectedString
                                        val dropTarget = i == selectedEvent && s == dragTargetString && dragTargetString != selectedString
                                        val action = event.actions.getOrNull(s)
                                        // The notehead circle already delimits the fret; do not add notation parentheses.
                                        val noteLabel = fret.toString()
                                        val radius = 14.dp.toPx()
                                        val noteFill = when { dropTarget -> Color(0xFFDDE9E4); chosen -> green; selected -> Color(0xFFFFE6B8); else -> Color.White }
                                        val noteOutline = when { dropTarget || chosen -> green; selected -> Color(0xFFB36A16); else -> ink }
                                        drawCircle(noteFill, radius = radius, center = Offset(x, y))
                                        drawCircle(noteOutline, radius = radius, center = Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (selected) 2.5f else 1.5f))
                                        if (playingEventIndex == i) {
                                            drawCircle(Color(0xFFE38B32), radius = 4.dp.toPx(), center = Offset(x - radius - 5.dp.toPx(), y))
                                        }
                                        drawText(textMeasurer, noteLabel, Offset(x - if (noteLabel.length > 2) 11.dp.toPx() else 5.dp.toPx(), y - 8.dp.toPx()), style = TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                                        if (action?.lowercaseChar() == 'v') {
                                            val waveWidth = 28.dp.toPx()
                                            val waveHeight = 3.dp.toPx()
                                            val waveY = y - radius - 4.dp.toPx()
                                            val segment = waveWidth / 3f
                                            val wave = Path().apply {
                                                moveTo(x - waveWidth / 2, waveY)
                                                repeat(3) { waveIndex ->
                                                    val startX = x - waveWidth / 2 + waveIndex * segment
                                                    cubicTo(startX + segment / 4, waveY - waveHeight, startX + segment * 3 / 4, waveY + waveHeight, startX + segment, waveY)
                                                }
                                            }
                                            drawPath(wave, green, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                                        } else if (action != null) {
                                            val preferredActionX = if (local < columnsPerSystem - 1) x + colGapPx / 2 else x + 25.dp.toPx()
                                            // Keep labels inside the canvas: drawText derives its max width from the
                                            // remaining canvas area and throws if the origin is beyond the edge.
                                            val actionX = preferredActionX.coerceIn(10.dp.toPx(), (size.width - 18.dp.toPx()).coerceAtLeast(10.dp.toPx()))
                                            drawText(textMeasurer, action.toString(), Offset(actionX - 4.dp.toPx(), y - 8.dp.toPx()), style = TextStyle(color = green, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                                        }
                                    }
                                }
                            }
                            selectionRect?.let { rect ->
                                drawRect(Color(0x33236957), Offset(rect.left, rect.top), androidx.compose.ui.geometry.Size(rect.width, rect.height))
                                drawRect(green, Offset(rect.left, rect.top), androidx.compose.ui.geometry.Size(rect.width, rect.height), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val movable = selectedEvent >= 0 && selectedString >= 0 && document.events.getOrNull(selectedEvent)?.frets?.getOrNull(selectedString) != null
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (movable) "Move selected note to:" else "Select a note to change its string", fontSize = 13.sp, color = ink, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(4.dp))
                        OutlinedTextField(fretToAdd, { fretToAdd = it.filter(Char::isDigit).take(2) }, Modifier.width(78.dp), label = { Text("New fret") }, singleLine = true)
                        OutlinedButton(onClick = { addNoteMode = !addNoteMode }) { Text(if (addNoteMode) "Click a string…" else "Add note") }
                        OutlinedButton(onClick = onAddPosition) { Text("Add tab position") }
                        OutlinedButton(onClick = { addBarMode = !addBarMode; addNoteMode = false }) { Text(if (addBarMode) "Click a position…" else "Add bar") }
                        OutlinedTextField(tempoInput, { tempoInput = it.filter(Char::isDigit).take(3) }, Modifier.width(90.dp), label = { Text("BPM") }, singleLine = true)
                        OutlinedButton(onClick = { tempoInput.toIntOrNull()?.takeIf { it in 20..400 }?.let(onSetTempo) }) { Text("Set tempo") }
                        Box {
                            OutlinedButton(onClick = { showLengthMenu = true }) { Text("Length: ${legacyLengthLabel(selectedLengthCode)}") }
                            DropdownMenu(expanded = showLengthMenu, onDismissRequest = { showLengthMenu = false }) {
                                (1..12).forEach { code ->
                                    DropdownMenuItem(text = { Text(legacyLengthLabel(code)) }, onClick = {
                                        selectedLengthCode = code
                                        if (movable) onSetLength(code)
                                        showLengthMenu = false
                                    })
                                }
                            }
                        }
                        if (movable) OutlinedButton(onClick = onDelete) { Text("Delete selected") }
                        if (selectedBarEvent >= 0) OutlinedButton(onClick = {
                            onRemoveBar(selectedBarEvent)
                            selectedBarEvent = -1
                        }) { Text("Remove selected bar") }
                        if (movable || selectedNotes.isNotEmpty()) {
                            OutlinedButton(onClick = { selectedNotes = onMoveDirection(selectedNotes, 1) }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) { Text("↓ Lower string") }
                            OutlinedButton(onClick = { selectedNotes = onMoveDirection(selectedNotes, -1) }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) { Text("↑ Higher string") }
                        }
                        if (selectedNotes.size <= 1) document.tuning.indices.forEach { s ->
                            val candidateFret = if (movable) document.tuning[selectedString] + document.events[selectedEvent].frets[selectedString]!! - document.tuning[s] else null
                            val enabled = movable && s != selectedString && document.events[selectedEvent].frets[s] == null && candidateFret != null && candidateFret in 0..36
                            if (enabled) OutlinedButton(onClick = { onMove(s) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text("${listOf("e", "B", "G", "D", "A", "E").getOrElse(s) { "S${s + 1}" }} · $candidateFret") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (addNoteMode) "Click the string and event position where the note belongs." else "${selectedNotes.size} notes selected · drag from empty staff space to add a rectangle selection", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                        if (selectedNotes.isNotEmpty()) TextButton(onClick = { selectedNotes = emptySet() }) { Text("Clear selection") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F5F2)), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("LEGACY FRET LIMITS · WHOLE TAB", fontSize = 10.sp, color = green, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(minimumFret, { minimumFret = it.filter(Char::isDigit).take(2) }, Modifier.width(112.dp), label = { Text("Lower limit") }, singleLine = true)
                                OutlinedButton(onClick = {
                                    val result = onGroupTransform(GroupFretRequest(GroupFretOperation.RAISE_MINIMUM, minimumFret.toIntOrNull() ?: -1, maximumFret.toIntOrNull() ?: -1, selectedNotes = selectedNotes))
                                    groupTransformMessage = result.message
                                    if (result.applied) selectedNotes = emptySet()
                                }) { Text("Apply lower limit") }
                                OutlinedTextField(maximumFret, { maximumFret = it.filter(Char::isDigit).take(2) }, Modifier.width(112.dp), label = { Text("Upper limit") }, singleLine = true)
                                OutlinedButton(onClick = {
                                    val result = onGroupTransform(GroupFretRequest(GroupFretOperation.LOWER_MAXIMUM, minimumFret.toIntOrNull() ?: -1, maximumFret.toIntOrNull() ?: -1, selectedNotes = selectedNotes))
                                    groupTransformMessage = result.message
                                    if (result.applied) selectedNotes = emptySet()
                                }) { Text("Apply upper limit") }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Separate: pitch transpose entire tab", fontSize = 12.sp, color = Color.Gray)
                                OutlinedTextField(transposeSemitones, { raw ->
                                    transposeSemitones = raw.filterIndexed { index, char -> char.isDigit() || index == 0 && char == '-' }.take(3)
                                }, Modifier.width(104.dp), label = { Text("Semitones") }, singleLine = true)
                                Button(onClick = {
                                    val result = onGroupTransform(GroupFretRequest(GroupFretOperation.TRANSPOSE, minimumFret.toIntOrNull() ?: -1, maximumFret.toIntOrNull() ?: -1, transposeSemitones.toIntOrNull() ?: 999))
                                    groupTransformMessage = result.message
                                    if (result.applied) selectedNotes = emptySet()
                                }) { Text("Transpose entire tab") }
                            }
                            Text(groupTransformMessage.ifBlank { "Lower/upper limits re-finger selected notes and preserve pitch. Transpose changes pitch across the entire song." }, fontSize = 12.sp, color = if (groupTransformMessage.contains("Could not") || groupTransformMessage.contains("must be") || groupTransformMessage.contains("Select notes")) Color(0xFF9B3B2E) else Color.Gray)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard("PITCH PRESERVED", "String changes calculate a new fret for the same sounding note.", Modifier.weight(1f))
            InfoCard("CUSTOM TUNING", "#tuning values set open-string pitch; #transpose shifts the tab.", Modifier.weight(1f))
        }
    }
}

private fun legacyLengthLabel(code: Int): String = when (code) {
    1 -> "Whole"
    2 -> "Dotted whole"
    3 -> "Half"
    4 -> "Dotted half"
    5 -> "Quarter"
    6 -> "Dotted quarter"
    7 -> "Eighth"
    8 -> "Dotted eighth"
    9 -> "16th"
    10 -> "Dotted 16th"
    11 -> "32nd"
    12 -> "Dotted 32nd"
    else -> "Quarter"
}

@Composable
private fun ChordPage(tuning: List<Int>, database: ChordDatabase, databaseLoaded: Boolean, naming: ChordNamingSystem, namingOverride: ChordNamingSystem?, onNamingOverride: (ChordNamingSystem?) -> Unit) {
    var selectedFrets by remember(tuning) { mutableStateOf(List<Int?>(tuning.size) { null }) }
    val selectedPitchClasses = remember(tuning, selectedFrets) {
        selectedFrets.mapIndexedNotNull { string, fret -> fret?.let { ((tuning[string] + it) % 12 + 12) % 12 } }.toSet()
    }
    val detectedChord = remember(selectedPitchClasses) { identifyChord(selectedPitchClasses) }
    val detectedPossibilities = remember(selectedPitchClasses) { identifyChordPossibilities(selectedPitchClasses) }
    var chordQuery by remember { mutableStateOf("") }
    val searchedChord = remember(chordQuery, naming) { parseChordSymbol(chordQuery, naming) }
    val voicings = remember(tuning, database, searchedChord) {
        searchedChord?.let { database.find(tuning, it) }.orEmpty()
    }
    val changeBuilderFrets: (List<Int?>) -> Unit = { frets ->
        selectedFrets = frets
        val notes = frets.mapIndexedNotNull { string, fret -> fret?.let { ((tuning[string] + it) % 12 + 12) % 12 } }.toSet()
        chordQuery = identifyChord(notes)?.name(naming).orEmpty()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChordBuilderCard(tuning, selectedFrets, selectedPitchClasses, detectedChord, chordQuery, { chordQuery = it }, naming, namingOverride, onNamingOverride,
            onFretsChanged = changeBuilderFrets,
            possibilities = detectedPossibilities,
            onChoosePossibility = { chordQuery = it.name(naming) },
            onClear = { changeBuilderFrets(List(tuning.size) { null }) })
        ChordLookupPanel(searchedChord, voicings, databaseLoaded, changeBuilderFrets, naming)
    }
}

@Composable
private fun ChordLookupPanel(
    chord: ChordSymbol?,
    voicings: List<ChordVoicing>,
    databaseLoaded: Boolean,
    onLoadShape: (List<Int?>) -> Unit,
    naming: ChordNamingSystem
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        chord?.let {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("${it.name(naming)} · ${it.pitchClasses.sorted().joinToString(" · ") { pc -> noteName(pc, naming) }}", fontSize = 18.sp, color = ink, fontWeight = FontWeight.SemiBold)
                    when {
                        !databaseLoaded -> Text("Loading shapes…", fontSize = 13.sp, color = Color.Gray)
                        voicings.isEmpty() -> Text("No built-in fingering for this chord.", fontSize = 13.sp, color = Color.Gray)
                        else -> {
                            val open = voicings.filter { shape -> shape.kind == VoicingKind.OPEN }
                            val barre = voicings.filter { shape -> shape.kind == VoicingKind.BARRE }
                            val fretted = voicings.filter { shape -> shape.kind == VoicingKind.OTHER }
                            if (open.isNotEmpty()) VoicingStrip("OPEN", open, onLoadShape, naming)
                            if (barre.isNotEmpty()) VoicingStrip("BARRE", barre, onLoadShape, naming)
                            if (fretted.isNotEmpty()) VoicingStrip("FRETTED", fretted, onLoadShape, naming)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChordBuilderCard(
    tuning: List<Int>,
    selectedFrets: List<Int?>,
    pitchClasses: Set<Int>,
    chord: ChordSymbol?,
    chordQuery: String,
    onChordQueryChange: (String) -> Unit,
    naming: ChordNamingSystem,
    namingOverride: ChordNamingSystem?,
    onNamingOverride: (ChordNamingSystem?) -> Unit,
    onFretsChanged: (List<Int?>) -> Unit,
    possibilities: List<ChordSymbol>,
    onChoosePossibility: (ChordSymbol) -> Unit,
    onClear: () -> Unit
) {
    val textMeasurer = rememberTextMeasurer()
    var showInstructions by remember { mutableStateOf(false) }
    val stringNames = listOf("e", noteName(11, naming), "G", "D", "A", "E")
    val chartLabelWidth = 64.dp
    val fretCellWidth = 58.dp
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = chordQuery,
                    onValueChange = onChordQueryChange,
                    placeholder = { Text("Name a chord") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.width(12.dp))
                NamingSelector("Chord names", namingOverride, inherited = naming, onSelect = onNamingOverride)
                Text("${selectedFrets.count { it != null }} / ${tuning.size} strings", fontSize = 12.sp, color = Color.Gray)
                TextButton(onClick = { showInstructions = !showInstructions }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(if (showInstructions) "Hide help" else "How to", fontSize = 12.sp)
                }
            }
            if (showInstructions) {
                Text("Tap a fret for each string. Fret 0 is open; tap × to mute a string. A matching chord name appears below.", fontSize = 12.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Canvas(Modifier.width(chartLabelWidth + fretCellWidth * 13).height((tuning.size * 48 + 38).dp).pointerInput(tuning, selectedFrets) {
                    detectTapGestures { tap ->
                        val rowGap = size.height / (tuning.size + 1)
                        val string = ((tap.y / rowGap).roundToInt() - 1).coerceIn(0, tuning.lastIndex)
                        val labelWidthPx = chartLabelWidth.toPx()
                        val fret = if (tap.x < labelWidthPx) null else ((tap.x - labelWidthPx) / fretCellWidth.toPx()).toInt().coerceIn(0, 12)
                        val updated = selectedFrets.toMutableList()
                        updated[string] = if (fret == null || updated[string] == fret) null else fret
                        onFretsChanged(updated)
                    }
                }) {
                    val labelWidth = chartLabelWidth.toPx()
                    val cellWidth = fretCellWidth.toPx()
                    val rowGap = size.height / (tuning.size + 1)
                    val gridRight = labelWidth + cellWidth * 13
                    for (fret in 0..12) {
                        val x = labelWidth + (fret + 0.5f) * cellWidth
                        drawText(textMeasurer, fret.toString(), Offset(x - 4.dp.toPx(), 3.dp.toPx()), style = TextStyle(color = Color(0xFF68736F), fontSize = 10.sp))
                    }
                    for (string in tuning.indices) {
                        val y = rowGap * (string + 1)
                        drawLine(Color(0xFF87918D), Offset(labelWidth, y), Offset(gridRight, y), 1.4f)
                        drawText(textMeasurer, stringNames.getOrElse(string) { "S${string + 1}" }, Offset(34.dp.toPx(), y - 8.dp.toPx()), style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        val activeFret = selectedFrets[string]
                        val stringMarker = activeFret?.let { fret -> noteName(tuning[string] + fret, naming) } ?: "×"
                        drawText(textMeasurer, stringMarker, Offset(8.dp.toPx(), y - 9.dp.toPx()), style = TextStyle(color = if (activeFret == null) Color(0xFF9B3D32) else green, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        for (fret in 0..12) {
                            val x = labelWidth + (fret + 1f) * cellWidth
                            drawLine(if (fret == 0) ink else Color(0xFFBCC5C1), Offset(x, y - rowGap * 0.42f), Offset(x, y + rowGap * 0.42f), if (fret == 0) 2.4f else 1f)
                        }
                        selectedFrets[string]?.let { fret ->
                            val x = labelWidth + (fret + 0.5f) * cellWidth
                            drawCircle(green, 13.dp.toPx(), Offset(x, y))
                            drawText(textMeasurer, fret.toString(), Offset(x - 4.dp.toPx(), y - 7.dp.toPx()), style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(chord?.name(naming) ?: if (pitchClasses.isEmpty()) "Choose notes above" else "No common chord match yet", color = if (chord != null) green else ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (pitchClasses.isNotEmpty()) Text(pitchClasses.sorted().joinToString(" · ") { noteName(it, naming) }, color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear") }
            }
            if (possibilities.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("Also recognized:", fontSize = 12.sp, color = Color.Gray)
                    possibilities.drop(1).forEach { possibility ->
                        TextButton(onClick = { onChoosePossibility(possibility) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                            Text(possibility.name(naming), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicingStrip(title: String, voicings: List<ChordVoicing>, onSelect: ((List<Int?>) -> Unit)? = null, naming: ChordNamingSystem = ChordNamingSystem.US) {
    Column {
        Spacer(Modifier.height(8.dp))
        Text(title, fontSize = 11.sp, color = green, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            voicings.forEach { voicing ->
                val onClick = onSelect?.let { select -> { select(voicing.frets) } }
                VoicingCard(voicing, onClick, naming)
            }
        }
    }
}

@Composable
private fun VoicingCard(voicing: ChordVoicing, onClick: (() -> Unit)? = null, naming: ChordNamingSystem = ChordNamingSystem.US) {
    val textMeasurer = rememberTextMeasurer()
    Card(Modifier.width(310.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9F6)), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(132.dp).height(142.dp)) {
                val left = 19.dp.toPx(); val right = size.width - 6.dp.toPx()
                val top = 23.dp.toPx(); val bottom = size.height - 15.dp.toPx()
                val dx = (right - left) / 5f; val dy = (bottom - top) / 4f
                for (i in 0..5) drawLine(Color(0xFF68736F), Offset(left + dx * i, top), Offset(left + dx * i, bottom), 1.2f)
                for (i in 0..4) drawLine(if (voicing.baseFret == 1 && i == 0) ink else Color(0xFF68736F), Offset(left, top + dy * i), Offset(right, top + dy * i), if (voicing.baseFret == 1 && i == 0) 2.5f else 1.2f)
                if (voicing.baseFret > 1) drawText(textMeasurer, voicing.baseFret.toString(), Offset(0f, top + dy * 0.65f), style = TextStyle(color = ink, fontSize = 10.sp))
                voicing.barreFret?.let { barreFret ->
                    if (barreFret in voicing.baseFret until voicing.baseFret + 4) {
                        val stringColumns = voicing.frets.asReversed().mapIndexedNotNull { column, fret -> if (fret == barreFret) column else null }
                        if (stringColumns.size > 1) {
                            val y = top + (barreFret - voicing.baseFret + 0.5f) * dy
                            drawLine(green.copy(alpha = 0.65f), Offset(left + dx * stringColumns.first(), y), Offset(left + dx * stringColumns.last(), y), strokeWidth = 7.dp.toPx())
                        }
                    }
                }
                voicing.frets.asReversed().forEachIndexed { column, fret ->
                    val x = left + dx * column
                    when {
                        fret == null -> drawText(textMeasurer, "×", Offset(x - 4.dp.toPx(), 3.dp.toPx()), style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        fret == 0 -> drawText(textMeasurer, "○", Offset(x - 5.dp.toPx(), 3.dp.toPx()), style = TextStyle(color = ink, fontSize = 12.sp))
                        fret >= voicing.baseFret && fret < voicing.baseFret + 4 -> {
                            val y = top + (fret - voicing.baseFret + 0.5f) * dy
                            drawCircle(green, 7.dp.toPx(), Offset(x, y))
                            val finger = voicing.fingers.getOrElse(voicing.fingers.lastIndex - column) { 0 }
                            if (finger > 0) drawText(textMeasurer, finger.toString(), Offset(x - 3.dp.toPx(), y - 5.dp.toPx()), style = TextStyle(color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
                listOf("E", "A", "D", "G", noteName(11, naming), "e").forEachIndexed { column, label ->
                    drawText(textMeasurer, label, Offset(left + dx * column - 3.dp.toPx(), bottom + 2.dp.toPx()), style = TextStyle(color = Color.Gray, fontSize = 8.sp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(voicing.label, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Fingers e B G D A E", color = Color.Gray, fontSize = 9.sp)
                Text(voicing.fingers.joinToString(" ") { if (it == 0) "·" else it.toString() }, color = green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Frets e B G D A E", color = Color.Gray, fontSize = 9.sp)
                Text(voicing.frets.joinToString(" ") { it?.toString() ?: "×" }, color = green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ScalePage(tuning: List<Int>, database: ChordDatabase, databaseLoaded: Boolean, naming: ChordNamingSystem) {
    val textMeasurer = rememberTextMeasurer()
    var key by remember { mutableIntStateOf(0) }
    var scaleIndex by remember { mutableIntStateOf(0) }
    val scale = scales[scaleIndex]
    val scaleChords = remember(key, scale) { chordsInScale(key, scale) }
    var selectedScaleChord by remember(key, scale) { mutableStateOf<ChordSymbol?>(null) }
    val scaleChordVoicings = remember(tuning, database, selectedScaleChord) {
        selectedScaleChord?.let { database.find(tuning, it) }.orEmpty()
    }
    val mainOctaveRoot = remember(tuning, key) {
        tuning.flatMapIndexed { string, openPitch ->
            (0..12).mapNotNull { fret ->
                val pitch = openPitch + fret
                if (((pitch % 12) + 12) % 12 == key) Triple(pitch, string, fret) else null
            }
        }.minWithOrNull(compareBy<Triple<Int, Int, Int>> { it.first }.thenBy { it.third }.thenBy { it.second })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                var keyExpanded by remember { mutableStateOf(false) }
                var scaleExpanded by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("SCALE EXPLORER", fontSize = 11.sp, color = green, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
                    Text("Find the shape across your fretboard", fontSize = 18.sp, color = ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { keyExpanded = true }) { Text("${noteName(key, naming)}  ▾") }
                        DropdownMenu(keyExpanded, { keyExpanded = false }) { noteNames.indices.forEach { i -> DropdownMenuItem(text = { Text(noteName(i, naming)) }, onClick = { key = i; keyExpanded = false }) } }
                    }
                    Box {
                        OutlinedButton(onClick = { scaleExpanded = true }) { Text("${scale.name}  ▾") }
                        DropdownMenu(scaleExpanded, { scaleExpanded = false }) { scales.forEachIndexed { i, sc -> DropdownMenuItem(text = { Text(sc.name) }, onClick = { scaleIndex = i; scaleExpanded = false }) } }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val noteSet = scale.steps.map { (it + key) % 12 }.toSet()
                Canvas(Modifier.fillMaxWidth().height((tuning.size * 44 + 26).dp)) {
                    val gapY = size.height / (tuning.size + 1)
                    val gapX = size.width / 14f
                    for (s in tuning.indices) {
                        val y = gapY * (s + 1)
                        drawLine(Color(0xFF87918D), Offset(0f, y), Offset(size.width, y), 1.4f)
                        for (fret in 0..12) {
                            val pitch = tuning[s] + fret
                            val pitchClass = ((pitch % 12) + 12) % 12
                            if (pitchClass in noteSet) {
                                val x = gapX * (fret + 1)
                                val mainOctave = mainOctaveRoot?.let { pitch >= it.first && pitch < it.first + 12 } == true
                                val noteColor = if (mainOctave) Color(0xFFD98C35) else if (fret == 0) Color(0xFFDDE9E4) else green
                                drawCircle(noteColor, 12.dp.toPx(), Offset(x, y))
                                drawText(textMeasurer, noteName(pitchClass, naming), Offset(x - 7.dp.toPx(), y - 6.dp.toPx()), style = TextStyle(color = if (fret == 0 && !mainOctave) ink else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                    for (fret in 0..12) {
                        val x = gapX * (fret + 1)
                        drawText(textMeasurer, fret.toString(), Offset(x - 4.dp.toPx(), size.height - 18.dp.toPx()), style = TextStyle(color = Color(0xFF68736F), fontSize = 10.sp, fontWeight = FontWeight.Medium))
                    }
                }
                Text("Frets 0–12  ·  tuning follows the loaded tab", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(10.dp).background(Color(0xFFD98C35), RoundedCornerShape(50)))
                    val rootDescription = mainOctaveRoot?.let { root ->
                        val stringName = listOf("e", "B", "G", "D", "A", "E").getOrElse(root.second) { "S${root.second + 1}" }
                        "$stringName string, fret ${root.third}"
                    } ?: "root position not found"
                    Text("Highlighted octave: ${noteName(key, naming)} through ${noteName((key + 11) % 12, naming)} · starts at $rootDescription", Modifier.weight(1f), fontSize = 12.sp, color = ink)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).background(Color(0xFFE8EEE9), RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Text("SCALE NOTES", fontSize = 11.sp, color = green, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text("${noteName(key, naming)} ${scale.name}: " + scale.steps.joinToString(" · ") { noteName((key + it) % 12, naming) }, fontSize = 13.sp, color = ink)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(2.5f)) {
                        Text("CHORDS IN THIS SCALE", fontSize = 11.sp, color = green, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text("Select a chord to see playable shapes", fontSize = 14.sp, color = ink, fontWeight = FontWeight.SemiBold)
                        if (scaleChords.isEmpty()) {
                            Text("No common triads found for this scale.", fontSize = 13.sp, color = Color.Gray)
                        } else {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                scaleChords.forEach { chord ->
                                    FilterChip(selected = selectedScaleChord == chord, onClick = { selectedScaleChord = chord }, label = { Text(chord.name(naming)) })
                                }
                            }
                        }
                    }
                }
                selectedScaleChord?.let { chord ->
                    Spacer(Modifier.height(10.dp))
                    Text("${chord.name(naming)} · ${chord.pitchClasses.sorted().joinToString(" · ") { noteName(it, naming) }}", fontSize = 14.sp, color = ink)
                    if (!databaseLoaded) Text("Loading chord shapes…", fontSize = 12.sp, color = Color.Gray)
                    else if (scaleChordVoicings.isEmpty()) Text("No built-in fingering for this chord.", fontSize = 12.sp, color = Color.Gray)
                    else {
                        listOf(VoicingKind.OPEN to "OPEN", VoicingKind.BARRE to "BARRE", VoicingKind.OTHER to "FRETTED").forEach { (kind, title) ->
                            val group = scaleChordVoicings.filter { it.kind == kind }
                            if (group.isNotEmpty()) VoicingStrip(title, group.take(4), naming = naming)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EEE9)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 10.sp, color = green, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp)); Text(body, fontSize = 13.sp, color = ink)
        }
    }
}

