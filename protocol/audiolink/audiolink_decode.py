#!/usr/bin/env python3
"""Reference decoder for the Ei Electronics AudioLINK+ status frame.

Implements the receiver described in PROTOCOL.md (binary energy FSK at
5.5/6.8 kHz, 100 bit/s, 41 wire bytes, CRC-16/CCITT-FALSE) using only the
Python standard library plus NumPy.  PCM WAV files are read directly; other
formats are converted to a temporary WAV file with GStreamer.  The legacy
20 bit/s Manchester AudioLINK variant is not decoded.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np


FRAME_BYTES = 41
FRAME_BITS = FRAME_BYTES * 8
BLOCK_MARKER = 0x72
END_MARKER = 0xAA
MARKER_BYTE_OFFSETS = (2, 9, 16, 23, 30, 37)
PAYLOAD_LENGTH = 30
TIME_COUNTER_EPOCH = 24090


class DecodeError(RuntimeError):
    """Raised when no checksum-valid AudioLINK+ frame is found."""


@dataclass
class Candidate:
    start_sample: float
    period_samples: float
    features: np.ndarray
    bits: np.ndarray
    wire: bytes
    fixed_score: int
    fixed_total: int
    low_level: float
    high_level: float
    crc_valid: bool

    @property
    def separation(self) -> float:
        spread = max(abs(self.high_level), abs(self.low_level), 1e-12)
        return (self.high_level - self.low_level) / spread


def _read_pcm_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as wav:
        channels = wav.getnchannels()
        sample_width = wav.getsampwidth()
        sample_rate = wav.getframerate()
        frame_count = wav.getnframes()
        raw = wav.readframes(frame_count)

    if sample_width == 1:
        samples = (np.frombuffer(raw, dtype=np.uint8).astype(np.float64) - 128.0) / 128.0
    elif sample_width == 2:
        samples = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    elif sample_width == 3:
        packed = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3)
        values = (
            packed[:, 0].astype(np.int32)
            | (packed[:, 1].astype(np.int32) << 8)
            | (packed[:, 2].astype(np.int32) << 16)
        )
        values = (values ^ 0x800000) - 0x800000
        samples = values.astype(np.float64) / 8388608.0
    elif sample_width == 4:
        samples = np.frombuffer(raw, dtype="<i4").astype(np.float64) / 2147483648.0
    else:
        raise DecodeError(f"unsupported WAV sample width: {sample_width} bytes")

    if channels > 1:
        samples = samples.reshape(-1, channels).mean(axis=1)
    return samples, sample_rate


def _conversion_commands(path: Path, decoded: Path, rate: int) -> list[list[str]]:
    commands: list[list[str]] = []
    gst = shutil.which("gst-launch-1.0")
    if gst is not None:
        commands.append([
            gst, "-q",
            "filesrc", f"location={path.resolve()}",
            "!", "decodebin", "!", "audioconvert", "!", "audioresample",
            "!", f"audio/x-raw,format=S16LE,channels=1,rate={rate}",
            "!", "wavenc", "!", "filesink", f"location={decoded}",
        ])
    return commands


def _ffmpeg_pcm(path: Path, rate: int) -> np.ndarray | None:
    """Decode via ffmpeg to raw 16-bit PCM on stdout (no temp file needed,
    which keeps sandboxed ffmpeg builds working)."""
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        return None
    command = [
        ffmpeg, "-v", "error", "-i", str(path.resolve()),
        "-ac", "1", "-ar", str(rate), "-f", "s16le", "-",
    ]
    result = subprocess.run(command, capture_output=True, check=False)
    if result.returncode != 0 or len(result.stdout) < 2:
        return None
    return np.frombuffer(result.stdout, dtype="<i2").astype(np.float64) / 32768.0


def convert_to_wav(path: Path, rate: int) -> tuple[np.ndarray, int]:
    """Decode a compressed recording via GStreamer or ffmpeg."""
    errors: list[str] = []
    with tempfile.TemporaryDirectory(prefix="audiolink-") as temp_dir:
        decoded = Path(temp_dir) / "decoded.wav"
        for command in _conversion_commands(path, decoded, rate):
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            if result.returncode == 0 and decoded.is_file():
                return _read_pcm_wav(decoded)
            errors.append(result.stderr.strip() or result.stdout.strip() or "unknown error")
            decoded.unlink(missing_ok=True)
    samples = _ffmpeg_pcm(path, rate)
    if samples is not None:
        return samples, rate
    raise DecodeError(
        f"could not decode {path}"
        + ("; install GStreamer or ffmpeg" if not errors else ": " + "; ".join(errors))
    )


def load_audio(path: Path) -> tuple[np.ndarray, int]:
    """Load PCM WAV directly, or decode a compressed file to 48 kHz mono."""
    try:
        return _read_pcm_wav(path)
    except (wave.Error, EOFError):
        pass
    return convert_to_wav(path, 48_000)


def fsk_envelopes(samples: np.ndarray, sample_rate: int) -> tuple[np.ndarray, np.ndarray]:
    """Return analytic-envelope magnitudes around 5.5 and 6.8 kHz."""
    low = _band_envelope(samples, sample_rate, 5500.0)
    high = _band_envelope(samples, sample_rate, 6800.0)
    return low, high


def _band_envelope(samples: np.ndarray, sample_rate: int, center: float, half: float = 250.0) -> np.ndarray:
    spectrum = np.fft.fft(samples)
    frequencies = np.fft.fftfreq(len(samples), 1.0 / sample_rate)
    keep = (frequencies >= center - half) & (frequencies <= center + half)
    return np.abs(np.fft.ifft(spectrum * (2.0 * keep)))


# The loud piezo sounder is rich in harmonics, which carry the same bit
# information in bands that everyday interference (speech, sibilants) rarely
# reaches.  Use matched harmonic orders for the two FSK classes: combining an
# extra harmonic for only one class can turn unrelated high-frequency noise
# into a systematic bit bias.  Fundamentals are always used; the second
# harmonic joins only when it shows real activity, so clean or band-limited
# signals are unaffected.  Still higher harmonics are too close to typical
# microphone/codec cutoffs to be dependable evidence.
ONE_BANDS_HZ = (5500.0, 11000.0)
ZERO_BANDS_HZ = (6800.0, 13600.0)


def envelope_views(samples: np.ndarray, sample_rate: int) -> list[tuple[np.ndarray, np.ndarray]]:
    """Return a small, predetermined set of matched FSK evidence views.

    Bands are normalized by their 95th percentile. The second harmonic is
    added to the primary view only when *both* FSK classes show real burst
    activity; this prevents an unmatched band from biasing one class. When
    present, the harmonic-only and fundamental-only views are fallbacks for
    interference confined to one frequency range. Every view still has to
    produce the transmitted bits, fixed framing, and CRC exactly.
    """

    def normalized(center: float) -> tuple[np.ndarray, bool]:
        envelope = _band_envelope(samples, sample_rate, center)
        p95 = float(np.percentile(envelope, 95))
        p25 = float(np.percentile(envelope, 25))
        return envelope / max(p95, 1e-12), p95 > 4.0 * p25

    one_fundamental, _ = normalized(ONE_BANDS_HZ[0])
    zero_fundamental, _ = normalized(ZERO_BANDS_HZ[0])
    views: list[tuple[np.ndarray, np.ndarray]] = []
    if len(ONE_BANDS_HZ) > 1 and len(ZERO_BANDS_HZ) > 1:
        one_harmonic, one_active = normalized(ONE_BANDS_HZ[1])
        zero_harmonic, zero_active = normalized(ZERO_BANDS_HZ[1])
        if one_active and zero_active:
            views.append((
                one_fundamental + one_harmonic,
                zero_fundamental + zero_harmonic,
            ))
            views.append((one_harmonic, zero_harmonic))
    views.append((one_fundamental, zero_fundamental))
    return views


def combined_envelopes(samples: np.ndarray, sample_rate: int) -> tuple[np.ndarray, np.ndarray]:
    """Return the primary matched-band evidence view."""
    return envelope_views(samples, sample_rate)[0]


def crc_ccitt_false(data: bytes) -> int:
    """CRC-16/CCITT-FALSE: poly 0x1021, init 0xffff, no reflection/xorout."""
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def bits_to_bytes(bits: np.ndarray) -> bytes:
    if len(bits) % 8:
        raise ValueError("bit count is not byte-aligned")
    return np.packbits(bits.astype(np.uint8), bitorder="big").tobytes()


def wire_to_payload(wire: bytes) -> bytes:
    if len(wire) != FRAME_BYTES:
        raise ValueError(f"wire frame must contain {FRAME_BYTES} bytes")
    chunks = [wire[offset + 1 : offset + 7] for offset in MARKER_BYTE_OFFSETS[:5]]
    payload = b"".join(chunks)
    if len(payload) != PAYLOAD_LENGTH:
        raise AssertionError("internal payload framing error")
    return payload


def wire_is_structurally_valid(wire: bytes) -> bool:
    return (
        len(wire) == FRAME_BYTES
        and wire[:2] == b"\x00\x00"
        and all(wire[offset] == BLOCK_MARKER for offset in MARKER_BYTE_OFFSETS)
        and wire[-1] == END_MARKER
    )


def wire_crc_valid(wire: bytes) -> bool:
    if not wire_is_structurally_valid(wire):
        return False
    payload = wire_to_payload(wire)
    received = int.from_bytes(wire[38:40], "big")
    return crc_ccitt_false(payload) == received


def canonical_frame(wire: bytes) -> bytes:
    """Return the canonical 34-byte buffer: AA + payload + CRC + AA."""
    return b"\xaa" + wire_to_payload(wire) + wire[38:40] + b"\xaa"


def _fixed_frame_bits() -> tuple[np.ndarray, np.ndarray]:
    positions: list[int] = []
    values: list[int] = []
    fixed_bytes = {0: 0x00, 1: 0x00, 40: END_MARKER}
    fixed_bytes.update({offset: BLOCK_MARKER for offset in MARKER_BYTE_OFFSETS})
    for byte_offset, value in fixed_bytes.items():
        for bit_offset in range(8):
            positions.append(byte_offset * 8 + bit_offset)
            values.append((value >> (7 - bit_offset)) & 1)
    return np.asarray(positions), np.asarray(values, dtype=np.uint8)


FIXED_POSITIONS, FIXED_VALUES = _fixed_frame_bits()

MARKER_ONE_BITS = (1, 2, 3, 6)   # 0x72 = 01110010
MARKER_ZERO_BITS = (0, 4, 5, 7)
END_ONE_BITS = (0, 2, 4, 6)      # 0xAA = 10101010
END_ZERO_BITS = (1, 3, 5, 7)


def _marker_anchored_thresholds(features: np.ndarray) -> np.ndarray:
    """Per-symbol thresholds anchored at the known marker bits.

    Each 0x72 marker and the closing 0xAA contain four known one and four
    known zero bits whose feature means give a local decision threshold;
    values between the anchors are interpolated linearly.  This compensates
    baseline drift of the discriminator across the frame (e.g. from in-band
    background noise), which a single global threshold cannot.
    """
    xs: list[float] = []
    ys: list[float] = []
    for offset in MARKER_BYTE_OFFSETS:
        block = features[offset * 8 : offset * 8 + 8]
        xs.append(offset * 8 + 3.5)
        ys.append((block[list(MARKER_ONE_BITS)].mean() + block[list(MARKER_ZERO_BITS)].mean()) / 2.0)
    end_block = features[320:328]
    xs.append(323.5)
    ys.append((end_block[list(END_ONE_BITS)].mean() + end_block[list(END_ZERO_BITS)].mean()) / 2.0)
    return np.interp(np.arange(FRAME_BITS, dtype=np.float64), xs, ys)


def _decide(
    features: np.ndarray,
    thresholds: np.ndarray,
    start: float,
    period: float,
    low: float,
    high: float,
) -> Candidate:
    bits = (features >= thresholds).astype(np.uint8)
    wire = bits_to_bytes(bits)
    fixed_score = int((bits[FIXED_POSITIONS] == FIXED_VALUES).sum())
    crc_valid = wire_crc_valid(wire)
    return Candidate(
        start_sample=start,
        period_samples=period,
        features=features,
        bits=bits,
        wire=wire,
        fixed_score=fixed_score,
        fixed_total=len(FIXED_POSITIONS),
        low_level=low,
        high_level=high,
        crc_valid=crc_valid,
    )


def _candidate_key(candidate: Candidate) -> tuple:
    return (candidate.crc_valid, candidate.fixed_score, candidate.separation)


def _row_kmeans(features: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Two-means per row; 5.5-kHz-dominant/high discriminator is one."""
    if features.ndim == 1:
        features = features[None, :]
    low = np.percentile(features, 25, axis=1)
    high = np.percentile(features, 75, axis=1)
    for _ in range(12):
        threshold = (low + high) / 2.0
        low_mask = features <= threshold[:, None]
        low_count = np.maximum(low_mask.sum(axis=1), 1)
        high_count = np.maximum((~low_mask).sum(axis=1), 1)
        new_low = (features * low_mask).sum(axis=1) / low_count
        new_high = (features * (~low_mask)).sum(axis=1) / high_count
        if np.allclose(low, new_low, rtol=0.0, atol=1e-12) and np.allclose(
            high, new_high, rtol=0.0, atol=1e-12
        ):
            break
        low, high = new_low, new_high
    bits = features >= ((low + high) / 2.0)[:, None]
    return bits.astype(np.uint8), low, high


