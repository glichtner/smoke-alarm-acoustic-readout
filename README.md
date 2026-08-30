# Smoke detector acoustic readout

Specifications of the acoustic status protocols of two smoke detector
families, Python reference decoders for both, and an Android app for landlords
that reads the detectors out with the phone microphone, keeps an inspection
log per detector, and produces the reports required for the annual
inspection.

| Manufacturer / models | Protocol | Trigger on the detector | Documentation |
|---|---|---|---|
| Ei Electronics Ei650, Ei650i | AudioLINK+ (FSK 5.5/6.8 kHz, 100 bit/s, CRC-16/CCITT-FALSE) | press the test button three times within five seconds | [protocol/audiolink/PROTOCOL.md](protocol/audiolink/PROTOCOL.md) |
| Hekatron Genius Plus, Plus X (optionally with radio module) | Smartsonic (FSK/biphase around 4.45 kHz, ~84 bit/s, Hamming FEC, CRC-16/CMS) | hold the test button for five seconds | [protocol/smartsonic/PROTOCOL.md](protocol/smartsonic/PROTOCOL.md) |

Both protocols transmit the detector's identity directly: AudioLINK+ carries a
32-bit detector ID, Smartsonic the 32-bit serial number (plus a separate
radio-module serial where fitted). No hashing, no server-side reference numbers.

## Repository layout

```text
protocol/audiolink/    AudioLINK+ specification, Python reference decoder, tests
protocol/smartsonic/   Smartsonic specification, Python reference decoder, tests
android/               Android app (Kotlin, Jetpack Compose, Room, WorkManager)
data/                  local recordings used by the tests — not part of the repo
```

## Reference decoders (Python)

Python 3.10 or newer and NumPy are required (`python3 -m pip install -r
requirements.txt`). PCM WAV is read directly; M4A/3GP input additionally
needs GStreamer (`gst-launch-1.0` with AAC demux/decoder plugins,
`audioconvert`, `audioresample`, `wavenc`).

```bash
python3 protocol/audiolink/audiolink_decode.py "data/sg Schlafzimmer.m4a"
python3 protocol/smartsonic/smartsonic_decode.py --readout-date 2026-08-30 data/flur.m4a
python3 protocol/smartsonic/smartsonic_decode.py --frame-hex '14 00 83 b4 98 f4 02 05 00 00 ff ff 4c 00 26 07 46 00 00 00 00 ca 5a'
```

Smartsonic payloads contain relative day counters, so `--readout-date` (the
date of the acoustic readout) matters for date reconstruction; it defaults to
today.

Tests (end-to-end tests against the original recordings are skipped when the
files in `data/` are absent):

```bash
python3 -m unittest discover -v -s protocol/audiolink
python3 -m unittest discover -v -s protocol/smartsonic
```

## Android app

[android/](android/) contains the landlord app. The UI is intentionally in
German — its audience are German landlords, for whom the annual
smoke-detector inspection is a legal obligation.

- **Read out a detector**: one button starts the microphone capture and runs
  both decoders (Kotlin ports of the reference decoders) over the same
  buffer, so the protocol is detected automatically from the signal. For
  known detector IDs/serials the decoded values are appended as a new
  inspection; unknown ones are registered with apartment and room.
- **Verdict**: every inspection is marked *in order* or *problem*. Problems
  are highlighted in red on the scan result, in the overview, in the
  inspection log and in the PDF report. Conditions:
  - Ei650i: sensor fault, battery not green (weak/critical), contamination
    high (raw ≥ 27, i.e. ≥ 8.4 of 10),
    replacement date reached (production + 11 years).
  - Hekatron: battery low, device fault, radio network fault, negative
    contamination forecast, any warranty flag (too old, too dirty, out of
    temperature range, …), radio-module fault/link error/battery low,
    replacement date reached (production + 10 years).
- **Overview**: all detectors grouped by apartment with the due date of the
  next inspection (one year after the last one; green/amber/red).
- **Reminders**: a daily background check (WorkManager) posts a notification
  when detectors are due within 30 days or overdue — also when the app has
  not been opened for months.
- **Inspection log**: tapping a detector shows every recorded inspection
  with the protocol's fields (Ei: battery voltage, sensor, contamination,
  event counters; Hekatron: fault flags, drift state, alarm and removal
  counters, last alarm/self-test dates, warranty flags, radio module).
- **Reports**: PDF inspection report over all detectors (latest inspection
  each, grouped by apartment) and CSV of the full inspection log, via the
  share sheet.
- **Backup / transfer**: JSON export of the complete database and an import
  that merges it (unknown detectors are added, inspections already present
  are skipped) — for moving to another phone.

Build and test (Android SDK 35; set `sdk.dir` in `android/local.properties`):

```bash
cd android
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # decoder tests
```

The decoder tests against real recordings run when `RAUCHMELDER_WAV_DIR`
points to a directory with mono PCM WAV conversions of the files in `data/`:
`audiolink-1.wav`, `audiolink-2.wav` (48 kHz, Ei650i), `smartsonic-44100.wav`,
`smartsonic-48000.wav` (Hekatron Genius Plus). Without it those tests are skipped;
synthesized waveforms still cover both decoders end to end.
