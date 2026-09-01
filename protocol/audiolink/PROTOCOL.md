# Ei Electronics AudioLINK+ (Ei650 / Ei650i) – protocol specification

AudioLINK+ is the acoustic status readout of Ei Electronics Ei650-series
smoke alarms. After the test button has been pressed three times within five
seconds, the alarm's sounder transmits one status frame as a binary
frequency-shift-keyed tone burst. This document specifies the signal, the
frame structure, the payload fields, and implementation details of a
receiver.

Values are given for the format variant referred to as "version 2" by the
manufacturer's software. A legacy AudioLINK variant (20 bit/s Manchester-coded
on/off keying around 3 kHz, 22-byte frames) exists and is not covered here.

## Transmission sequence

One readout consists of the following acoustic sections:

| Section | Description |
|---|---|
| lead-in | two short pulses around 3 kHz, approximately 100 ms apart |
| gap | approximately 200 ms |
| data frame | 328 FSK symbols, approximately 3.28 s |
| lead-out | two short pulses around 3 kHz, approximately 100 ms apart, about 30 ms after the last symbol |
| ringdown | sounder decay of roughly 0.3 s |

The frame is transmitted once per readout. A strong component around 3 kHz
is present during the whole data frame and forms a 100 Hz spectral comb
(the symbol rate); it can be used for framing and clock recovery but carries
no bit information.

## Physical layer

| Property | Value |
|---|---|
| modulation | binary energy FSK (one of two tones dominant per symbol) |
| logical `1` | tone around 5.5 kHz |
| logical `0` | tone around 6.8 kHz |
| analysis bands | 5.25–5.75 kHz and 6.55–7.05 kHz |
| symbol duration | 10 ms (nominal) |
| data rate | 100 bit/s (nominal) |
| line code | none (direct bits, no Manchester coding) |
| bit order | MSB first within each byte |
| frame length | 328 bits = 41 bytes, about 3.28 s |

The tone frequencies lie within roughly ±20 Hz of the nominal values. The
symbol clock has a tolerance of about ±3 % and must be recovered from the
frame's fixed bits (see *Receiver implementation*).

A suitable per-sample discriminator is

```text
D = (env_5.5k - env_6.8k) / (env_5.5k + env_6.8k + epsilon)
```

with `env_x` the envelope of the respective analysis band. Averaged over the
inner 72.5 % of each symbol (skipping 13.75 % at both edges, where the tone
switches), `D` separates the two symbol classes by more than 8 dB of energy
ratio in clean captures. The absolute level of `D` differs between captures,
so the decision threshold must be derived per frame (two-class clustering),
not fixed.

## Wire framing

The 41 transmitted bytes have this structure:

```text
00 00
72 + 6 payload bytes
72 + 6 payload bytes
72 + 6 payload bytes
72 + 6 payload bytes
72 + 6 payload bytes
72 + CRC_hi + CRC_lo + AA
```

- bytes 0–1 (`00 00`): prefix;
- byte offsets 2, 9, 16, 23, 30, 37: block marker `0x72`, spaced 7 bytes
  (56 symbols) apart;
- the six bytes after each of the first five markers are payload (30 bytes
  in total);
- the two bytes after the sixth marker are the CRC, big endian;
- byte 40 (`0xAA`): end marker.

The marker's seven trailing bits `1110010` (`+++--+-` in FSK sign) serve as
the synchronization pattern. In total 72 bits of the frame are fixed
(prefix, six markers, end marker) and available for correlation.

### Canonical form

Receivers normalize a frame to a 34-byte canonical buffer:

```text
AA + payload[30] + CRC_hi + CRC_lo + AA
```

The leading `AA` is inserted synthetically; the trailing `AA` is the wire
end marker. "Canonical offsets" below refer to this buffer; "payload
offsets" start one byte later.

Because the markers are interleaved, a multi-byte field can be split on the
wire while being contiguous canonically, e.g. the detector ID:

```text
wire[29]  wire[30]     wire[31..33]
01        72 marker    25 49 f4
```

## CRC

| Parameter | Value |
|---|---|
| name | CRC-16/CCITT-FALSE |
| width | 16 bits |
| polynomial | `0x1021` |
| initial value | `0xffff` |
| input/output reflected | no / no |
| final XOR | `0x0000` |
| byte order on the wire | big endian |
| coverage | payload bytes 0–29 exactly (canonical 1–30) |
| check value for `123456789` | `0x29b1` |

