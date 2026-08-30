# Hekatron Smartsonic (Genius Plus / Plus X) – protocol specification

Smartsonic is the acoustic status readout of Hekatron Genius smoke alarms.
After the test button has been held for five seconds, the alarm transmits one
status frame as an audible two-tone frequency-shift-keyed burst around
4.45 kHz. This document specifies the signal, the coding, the frame
structure, the payload fields, and implementation details of a receiver.

## Physical layer

| Property | Value |
|---|---:|
| center frequency | 4,449.1 Hz |
| modulation | two-tone FSK, one tone below and one above the center |
| tone separation | device-dependent, roughly 700–900 Hz (not fixed by the protocol) |
| physical symbol rate | ≈ 83.8 bit/s (11.92–11.94 ms per physical bit) |
| symbol coding | biphase: each physical bit is one tone followed by the other |
| payload rate | ≈ 41.9 bit/s net (Hamming(8,4) FEC) |
| frame | preamble (≈ 286 ms) + Hamming-coded length, payload, CRC |

The receiver does not need to know the tone separation: it mixes the signal
down to the center frequency and evaluates only the sign and order of the
frequency deviation. A transmitter compatible with the reference receiver can
use ±250 Hz deviation with continuous phase (CPFSK).

### Reference demodulation chain

Sampling at 44,100 Hz, mono, 16-bit PCM. Processing in 1,024-sample blocks
or as a batch:

1. complex down-mixing with 4,449.1 Hz to I/Q;
2. IIR low-pass of both branches;
3. decimation by 8 to the working rate of 5,512.5 Hz;
4. IIR band limiting of I/Q;
5. phase/frequency discriminator;
6. median filter of length 5;
7. IIR smoothing, scaling by 1,000, rounding, clamping to ±400;
8. preamble correlation;
9. bit correlation with clock tracking;
10. Hamming decoding, frame assembly, CRC check.

The discriminator is the central two-sample approximation of the phase
derivative, normalized by the instantaneous power:

```text
d[n] = (I[n-1] * (Q[n] - Q[n-2]) - Q[n-1] * (I[n] - I[n-2]))
       ---------------------------------------------------------
                          I[n]^2 + Q[n]^2
```

IIR coefficients (`a` = denominator with `a[0] = 1`, `b` = numerator;
transposed direct form II):

```text
before decimation (I and Q):
a = [1, -1.85716065, 0.86670342]
b = [0.00238569, 0.00477138, 0.00238569]

I/Q after decimation, and again on the discriminator at the end:
a = [1, -1.99194039, 0.99197274]
b = [0.99597828, -1.99195657, 0.99597828]

smoothing after discriminator and median filter:
a = [1, -3.22692923, 3.9658094, -2.19293563, 0.45943954]
b = [0.00033651, 0.00134602, 0.00201903, 0.00134602, 0.00033651]
```

The output is the discriminator sequence at 5,512.5 Hz with values in
[−400, 400]; all sample counts below refer to this rate.

## Symbol and bit coding

Nominal physical bit period at the working rate:

| Preamble variant | Samples per bit | Duration | Rate |
|---|---:|---:|---:|
| SYNC1 | 65.708 | 11.920 ms | 83.89 bit/s |
| SYNC2 | 65.802 | 11.937 ms | 83.77 bit/s |

Each physical bit is detected by correlating the discriminator with a
64-sample template:

```text
[0 × 3, -1 × 26, 0 × 6, +1 × 26, 0 × 3]
```

- positive correlation (low deviation followed by high deviation) is `1`;
- negative correlation is `0`;
- the zero regions are guard intervals around the tone transitions.

### Hamming(8,4) FEC

Every nibble is transmitted as an eight-bit codeword with minimum distance 4:

```text
nibble:   0  1  2  3  4  5  6  7  8  9  A  B  C  D  E  F
codeword: 00 87 99 1e aa 2d 33 b4 4b cc d2 55 e1 66 78 ff
```

