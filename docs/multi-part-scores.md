# Multi-part score support

The editable score model stores each instrument as an independent `TabPart`. A part owns its name, tuning, MIDI program, event sequence, meter, and tempo metadata. The editor shows one selected part at a time; switching parts does not discard the others. The editable-project format and MusicXML round-trip every part, and desktop playback sends each part through its own MIDI channel.

PowerTab imports must build on this model. The PowerTab v4 container has separate instrument blocks; each block must become its own part before import is considered complete. Staff voices inside an instrument block are combined by shared position into that instrument's events, retaining distinct simultaneous string notes. An importer must never silently throw away an instrument block.

## Current limits

- The tab editor displays one selected part at a time; synchronized multi-staff display is a later UI step.
- The current event model aligns simultaneously played notes within each part but does not yet store arbitrary absolute onset offsets independent of event order. PowerTab importer accuracy for irregular spacing needs to be assessed against real files.
- Drum-kit mapping and per-part MIDI channel selection controls are not yet exposed in the UI.
- MIDI playback supports up to 15 melodic parts because channel 10 is reserved for percussion.

These limits are explicit so the importer does not claim lossless score conversion prematurely.