Frames whose CRC does not match must be discarded.

## Payload layout

Multi-byte numbers are big endian. Time and event counters count in units of
four hours.

| Canonical | Payload | Length | Field |
|---:|---:|---:|---|
| 0 | – | 1 | synthetic `AA` |
| 1–2 | 0–1 | 2 | operating down-counter `C` |
| 3 | 2 | 1 | device type: `01` = Ei650, `02` = Ei650i |
| 4 | 3 | 1 | bits 5..0: raw contamination value; bits 7..6 unused |
| 5 | 4 | 1 | status and battery code |
| 6–8 | 5–7 | 3 | test-button event block |
| 9–11 | 8–10 | 3 | smoke-alarm event block |
| 12–17 | 11–16 | 6 | not used by the Ei650/Ei650i (other event blocks in related models); `00` |
| 18–20 | 17–19 | 3 | low-battery event block |
| 21–23 | 20–22 | 3 | removal event block (count with baseline 1) |
| 24–27 | 23–26 | 4 | detector ID |
| 28–29 | 27–28 | 2 | production date |
| 30 | 29 | 1 | reserved; `00` |
| 31–32 | – | 2 | CRC-16/CCITT-FALSE |
| 33 | – | 1 | end marker `AA` |

### Operating time

`C` starts at `0x5e1a` (24090) and decrements once every four hours:

```text
uptime_hours = (0x5e1a - C) * 4
```

### Event blocks

Each event block is `counter_hi, counter_lo, count`. The counter holds the
value of the operating counter at the time of the most recent event of that
kind. With the current operating counter `C`:

```text
event_age_hours = (event_counter - C) * 4      (only if count > 0)
```

The test-button, smoke-alarm, and low-battery counts are used directly. The
removal count is stored with a baseline of 1:

```text
removals = max(raw_count - 1, 0)
```

### Contamination

```text
dust_raw = payload[3] & 0x3f
dust     = round_half_even(min(dust_raw / 3.2, 10.0), 1)     # scale 0.0–10.0
```

The value is unavailable when `dust_raw > 32` or when status bit 5 is set.
Raw values of 27 and above (8.4 on the display scale) indicate high
contamination.

### Status byte (payload 4)

| Bits | Meaning |
|---|---|
| 3..0 | battery code (see below) |
| 4 | unassigned for the Ei650/Ei650i |
| 5 | contamination value being calculated / unavailable |
| 6 | unassigned for the Ei650/Ei650i |
| 7 | sensor failure |

### Battery voltage

The battery code indexes this table (volts):

```text
code:  0    1    2    3    4    5    6    7
       2.35 2.38 2.42 2.46 2.50 2.54 2.58 2.63

code:  8    9   10   11   12   13   14   15
       2.67 2.72 2.77 2.82 2.87 2.92 2.98 3.04
```

Classification: codes 9–15 good, 4–8 weak, 0–3 critical.

### Production date

With payload bytes `b27` and `b28`:

```text
day   = b27 >> 3
month = ((b27 & 0x07) << 1) | (b28 >> 7)
year  = 1980 + (b28 & 0x7f)
```

The alarm's end of life is eleven calendar years after the production month.

### Detector ID

Payload bytes 23–26 hold a 32-bit detector identifier. It is displayed as
eight hexadecimal digits in transmission order (e.g. `01 25 49 f4` →
`012549f4`). The ID is transmitted as-is; there is no hashing, checksum
derivation, or numeric conversion. Whether it equals the serial number
printed on the alarm's label is not specified here.

## Receiver implementation

A batch receiver for a captured buffer works as follows:

1. Compute the band envelopes around 5.5 and 6.8 kHz (analytic envelope via
   band-pass, or quadrature demodulation with a ~±250 Hz low-pass) and the
   normalized discriminator `D`. The sounder is rich in harmonics, so the
   second harmonics at 11 kHz (for `1`) and 13.6 kHz (for `0`) carry the same
   bit information in bands that everyday interference such as speech rarely
   reaches; combining the matched harmonic pair - normalized per band by a
   high percentile, and including a harmonic band only when it shows real
   burst activity - makes the discriminator markedly more robust. Higher
   harmonics near typical microphone/codec cutoffs are not mixed in: evidence
   from an unmatched harmonic order can bias one FSK class.
   Include the second harmonic only when both FSK classes show activity. If
   the combined view fails validation, evaluate the matched second-harmonic
   pair and the fundamental pair separately; this handles interference
   confined to one range without changing any decided bits.