Transmission order per byte: codeword of the low nibble, then codeword of
the high nibble; within a codeword bit 0 first. All frame bytes, including
the length byte and the CRC, are coded this way.

Decoding selects the codeword with the smallest Hamming distance. At
distance 2 (ambiguous), the least reliable received bit (smallest absolute
correlation) is inverted and the selection repeated. There is no rejection
based on distance; integrity is established by the CRC alone.

## Synchronization

The frame starts with one of two preambles, given as runs of frequency-sign
values at the working rate, `sign × length`:

```text
SYNC1, 1,576 samples (≈ 285.9 ms):
+65 -132 +33 -33 +163 -132 +98 -66 +33 -33
+32 -33 +66 -99 +130 -166 +32 -33 +131 -66

SYNC2, 1,578 samples (≈ 286.3 ms):
-67 +128 -34 +32 -168 +129 -101 +64 -34 +32
-34 +32 -67 +96 -135 +161 -33 +33 -134 +64
```

A preamble is detected when the correlation of the clamped discriminator
with the pattern reaches 300,000 in magnitude (the maximum is
400 × pattern length ≈ 630,000). Both preambles must be searched; a
transmitter uses one of them. Nothing in the payload indicates which one.
The preamble is not a sequence of payload bytes and there is no separate
byte start marker.

The first physical bit begins about 25 samples before to 90 samples after
the preamble's end; the exact position and the symbol period are found by
search (see *Receiver implementation*). After acquisition, the symbol clock
is tracked from the bit correlations, updating the phase once per 16 bits
(one byte).

## Framing and CRC

Decoded wire structure (all bytes Hamming-coded on the air):

```text
[N: u8] [payload: N bytes] [CRC low] [CRC high]
```

- `N < 40`;
- `N = 20`: standard payload;
- `N = 32`: payload with radio-module extension;
- other lengths are not defined; payload bytes beyond offset 31 are ignored;
- the frame ends after the second CRC byte without an end marker;
- the frame is transmitted once per readout.

| CRC parameter | Value |
|---|---:|
| name | CRC-16/CMS |
| width | 16 bits |
| polynomial | `0x8005` |
| initial value | `0xffff` |
| input/output reflected | no / no |
| final XOR | `0x0000` |
| coverage | payload only (not the length byte) |
| wire order | low byte first |
| check value for `123456789` | `0xaee7` |

## Payload

Offsets count from the first payload byte (after the length byte). `LE` =
little endian, `BE` = big endian.

| Offset | Length | Endian | Field |
|---:|---:|---|---|
| 0 | 1 | – | header byte; values `0x00` and `0x05` occur; not evaluated |
| 1 | 4 | BE | detector serial number, `uint32` |
| 5 | 1 | – | bits 3..0 detector type, bits 7..4 radio-module type |
| 6 | 1 | – | number of removals |
| 7 | 1 | – | number of alarms in total |
| 8 | 1 | – | number of alarms in the last three months |
| 9 | 2 | LE | day of the last alarm relative to production; `ffff` = none |
| 11 | 2 | LE | device age in days at the time of the readout |
| 13 | 2 | LE | cumulative hours in storage mode |
| 15 | 2 | LE | day of the last self-test relative to production; `ffff` = none |
| 17 | 2 | LE | warranty flags |
| 19 | 1 | – | detector status |
| 20 | 1 | – | radio status (extension only) |
| 21 | 4 | BE | radio-module serial number, `uint32` (extension only) |
| 25 | 4 | BE | radio line ID (extension only) |
| 29 | 1 | – | radio line letter/number (extension only) |
| 30 | 1 | – | radio-module switch mask (extension only) |
| 31 | 1 | – | radio interference, in 0.1 % units (extension only) |

### Serial numbers

The detector serial number (bytes 1–4) and, if present, the radio-module
serial number (bytes 21–24) are unsigned 32-bit big-endian integers and are
displayed in decimal (e.g. `83 b4 98 f4` → `2209650932`). They are
transmitted as-is. `0` and `0xffffffff` should be treated as empty/dummy
values.

