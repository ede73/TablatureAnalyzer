package org.tablatureanalyzer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val ink = Color(0xFFE6ECE9)
private val green = Color(0xFF69C5A7)
private val paper = Color(0xFF121715)
private val surface = Color(0xFF1B2220)
private val surfaceRaised = Color(0xFF252F2B)
private val line = Color(0xFF74827D)
private val muted = Color(0xFFA5B0AB)
private val highlight = Color(0xFF79C7B2)
private val selection = Color(0xFF294A3F)
private val error = Color(0xFFF29A90)
private const val UNDO_HISTORY_LIMIT = 100

private enum class MarkerPlacement { BAR, REPEAT_START, REPEAT_END }

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun TablatureApp(initialDocument: TabDocument? = null) {
    var document by remember { mutableStateOf(initialDocument ?: TabDocument(listOf(64, 59, 55, 50, 45, 40), emptyList())) }
    var selectedEvent by remember { mutableIntStateOf(-1) }
    var selectedString by remember { mutableIntStateOf(-1) }
    var playheadEventIndex by remember { mutableIntStateOf(0) }
    var selectedPlaybackRange by remember { mutableStateOf<IntRange?>(null) }
    var undoHistory by remember { mutableStateOf(emptyList<TabDocument>()) }
    var redoHistory by remember { mutableStateOf(emptyList<TabDocument>()) }
    var lastUndoCoalesceKey by remember { mutableStateOf<String?>(null) }
    var historyResetRequestId by remember { mutableIntStateOf(0) }
    fun updateDocument(next: TabDocument, coalesceKey: String? = null) {
        if (next == document) return
        if (coalesceKey == null || coalesceKey != lastUndoCoalesceKey) {
            undoHistory = (undoHistory + document).takeLast(UNDO_HISTORY_LIMIT)
        }
        redoHistory = emptyList()
        lastUndoCoalesceKey = coalesceKey
        document = next
    }
    fun loadDocument(next: TabDocument) {
        document = next
        undoHistory = emptyList()
        redoHistory = emptyList()
        lastUndoCoalesceKey = null
        historyResetRequestId += 1
        selectedEvent = -1
        selectedString = -1
        playheadEventIndex = 0
        selectedPlaybackRange = null
    }
    fun undoDocument() {
        val previous = undoHistory.lastOrNull() ?: return
        undoHistory = undoHistory.dropLast(1)
        redoHistory = (redoHistory + document).takeLast(UNDO_HISTORY_LIMIT)
        lastUndoCoalesceKey = null
        document = previous
        historyResetRequestId += 1
        selectedEvent = -1
        selectedString = -1
        playheadEventIndex = playheadEventIndex.coerceIn(0, previous.events.lastIndex.coerceAtLeast(0))
        selectedPlaybackRange = null
    }
    fun redoDocument() {
        val next = redoHistory.lastOrNull() ?: return
        redoHistory = redoHistory.dropLast(1)
        undoHistory = (undoHistory + document).takeLast(UNDO_HISTORY_LIMIT)
        lastUndoCoalesceKey = null
        document = next
        historyResetRequestId += 1
        selectedEvent = -1
        selectedString = -1
        playheadEventIndex = playheadEventIndex.coerceIn(0, next.events.lastIndex.coerceAtLeast(0))
        selectedPlaybackRange = null
    }
    val storedTempo = document.tempoChanges.firstOrNull { it.eventIndex == 0 }?.beatsPerMinute ?: 120
    var tempoInput by remember { mutableStateOf(storedTempo.toString()) }
    LaunchedEffect(storedTempo) {
        if (tempoInput.toIntOrNull() != storedTempo) tempoInput = storedTempo.toString()
    }
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
    var importError by remember { mutableStateOf<String?>(null) }
    var showNewTabConfirmation by remember { mutableStateOf(false) }
    var playingEventIndex by remember { mutableIntStateOf(-1) }
    var durationEditedEventIndex by remember { mutableIntStateOf(-1) }
    var durationPreviewRequestId by remember { mutableIntStateOf(0) }
    val chordDatabase = ChordDatabase.BuiltIn
    val chordDatabaseLoaded = true
    MaterialTheme(colorScheme = darkColorScheme(
        primary = green,
        onPrimary = paper,
        secondary = highlight,
        onSecondary = paper,
        background = paper,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceRaised,
        onSurfaceVariant = muted,
        outline = line
    )) {
        Column(
            Modifier.fillMaxSize().background(paper).padding(horizontal = 18.dp, vertical = 14.dp)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || (!event.isMetaPressed && !event.isCtrlPressed)) false
                    else when {
                        event.key == Key.Z && event.isShiftPressed && redoHistory.isNotEmpty() -> { redoDocument(); true }
                        event.key == Key.Z && !event.isShiftPressed && undoHistory.isNotEmpty() -> { undoDocument(); true }
                        event.key == Key.Y && redoHistory.isNotEmpty() -> { redoDocument(); true }
                        else -> false
                    }
                }
        ) {
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
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = {
                        val hasContent = document.events.isNotEmpty() ||
                            document.tempoChanges != listOf(TabTempoChange(0, 120)) ||
                            document.timeSignatures != listOf(TabTimeSignatureChange(0, 4, 4))
                        if (hasContent) showNewTabConfirmation = true
                        else loadDocument(TabDocument(document.tuning, emptyList()))
                    }) { Text("New") }
                    PlatformOpenTabButton(onText = { text -> loadDocument(TabCodec.parse(text)) })
                    OutlinedButton(onClick = { showImport = true }) { Text("Paste") }
                    PlatformSaveProjectButton(TabCodec.encodeInternal(document))
                    PlatformSaveMusicXmlButton(MusicXmlCodec.encode(document))
                    OutlinedButton(onClick = ::undoDocument, enabled = undoHistory.isNotEmpty()) { Text("↶ Undo") }
                    OutlinedButton(onClick = ::redoDocument, enabled = redoHistory.isNotEmpty()) { Text("↷ Redo") }
                    OutlinedTextField(
                        value = tempoInput,
                        onValueChange = { value ->
                            tempoInput = value.filter(Char::isDigit).take(3)
                            tempoInput.toIntOrNull()?.takeIf { it in 20..400 }?.let { bpm ->
                                updateDocument(document.copy(tempoChanges = listOf(TabTempoChange(0, bpm)) + document.tempoChanges.filter { it.eventIndex > 0 }), "tempo")
                            }
                        },
                        modifier = Modifier.width(90.dp),
                        label = { Text("BPM") },
                        singleLine = true
                    )
                    PlatformMidiPlayerButton(
                        document,
                        durationEditedEventIndex.takeIf { page == 0 && it >= 0 },
                        durationPreviewRequestId,
                        playheadEventIndex,
                        selectedPlaybackRange
                    ) { eventIndex -> playingEventIndex = eventIndex ?: -1 }
                }
            }
            if (page == 0) {
                Spacer(Modifier.height(4.dp))
                GuitarInputPanel(GuitarInputMode.TAB_RECORD, document.tuning, tabNaming) { pitches, _ ->
                    val event = tabEventForPitches(document.tuning, pitches) ?: return@GuitarInputPanel
                    val eventIndex = document.events.size
                    updateDocument(document.copy(events = document.events + event))
                    selectedEvent = eventIndex
                    selectedString = event.frets.indexOfFirst { it != null }
                    playheadEventIndex = eventIndex
                }
            }
            Spacer(Modifier.height(6.dp))
            if (page == 0) {
                EditorPage(document, selectedEvent, selectedString, playingEventIndex, playheadEventIndex, historyResetRequestId, tabNaming, tabNamingOverride, { tabNamingOverride = it }, onSetPlaybackStart = { playheadEventIndex = it }, onPlaybackSelectionChanged = { selectedPlaybackRange = it }, onSelect = { e, s -> selectedEvent = e; selectedString = s }, onMove = { event, from, target ->
                    val updated = moveNote(document, event, from, target)
                    if (updated != document) {
                        updateDocument(updated)
                        selectedEvent = event
                        selectedString = target
                    }
                }, onSetFret = { event, string, fret ->
                    updateDocument(setNoteFret(document, event, string, fret))
                }, onMoveDirection = { selection, direction ->
                    val notesToMove = selection.ifEmpty {
                        if (selectedEvent >= 0 && selectedString >= 0) setOf(NotePosition(selectedEvent, selectedString)) else emptySet()
                    }
                    val result = moveNotesInDirection(document, notesToMove, direction)
                    if (result.applied) {
                        updateDocument(result.document)
                        result.movedSelection.singleOrNull()?.let { moved ->
                            selectedEvent = moved.eventIndex
                            selectedString = moved.stringIndex
                        }
                    }
                    if (result.applied) result.movedSelection else selection
                }, onAdd = { event, string, fret, lengthCode ->
                    updateDocument(addNoteAt(document, event, string, fret, lengthCode))
                    selectedEvent = event.coerceIn(0, document.events.lastIndex.coerceAtLeast(0))
                    selectedString = string
                }, onSetLength = { positions, code ->
                    updateDocument(setNoteLengthsAt(document, positions, code))
                    durationEditedEventIndex = selectedEvent.takeIf { eventIndex -> eventIndex >= 0 && positions.any { it.eventIndex == eventIndex } }
                        ?: positions.minOfOrNull { it.eventIndex }
                        ?: -1
                    durationPreviewRequestId += 1
                }, onConvertToRest = { eventIndices ->
                    updateDocument(convertEventsToRests(document, eventIndices))
                    selectedEvent = -1
                    selectedString = -1
                }, onRemoveRest = { eventIndex ->
                    updateDocument(removeRestAt(document, eventIndex))
                }, onSetRestLength = { eventIndex, code ->
                    updateDocument(setRestLengthAt(document, eventIndex, code))
                    durationEditedEventIndex = eventIndex
                    durationPreviewRequestId += 1
                }, onRemoveBar = { eventIndex ->
                    updateDocument(removeMeasureMarker(document, eventIndex))
                }, onAddBar = { eventIndex ->
                    updateDocument(addMeasureBar(document, eventIndex))
                }, onAddRepeatStart = { eventIndex ->
                    updateDocument(addRepeatStart(document, eventIndex))
                }, onAddRepeatEnd = { eventIndex ->
                    updateDocument(addRepeatEnd(document, eventIndex))
                }, onAdjustRepeatCount = { eventIndex, delta ->
                    updateDocument(adjustRepeatCount(document, eventIndex, delta), "repeat-count-$eventIndex")
                }, onMoveBar = { fromEvent, toEvent ->
                    val moved = moveMeasureBar(document, fromEvent, toEvent)
                    val didMove = moved != document
                    updateDocument(moved)
                    didMove
                }, onDelete = {
                    updateDocument(deleteNoteAt(document, selectedEvent, selectedString))
                    selectedEvent = selectedEvent.coerceAtMost(document.events.lastIndex)
                    selectedString = -1
                }, onGroupTransform = { request ->
                    val result = applyGroupFretOperation(document, request)
                    if (result.applied) {
                        val coalesceKey = request.operation.takeIf { it != GroupFretOperation.TRANSPOSE }
                            ?.let { "fret-range-$it" }
                        updateDocument(result.document, coalesceKey)
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
            AlertDialog(onDismissRequest = { showImport = false }, title = { Text("Paste tab, MusicXML, or project") },
                text = {
                    Column {
                        Box(Modifier.fillMaxWidth().height(132.dp).background(surfaceRaised, RoundedCornerShape(8.dp)).padding(10.dp)) {
                            if (importText.isEmpty()) Text("Paste tab text here", color = muted, fontSize = 13.sp)
                            BasicTextField(
                                value = importText,
                                onValueChange = { importText = it; importError = null },
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                textStyle = LocalTextStyle.current.copy(color = ink, fontSize = 13.sp)
                            )
                        }
                        importError?.let { Text(it, color = error, fontSize = 12.sp) }
                    }
                }, confirmButton = { Button(onClick = {
                    runCatching { TabCodec.parse(importText) }.onSuccess { parsed ->
                        loadDocument(parsed)
                        importText = ""
                        importError = null
                        showImport = false
                    }.onFailure { importError = it.message ?: "Could not read this tab." }
                }, enabled = importText.isNotBlank()) { Text("Load") } }, dismissButton = { TextButton(onClick = { showImport = false }) { Text("Cancel") } })
        }
        if (showNewTabConfirmation) {
            AlertDialog(
                onDismissRequest = { showNewTabConfirmation = false },
                title = { Text("Start a new tab?") },
                text = { Text("Clear the current notes, bars, repeats, and tempo changes? The current tuning will be kept.") },
                confirmButton = {
                    Button(onClick = {
                        loadDocument(TabDocument(document.tuning, emptyList()))
                        showNewTabConfirmation = false
                    }) { Text("Clear tab") }
                },
                dismissButton = { TextButton(onClick = { showNewTabConfirmation = false }) { Text("Cancel") } }
            )
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
@OptIn(ExperimentalLayoutApi::class)
private fun EditorPage(document: TabDocument, selectedEvent: Int, selectedString: Int, playingEventIndex: Int, playheadEventIndex: Int, historyResetRequestId: Int, naming: ChordNamingSystem, namingOverride: ChordNamingSystem?, onNamingOverride: (ChordNamingSystem?) -> Unit, onSetPlaybackStart: (Int) -> Unit, onPlaybackSelectionChanged: (IntRange?) -> Unit, onSelect: (Int, Int) -> Unit, onMove: (Int, Int, Int) -> Unit, onSetFret: (Int, Int, Int) -> Unit, onMoveDirection: (Set<NotePosition>, Int) -> Set<NotePosition>, onAdd: (Int, Int, Int, Int) -> Unit, onSetLength: (Set<NotePosition>, Int) -> Unit, onConvertToRest: (Set<Int>) -> Unit, onRemoveRest: (Int) -> Unit, onSetRestLength: (Int, Int) -> Unit, onRemoveBar: (Int) -> Unit, onAddBar: (Int) -> Unit, onAddRepeatStart: (Int) -> Unit, onAddRepeatEnd: (Int) -> Unit, onAdjustRepeatCount: (Int, Int) -> Unit, onMoveBar: (Int, Int) -> Boolean, onDelete: () -> Unit, onGroupTransform: (GroupFretRequest) -> GroupFretResult) {
    val textMeasurer = rememberTextMeasurer()
    var dragTargetString by remember { mutableIntStateOf(-1) }
    var dragTargetFret by remember { mutableIntStateOf(-1) }
    var heldNote by remember { mutableStateOf<NotePosition?>(null) }
    var minimumFret by remember { mutableStateOf("0") }
    var maximumFret by remember { mutableStateOf("12") }
    var transposeSemitones by remember { mutableStateOf("0") }
    var showFretTools by remember { mutableStateOf(false) }
    var groupTransformMessage by remember { mutableStateOf("") }
    var selectedNotes by remember { mutableStateOf(emptySet<NotePosition>()) }
    var selectedRestEvent by remember { mutableIntStateOf(-1) }
    var selectedBarEvent by remember { mutableIntStateOf(-1) }
    LaunchedEffect(historyResetRequestId) {
        selectedNotes = emptySet()
        selectedRestEvent = -1
        selectedBarEvent = -1
        onSelect(-1, -1)
    }
    LaunchedEffect(selectedRestEvent, document.events) {
        if (selectedRestEvent >= 0 && document.events.getOrNull(selectedRestEvent)?.restDurationUnits == null) {
            selectedRestEvent = -1
        }
    }
    LaunchedEffect(selectedNotes, document.events) {
        val eventIndices = selectedNotes.filter { position ->
            document.events.getOrNull(position.eventIndex)?.let { it.restDurationUnits == null && it.frets.getOrNull(position.stringIndex) != null } == true
        }.map { it.eventIndex }
        // A normal single-note click sets the playback start; looping that one
        // note is surprising. Treat only an explicit multi-note selection as a loop.
        onPlaybackSelectionChanged(eventIndices.takeIf { it.size > 1 }?.let { indices ->
            indices.minOrNull()!!..indices.maxOrNull()!!
        })
    }
    LaunchedEffect(selectedNotes, document) {
        val frets = selectedNotes.mapNotNull { position ->
            document.events.getOrNull(position.eventIndex)?.takeIf { it.restDurationUnits == null }?.frets?.getOrNull(position.stringIndex)
        }
        if (frets.isNotEmpty()) {
            minimumFret = frets.minOrNull().toString()
            maximumFret = frets.maxOrNull().toString()
        }
    }
    var selectionRect by remember { mutableStateOf<Rect?>(null) }
    var markerPlacement by remember { mutableStateOf<MarkerPlacement?>(null) }
    var addNoteMode by remember { mutableStateOf(false) }
    var selectedLengthCode by remember { mutableIntStateOf(5) }
    var showLengthMenu by remember { mutableStateOf(false) }
    var durationMenuTargets by remember { mutableStateOf(emptySet<NotePosition>()) }
    var durationPreview by remember { mutableStateOf<Pair<NotePosition, Int>?>(null) }
    val editorFocusRequester = remember { FocusRequester() }
    val staffScrollState = rememberScrollState()
    var lastFollowedSystem by remember { mutableIntStateOf(-1) }
    fun applyFretLimitEdit(operation: GroupFretOperation, input: String) {
        val limit = input.toIntOrNull()?.takeIf { it in 0..36 } ?: return
        if (selectedNotes.isEmpty()) return
        val request = when (operation) {
            GroupFretOperation.RAISE_MINIMUM -> GroupFretRequest(operation, limit, maximumFret.toIntOrNull() ?: -1, selectedNotes = selectedNotes)
            GroupFretOperation.LOWER_MAXIMUM -> GroupFretRequest(operation, minimumFret.toIntOrNull() ?: -1, limit, selectedNotes = selectedNotes)
            GroupFretOperation.TRANSPOSE -> return
        }
        val result = onGroupTransform(request)
        groupTransformMessage = result.message
        if (result.applied) selectedNotes = result.movedSelection
    }
    Column(Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(staffScrollState).padding(if (document.events.isEmpty()) 10.dp else 16.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    NamingSelector("Tab names", namingOverride, inherited = naming, onSelect = onNamingOverride)
                    Text("Click empty position to set play start · select notes to loop · drag up/down: string · left/right: fret", fontSize = 12.sp, color = muted)
                }
                Spacer(Modifier.height(if (document.events.isEmpty()) 4.dp else 12.dp))
                if (document.events.isEmpty()) {
                    Canvas(
                            Modifier.fillMaxWidth().height(maxOf(206.dp, 52.dp + 32.dp * (document.tuning.size - 1))).background(paper, RoundedCornerShape(12.dp))
                                .pointerInput(addNoteMode, markerPlacement, selectedLengthCode, document.tuning) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val string = ((down.position.y - 26.dp.toPx()) / 32.dp.toPx()).roundToInt().coerceIn(0, document.tuning.lastIndex)
                                        val left = 38.dp.toPx()
                                        val columnGap = 62.dp.toPx()
                                        val event = (((down.position.x - left) / columnGap).roundToInt() - 1).coerceAtLeast(0)
                                        onSetPlaybackStart(event)
                                        when (markerPlacement) {
                                            MarkerPlacement.BAR -> {
                                                onAddBar(event)
                                                selectedBarEvent = event
                                                selectedNotes = emptySet()
                                                markerPlacement = null
                                            }
                                            MarkerPlacement.REPEAT_START -> {
                                                onAddRepeatStart(event)
                                                selectedBarEvent = event
                                                selectedNotes = emptySet()
                                                markerPlacement = null
                                            }
                                            MarkerPlacement.REPEAT_END -> {
                                                onAddRepeatEnd(event)
                                                selectedBarEvent = event
                                                selectedNotes = emptySet()
                                                markerPlacement = null
                                            }
                                            null -> if (addNoteMode) {
                                                onAdd(event, string, 0, selectedLengthCode)
                                                selectedBarEvent = -1
                                            } else {
                                                selectedBarEvent = -1
                                                onSelect(-1, -1)
                                            }
                                        }
                                    }
                                }
                        ) {
                            val rowGap = 32.dp.toPx()
                            val left = 38.dp.toPx()
                            document.tuning.forEachIndexed { string, _ ->
                                val y = 26.dp.toPx() + rowGap * string
                                drawLine(line, Offset(left, y), Offset(size.width - 8.dp.toPx(), y), strokeWidth = 1.4f)
                                drawText(textMeasurer, listOf("e", noteName(11, naming), "G", "D", "A", "E").getOrElse(string) { "S${string + 1}" }, Offset(5f, y - 8.dp.toPx()), style = TextStyle(color = ink, fontSize = 12.sp))
                            }
                        }
                } else {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val density = LocalDensity.current
                        val colGapPx = with(density) { 62.dp.toPx() }
                        val leftPx = with(density) { 38.dp.toPx() }
                        val columnsPerSystem = ((constraints.maxWidth.toFloat() - leftPx - with(density) { 8.dp.toPx() }) / colGapPx).toInt().coerceAtLeast(1)
                        val systems = ((document.events.size + columnsPerSystem - 1) / columnsPerSystem).coerceAtLeast(1)
                        val systemHeight = maxOf(206.dp, 52.dp + 32.dp * (document.tuning.size - 1))
                        LaunchedEffect(playingEventIndex, columnsPerSystem, systemHeight) {
                            val activeSystem = if (playingEventIndex < 0) -1 else (playingEventIndex / columnsPerSystem).coerceIn(0, systems - 1)
                            if (activeSystem < 0) {
                                lastFollowedSystem = -1
                            } else if (activeSystem != lastFollowedSystem) {
                                lastFollowedSystem = activeSystem
                                val systemHeightPx = with(density) { systemHeight.roundToPx() }
                                val canvasTopPx = with(density) { 52.dp.roundToPx() }
                                val systemTop = canvasTopPx + activeSystem * systemHeightPx
                                val systemBottom = systemTop + systemHeightPx
                                val viewportTop = staffScrollState.value
                                val viewportBottom = viewportTop + staffScrollState.viewportSize
                                val bottomClearance = with(density) { 24.dp.roundToPx() }
                                val target = when {
                                    systemTop < viewportTop -> systemTop
                                    systemBottom > viewportBottom -> systemBottom - staffScrollState.viewportSize + bottomClearance
                                    else -> viewportTop
                                }.coerceAtLeast(0)
                                if (target != viewportTop) staffScrollState.animateScrollTo(target)
                            }
                        }
                        Canvas(Modifier.fillMaxWidth().height(systemHeight * systems)
                            .focusRequester(editorFocusRequester)
                            .focusable()
                            .onKeyEvent { keyEvent ->
                                val code = if (keyEvent.type == KeyEventType.KeyDown) when (keyEvent.key) {
                                    Key.One, Key.NumPad1 -> 1
                                    Key.Two, Key.NumPad2 -> 3
                                    Key.Four, Key.NumPad4 -> 5
                                    Key.Eight, Key.NumPad8 -> 7
                                    else -> null
                                } else null
                                if (code != null && selectedNotes.isNotEmpty()) {
                                    selectedLengthCode = code
                                    onSetLength(selectedNotes, code)
                                    true
                                } else false
                            }
                            .pointerInput(document, columnsPerSystem, addNoteMode, markerPlacement, selectedLengthCode, selectedRestEvent) {
                                awaitEachGesture {
                                    var downEvent = awaitPointerEvent()
                                    while (downEvent.changes.none { it.pressed }) downEvent = awaitPointerEvent()
                                    val down = downEvent.changes.first { it.pressed }
                                    val system = (down.position.y / systemHeight.toPx()).toInt().coerceIn(0, systems - 1)
                                    val localColumn = ((down.position.x - leftPx) / colGapPx).roundToInt() - 1
                                    val rawEvent = system * columnsPerSystem + localColumn
                                    val startEvent = if (addNoteMode || markerPlacement != null) rawEvent.coerceAtLeast(0) else rawEvent.coerceIn(0, document.events.lastIndex.coerceAtLeast(0))
                                    val localY = down.position.y - system * systemHeight.toPx()
                                    val startString = ((localY - 26.dp.toPx()) / (32.dp.toPx())).roundToInt().coerceIn(0, document.tuning.lastIndex)
                                    if (downEvent.buttons.isSecondaryPressed) {
                                        val hit = nearestNoteAt(document, down.position, systemHeight.toPx(), leftPx, colGapPx, columnsPerSystem, 26.dp.toPx(), 32.dp.toPx(), 22.dp.toPx())
                                        if (hit != null) {
                                            selectedBarEvent = -1
                                            selectedNotes = setOf(hit)
                                            onSelect(hit.eventIndex, hit.stringIndex)
                                            editorFocusRequester.requestFocus()
                                            var dragged = false
                                            var previewCode = currentLengthCode(document, hit)
                                            val currentIndex = DURATION_DRAG_CODES.indexOf(previewCode).coerceAtLeast(0)
                                            val stepPx = 36.dp.toPx()
                                            drag(down.id) { change ->
                                                val deltaX = change.position.x - down.position.x
                                                if (kotlin.math.abs(deltaX) > viewConfiguration.touchSlop) dragged = true
                                                if (dragged) {
                                                    val step = (deltaX / stepPx).toInt()
                                                    previewCode = DURATION_DRAG_CODES[(currentIndex + step).coerceIn(DURATION_DRAG_CODES.indices)]
                                                    durationPreview = hit to previewCode
                                                    change.consume()
                                                }
                                            }
                                            if (dragged) {
                                                selectedLengthCode = previewCode
                                                onSetLength(setOf(hit), previewCode)
                                            } else {
                                                durationMenuTargets = setOf(hit)
                                                selectedLengthCode = previewCode
                                                showLengthMenu = true
                                            }
                                            durationPreview = null
                                        }
                                        return@awaitEachGesture
                                    }
                                    editorFocusRequester.requestFocus()
                                    onSetPlaybackStart(startEvent)
                                    markerPlacement?.let { placement ->
                                        when (placement) {
                                            MarkerPlacement.BAR -> onAddBar(startEvent)
                                            MarkerPlacement.REPEAT_START -> onAddRepeatStart(startEvent)
                                            MarkerPlacement.REPEAT_END -> onAddRepeatEnd(startEvent)
                                        }
                                        selectedBarEvent = startEvent
                                        selectedRestEvent = -1
                                        selectedNotes = emptySet()
                                        onSelect(-1, -1)
                                        markerPlacement = null
                                        return@awaitEachGesture
                                    }
                                    val clickedRestEvent = document.events.indices.firstOrNull { eventIndex ->
                                        val event = document.events[eventIndex]
                                        if (event.restDurationUnits == null) return@firstOrNull false
                                        val eventSystem = eventIndex / columnsPerSystem
                                        val local = eventIndex % columnsPerSystem
                                        val x = leftPx + colGapPx * (local + 1)
                                        val y = eventSystem * systemHeight.toPx() + 26.dp.toPx() + 32.dp.toPx() * ((document.tuning.size - 1) / 2f)
                                        kotlin.math.abs(down.position.x - x) <= 18.dp.toPx() && kotlin.math.abs(down.position.y - y) <= 24.dp.toPx()
                                    }
                                    if (clickedRestEvent != null) {
                                        selectedRestEvent = if (selectedRestEvent == clickedRestEvent) -1 else clickedRestEvent
                                        if (selectedRestEvent >= 0) {
                                            selectedLengthCode = document.events[selectedRestEvent].restDurationUnits
                                                ?.let { lengthCodeForUnits(it, document.divisions) } ?: 5
                                        }
                                        selectedNotes = emptySet()
                                        selectedBarEvent = -1
                                        onSelect(-1, -1)
                                        down.consume()
                                        return@awaitEachGesture
                                    }
                                    selectedRestEvent = -1
                                    val clickedRepeatStart = document.events.indices.firstOrNull { eventIndex ->
                                        val event = document.events[eventIndex]
                                        if (!event.repeatStartBefore) false else {
                                            val local = eventIndex % columnsPerSystem
                                            val x = leftPx + colGapPx * (local + 1) - colGapPx / 2
                                            val localClickY = down.position.y - (eventIndex / columnsPerSystem) * systemHeight.toPx()
                                            val lineBottom = minOf(214.dp.toPx(), systemHeight.toPx() - 4.dp.toPx())
                                            kotlin.math.abs(down.position.x - x) <= 12.dp.toPx() && localClickY in 8.dp.toPx()..lineBottom
                                        }
                                    }
                                    val clickedBar = clickedRepeatStart ?: document.events.indices.firstOrNull { eventIndex ->
                                        val event = document.events[eventIndex]
                                        val eventSystem = eventIndex / columnsPerSystem
                                        val local = eventIndex % columnsPerSystem
                                        val x = leftPx + colGapPx * (local + 1)
                                        val localClickY = down.position.y - eventSystem * systemHeight.toPx()
                                        val lineBottom = minOf(214.dp.toPx(), systemHeight.toPx() - 4.dp.toPx())
                                        val withinLine = localClickY in 8.dp.toPx()..lineBottom
                                        val onEnd = (event.barAfter || event.repeatEndAfter) && kotlin.math.abs(down.position.x - (x + colGapPx / 2)) <= 12.dp.toPx()
                                        withinLine && onEnd
                                    }
                                    if (clickedBar != null) {
                                        if (selectedBarEvent == clickedBar) {
                                            selectedBarEvent = -1
                                            selectedNotes = emptySet()
                                            onSelect(-1, -1)
                                            return@awaitEachGesture
                                        }
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
                                    val hasNote = document.events.getOrNull(startEvent)?.let { it.restDurationUnits == null && it.frets.getOrNull(startString) != null } == true
                                    heldNote = NotePosition(startEvent, startString).takeIf { hasNote }
                                    var draggingNote = false
                                    var selectingRectangle = false
                                    var rectangleEnd = down.position
                                    var noteDragDelta = Offset.Zero
                                    val pastSlop = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                        if (hasNote) {
                                            draggingNote = true
                                            onSelect(startEvent, startString)
                                            // The first movement that crosses touch slop may also be the
                                            // release position (common for a short desktop drag). Keep it.
                                            noteDragDelta = change.position - down.position
                                            val targetSystem = (change.position.y / systemHeight.toPx()).toInt().coerceIn(0, systems - 1)
                                            val targetLocalY = change.position.y - targetSystem * systemHeight.toPx()
                                            val horizontalDrag = kotlin.math.abs(noteDragDelta.x) > kotlin.math.abs(noteDragDelta.y)
                                            dragTargetString = if (!horizontalDrag) {
                                                ((targetLocalY - 26.dp.toPx()) / 32.dp.toPx()).roundToInt().coerceIn(0, document.tuning.lastIndex)
                                            } else startString
                                            val originalFret = document.events.getOrNull(startEvent)?.frets?.getOrNull(startString)
                                            val fretDelta = (noteDragDelta.x / 20.dp.toPx()).roundToInt()
                                            dragTargetFret = when {
                                                originalFret == null -> -1
                                                horizontalDrag -> (originalFret + fretDelta).coerceIn(0, 36)
                                                dragTargetString in document.tuning.indices -> originalFret + document.tuning[startString] - document.tuning[dragTargetString]
                                                else -> -1
                                            }
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
                                        if (addNoteMode && !hasNote) {
                                            onAdd(startEvent, startString, 0, selectedLengthCode)
                                            selectedNotes = setOf(NotePosition(startEvent, startString))
                                            selectedRestEvent = -1
                                            selectedBarEvent = -1
                                        } else if (hasNote && !draggingNote) {
                                            selectedBarEvent = -1
                                            onSelect(startEvent, startString)
                                            selectedNotes = setOf(NotePosition(startEvent, startString))
                                            editorFocusRequester.requestFocus()
                                        } else {
                                            selectedBarEvent = -1
                                            onSelect(-1, -1)
                                            selectedNotes = emptySet()
                                        }
                                    } else if (draggingNote) {
                                        drag(down.id) { change ->
                                            noteDragDelta = change.position - down.position
                                            val targetSystem = (change.position.y / systemHeight.toPx()).toInt().coerceIn(0, systems - 1)
                                            val targetLocalY = change.position.y - targetSystem * systemHeight.toPx()
                                            val target = ((targetLocalY - 26.dp.toPx()) / 32.dp.toPx()).roundToInt().coerceIn(0, document.tuning.lastIndex)
                                            val horizontalDrag = kotlin.math.abs(noteDragDelta.x) > kotlin.math.abs(noteDragDelta.y)
                                            dragTargetString = if (horizontalDrag) startString else target
                                            val originalFret = document.events.getOrNull(startEvent)?.frets?.getOrNull(startString)
                                            val fretDelta = (noteDragDelta.x / 20.dp.toPx()).roundToInt()
                                            dragTargetFret = when {
                                                originalFret == null -> -1
                                                horizontalDrag -> (originalFret + fretDelta).coerceIn(0, 36)
                                                dragTargetString in document.tuning.indices -> originalFret + document.tuning[startString] - document.tuning[dragTargetString]
                                                else -> -1
                                            }
                                            change.consume()
                                        }
                                        if (kotlin.math.abs(noteDragDelta.x) > kotlin.math.abs(noteDragDelta.y)) {
                                            val originalFret = document.events.getOrNull(startEvent)?.frets?.getOrNull(startString)
                                            if (originalFret != null && dragTargetFret >= 0 && dragTargetFret != originalFret) onSetFret(startEvent, startString, dragTargetFret)
                                            selectedNotes = setOf(NotePosition(startEvent, startString))
                                        } else {
                                            val target = dragTargetString
                                            if (target in document.tuning.indices && target != startString) {
                                                onMove(startEvent, startString, target)
                                                selectedNotes = setOf(NotePosition(startEvent, target))
                                            } else {
                                                selectedNotes = setOf(NotePosition(startEvent, startString))
                                            }
                                        }
                                        dragTargetString = -1
                                        dragTargetFret = -1
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
                                                if (event.restDurationUnits != null) return@flatMapIndexed emptyList()
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
                                    heldNote = null
                                }
                            }) {
                            val systemHeightPx = systemHeight.toPx()
                            val rowGap = 32.dp.toPx()
                            val left = leftPx
                            repeat(systems) { system ->
                                val top = system * systemHeightPx
                                if (system > 0) drawLine(muted.copy(alpha = 0.22f), Offset(0f, top), Offset(size.width, top), 1f)
                                for (s in document.tuning.indices) {
                                    val y = top + 26.dp.toPx() + rowGap * s
                                    drawLine(line, Offset(left, y), Offset(size.width - 8.dp.toPx(), y), strokeWidth = 1.4f)
                                    drawText(textMeasurer, listOf("e", noteName(11, naming), "G", "D", "A", "E").getOrElse(s) { "S${s + 1}" }, Offset(5f, y - 8.dp.toPx()), style = TextStyle(color = ink, fontSize = 12.sp))
                                }
                            }
                            document.events.forEachIndexed { i, event ->
                                val system = i / columnsPerSystem
                                val local = i % columnsPerSystem
                                val top = system * systemHeightPx
                                val x = left + colGapPx * (local + 1)
                                if (playingEventIndex < 0 && playheadEventIndex == i) {
                                    val cursorTop = top + 12.dp.toPx()
                                    val cursorBottom = top + minOf(214.dp.toPx(), systemHeightPx - 4.dp.toPx())
                                    var dashY = cursorTop
                                    while (dashY < cursorBottom) {
                                        drawLine(highlight.copy(alpha = 0.82f), Offset(x, dashY), Offset(x, minOf(dashY + 5.dp.toPx(), cursorBottom)), strokeWidth = 1.5.dp.toPx())
                                        dashY += 9.dp.toPx()
                                    }
                                    val cursorTip = Path().apply {
                                        moveTo(x - 4.dp.toPx(), top + 5.dp.toPx())
                                        lineTo(x + 4.dp.toPx(), top + 5.dp.toPx())
                                        lineTo(x, top + 11.dp.toPx())
                                        close()
                                    }
                                    drawPath(cursorTip, highlight)
                                }
                                if (event.repeatStartBefore) {
                                    val repeatX = x - colGapPx / 2
                                    val repeatColor = if (i == selectedBarEvent) highlight else green
                                    val barBottom = minOf(214.dp.toPx(), systemHeightPx - 4.dp.toPx())
                                    drawLine(repeatColor, Offset(repeatX, top + 8.dp.toPx()), Offset(repeatX, top + barBottom), 3.5f)
                                    drawLine(repeatColor.copy(alpha = 0.75f), Offset(repeatX + 4.dp.toPx(), top + 8.dp.toPx()), Offset(repeatX + 4.dp.toPx(), top + barBottom), 1.5f)
                                    val dotY = top + 26.dp.toPx() + rowGap * ((document.tuning.size - 1) / 2f)
                                    drawCircle(repeatColor, 2.2.dp.toPx(), Offset(repeatX + 8.dp.toPx(), dotY - 4.dp.toPx()))
                                    drawCircle(repeatColor, 2.2.dp.toPx(), Offset(repeatX + 8.dp.toPx(), dotY + 4.dp.toPx()))
                                }
                                if (event.barAfter) {
                                    val barColor = if (i == selectedBarEvent) highlight else green
                                    val barBottom = minOf(214.dp.toPx(), systemHeightPx - 4.dp.toPx())
                                    val repeatX = x + colGapPx / 2
                                    if (event.repeatEndAfter) {
                                        drawLine(barColor.copy(alpha = 0.75f), Offset(repeatX - 4.dp.toPx(), top + 8.dp.toPx()), Offset(repeatX - 4.dp.toPx(), top + barBottom), 1.5f)
                                    }
                                    drawLine(barColor, Offset(repeatX, top + 8.dp.toPx()), Offset(repeatX, top + barBottom), if (event.repeatEndAfter || i == selectedBarEvent) 3.5f else 2f)
                                    if (event.repeatEndAfter) {
                                        val dotY = top + 26.dp.toPx() + rowGap * ((document.tuning.size - 1) / 2f)
                                        drawCircle(barColor, 2.2.dp.toPx(), Offset(repeatX - 8.dp.toPx(), dotY - 4.dp.toPx()))
                                        drawCircle(barColor, 2.2.dp.toPx(), Offset(repeatX - 8.dp.toPx(), dotY + 4.dp.toPx()))
                                        drawText(textMeasurer, "×${event.repeatCount}", Offset(repeatX - 16.dp.toPx(), top + 2.dp.toPx()), style = TextStyle(color = barColor, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                                    }
                                }
                                if (event.restDurationUnits != null) {
                                    val restCenter = Offset(x, top + 26.dp.toPx() + rowGap * ((document.tuning.size - 1) / 2f))
                                    val restColor = if (selectedRestEvent == i || playingEventIndex == i) highlight else green
                                    if (selectedRestEvent == i) {
                                        drawRoundRect(
                                            selection.copy(alpha = 0.55f),
                                            Offset(restCenter.x - 17.dp.toPx(), restCenter.y - 21.dp.toPx()),
                                            androidx.compose.ui.geometry.Size(34.dp.toPx(), 42.dp.toPx()),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
                                        )
                                    }
                                    val restCode = lengthCodeForUnits(event.restDurationUnits, document.divisions) ?: 5
                                    drawRestGlyph(restCode, restCenter, restColor, 1.7f)
                                    if (playingEventIndex == i) {
                                        drawCircle(highlight, radius = 4.dp.toPx(), center = Offset(restCenter.x - 18.dp.toPx(), restCenter.y))
                                    }
                                }
                                if (event.restDurationUnits == null) event.frets.forEachIndexed { s, fret ->
                                    if (fret != null) {
                                        val y = top + 26.dp.toPx() + rowGap * s
                                        val selected = NotePosition(i, s) in selectedNotes
                                        val chosen = i == selectedEvent && s == selectedString
                                        val dropTarget = i == selectedEvent && s == dragTargetString && dragTargetString != selectedString
                                        val fretPreview = chosen && dragTargetString == selectedString && dragTargetFret >= 0
                                        val verticalMovePreview = chosen && dragTargetString in document.tuning.indices && dragTargetString != s
                                        val action = event.actions.getOrNull(s)
                                        // The notehead circle already delimits the fret; do not add notation parentheses.
                                        val noteLabel = (if (fretPreview) dragTargetFret else fret).toString()
                                        val radius = 14.dp.toPx()
                                        val noteFill = when { fretPreview || selected -> selection; dropTarget -> surfaceRaised; chosen -> green; else -> surfaceRaised }
                                        val noteOutline = when { fretPreview || selected || dropTarget || chosen -> green; else -> line }
                                        val originAlpha = if (verticalMovePreview) 0.30f else 1f
                                        drawCircle(noteFill.copy(alpha = originAlpha), radius = radius, center = Offset(x, y))
                                        drawCircle(noteOutline.copy(alpha = originAlpha), radius = radius, center = Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (selected || fretPreview) 2.5f else 1.5f))
                                        if (playingEventIndex == i) {
                                            drawCircle(highlight, radius = 4.dp.toPx(), center = Offset(x - radius - 5.dp.toPx(), y))
                                        }
                                        drawText(textMeasurer, noteLabel, Offset(x - if (noteLabel.length > 2) 11.dp.toPx() else 5.dp.toPx(), y - 8.dp.toPx()), style = TextStyle(color = ink.copy(alpha = originAlpha), fontSize = 11.sp, fontWeight = FontWeight.Bold))
                                        if (verticalMovePreview && dragTargetFret in 0..36) {
                                            val previewY = top + 26.dp.toPx() + rowGap * dragTargetString
                                            val targetEmpty = event.frets.getOrNull(dragTargetString) == null
                                            val previewFill = if (targetEmpty) selection else surfaceRaised
                                            drawCircle(previewFill.copy(alpha = 0.94f), radius = radius, center = Offset(x, previewY))
                                            drawCircle(green, radius = radius, center = Offset(x, previewY), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
                                            val previewLabel = dragTargetFret.toString()
                                            drawText(textMeasurer, previewLabel, Offset(x - if (previewLabel.length > 2) 11.dp.toPx() else 5.dp.toPx(), previewY - 8.dp.toPx()), style = TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                                        }
                                        if (heldNote == NotePosition(i, s)) {
                                            drawMoveArrows(Offset(x, y), radius, green)
                                        }
                                        if (fretPreview) {
                                            val previewText = "Fret $dragTargetFret"
                                            val previewLayout = textMeasurer.measure(previewText, style = TextStyle(color = paper, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                                            val bubbleWidth = previewLayout.size.width + 12.dp.toPx()
                                            val bubbleHeight = previewLayout.size.height + 6.dp.toPx()
                                            val bubbleLeft = (x - bubbleWidth / 2).coerceIn(0f, size.width - bubbleWidth)
                                            val bubbleTop = (y - radius - bubbleHeight - 5.dp.toPx()).coerceAtLeast(2.dp.toPx())
                                            drawRoundRect(green, Offset(bubbleLeft, bubbleTop), androidx.compose.ui.geometry.Size(bubbleWidth, bubbleHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                                            drawText(textMeasurer, previewText, Offset(bubbleLeft + 6.dp.toPx(), bubbleTop + 3.dp.toPx()), style = TextStyle(color = paper, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                                        }
                                        val shownLength = durationPreview?.takeIf { it.first == NotePosition(i, s) }?.second
                                            ?: event.lengthCodes.getOrNull(s)
                                            ?: event.durationUnits.getOrNull(s)?.let { lengthCodeForUnits(it, document.divisions) }
                                        shownLength?.let { code ->
                                            drawDurationGlyph(code, Offset(x + radius + 8.dp.toPx(), y - 7.dp.toPx()), green, 1.dp.toPx())
                                            if (durationPreview?.first == NotePosition(i, s)) {
                                                drawText(textMeasurer, durationFraction(code), Offset(x + radius + 8.dp.toPx(), y - 22.dp.toPx()), style = TextStyle(color = green, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                                            }
                                        }
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
                                drawRect(green.copy(alpha = 0.20f), Offset(rect.left, rect.top), androidx.compose.ui.geometry.Size(rect.width, rect.height))
                                drawRect(green, Offset(rect.left, rect.top), androidx.compose.ui.geometry.Size(rect.width, rect.height), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
                    val movable = selectedEvent >= 0 && selectedString >= 0 && document.events.getOrNull(selectedEvent)?.frets?.getOrNull(selectedString) != null
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioOption("Allow adding notes", addNoteMode, onClick = {
                                addNoteMode = !addNoteMode
                                if (addNoteMode) markerPlacement = null
                            })
                        }
                        OutlinedButton(onClick = {
                            markerPlacement = if (markerPlacement == MarkerPlacement.BAR) null else MarkerPlacement.BAR
                            addNoteMode = false
                        }) { Text(if (markerPlacement == MarkerPlacement.BAR) "Place bar line…" else "Place bar line") }
                        OutlinedButton(onClick = {
                            markerPlacement = if (markerPlacement == MarkerPlacement.REPEAT_START) null else MarkerPlacement.REPEAT_START
                            addNoteMode = false
                        }) { Text(if (markerPlacement == MarkerPlacement.REPEAT_START) "Place repeat start…" else "Repeat start") }
                        OutlinedButton(onClick = {
                            markerPlacement = if (markerPlacement == MarkerPlacement.REPEAT_END) null else MarkerPlacement.REPEAT_END
                            addNoteMode = false
                        }) { Text(if (markerPlacement == MarkerPlacement.REPEAT_END) "Place repeat end…" else "Repeat end") }
                        document.events.getOrNull(selectedBarEvent)?.takeIf { it.repeatEndAfter }?.let { repeatEvent ->
                            RepeatCountDragControl(repeatEvent.repeatCount) { delta -> onAdjustRepeatCount(selectedBarEvent, delta) }
                        }
                        if (selectedNotes.isNotEmpty() || selectedRestEvent >= 0) {
                            RadioOption("Rest", selectedRestEvent >= 0) {
                                if (selectedRestEvent >= 0) {
                                    val restoredEventIndex = selectedRestEvent
                                    val restored = document.events.getOrNull(restoredEventIndex)?.frets
                                        ?.mapIndexedNotNull { string, fret -> if (fret != null) NotePosition(restoredEventIndex, string) else null }
                                        .orEmpty()
                                    onRemoveRest(restoredEventIndex)
                                    selectedNotes = restored.toSet()
                                    restored.firstOrNull()?.let { onSelect(it.eventIndex, it.stringIndex) }
                                    selectedRestEvent = -1
                                } else {
                                    val eventIndices = selectedNotes.map { it.eventIndex }.toSet()
                                    val firstEvent = eventIndices.minOrNull()
                                    if (firstEvent != null) {
                                        val event = document.events[firstEvent]
                                        val units = event.frets.indices.mapNotNull { string ->
                                            if (event.frets[string] == null) null
                                            else event.durationUnits.getOrNull(string)
                                                ?: event.lengthCodes.getOrNull(string)?.let { durationUnitsForLengthCode(it, document.divisions) }
                                                ?: document.divisions
                                        }.maxOrNull() ?: document.divisions
                                        selectedLengthCode = lengthCodeForUnits(units, document.divisions) ?: 5
                                        onConvertToRest(eventIndices)
                                        selectedNotes = emptySet()
                                        selectedBarEvent = -1
                                        selectedRestEvent = firstEvent
                                        onSelect(-1, -1)
                                    }
                                }
                            }
                        }
                        Box {
                            OutlinedButton(
                                onClick = { showLengthMenu = true },
                                modifier = Modifier.semantics { contentDescription = "Note duration: ${legacyLengthLabel(selectedLengthCode)}" }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DurationGlyphIcon(selectedLengthCode, Modifier.size(24.dp, 22.dp))
                                    Text("▾")
                                }
                            }
                            DropdownMenu(expanded = showLengthMenu, onDismissRequest = {
                                showLengthMenu = false
                                durationMenuTargets = emptySet()
                            }) {
                                (1..12).forEach { code ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                Modifier.fillMaxWidth().semantics { contentDescription = legacyLengthLabel(code) },
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                DurationGlyphIcon(code, Modifier.size(28.dp, 24.dp))
                                                when (code) {
                                                    1 -> Text("1", color = muted, fontSize = 12.sp)
                                                    3 -> Text("2", color = muted, fontSize = 12.sp)
                                                    5 -> Text("4", color = muted, fontSize = 12.sp)
                                                    7 -> Text("8", color = muted, fontSize = 12.sp)
                                                }
                                            }
                                        },
                                        onClick = {
                                        selectedLengthCode = code
                                        val targets = durationMenuTargets.ifEmpty { selectedNotes.ifEmpty {
                                            if (movable) setOf(NotePosition(selectedEvent, selectedString)) else emptySet()
                                        } }
                                        if (selectedRestEvent >= 0) onSetRestLength(selectedRestEvent, code)
                                        else if (targets.isNotEmpty()) onSetLength(targets, code)
                                        durationMenuTargets = emptySet()
                                        showLengthMenu = false
                                    })
                                }
                            }
                        }
                        if (movable) OutlinedButton(onClick = onDelete) { Text("Delete selected") }
                        if (selectedBarEvent >= 0) OutlinedButton(onClick = {
                            onRemoveBar(selectedBarEvent)
                            selectedBarEvent = -1
                        }) { Text("Remove selected marker") }
                        // Keep the direct string-move actions alongside drag-and-drop. Dragging is
                        // useful, but the explicit pitch-preserving choices are often easier to
                        // understand and more precise, especially on a trackpad or touchscreen.
                        if (selectedNotes.size > 1) {
                            OutlinedButton(onClick = { selectedNotes = onMoveDirection(selectedNotes, 1) }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) { Text("↓ Move") }
                            OutlinedButton(onClick = { selectedNotes = onMoveDirection(selectedNotes, -1) }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) { Text("↑ Move") }
                        }
                    }
                    if (selectedNotes.isNotEmpty() || selectedRestEvent >= 0 || selectedBarEvent >= 0) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val selectedMarker = document.events.getOrNull(selectedBarEvent)
                            val selectionLabel = when {
                                selectedRestEvent >= 0 -> "Rest selected"
                                selectedBarEvent < 0 -> "${selectedNotes.size} selected"
                                selectedMarker?.repeatStartBefore == true && selectedMarker.repeatEndAfter -> "Repeat markers selected"
                                selectedMarker?.repeatStartBefore == true -> "Repeat start selected"
                                selectedMarker?.repeatEndAfter == true -> "Repeat end selected"
                                else -> "Bar line selected"
                            }
                            Text(selectionLabel, fontSize = 12.sp, color = muted)
                            TextButton(onClick = {
                                selectedNotes = emptySet()
                                selectedRestEvent = -1
                                selectedBarEvent = -1
                                onSelect(-1, -1)
                            }) { Text("Clear selection") }
                        }
                    }
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showFretTools = !showFretTools }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(if (showFretTools) "Fret range & transpose ▴" else "Fret range & transpose ▾")
                    }
                }
                if (showFretTools) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Fret range", fontSize = 13.sp, color = ink, fontWeight = FontWeight.Medium)
                        OutlinedTextField(minimumFret, { value ->
                            minimumFret = value.filter(Char::isDigit).take(2)
                            applyFretLimitEdit(GroupFretOperation.RAISE_MINIMUM, minimumFret)
                        }, Modifier.width(84.dp), label = { Text("Min") }, singleLine = true)
                        Text("to", color = muted)
                        OutlinedTextField(maximumFret, { value ->
                            maximumFret = value.filter(Char::isDigit).take(2)
                            applyFretLimitEdit(GroupFretOperation.LOWER_MAXIMUM, maximumFret)
                        }, Modifier.width(84.dp), label = { Text("Max") }, singleLine = true)
                        Text("Transpose song", fontSize = 13.sp, color = ink, fontWeight = FontWeight.Medium)
                        OutlinedTextField(transposeSemitones, { raw ->
                            transposeSemitones = raw.filterIndexed { index, char -> char.isDigit() || index == 0 && char == '-' }.take(3)
                        }, Modifier.width(112.dp), label = { Text("Semitones", maxLines = 1) }, singleLine = true)
                        Button(
                            enabled = transposeSemitones.toIntOrNull()?.let { it in -48..48 && it != 0 } == true,
                            onClick = {
                                val result = onGroupTransform(GroupFretRequest(GroupFretOperation.TRANSPOSE, minimumFret.toIntOrNull() ?: -1, maximumFret.toIntOrNull() ?: -1, transposeSemitones.toIntOrNull() ?: 999))
                                groupTransformMessage = result.message
                                if (result.applied) selectedNotes = emptySet()
                            }
                        ) { Text("Transpose") }
                    }
                    if (groupTransformMessage.isNotBlank()) {
                        Text(groupTransformMessage, fontSize = 12.sp, color = if (groupTransformMessage.contains("Could not") || groupTransformMessage.contains("must be") || groupTransformMessage.contains("Select notes")) error else muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(Modifier.size(18.dp)) {
            val radius = size.minDimension / 2f - 1.dp.toPx()
            drawCircle(if (selected) green else muted, radius, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            if (selected) drawCircle(green, radius = radius * 0.52f)
        }
        Text(label, fontSize = 13.sp, color = ink)
    }
}

@Composable
private fun RepeatCountDragControl(repeatCount: Int, onAdjust: (Int) -> Unit) {
    val density = LocalDensity.current
    val latestOnAdjust = rememberUpdatedState(onAdjust)
    Box(
        Modifier
            .background(surfaceRaised, RoundedCornerShape(50))
            .pointerInput(Unit) {
                val step = with(density) { 24.dp.toPx() }
                var remainder = 0f
                detectHorizontalDragGestures(
                    onDragStart = { remainder = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        remainder += amount
                        while (remainder >= step) {
                            latestOnAdjust.value(1)
                            remainder -= step
                        }
                        while (remainder <= -step) {
                            latestOnAdjust.value(-1)
                            remainder += step
                        }
                    }
                )
            }
            .semantics { contentDescription = "Repeat count $repeatCount; drag left or right to adjust" }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("↔", color = green, fontSize = 15.sp)
            Text("×$repeatCount", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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

private val DURATION_DRAG_CODES = listOf(9, 7, 5, 3, 1) // Sixteenth through whole note.

private fun currentLengthCode(document: TabDocument, position: NotePosition): Int =
    document.events.getOrNull(position.eventIndex)?.let { event ->
        event.lengthCodes.getOrNull(position.stringIndex)
            ?: event.durationUnits.getOrNull(position.stringIndex)?.let { lengthCodeForUnits(it, document.divisions) }
    } ?: 5

private fun durationFraction(code: Int): String = when (code) {
    1 -> "1/1"
    2 -> "3/2"
    3 -> "1/2"
    4 -> "3/4"
    5 -> "1/4"
    6 -> "3/8"
    7 -> "1/8"
    8 -> "3/16"
    9 -> "1/16"
    10 -> "3/32"
    11 -> "1/32"
    12 -> "3/64"
    else -> "1/4"
}

private fun nearestNoteAt(document: TabDocument, position: Offset, systemHeightPx: Float, leftPx: Float, columnGapPx: Float, columnsPerSystem: Int, rowTopPx: Float, rowGapPx: Float, hitRadiusPx: Float): NotePosition? {
    var closest: NotePosition? = null
    var closestDistance = Float.POSITIVE_INFINITY
    document.events.forEachIndexed { eventIndex, event ->
        val system = eventIndex / columnsPerSystem
        val local = eventIndex % columnsPerSystem
        event.frets.forEachIndexed { stringIndex, fret ->
            if (fret != null) {
                val x = leftPx + columnGapPx * (local + 1)
                val y = system * systemHeightPx + rowTopPx + rowGapPx * stringIndex
                val dx = position.x - x
                val dy = position.y - y
                val distance = dx * dx + dy * dy
                if (distance < closestDistance) {
                    closestDistance = distance
                    closest = NotePosition(eventIndex, stringIndex)
                }
            }
        }
    }
    return closest?.takeIf { closestDistance <= hitRadiusPx * hitRadiusPx }
}

private fun lengthCodeForUnits(units: Int, divisions: Int): Int? = (1..12).firstOrNull { code ->
    val expected = when (code) {
        1 -> divisions * 4
        2 -> divisions * 6
        3 -> divisions * 2
        4 -> divisions * 3
        5 -> divisions
        6 -> divisions * 3 / 2
        7 -> divisions / 2
        8 -> divisions * 3 / 4
        9 -> divisions / 4
        10 -> divisions * 3 / 8
        11 -> divisions / 8
        else -> divisions * 3 / 16
    }
    expected == units
}

@Composable
private fun DurationGlyphIcon(code: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawDurationGlyph(code, Offset(size.width / 2f, size.height - 4.dp.toPx()), green, 1.dp.toPx())
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMoveArrows(center: Offset, noteRadius: Float, color: Color) {
    val gap = 2.dp.toPx()
    val arrowLength = 5.dp.toPx()
    val headLength = 2.5.dp.toPx()
    val halfHeadWidth = 1.8.dp.toPx()
    val stroke = 1.4.dp.toPx()
    listOf(Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f)).forEach { direction ->
        val start = center + direction * (noteRadius + gap)
        val tip = start + direction * arrowLength
        val back = tip - direction * headLength
        val perpendicular = Offset(-direction.y, direction.x) * halfHeadWidth
        drawLine(color, start, tip, strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, tip, back + perpendicular, strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, tip, back - perpendicular, strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDurationGlyph(code: Int, headCenter: Offset, color: Color, unit: Float) {
    val headWidth = 6f * unit
    val headHeight = 4f * unit
    val head = androidx.compose.ui.geometry.Rect(
        headCenter.x - headWidth / 2f,
        headCenter.y - headHeight / 2f,
        headCenter.x + headWidth / 2f,
        headCenter.y + headHeight / 2f
    )
    val openHead = code <= 4
    drawOval(
        color,
        topLeft = Offset(head.left, head.top),
        size = androidx.compose.ui.geometry.Size(head.width, head.height),
        style = if (openHead) androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f * unit) else androidx.compose.ui.graphics.drawscope.Fill
    )
    if (code >= 3) {
        val stemX = head.right - 0.5f * unit
        val stemTop = headCenter.y - 15f * unit
        drawLine(color, Offset(stemX, headCenter.y), Offset(stemX, stemTop), strokeWidth = 1.4f * unit)
        val flags = ((code - 5) / 2).coerceIn(0, 3)
        repeat(flags) { index ->
            val flagTop = stemTop + index * 3.5f * unit
            val flag = Path().apply {
                moveTo(stemX, flagTop)
                cubicTo(stemX + 6f * unit, flagTop + 1f * unit, stemX + 6f * unit, flagTop + 4f * unit, stemX + 2f * unit, flagTop + 6f * unit)
            }
            drawPath(flag, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.3f * unit))
        }
    }
    if (code in 2..12 && code % 2 == 0) {
        drawCircle(color, radius = 1.2f * unit, center = Offset(head.right + 3f * unit, headCenter.y))
    }
}

/** Draw a staff-style rest mark centered at the event's single time position. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRestGlyph(code: Int, center: Offset, color: Color, unit: Float) {
    if (code in 1..4) {
        val y = center.y + if (code <= 2) 1f else -4f * unit
        drawRect(color, Offset(center.x - 4f * unit, y), androidx.compose.ui.geometry.Size(8f * unit, 3f * unit))
        if (code == 2 || code == 4) drawCircle(color, radius = 1.5f * unit, center = Offset(center.x + 7f * unit, center.y - 1f * unit))
        return
    }
    val spine = Path().apply {
        moveTo(center.x + 2f * unit, center.y - 9f * unit)
        cubicTo(center.x - 5f * unit, center.y - 8f * unit, center.x + 4f * unit, center.y - 4f * unit, center.x - 1f * unit, center.y - 1f * unit)
        cubicTo(center.x - 5f * unit, center.y + 2f * unit, center.x + 4f * unit, center.y + 5f * unit, center.x - 2f * unit, center.y + 9f * unit)
        lineTo(center.x - 4f * unit, center.y + 12f * unit)
    }
    drawPath(spine, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    val flags = when (code) {
        7, 8 -> 1
        9, 10 -> 2
        11, 12 -> 3
        else -> 0
    }
    repeat(flags) { index ->
        val y = center.y - 6f * unit + index * 4.5f * unit
        val flag = Path().apply {
            moveTo(center.x + 1f * unit, y)
            cubicTo(center.x + 7f * unit, y + 1f * unit, center.x + 5f * unit, y + 5f * unit, center.x + 2f * unit, y + 6f * unit)
        }
        drawPath(flag, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
    if (code in 2..12 && code % 2 == 0) {
        drawCircle(color, radius = 1.5f * unit, center = Offset(center.x + 7f * unit, center.y - 1f * unit))
    }
}

@Composable
private fun ChordPage(tuning: List<Int>, database: ChordDatabase, databaseLoaded: Boolean, naming: ChordNamingSystem, namingOverride: ChordNamingSystem?, onNamingOverride: (ChordNamingSystem?) -> Unit) {
    var selectedFrets by remember(tuning) { mutableStateOf(List<Int?>(tuning.size) { null }) }
    var micPitchGuesses by remember(tuning) { mutableStateOf(emptyList<Int>()) }
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
        GuitarInputPanel(GuitarInputMode.CHORD_GUESS, tuning, naming) { pitches, rankedGuess ->
            micPitchGuesses = pitches
            rankedGuess?.let { chordQuery = it.name(naming) }
        }
        ChordBuilderCard(tuning, selectedFrets, selectedPitchClasses, detectedChord, chordQuery, { chordQuery = it }, naming, namingOverride, onNamingOverride, micPitchGuesses,
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
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("${it.name(naming)} · ${it.pitchClasses.sorted().joinToString(" · ") { pc -> noteName(pc, naming) }}", fontSize = 18.sp, color = ink, fontWeight = FontWeight.SemiBold)
                    when {
                        !databaseLoaded -> Text("Loading shapes…", fontSize = 13.sp, color = muted)
                        voicings.isEmpty() -> Text("No built-in fingering for this chord.", fontSize = 13.sp, color = muted)
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
    micPitchGuesses: List<Int>,
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
    Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp)) {
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
                Text("${selectedFrets.count { it != null }} / ${tuning.size} strings", fontSize = 12.sp, color = muted)
                TextButton(onClick = { showInstructions = !showInstructions }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(if (showInstructions) "Hide help" else "How to", fontSize = 12.sp)
                }
            }
            if (showInstructions) {
                Text("Tap a fret for each string. Fret 0 is open; tap × to mute a string. A matching chord name appears below.", fontSize = 12.sp, color = muted)
            }
            Text(
                if (micPitchGuesses.isEmpty()) "Mic pitch guesses · none detected" else
                    "Mic pitch guesses · orange · ${micPitchGuesses.sorted().joinToString(" · ") { "${noteName(it, naming)} (${it})" }}",
                fontSize = 11.sp,
                color = if (micPitchGuesses.isEmpty()) muted else Color(0xFFE6A34C)
            )
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
                        drawText(textMeasurer, fret.toString(), Offset(x - 4.dp.toPx(), 3.dp.toPx()), style = TextStyle(color = muted, fontSize = 10.sp))
                    }
                    for (string in tuning.indices) {
                        val y = rowGap * (string + 1)
                        drawLine(line, Offset(labelWidth, y), Offset(gridRight, y), 1.4f)
                        drawText(textMeasurer, stringNames.getOrElse(string) { "S${string + 1}" }, Offset(34.dp.toPx(), y - 8.dp.toPx()), style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        val activeFret = selectedFrets[string]
                        val stringMarker = activeFret?.let { fret -> noteName(tuning[string] + fret, naming) } ?: "×"
                        drawText(textMeasurer, stringMarker, Offset(8.dp.toPx(), y - 9.dp.toPx()), style = TextStyle(color = if (activeFret == null) error else green, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        for (fret in 0..12) {
                            val x = labelWidth + (fret + 1f) * cellWidth
                            drawLine(if (fret == 0) ink else line, Offset(x, y - rowGap * 0.42f), Offset(x, y + rowGap * 0.42f), if (fret == 0) 2.4f else 1f)
                        }
                        micPitchGuesses.forEach { midi ->
                            val fret = midi - tuning[string]
                            if (fret in 0..12) {
                                val x = labelWidth + (fret + 0.5f) * cellWidth
                                drawCircle(Color(0xFFE6A34C), 11.dp.toPx(), Offset(x, y))
                                drawText(textMeasurer, noteName(midi, naming), Offset(x - 8.dp.toPx(), y - 6.dp.toPx()), style = TextStyle(color = paper, fontSize = 8.sp, fontWeight = FontWeight.Bold))
                            }
                        }
                        selectedFrets[string]?.let { fret ->
                            val x = labelWidth + (fret + 0.5f) * cellWidth
                            drawCircle(green, 13.dp.toPx(), Offset(x, y))
                            drawText(textMeasurer, fret.toString(), Offset(x - 4.dp.toPx(), y - 7.dp.toPx()), style = TextStyle(color = paper, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(chord?.name(naming) ?: if (pitchClasses.isEmpty()) "Choose notes above" else "No common chord match yet", color = if (chord != null) green else ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (pitchClasses.isNotEmpty()) Text(pitchClasses.sorted().joinToString(" · ") { noteName(it, naming) }, color = muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear") }
            }
            if (possibilities.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("Also recognized:", fontSize = 12.sp, color = muted)
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
    Card(Modifier.width(310.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = surfaceRaised), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(132.dp).height(142.dp)) {
                val left = 19.dp.toPx(); val right = size.width - 6.dp.toPx()
                val top = 23.dp.toPx(); val bottom = size.height - 15.dp.toPx()
                val dx = (right - left) / 5f; val dy = (bottom - top) / 4f
                for (i in 0..5) drawLine(line, Offset(left + dx * i, top), Offset(left + dx * i, bottom), 1.2f)
                for (i in 0..4) drawLine(if (voicing.baseFret == 1 && i == 0) ink else line, Offset(left, top + dy * i), Offset(right, top + dy * i), if (voicing.baseFret == 1 && i == 0) 2.5f else 1.2f)
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
                            if (finger > 0) drawText(textMeasurer, finger.toString(), Offset(x - 3.dp.toPx(), y - 5.dp.toPx()), style = TextStyle(color = paper, fontSize = 8.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
                listOf("E", "A", "D", "G", noteName(11, naming), "e").forEachIndexed { column, label ->
                    drawText(textMeasurer, label, Offset(left + dx * column - 3.dp.toPx(), bottom + 2.dp.toPx()), style = TextStyle(color = muted, fontSize = 8.sp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(voicing.label, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Fingers e B G D A E", color = muted, fontSize = 9.sp)
                Text(voicing.fingers.joinToString(" ") { if (it == 0) "·" else it.toString() }, color = green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Frets e B G D A E", color = muted, fontSize = 9.sp)
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
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp)) {
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
                        drawLine(line, Offset(0f, y), Offset(size.width, y), 1.4f)
                        for (fret in 0..12) {
                            val pitch = tuning[s] + fret
                            val pitchClass = ((pitch % 12) + 12) % 12
                            if (pitchClass in noteSet) {
                                val x = gapX * (fret + 1)
                                val mainOctave = mainOctaveRoot?.let { pitch >= it.first && pitch < it.first + 12 } == true
                                val noteColor = if (mainOctave) highlight else if (fret == 0) surfaceRaised else green
                                drawCircle(noteColor, 12.dp.toPx(), Offset(x, y))
                                drawText(textMeasurer, noteName(pitchClass, naming), Offset(x - 7.dp.toPx(), y - 6.dp.toPx()), style = TextStyle(color = if (fret == 0 && !mainOctave) ink else paper, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                    for (fret in 0..12) {
                        val x = gapX * (fret + 1)
                        drawText(textMeasurer, fret.toString(), Offset(x - 4.dp.toPx(), size.height - 18.dp.toPx()), style = TextStyle(color = muted, fontSize = 10.sp, fontWeight = FontWeight.Medium))
                    }
                }
                Text("Frets 0–12  ·  tuning follows the loaded tab", fontSize = 12.sp, color = muted)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(10.dp).background(highlight, RoundedCornerShape(50)))
                    val rootDescription = mainOctaveRoot?.let { root ->
                        val stringName = listOf("e", "B", "G", "D", "A", "E").getOrElse(root.second) { "S${root.second + 1}" }
                        "$stringName string, fret ${root.third}"
                    } ?: "root position not found"
                    Text("Highlighted octave: ${noteName(key, naming)} through ${noteName((key + 11) % 12, naming)} · starts at $rootDescription", Modifier.weight(1f), fontSize = 12.sp, color = ink)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).background(surfaceRaised, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Text("SCALE NOTES", fontSize = 11.sp, color = green, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text("${noteName(key, naming)} ${scale.name}: " + scale.steps.joinToString(" · ") { noteName((key + it) % 12, naming) }, fontSize = 13.sp, color = ink)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(2.5f)) {
                        Text("CHORDS IN THIS SCALE", fontSize = 11.sp, color = green, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text("Select a chord to see playable shapes", fontSize = 14.sp, color = ink, fontWeight = FontWeight.SemiBold)
                        if (scaleChords.isEmpty()) {
                            Text("No common triads found for this scale.", fontSize = 13.sp, color = muted)
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
                    if (!databaseLoaded) Text("Loading chord shapes…", fontSize = 12.sp, color = muted)
                    else if (scaleChordVoicings.isEmpty()) Text("No built-in fingering for this chord.", fontSize = 12.sp, color = muted)
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
    Card(modifier, colors = CardDefaults.cardColors(containerColor = surfaceRaised), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 10.sp, color = green, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp)); Text(body, fontSize = 13.sp, color = ink)
        }
    }
}

