# Windows laptop microphone recording

`AmGCEm.m4a` is a real guitar recording supplied by the project owner, recorded
with a laptop microphone on Windows. Retained as a cross-platform detector fixture.
The owner reports audible laptop fan noise; this is normal room audio, not an
isolated studio instrument recording.

Approximate owner annotations (seconds from the start):

| Time | Content |
| --- | --- |
| 0–6 | Background noise |
| 6 | Am strumming |
| 10 | G strumming |
| 13 | C strumming |
| 14 | Em strumming |
| Later | Am strumming, then Am |

These are approximate cues, not precise segment boundaries. Do not tune the
detector to the timestamps or assume this one microphone represents all inputs.
Direct file analysis tests pitch/chord detection; playback through speakers into
a microphone additionally tests the capture path and room/speaker response.

`AmGCEm.wav` is the decoded 44,100 Hz, mono, signed 16-bit PCM version of the
original (29.291 seconds), generated with PyAV's audio resampler. Tests use WAV
so neither Windows nor macOS needs an AAC decoder or Python installed.

## Run and inspect

Run `./gradlew :composeApp:desktopTest` (Windows: `gradlew.bat`).
The test writes `composeApp/build/reports/audio/AmGCEm.csv` with per-frame raw
pitches, instantaneous chord, rolling chord and microphone level. It exercises
the shared FFT and rolling ranker with the default raw input threshold, without
root-continuity hints. It does not simulate the UI's confirmed-chord hold logic.

Comparison against the prior detector, using the same decoded audio and test:

| Interior window | Expected | Prior rolling result | Updated rolling result |
| --- | --- | --- | --- |
| 7.5–10.0 s | Am | 23/27 | 22/27 |
| 10.8–13.0 s | G | 16/23 | 23/23 |
| 13.5–14.5 s | C | 11/11 | 11/11 |
| 17.0–18.8 s | Am | 19/19 | 19/19 |

These are one recording's frame counts, not general accuracy estimates. The
first Am has a small regression; later weak decay/noise remains unreliable.
The roughly 14.8–15.8 s section has a strong ~207 Hz G-sharp peak with E/B
evidence, consistent with E major, followed by G-natural/Em evidence around
16 s. Preserve the owner's Em annotation as approximate; do not assert Em
through the entire interval.

The changes are deliberately separate: desktop input-gate calibration is in
`GuitarInputGate.kt`; spectral peak validation/DC removal is in the shared
`GuitarFftAnalyzer.kt`; omitted-fifth ranking is in `TabModel.kt`. The WAV test
and synthetic chord tests can be run on macOS before accepting shared changes.

## Single notes

`SingleNotes.m4a` is a second Windows laptop-microphone recording supplied by
the owner, with `SingleNotes.wav` decoded to the same PCM format (35.243 s).
Approximate owner cues: open low E begins around 8 s, repeated open A around
15 s, a melody on/from the fifth string starts around 21 s and continues to
around 31.5 s. The suggested C-minor key is uncertain, so individual melody
pitches are not treated as ground truth.

`RecordedSingleNoteTest` writes `build/reports/audio/SingleNotes.csv`. With the
default raw gate, the original detector and revised detector both return E2 in
60/64 frames from 8–14 s (remaining frames unvoiced). Open A from 15–21 s is
64/65 originally and 63/65 revised; the remaining estimates are A-sharp.
Both reject all 72 background frames before 7 s. This does **not** reproduce
the owner's live E2 being recorded as D-string fret 4/G-string open/A-string
fret 3; the saved Sound Recorder input and live app input may differ.

Single-note analysis now first uses the most recent 93 ms rather than the full
371 ms chord window, with the longer window as an unvoiced fallback. It checks
for a substantially better full-period match before accepting a strong second
harmonic and interpolates period minima. Synthetic tests cover harmonic-rich
guitar pitches, octave mistakes and changes inside the old analysis window.
These tests establish response to note changes, not accuracy on the unlabeled
real melody or the UI's attack-trigger timing.

The subsequent live-trigger fix runs tab capture in 1024-sample hops (~23 ms),
uses short-hop energy for pluck detection, and accepts stable pitch changes
without demanding a pluck. `SingleNoteRecorderTest` exercises actual synthesized
audio at four notes/second, including alternating trill pitches, through the
pitch detector and live event trigger. A six-note/second experiment still missed
one note in a 24-note pattern; six notes/second is not a verified capability.
`RecordedSingleNoteTest` additionally writes `SingleNotes-events.csv` from the
live cadence and event trigger, rather than assuming pitch estimates are events.

The desktop tab listener now also offers **Save last 10 s**. To investigate a
live mismatch, save immediately after it occurs; the WAV and CSV go into
`Documents/TablatureAnalyzer-audio-debug` and contain what the app actually
captured and its detected pitches.

Diagnostic CSVs also include the active gate, short-hop RMS and emitted tab MIDI
event, so pitch detection can be distinguished from note insertion. Numbers use
locale-independent formatting.

## Live Windows investigation (2026-11-01)

**Later outcome:** disabling Realtek microphone Audio enhancements (previously
Device Default Effects) restored usable guitar input during in-app speaker replay.
The live UI detected open E2 and A2 and inserted much of the melody. The owner
reported many almost-correct notes after increasing speaker playback to 100%.
They subsequently returned speaker volume to 32%. Accuracy is improved, not
perfect. See `WINDOWS_HANDOFF.md` for shared-code/Mac review and remaining gaps.
The observations below describe the earlier failing captures, before this fix.

Actual speaker-to-microphone replay of this fixture at 3x sample gain reproduced
the failure: the running tab initially added only three wrong notes. The selected
input was then changed from Default to the explicit Realtek microphone. Separate
Java Sound and Windows WASAPI RAW captures both contained long near-silent
stretches, often around -86 dBFS. The microphone's Java port volume reported 89%.
RAW capture alone did not resolve the loss; Windows/vendor processing is a
suspect, not a confirmed cause. This is **not fixed or live-validated** by the
offline fixture results.

Lowering the gate from 0.0015 to 0.00005 in a replay of the RAW mic recording
increased events from 10 to 24, but many were wrong; do not treat that as a fix.
The production gate was not lowered. Desktop locking and denied Settings access
then blocked inspection of microphone enhancements and further UI verification.

To inspect another actual mic capture, set `TABLATURE_DIAGNOSTIC_WAV` to the
absolute path of a 44.1 kHz mono PCM WAV and run desktop tests filtered to
`MicrophoneDiagnosticTest`. The opt-in diagnostic writes
`build/reports/audio/microphone-diagnostic.csv`, comparing candidate pitches and
events with the normal and experimental quiet gates. It is skipped without the
environment variable.
