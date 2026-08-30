# Hekatron Smartsonic

Protocol specification ([PROTOCOL.md](PROTOCOL.md)) and Python reference
decoder for the acoustic status readout of Hekatron Genius Plus / Plus X
smoke alarms. The Android app in `../../android/` contains a Kotlin port of
the decoder.

## Decoder

Python 3.10 or newer and NumPy are required. PCM WAV is read directly. M4A
and other compressed formats additionally require GStreamer with
`gst-launch-1.0`, a suitable demuxer/decoder, `audioconvert`, `audioresample`,
and `wavenc`.

```bash
python3 protocol/smartsonic/smartsonic_decode.py recording.m4a
python3 protocol/smartsonic/smartsonic_decode.py --json recording.wav
python3 protocol/smartsonic/smartsonic_decode.py --all-frames recording.m4a
python3 protocol/smartsonic/smartsonic_decode.py --readout-date 2020-05-20 recording.m4a
```

The readout date matters: the payload contains relative day counters, not
absolute dates. Without `--readout-date`, today's local date is used.

An already recovered frame can be parsed without audio:

```bash
python3 protocol/smartsonic/smartsonic_decode.py \
  --readout-date 2020-05-20 \
  --frame-hex '14 00 83 b4 98 f4 02 05 00 00 ff ff 4c 00 26 07 46 00 00 00 00 ca 5a'
```

## Tests

```bash
python3 -m unittest discover -v -s protocol/smartsonic
```

The suite covers CRC, framing, all 256 byte values through the Hamming codec,
payload fields, the radio extension, and a synthesized 44.1 kHz CPFSK
waveform through the complete audio decoder. If `data/flur.m4a` is present
(not part of the repository), it is decoded end to end as well; a further
recording can be checked by setting `SMARTSONIC_REAL_RECORDING` to its path.