def _evaluate_rows(
    discriminator_sum: np.ndarray,
    starts: np.ndarray,
    period_samples: float,
) -> list[Candidate]:
    symbol_numbers = np.arange(FRAME_BITS, dtype=np.float64)
    symbol_starts = starts[:, None] + period_samples * symbol_numbers
    # Ignore the transition-prone outer 13.75 percent on each side.
    first = np.rint(symbol_starts + period_samples * 0.1375).astype(np.int64)
    last = np.rint(symbol_starts + period_samples * 0.8625).astype(np.int64)
    valid = (first[:, 0] >= 0) & (last[:, -1] < len(discriminator_sum))
    if not np.any(valid):
        return []
    starts = starts[valid]
    first = first[valid]
    last = last[valid]
    features = (discriminator_sum[last] - discriminator_sum[first]) / np.maximum(last - first, 1)
    _, low, high = _row_kmeans(features)

    results: list[Candidate] = []
    for row in range(len(starts)):
        uniform = np.full(FRAME_BITS, (low[row] + high[row]) / 2.0)
        global_candidate = _decide(
            features[row], uniform, float(starts[row]), float(period_samples),
            float(low[row]), float(high[row]),
        )
        local_candidate = _decide(
            features[row], _marker_anchored_thresholds(features[row]),
            float(starts[row]), float(period_samples), float(low[row]), float(high[row]),
        )
        results.append(max(global_candidate, local_candidate, key=_candidate_key))
    return results


