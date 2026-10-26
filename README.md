# Tablature Analyzer

A Kotlin Multiplatform reimplementation of my personal TablatureAnalyzer project, which I began in 1996 using an early Java beta and AWT. It runs on desktop and Android.

## Run

Requires JDK 17. Desktop:

```sh
./gradlew :composeApp:run
```

GitHub Actions builds Apple silicon and Intel DMGs on pushes to `main`. Download them from the run's **Artifacts** section under **Actions → Build macOS DMG**.

Android builds require Android SDK platform 35:

```sh
./gradlew :androidApp:assembleDebug
```

## Current functionality

- Import ASCII tablature and the app's MusicXML subset.
- Add and delete notes and tab positions; edit legacy note lengths; add, move, and remove measure bars.
- Select notes with a rectangle, drag individual notes between strings, or move selected groups while preserving pitch.
- Set tempo, export MusicXML, and play MIDI on desktop at 100%, 50%, or 25% speed.
- Analyze chord names from selected notes and explore scales.

The MusicXML reader and writer support the fields used by this app. MIDI playback is available on desktop.

# Screenshots
<img width="1610" height="761" alt="image" src="https://github.com/user-attachments/assets/6d0ba3c4-74c7-42d3-850f-2fe2eec3433d" />
<img width="1363" height="457" alt="image" src="https://github.com/user-attachments/assets/ed5d59af-1fa5-4229-9bbd-5b397638ede9" />
<img width="1164" height="855" alt="image" src="https://github.com/user-attachments/assets/fe635d0d-3175-457f-8c4c-d12de7307671" />
<img width="1158" height="986" alt="image" src="https://github.com/user-attachments/assets/937e3b5c-7140-4a4e-9015-24e21fc7e583" />