### Device types (byte 5)

Low nibble (detector):

| Value | Product |
|---:|---|
| 0 | Genius H |
| 1 | Genius Hx |
| 2 | Genius Plus |
| 3 | Genius Plus X |
| other | unknown |

High nibble (radio module):

| Value | Module |
|---:|---|
| 0 | none |
| 1 | FM.Basis |
| 2 | FM.Pro |
| 3 | FM.MCP |
| 4 | FM.Basis X |
| 5 | FM.Pro X |
| other | unknown |

### Date reconstruction

The payload contains relative day counters only. With the readout date `R`,
the age `A` (bytes 11–12), and an event counter `E` (bytes 9–10 or 15–16):

```text
production date = R - A days
event date      = R - (A - E) days
```

`E = 0xffff` or `E > A` means no event. The readout date must therefore be
stored together with a frame if the frame is to be interpreted later. The
alarm's end of life is ten years after production.

### Warranty flags (bytes 17–18, LE)

A zero value means the warranty is intact. Bits:

| Bit | Mask | Meaning |
|---:|---:|---|
| 0 | `0001` | maximum contamination exceeded |
| 1 | `0002` | out of temperature range |
| 2 | `0004` | detector too old |
| 3 | `0008` | storage time exceeded |
| 4 | `0010` | activation time exceeded |
| 5 | `0020` | too many events |
| 6 | `0040` | too many alarms |
| 7 | `0080` | too many faults |
| 8 | `0100` | too many self-tests |
| 9 | `0200` | too many radio faults |
| 10 | `0400` | too many radio out-of-order events |
| 11 | `0800` | radio installation too old |
| 12 | `1000` | too much radio activity |
| 13 | `2000` | too much radio interference |
| 14 | `4000` | too many radio transmit events |
| 15 | `8000` | too many radio receive events |

### Detector status (byte 19)

| Bit | Mask | Meaning |
|---:|---:|---|
| 0 | `01` | battery low |
| 1 | `02` | device fault |
| 2 | `04` | radio network fault |
| 3–6 | `78` | drift state `(byte >> 3) & 0x0f`, valid range 0–8 |
| 7 | `80` | negative contamination forecast |

No battery voltage and no continuous contamination value are transmitted.

### Radio status (byte 20)

| Bit | Mask | Meaning |
|---:|---:|---|
| 0 | `01` | remote alarm |
| 1 | `02` | radio link error |
| 2 | `04` | remote error |
| 3 | `08` | remote battery low |
| 4 | `10` | radio-module battery low |
| 5 | `20` | self-test |
| 6 | `40` | transmission range test |
| 7 | `80` | radio-module fault |

### Radio line (byte 29)

- high nibble 0–9: line letter `A`–`J`;
- low nibble 0–9: line number;
- other nibble values are invalid.

### Radio-module switches (byte 30)

| Bit | Mask | Meaning |
|---:|---:|---|
| 0 | `01` | suppress warnings |
| 1 | `02` | suppress alarms |
| 2 | `04` | send collective alarm |
| 3 | `08` | receive collective alarm |
| 4 | `10` | radio link supervision |
| 5 | `20` | reduced transmitting power |
| 6–7 | `c0` | reserved; must be zero |

### Radio interference (byte 31)

`percent = raw / 10`. Values above 0.2 % indicate a fault.

### Validity rules

A frame is rejected when the drift state exceeds 8, a radio-line nibble is
not a decimal digit, or a reserved switch bit is set.

## Receiver implementation

Batch decoding of a captured buffer (44.1 kHz; other rates are resampled
first):

1. Run the demodulation chain to obtain the clamped discriminator `d[n]`.
2. Correlate `d` with both preambles (piecewise-constant patterns, so the
   correlation can be computed from a prefix sum in O(runs) per position).
   Take positions with |correlation| ≥ 300,000, strongest first, suppressing
   neighbors closer than one pattern length.
3. Compute the bit correlation `c[n]` of `d` with the 64-sample template
   (also from the prefix sum).