def find_frame(low_envelope: np.ndarray, high_envelope: np.ndarray, sample_rate: int) -> Candidate:
    """Global matched-filter frame search across the whole buffer.

    The 72 fixed frame bits (prefix, six 0x72 markers, closing 0xAA) act as
    the correlation pattern, so the frame is located regardless of handling
    noise or lead-in pulses preceding it, without an energy-based
    segmentation step.
    """
    total_energy = low_envelope + high_envelope
    noise_floor = max(float(np.percentile(total_energy, 25)) * 2.0, 1e-12)
    discriminator = (low_envelope - high_envelope) / (total_energy + noise_floor)
    discriminator_sum = np.r_[0.0, np.cumsum(discriminator)]
    sign = np.where(FIXED_VALUES == 1, 1.0, -1.0)

    def matched_scores(starts: np.ndarray, period: float) -> tuple[np.ndarray, np.ndarray]:
        symbol_starts = starts[:, None] + period * FIXED_POSITIONS[None, :]
        first = np.rint(symbol_starts + period * 0.1375).astype(np.int64)
        last = np.rint(symbol_starts + period * 0.8625).astype(np.int64)
        # FIXED_POSITIONS is not sorted, so bound-check the extremes per row
        valid = (first.min(axis=1) >= 0) & (last.max(axis=1) < len(discriminator_sum))
        starts, first, last = starts[valid], first[valid], last[valid]
        if not len(starts):
            return starts, np.empty(0)
        features = (discriminator_sum[last] - discriminator_sum[first]) / np.maximum(last - first, 1)
        return starts, features @ sign

    # stage 1: coarse scan (step 1/8 symbol, all periods), top starts per period
    step = max(1, round(sample_rate * 0.00125))
    seeds: list[tuple[float, int, float]] = []
    for period in np.arange(sample_rate * 0.0097, sample_rate * 0.0103 + 1e-9, 0.5):
        starts, scores = matched_scores(np.arange(0, len(total_energy), step), float(period))
        if not len(starts):
            continue
        taken: list[int] = []
        for index in np.argsort(scores)[::-1]:
            start = int(starts[index])
            if any(abs(start - previous) <= sample_rate // 20 for previous in taken):
                continue
            taken.append(start)
            seeds.append((float(scores[index]), start, float(period)))
            if len(taken) >= 5:
                break
    if not seeds:
        raise DecodeError("could not construct any AudioLINK+ frame candidates")
    seeds.sort(reverse=True)
    merged: list[tuple[float, int, float]] = []
    for seed in seeds:
        if all(abs(seed[1] - other[1]) >= sample_rate // 10 for other in merged):
            merged.append(seed)
        if len(merged) >= 10:
            break

    # stage 2: refine each seed's alignment via the matched filter
    aligned: list[tuple[float, int, float]] = []
    for score, start, period in merged:
        best = (score, start, period)
        for refined_period in np.arange(period - 0.75, period + 0.751, 0.25):
            starts, scores = matched_scores(
                np.arange(start - 90, start + 91, 6), float(refined_period)
            )
            if len(starts) and float(scores.max()) > best[0]:
                index = int(np.argmax(scores))
                best = (float(scores[index]), int(starts[index]), float(refined_period))
        aligned.append(best)
    aligned.sort(reverse=True)

    # stage 3: full evaluation (thresholding + CRC) on a fine grid, best first
    best_invalid: Candidate | None = None
    for _, start, period in aligned:
        rows: list[Candidate] = []
        for refined_period in np.arange(period - 0.65, period + 0.651, 0.025):
            rows.extend(
                _evaluate_rows(discriminator_sum, np.arange(start - 12, start + 13), float(refined_period))
            )
        rows.sort(key=_candidate_key, reverse=True)
        if rows and rows[0].crc_valid:
            return rows[0]
        if rows and (best_invalid is None or _candidate_key(rows[0]) > _candidate_key(best_invalid)):
            best_invalid = rows[0]
    if best_invalid is not None:
        raise DecodeError(
            "an AudioLINK+-like clock was found, but no checksum-valid frame could be reconstructed "
            f"(best framing score {best_invalid.fixed_score}/{best_invalid.fixed_total})"
        )
    raise DecodeError("no checksum-valid AudioLINK+ frame found")


def _counter_age(counter: int, count: int, current_counter: int) -> dict[str, int | None]:
    if count <= 0:
        return {"counter_raw": counter, "age_hours": None, "age_days": None}
    hours = (counter - current_counter) * 4
    return {"counter_raw": counter, "age_hours": hours, "age_days": hours // 24}


def _event(
    payload: bytes,
    offset: int,
    current_counter: int,
    *,
    removal_bias: bool = False,
) -> dict[str, int | None]:
    counter = int.from_bytes(payload[offset : offset + 2], "big")
    raw_count = payload[offset + 2]
    count = max(0, raw_count - 1) if removal_bias else raw_count
    result: dict[str, int | None] = {"count": count, "count_raw": raw_count}
    result.update(_counter_age(counter, count, current_counter))
    return result


def _battery_voltage(nibble: int) -> float:
    values = (2.35, 2.38, 2.42, 2.46, 2.50, 2.54, 2.58, 2.63,
              2.67, 2.72, 2.77, 2.82, 2.87, 2.92, 2.98, 3.04)
    return values[nibble]


def _manufacture_date(payload: bytes) -> str:
    first, second = payload[27:29]
    day = first >> 3
    month = ((first & 0x07) << 1) | (second >> 7)
    year = 1980 + (second & 0x7F)
    return f"{year:04d}-{month:02d}-{day:02d}"


def decode_fields(payload: bytes) -> dict[str, Any]:
    if len(payload) != PAYLOAD_LENGTH:
        raise ValueError(f"payload must contain exactly {PAYLOAD_LENGTH} bytes")
    uptime_counter = int.from_bytes(payload[0:2], "big")
    uptime_hours = (TIME_COUNTER_EPOCH - uptime_counter) * 4
    type_code = payload[2]
    contamination_raw = payload[3] & 0x3F
    status = payload[4]
    battery_code = status & 0x0F
    alarm_id = payload[23:27].hex()
    manufacture_month = ((payload[27] & 0x07) << 1) | (payload[28] >> 7)
    manufacture_year = 1980 + (payload[28] & 0x7F)

    return {
        "model": {1: "Ei650", 2: "Ei650i"}.get(type_code, f"unknown ({type_code})"),
        "model_code": type_code,
        "uptime_counter_raw": uptime_counter,
        "uptime_hours": uptime_hours,
        "uptime_days": uptime_hours // 24,
        "contamination_raw": contamination_raw,
        "contamination_level": round(min(contamination_raw / 3.2, 10.0), 1)
        if contamination_raw <= 32 and not bool(status & 0x20)
        else None,
        "contamination_valid": contamination_raw <= 32 and not bool(status & 0x20),
        "dust_calculating": bool(status & 0x20),
        "sensor_ok": not bool(status & 0x80),
        "status_raw": status,
        "battery_code": battery_code,
        "battery_voltage_v": _battery_voltage(battery_code),
        "battery_status": "green" if battery_code > 8 else ("amber" if battery_code >= 4 else "red"),
        "test_button": _event(payload, 5, uptime_counter),
        "smoke_alarm": _event(payload, 8, uptime_counter),
        "reserved_event_bytes_11_16": payload[11:17].hex(),
        "low_battery": _event(payload, 17, uptime_counter),
        "removal": _event(payload, 20, uptime_counter, removal_bias=True),
        "alarm_id": alarm_id,
        "date_code_raw": payload[27:29].hex(),
        "manufacture_date": _manufacture_date(payload),
        "replacement_month": f"{manufacture_year + 11:04d}-{manufacture_month:02d}",
        "reserved_byte_29": payload[29],
    }


def estimate_fsk_frequencies(
    samples: np.ndarray,
    candidate: Candidate,
    sample_rate: int,
) -> dict[str, float]:
    fft_size = 8192
    frequencies = np.fft.rfftfreq(fft_size, 1.0 / sample_rate)
    estimates: dict[str, float] = {}
    for bit, low, high in ((1, 5000.0, 6000.0), (0, 6300.0, 7300.0)):
        power = np.zeros(fft_size // 2 + 1)
        for symbol in np.flatnonzero(candidate.bits == bit):
            base = candidate.start_sample + symbol * candidate.period_samples
            first = round(base + 0.1375 * candidate.period_samples)
            last = round(base + 0.8625 * candidate.period_samples)
            segment = samples[first:last]
            if len(segment) < 2:
                continue
            power += np.abs(np.fft.rfft(segment * np.hanning(len(segment)), fft_size)) ** 2
        band = np.flatnonzero((frequencies >= low) & (frequencies <= high))
        estimates[f"logical_{bit}_hz"] = float(frequencies[band[np.argmax(power[band])]])
    return estimates


def decode_file(path: Path) -> dict[str, Any]:
    samples, sample_rate = load_audio(path)
    if len(samples) < round(sample_rate * 3.0):
        raise DecodeError("audio is too short to contain the 3.28-second frame")
    if sample_rate < 15000:
        raise DecodeError("sample rate is too low for the 6.8-kHz FSK band")
    last_error: DecodeError | None = None
    candidate: Candidate | None = None
    for low_envelope, high_envelope in envelope_views(samples, sample_rate):
        try:
            candidate = find_frame(low_envelope, high_envelope, sample_rate)
            break
        except DecodeError as error:
            last_error = error
    if candidate is None:
        assert last_error is not None
        raise last_error
    payload = wire_to_payload(candidate.wire)
    received_crc = int.from_bytes(candidate.wire[38:40], "big")

    return {
        "file": str(path),
        "sample_rate_hz": sample_rate,
        "duration_s": len(samples) / sample_rate,
        "protocol": "AudioLINK+ (version 2)",
        "fsk_tones": estimate_fsk_frequencies(samples, candidate, sample_rate),
        "nominal_symbol_period_ms": 10.0,
        "nominal_bit_rate_bps": 100.0,
        "symbol_period_fit_ms": candidate.period_samples / sample_rate * 1000.0,
        "bit_rate_fit_bps": sample_rate / candidate.period_samples,
        "frame_start_s": candidate.start_sample / sample_rate,
        "physical_bits_msb_first": "".join(str(int(bit)) for bit in candidate.bits),
        "wire_bytes_hex": candidate.wire.hex(" "),
        "canonical_bytes_hex": canonical_frame(candidate.wire).hex(" "),
        "payload_hex": payload.hex(" "),
        "crc": {
            "algorithm": "CRC-16/CCITT-FALSE",
            "received_hex": f"{received_crc:04x}",
            "calculated_hex": f"{crc_ccitt_false(payload):04x}",
            "valid": True,
        },
        "framing_score": f"{candidate.fixed_score}/{candidate.fixed_total}",
        "symbol_levels": {
            "logical_0_discriminator_center": candidate.low_level,
            "logical_1_discriminator_center": candidate.high_level,
            "center_separation": candidate.high_level - candidate.low_level,
        },
        "fields": decode_fields(payload),
    }


def _human_output(result: dict[str, Any]) -> str:
    fields = result["fields"]
    crc = result["crc"]
    contamination = (
        "unavailable"
        if fields["contamination_level"] is None
        else f"{fields['contamination_level']:.5g}"
    )
    lines = [
        f"File: {result['file']}",
        f"Protocol: {result['protocol']}",
        f"Audio: {result['sample_rate_hz']} Hz, {result['duration_s']:.4f} s",
        f"FSK tones: 1={result['fsk_tones']['logical_1_hz']:.1f} Hz, 0={result['fsk_tones']['logical_0_hz']:.1f} Hz",
        "Symbol clock: nominal 10 ms / 100 bit/s "
        f"(search fit {result['symbol_period_fit_ms']:.3f} ms / {result['bit_rate_fit_bps']:.2f} bit/s)",
        f"Frame start: {result['frame_start_s']:.6f} s",
        f"Wire bytes:      {result['wire_bytes_hex']}",
        f"Canonical bytes: {result['canonical_bytes_hex']}",
        f"Payload:         {result['payload_hex']}",
        f"CRC: received {crc['received_hex']}, calculated {crc['calculated_hex']} ({'OK' if crc['valid'] else 'FAIL'})",
        f"Model: {fields['model']}",
        f"Alarm ID: {fields['alarm_id']}",
        f"Uptime: {fields['uptime_hours']} h ({fields['uptime_days']} days)",
        f"Battery: {fields['battery_voltage_v']:.2f} V (code {fields['battery_code']})",
        f"Sensor: {'OK' if fields['sensor_ok'] else 'failure'}",
        f"Contamination: {contamination} (raw {fields['contamination_raw']})",
        f"Test button: {fields['test_button']['count']} event(s), age days {fields['test_button']['age_days']}",
        f"Smoke alarm: {fields['smoke_alarm']['count']} event(s), age days {fields['smoke_alarm']['age_days']}",
        f"Low battery: {fields['low_battery']['count']} event(s), age days {fields['low_battery']['age_days']}",
        f"Removal: {fields['removal']['count']} event(s), age days {fields['removal']['age_days']}",
        f"Manufactured: {fields['manufacture_date']} (raw {fields['date_code_raw']})",
        f"Replace by: {fields['replacement_month']}",
    ]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audio", nargs="+", type=Path, help="PCM WAV or a GStreamer-decodable audio file")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    args = parser.parse_args(argv)

    results: list[dict[str, Any]] = []
    failed = False
    for path in args.audio:
        try:
            results.append(decode_file(path))
        except (DecodeError, OSError) as error:
            failed = True
            print(f"{path}: decode failed: {error}", file=sys.stderr)

    if args.json:
        print(json.dumps(results if len(args.audio) > 1 else (results[0] if results else {}), indent=2))
    else:
        print("\n\n".join(_human_output(result) for result in results))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
