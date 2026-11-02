# Windows audio adjustments — Mac handoff

Branch: `windows_adjustments`. No pull request requested.

## What was actually established

- Offline pitch estimates are not proof of successful live tab recording.
- Windows Realtek microphone **Audio enhancements / Device Default Effects**
  suppressed most guitar audio during speaker-to-mic replay. After switching
  that microphone setting to **Off**, an in-app replay detected open E2, open
  A2 and much of the melody, and inserted many notes into the visible tab.
  Before/after captures changed from roughly -85 dBFS to approximately -44 dBFS
  during played notes. Raw WASAPI capture alone had not solved the problem.
- The owner subsequently reported many almost-correct notes while testing at
  Windows speaker volume 100%. They later reduced volume to 32% themselves.
  Speaker volume and microphone effects are OS settings, not repository changes.
- Some wrong pitches, duplicate events and missed transitions remain. Do not
  describe six notes/second or live chord accuracy as fully verified. Four
  notes/second and unplucked trills pass the synthetic full-pipeline tests.
- Live chord comparison was interrupted during test-harness/hot-reload work.
  Recorded chord fixtures pass offline assertions; that is a separate result.

## Code groups to review independently on Mac

- `GuitarFftAnalyzer.kt`: spectral peaks must support their own fundamentals;
  remove DC before FFT; shorten single-note analysis with longer fallback;
  interpolate periodicity minima and guard octave correction; adapt decimation
  to actual input rate. Shared code, so regression-check it on Mac.
- `TabModel.kt`: penalize omitted-fifth extensions when ranking audio chords,
  to avoid preferring C6(drop5) over equivalent Am.
- Desktop capture: negotiate PCM input rate/channel count, decode the strongest
  stereo channel to avoid phase cancellation, and pass the actual sample rate
  to analysis and diagnostic WAV output. Refresh inputs and retain selection.
- `SingleNoteRecorder.kt`: use ~23 ms capture hops, short-hop attack level,
  stable pitch-change events independent of plucks, and shorter cooldowns.
- Input controls: live software gain up to approximately +36 dB and a raw noise
  gate, saved per input. Calibration gain now changes the effective signal gate.
  Reset restores gain 1 and the default signal gate. Software gain cannot undo
  audio already removed by Windows microphone processing.
- `WavePlayback.desktop.kt`: stream a selected WAV from the app, on a background
  thread, independently of MIDI and listening. The development checkout initially
  offers the SingleNotes fixture when no WAV preference is saved.
- MIDI output: explicitly connect the sequencer and own receiver/device cleanup;
  prefer Windows' internal GS/Mapper output on Windows, retain software synthesis
  elsewhere. Audible MIDI playback still needs verification on working hardware.

## Tests and recordings

`./gradlew :composeApp:desktopTest` passes. The opt-in microphone diagnostic test
is skipped without `TABLATURE_DIAGNOSTIC_WAV`. Original Windows laptop-mic M4A
recordings and decoded WAVs are under `composeApp/src/desktopTest/resources/audio`.
See that directory's README for approximate labels and measured limitations.
The recordings include laptop fan noise. String/fret placement cannot be known
from pitch alone; multiple guitar positions are an intended part of this app.

## Development control and hot reload

Use `:composeApp:hotRunDesktopAsync --mainClass=org.tablatureanalyzer.app.MainKt`
and `:composeApp:reload`. Keep one app instance. The old hot-reload version can
break existing suspended coroutines or captured callbacks when their structure
changes; a clean single-instance restart was necessary after those failures.

The local test harness is enabled only with `compose.reload.argfile` present.
It watches the app working directory's ignored `build/audio-test.properties`:

```properties
id=unique-run-id
page=0
input=Microphone (2- Realtek(R) Audio)
gain=4
noise=0.0038
wav=C:/absolute/path/to/SingleNotes.wav
```

Page 0 is single-note tab recording; page 2 is chord guessing. One command plays
one WAV while listening, saves 10-second mic tails at 14/21/33 seconds, and stops
its test capture afterward. Logs and actual emitted UI events go to
`build/reports/audio/live-<id>*`. Use a blank `id` to disable automatic tests and
remove `page` to stop forcing navigation. Current local commands are disabled.
Do not run this harness while the user is manually testing.

## System incident

Realtek disappeared from Windows during the session and system-wide playback
also stopped. The cause was not established. Audio-service restart and PnP
rescan attempts were denied; no drivers were deliberately changed or disabled.
Reboot restored audio. Avoid multiple capture processes and do not claim this
incident was proved unrelated to testing. The final app was left running with
automatic test commands disabled for the owner's manual testing.