4. For each preamble hit, search start ∈ [end − 25, end + 90] samples,
   period ∈ nominal ± 2.5 samples in steps of 0.02, and both polarities.
   Decode the first seven bytes at each hypothesis (linear clock) and rank
   hypotheses by the total Hamming distance; a length byte outside 20–39 or
   unknown type nibbles in byte 6 of the wire (payload byte 5) demote a
   hypothesis. Decode the full frame (`N + 3` bytes) for the best
   hypotheses and accept the first one whose CRC matches.
5. If no linear clock yields a valid CRC, repeat for the strongest
   hypotheses with per-symbol tracking: sample each bit at the strongest
   |c| within ±12 samples of its expected position and correct the expected
   position by the weighted mean offset once per 16 bits.

A live receiver keeps a rolling buffer of at least 8 s and repeats the batch
search every few seconds. The 16-bit CRC plus the length and type checks
make false accepts negligible.

## Example frames

Two CRC-valid frames for testing an implementation. Both are 20-byte
payloads of a Genius Plus without radio module, header byte `00`, no
alarms, no warranty flags, status `00`.

Frame A (readout date 2020-05-20):

```text
wire: 14 00 83 b4 98 f4 02 05 00 00 ff ff 4c 00 26 07 46 00 00 00 00 ca 5a
```

| Payload | Hex | Value |
|---:|---|---|
| 1–4 | `83 b4 98 f4` | serial 2209650932 |
| 5 | `02` | Genius Plus, no radio module |
| 6 | `05` | 5 removals |
| 11–12 | `4c 00` | age 76 days → production 2020-03-05 |
| 13–14 | `26 07` | 1,830 storage hours |
| 15–16 | `46 00` | self-test on day 70 → 2020-05-14 |
| CRC | `ca 5a` | `0x5aca` |

Frame B (readout date 2026-08-30):

```text
wire: 14 00 84 cb 5f b2 02 05 00 00 ff ff 50 00 22 07 4f 00 00 00 00 30 7e
```

| Payload | Hex | Value |
|---:|---|---|
| 1–4 | `84 cb 5f b2` | serial 2227920818 |
| 5 | `02` | Genius Plus, no radio module |
| 6 | `05` | 5 removals |
| 11–12 | `50 00` | age 80 days → production 2026-06-11 |
| 13–14 | `22 07` | 1,826 storage hours |
| 15–16 | `4f 00` | self-test on day 79 → 2026-08-29 |
| CRC | `30 7e` | `0x7e30` |

A payload with radio extension for parser tests (32 bytes, Genius Plus X
with FM.Basis X, line B2, remote-alarm flag, switches "suppress warnings"
and "reduced transmitting power", interference 2.5 %):

```text
05 12 a1 00 0b 43 00 00 00 ff ff da 02 00 00 bc 02 00 00 00
01 ab 00 10 01 00 00 00 01 12 21 19
```

## Reserved and unknown fields

- payload byte 0: not evaluated; values `0x00` and `0x05` occur;
- payload lengths other than 20 and 32: undefined;
- the selection between SYNC1 and SYNC2 and the tone separation are
  device-dependent and not signaled in the payload;
- the measurement behind the radio interference value is not specified.

## Reference decoder

`smartsonic_decode.py` (Python 3, NumPy) implements the receiver described
above for PCM WAV input; compressed recordings are converted with GStreamer
when available. Because the payload dates are relative, the readout date is
passed with `--readout-date` (default: today).

```bash
python3 protocol/smartsonic/smartsonic_decode.py --readout-date 2026-08-30 recording.m4a
python3 protocol/smartsonic/smartsonic_decode.py --json recording.wav
python3 protocol/smartsonic/smartsonic_decode.py --all-frames recording.m4a
python3 protocol/smartsonic/smartsonic_decode.py --frame-hex '14 00 83 b4 98 f4 02 05 00 00 ff ff 4c 00 26 07 46 00 00 00 00 ca 5a'
python3 -m unittest discover -v -s protocol/smartsonic
```