2. Keep a prefix sum of `D` so that the mean of `D` over any symbol window
   is available in O(1).
3. Search jointly over frame start and symbol period (10 ms ± 3 %). Score a
   candidate by correlating the 72 fixed frame bits with the symbol means
   (expected `1` positive, expected `0` negative). This score locates the
   frame anywhere in the buffer, including behind handling noise or the
   lead-in pulses, without an energy-based segmentation step.
4. For the best-scoring candidates, refine start and period on a fine grid
   and sample all 328 symbol means.
5. Decide bits twice and keep the better result: (a) against a single global
   threshold from two-means clustering of the symbol means, and (b) against
   per-symbol thresholds anchored at the known marker bits — each 0x72
   marker and the closing 0xAA contain four known one and four known zero
   bits whose feature means give a local threshold, interpolated linearly
   between the anchors. The local thresholds compensate baseline drift of
   the discriminator across the 3.3 s frame (e.g. from in-band background
   noise), which a single global threshold cannot.
6. Assemble 41 bytes MSB first; require the prefix, all six markers, and the
   end marker; extract the payload; accept the frame only if the CRC matches.
   The CRC is used strictly for validation, never to choose or flip payload
   bits. A structurally plausible frame with a failing CRC is discarded.

A live receiver additionally keeps a rolling buffer of at least 12 s (frame
plus lead-in/lead-out plus margin) and repeats the batch search every few
seconds until a frame is accepted; a cheap tone detector on the two FSK
bands (e.g. Goertzel bins) can signal reception to the user and trigger a
decode attempt as soon as a tone burst ends. Because the marker and CRC checks require
72 exact fixed bits plus a 16-bit CRC, the false-accept probability of the
search is negligible even over many candidates.

Any sample rate of 15 kHz or more is suitable; 44.1 and 48 kHz are typical.

## Example frames

Two frames for testing an implementation (CRC-valid; device type Ei650i;
battery code 15 = 3.04 V; contamination 0; production 2025-09-15).

Frame A:

```text
wire:      00 00 | 72 5e 1a 02 00 0f 5e | 72 1a 01 00 00 00 00 |
           72 00 00 00 00 00 00 | 72 00 00 5e 1a 02 01 |
           72 25 49 f4 7c ad 00 | 72 4b 02 aa
canonical: aa | 5e 1a 02 00 0f | 5e 1a 01 | 00 00 00 |
           00 00 00 00 00 00 | 00 00 00 | 5e 1a 02 |
           01 25 49 f4 | 7c ad | 00 | 4b 02 | aa
decoded:   ID 012549f4, uptime 0 h, test-button events 1 (age 0 h),
           smoke alarms 0, low-battery events 0, removals 1 (raw 2)
```

Frame B:

```text
wire:      00 00 | 72 5e 1a 02 00 0f 00 | 72 00 00 00 00 00 00 |
           72 00 00 00 00 00 00 | 72 00 00 5e 1a 01 01 |
           72 25 49 ef 7c ad 00 | 72 91 07 aa
canonical: aa | 5e 1a 02 00 0f | 00 00 00 | 00 00 00 |
           00 00 00 00 00 00 | 00 00 00 | 5e 1a 01 |
           01 25 49 ef | 7c ad | 00 | 91 07 | aa
decoded:   ID 012549ef, uptime 0 h, test-button events 0,
           smoke alarms 0, low-battery events 0, removals 0 (raw 1)
```

## Reserved and unassigned fields

- payload bytes 11–16: not evaluated for the Ei650/Ei650i;
- payload byte 29: reserved;
- status bits 4 and 6: unassigned for the Ei650/Ei650i;
- payload byte 3 bits 7..6: unused.

## Reference decoder

`audiolink_decode.py` (Python 3, NumPy) implements the receiver described
above for PCM WAV input; compressed recordings are converted with GStreamer
when available.

```bash
python3 protocol/audiolink/audiolink_decode.py recording.wav
python3 protocol/audiolink/audiolink_decode.py --json recording.m4a
python3 -m unittest discover -v -s protocol/audiolink
```
