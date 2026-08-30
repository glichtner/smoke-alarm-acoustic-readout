#!/usr/bin/env python3
"""Reference decoder for the Hekatron Genius Smartsonic status frame.

Implements the receiver described in PROTOCOL.md.  It reads PCM WAV directly
and uses GStreamer, when available, for compressed recordings such as M4A.
NumPy is the only Python dependency.

The public surface is split into three layers:

* :func:`decode_audio` -- waveform to a checksum-valid frame
* :func:`parse_wire_frame` -- length/payload/CRC framing
* :func:`parse_payload` -- Smartsonic fields and date reconstruction
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import shutil
import subprocess
import tempfile
import wave
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Iterable, Sequence

import numpy as np


ADC_RATE = 44_100
CARRIER_HZ = 4_449.1
# Deviation used by the synthetic test transmitter.  The tone separation is
# device-dependent and not a demodulator constant.
REFERENCE_SYNTH_DEVIATION_HZ = 250.0
DECIMATION = 8
DEMOD_RATE = ADC_RATE / DECIMATION
CLAMP = 400
SYNC_THRESHOLD = 300_000
MAX_PAYLOAD_LENGTH = 39
MIN_KNOWN_PAYLOAD_LENGTH = 20

HAMMING_CODEBOOK = np.asarray(
    [
        0x00,
        0x87,
        0x99,
        0x1E,
        0xAA,
        0x2D,
        0x33,
        0xB4,
        0x4B,
        0xCC,
        0xD2,
        0x55,
        0xE1,
        0x66,
        0x78,
        0xFF,
    ],
    dtype=np.uint8,
)

# Samples at 5,512.5 Hz.  The zeros are correlation guard regions around the
# FSK transitions, not carrier-off periods.
BIT_TEMPLATE = np.concatenate(
    (np.zeros(3), -np.ones(26), np.zeros(6), np.ones(26), np.zeros(3))
)

# Run-length form of the two preambles.  Values are signs of the demodulated
# frequency deviation at 5,512.5 Hz.
SYNC1_RLE: tuple[tuple[int, int], ...] = (
    (+1, 65),
    (-1, 132),
    (+1, 33),
    (-1, 33),
    (+1, 163),
    (-1, 132),
    (+1, 98),
    (-1, 66),
    (+1, 33),
    (-1, 33),
    (+1, 32),
    (-1, 33),
    (+1, 66),
    (-1, 99),
    (+1, 130),
    (-1, 166),
    (+1, 32),
    (-1, 33),
    (+1, 131),
    (-1, 66),
)
SYNC2_RLE: tuple[tuple[int, int], ...] = (
    (-1, 67),
    (+1, 128),
    (-1, 34),
    (+1, 32),
    (-1, 168),
    (+1, 129),
    (-1, 101),
    (+1, 64),
    (-1, 34),
    (+1, 32),
    (-1, 34),
    (+1, 32),
    (-1, 67),
    (+1, 96),
    (-1, 135),
    (+1, 161),
    (-1, 33),
    (+1, 33),
    (-1, 134),
    (+1, 64),
)
SYNC_PATTERNS = (
    ("SYNC1", np.concatenate([np.full(count, sign) for sign, count in SYNC1_RLE]), 65.708),
    ("SYNC2", np.concatenate([np.full(count, sign) for sign, count in SYNC2_RLE]), 65.802),
)

PRODUCT_TYPES = {
    0: "Genius H",
    1: "Genius Hx",
    2: "Genius Plus",
    3: "Genius Plus X",
}
RADIO_PRODUCT_TYPES = {
    0: "no radio module",
    1: "FM.Basis",
    2: "FM.Pro",
    3: "FM.MCP",
    4: "FM.Basis X",
    5: "FM.Pro X",
}

WARRANTY_FLAG_NAMES = (
    "max_dirty",
    "out_of_temperature",
    "detector_too_old",
    "storage_time_exceeded",
    "activation_time_exceeded",
    "too_many_events",
    "too_many_alarms",
    "too_many_faults",
    "too_many_self_tests",
    "too_many_radio_faults",
    "too_many_radio_out_of_order_events",
    "radio_installation_too_old",
    "too_much_radio_activity",
    "too_much_radio_interference",
    "too_many_radio_tx_events",
    "too_many_radio_rx_events",
)

# Radio status bits; the raw value is retained in the output as well.
RADIO_STATE_FLAGS = {
    0x01: "remote_alarm",
    0x02: "radio_link_error",
    0x04: "remote_error",
    0x08: "remote_battery_low",
    0x10: "fm_battery_low_fault",
    0x20: "self_test",
    0x40: "transmission_range_test",
    0x80: "fm_fault",
}
RADIO_SWITCH_FLAGS = {
    0x01: "suppress_warnings",
    0x02: "suppress_alarms",
    0x04: "send_collective_alarm",
    0x08: "receive_collective_alarm",
    0x10: "radio_link_supervision",
    0x20: "reduced_transmitting_power",
}


class DecodeError(RuntimeError):
    """Raised when no checksum-valid Smartsonic frame can be recovered."""


@dataclass(frozen=True)
class WireFrame:
    """Checksum-validated Smartsonic frame."""

    length: int
    payload: bytes
    received_crc: int
    computed_crc: int
    wire: bytes


@dataclass(frozen=True)
class DecodeCandidate:
    """Signal metadata for a successfully recovered wire frame."""

    frame: WireFrame
    sync_name: str
    sync_start: int
    sync_correlation: float
    sync_quality_percent: float
    symbol_start: float
    symbol_period: float
    polarity: int
    hamming_distance: int
    low_tone_hz: float | None = None
    high_tone_hz: float | None = None


def crc16_cms(data: bytes) -> int:
    """CRC-16/CMS (poly 0x8005, init 0xffff, non-reflected, xorout 0)."""
    crc = 0xFFFF
    for byte in data:
        for _ in range(8):
            feedback = ((crc >> 8) ^ byte) & 0x80
            crc = ((crc << 1) ^ 0x8005) & 0xFFFF if feedback else (crc << 1) & 0xFFFF
            byte = (byte << 1) & 0xFF
    return crc


def parse_wire_frame(wire: bytes) -> WireFrame:
    """Validate ``length + payload + CRC-low + CRC-high`` framing."""
    if len(wire) < 3:
        raise DecodeError("frame is shorter than length plus CRC")
    length = wire[0]
    if length >= 40:
        raise DecodeError(f"invalid payload length {length}; protocol requires length < 40")
    expected = length + 3
    if len(wire) != expected:
        raise DecodeError(f"frame length byte says {length}, but {len(wire)} wire bytes were supplied")
    payload = wire[1 : 1 + length]
    received = wire[-2] | (wire[-1] << 8)
    computed = crc16_cms(payload)
    if received != computed:
        raise DecodeError(f"CRC mismatch: received 0x{received:04x}, computed 0x{computed:04x}")
    return WireFrame(length, payload, received, computed, bytes(wire))


def _flag_names(raw: int, mapping: dict[int, str]) -> list[str]:
    return [name for mask, name in mapping.items() if raw & mask]


def _relative_event_date(reference_date: dt.date, production_age: int, offset: int) -> str | None:
    if offset == 0xFFFF or offset > production_age:
        return None
    return (reference_date - dt.timedelta(days=production_age - offset)).isoformat()


def parse_payload(payload: bytes, reference_date: dt.date | None = None) -> dict[str, Any]:
    """Parse a 20- or 32-byte Smartsonic payload.

    Dates in Smartsonic are relative counters, not absolute timestamps.
    ``reference_date`` therefore means the date on which the acoustic readout
    was made.  It defaults to today's local date.
    """
    if len(payload) < MIN_KNOWN_PAYLOAD_LENGTH:
        raise DecodeError(f"known payload parser needs at least 20 bytes, got {len(payload)}")
    if 20 < len(payload) < 32:
        raise DecodeError(f"truncated radio extension: got {len(payload)} bytes, need at least 32")

    reference_date = reference_date or dt.date.today()
    serial = int.from_bytes(payload[1:5], "big")
    type_byte = payload[5]
    product_code = type_byte & 0x0F
    radio_product_code = type_byte >> 4
    last_alarm_offset = int.from_bytes(payload[9:11], "little")
    production_age = int.from_bytes(payload[11:13], "little")
    storage_hours = int.from_bytes(payload[13:15], "little")
    last_selftest_offset = int.from_bytes(payload[15:17], "little")
    warranty_raw = int.from_bytes(payload[17:19], "little")
    status = payload[19]
    drift_state = (status >> 3) & 0x0F
    if drift_state > 8:
        raise DecodeError(f"invalid detector drift state {drift_state}; valid range is 0..8")

    result: dict[str, Any] = {
        # Byte 0 is a header byte that is not evaluated; values 0x00 and 0x05
        # occur.  A protocol version is a possible interpretation.
        "unknown_byte_0": payload[0],
        "protocol_version_candidate": payload[0],
        "detector_serial": serial,
        "detector_serial_hex": payload[1:5].hex(),
        "product_type_code": product_code,
        "product_type": PRODUCT_TYPES.get(product_code, "unknown"),
        "radio_product_type_code": radio_product_code,
        "radio_product_type": RADIO_PRODUCT_TYPES.get(radio_product_code, "unknown"),
        "deinstallation_count": payload[6],
        "alarm_count": payload[7],
        "alarm_count_last_3_months": payload[8],
        "last_alarm_offset_days": None if last_alarm_offset == 0xFFFF else last_alarm_offset,
        "production_age_days": production_age,
        "hours_in_storage_mode": storage_hours,
        "last_selftest_offset_days": None if last_selftest_offset == 0xFFFF else last_selftest_offset,
        "readout_reference_date": reference_date.isoformat(),
        "production_date": (reference_date - dt.timedelta(days=production_age)).isoformat(),
        "last_alarm_date": _relative_event_date(reference_date, production_age, last_alarm_offset),
        "last_selftest_date": _relative_event_date(reference_date, production_age, last_selftest_offset),
        "warranty_flags_raw": warranty_raw,
        "warranty_possible": warranty_raw == 0,
        "warranty_state_app_mask": 1 if warranty_raw == 0 else warranty_raw << 1,
        "warranty_flags": [
            name for bit, name in enumerate(WARRANTY_FLAG_NAMES) if warranty_raw & (1 << bit)
        ],
        "status_raw": status,
        "battery_low_fault": bool(status & 0x01),
        "device_fault": bool(status & 0x02),
        "radio_network_fault": bool(status & 0x04),
        "drift_state": drift_state,
        "dirt_forecast_negative": bool(status & 0x80),
        "has_radio_extension": len(payload) >= 32,
    }

    if len(payload) >= 32:
        radio_state = payload[20]
        radio_serial = int.from_bytes(payload[21:25], "big")
        line_id = int.from_bytes(payload[25:29], "big")
        line_byte = payload[29]
        line_letter_code = line_byte >> 4
        line_number = line_byte & 0x0F
        if line_letter_code > 9 or line_number > 9:
            raise DecodeError(
                f"invalid radio line byte 0x{line_byte:02x}; both nibbles must be decimal digits"
            )
        line_letter = chr(ord("A") + line_letter_code)
        line = f"{line_letter}{line_number}"
        radio_switch = payload[30]
        if radio_switch & 0xC0:
            raise DecodeError(
                f"invalid radio switch mask 0x{radio_switch:02x}; bits 6 and 7 are reserved"
            )
        interference_raw = payload[31]
        result["radio"] = {
            "state_raw": radio_state,
            "state_flags": _flag_names(radio_state, RADIO_STATE_FLAGS),
            "serial": radio_serial,
            "serial_hex": payload[21:25].hex(),
            "line_id": line_id,
            "line_id_hex": payload[25:29].hex(),
            "line_code_raw": line_byte,
            "line_letter_code": line_letter_code,
            "line_letter": line_letter,
            "line_number": line_number,
            "line": line,
            "switch_raw": radio_switch,
            "switch_flags": _flag_names(radio_switch, RADIO_SWITCH_FLAGS),
            "switch_reserved_bits_set": False,
            "interference_raw": interference_raw,
            "interference_percent": interference_raw / 10.0 if interference_raw > 0 else 0.0,
        }
    else:
        result["radio"] = None

    if len(payload) > 32:
        result["unparsed_extension_hex"] = payload[32:].hex()
    return result


def _read_pcm_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as wav_file:
        channels = wav_file.getnchannels()
        width = wav_file.getsampwidth()
        rate = wav_file.getframerate()
        frames = wav_file.readframes(wav_file.getnframes())

    if width == 1:
        samples = (np.frombuffer(frames, dtype=np.uint8).astype(np.float64) - 128.0) / 128.0
    elif width == 2:
        samples = np.frombuffer(frames, dtype="<i2").astype(np.float64) / 32768.0
    elif width == 3:
        packed = np.frombuffer(frames, dtype=np.uint8).reshape(-1, 3)
        values = (
            packed[:, 0].astype(np.int32)
            | (packed[:, 1].astype(np.int32) << 8)
            | (packed[:, 2].astype(np.int32) << 16)
        )
        values = (values ^ 0x800000) - 0x800000
        samples = values.astype(np.float64) / 8_388_608.0
    elif width == 4:
        samples = np.frombuffer(frames, dtype="<i4").astype(np.float64) / 2_147_483_648.0
    else:
        raise DecodeError(f"unsupported PCM WAV sample width: {width} bytes")

    if channels > 1:
        samples = samples.reshape(-1, channels).mean(axis=1)
    return samples, rate


def _resample_linear(samples: np.ndarray, source_rate: int, target_rate: int) -> np.ndarray:
    if source_rate == target_rate:
        return samples
    if source_rate <= 0:
        raise DecodeError(f"invalid sample rate {source_rate}")
    output_length = int(round(len(samples) * target_rate / source_rate))
    source_positions = np.arange(output_length, dtype=np.float64) * source_rate / target_rate
    return np.interp(source_positions, np.arange(len(samples)), samples)


def load_audio(path: Path) -> np.ndarray:
    """Load any supported recording and return mono float PCM at 44.1 kHz."""
    try:
        samples, rate = _read_pcm_wav(path)
        return _resample_linear(samples, rate, ADC_RATE)
    except (wave.Error, EOFError):
        pass

    gst = shutil.which("gst-launch-1.0")
    if gst is None:
        raise DecodeError(
            f"{path} is not a PCM WAV and GStreamer is unavailable; "
            "install gst-launch-1.0 or convert it to PCM WAV"
        )
    with tempfile.TemporaryDirectory(prefix="smartsonic-") as temp_dir:
        decoded = Path(temp_dir) / "decoded.wav"
        command = [
            gst,
            "-q",
            "filesrc",
            f"location={path.resolve()}",
            "!",
            "decodebin",
            "!",
            "audioconvert",
            "!",
            "audioresample",
            "!",
            f"audio/x-raw,format=S16LE,channels=1,rate={ADC_RATE}",
            "!",
            "wavenc",
            "!",
            "filesink",
            f"location={decoded}",
        ]
        process = subprocess.run(command, capture_output=True, text=True, check=False)
        if process.returncode != 0:
            detail = process.stderr.strip() or process.stdout.strip() or "unknown GStreamer error"
            raise DecodeError(f"could not decode {path}: {detail}")
        samples, rate = _read_pcm_wav(decoded)
        if rate != ADC_RATE:
            raise DecodeError(f"GStreamer returned unexpected sample rate {rate}")
        return samples


def _iir_filter(samples: np.ndarray, a: Sequence[float], b: Sequence[float]) -> np.ndarray:
    """Transposed direct-form-II IIR filter."""
    a_array = np.asarray(a, dtype=np.float64)
    b_array = np.asarray(b, dtype=np.float64)
    if len(a_array) != len(b_array) or a_array[0] != 1.0:
        raise ValueError("IIR coefficient arrays must have equal length and a[0] == 1")
    state = np.zeros(len(a_array), dtype=np.float64)
    output = np.empty(len(samples), dtype=np.float64)
    order = len(a_array) - 1
    for index, sample in enumerate(samples):
        value = b_array[0] * sample + state[1]
        for tap in range(1, order):
            state[tap] = state[tap + 1] + b_array[tap] * sample - a_array[tap] * value
        state[order] = b_array[order] * sample - a_array[order] * value
        output[index] = value
    return output


def _rolling_median(samples: np.ndarray, width: int = 5) -> np.ndarray:
    history = np.zeros(width, dtype=np.float64)
    result = np.empty(len(samples), dtype=np.float64)
    for index, sample in enumerate(samples):
        history[1:] = history[:-1]
        history[0] = sample
        result[index] = np.median(history)
    return result


def demodulate(samples: np.ndarray) -> np.ndarray:
    """Convert 44.1-kHz PCM to the clamped 5,512.5-Hz frequency discriminator."""
    if len(samples) < ADC_RATE // 2:
        raise DecodeError("recording is too short to contain a Smartsonic frame")
    positions = np.arange(len(samples), dtype=np.float64)
    phase = 2.0 * np.pi * CARRIER_HZ / ADC_RATE * positions
    in_phase = samples * np.cos(phase)
    quadrature = samples * -np.sin(phase)

    first_a = (1.0, -1.85716065, 0.86670342)
    first_b = (0.00238569, 0.00477138, 0.00238569)
    in_phase = _iir_filter(in_phase, first_a, first_b)[::DECIMATION]
    quadrature = _iir_filter(quadrature, first_a, first_b)[::DECIMATION]

    second_a = (1.0, -1.99194039, 0.99197274)
    second_b = (0.99597828, -1.99195657, 0.99597828)
    in_phase = _iir_filter(in_phase, second_a, second_b)
    quadrature = _iir_filter(quadrature, second_a, second_b)

    discriminator = np.zeros_like(in_phase)
    # Central two-step phase derivative: z[n-1] crossed with z[n]-z[n-2],
    # normalized by |z[n]|².
    numerator = (
        in_phase[1:-1] * (quadrature[2:] - quadrature[:-2])
        - quadrature[1:-1] * (in_phase[2:] - in_phase[:-2])
    )
    denominator = np.maximum(in_phase[2:] ** 2 + quadrature[2:] ** 2, 1e-12)
    discriminator[2:] = numerator / denominator
    discriminator = _rolling_median(discriminator, 5)

    smooth_a = (1.0, -3.22692923, 3.9658094, -2.19293563, 0.45943954)
    smooth_b = (0.00033651, 0.00134602, 0.00201903, 0.00134602, 0.00033651)
    discriminator = _iir_filter(discriminator, smooth_a, smooth_b)
    discriminator = _iir_filter(discriminator, second_a, second_b)
    return np.clip(np.rint(discriminator * 1000.0), -CLAMP, CLAMP)


def _nearest_nibble(codeword: int) -> tuple[int, int]:
    best_nibble = 0
    best_distance = 9
    for nibble, encoded in enumerate(HAMMING_CODEBOOK):
        distance = (codeword ^ int(encoded)).bit_count()
        if distance < best_distance:
            best_nibble = nibble
            best_distance = distance
    return best_nibble, best_distance


def _decode_nibble_correlations(correlations: np.ndarray) -> tuple[int, int]:
    codeword = sum(int(correlations[bit] > 0) << bit for bit in range(8))
    nibble, distance = _nearest_nibble(codeword)
    if distance == 2:
        # Two hard errors are ambiguous for this distance-4 code: retry after
        # flipping the least confident bit (smallest absolute correlation).
        # The original hard distance remains the reported metric.
        weakest = int(np.argmin(np.abs(correlations)))
        nibble, _ = _nearest_nibble(codeword ^ (1 << weakest))
    return nibble, distance


def hamming_decode_correlations(correlations: np.ndarray) -> tuple[bytes, int]:
    """Decode groups of 16 physical bits into bytes, low nibble first."""
    if len(correlations) % 16:
        raise ValueError("correlation count must be a multiple of 16")
    decoded = bytearray()
    total_distance = 0
    for offset in range(0, len(correlations), 16):
        low, low_distance = _decode_nibble_correlations(correlations[offset : offset + 8])
        high, high_distance = _decode_nibble_correlations(correlations[offset + 8 : offset + 16])
        decoded.append(low | (high << 4))
        total_distance += low_distance + high_distance
    return bytes(decoded), total_distance


def hamming_encode(data: bytes) -> np.ndarray:
    """Encode bytes to physical bit order; useful for test-vector generation."""
    bits: list[int] = []
    for byte in data:
        for nibble in (byte & 0x0F, byte >> 4):
            codeword = int(HAMMING_CODEBOOK[nibble])
            bits.extend((codeword >> bit) & 1 for bit in range(8))
    return np.asarray(bits, dtype=np.uint8)


def build_wire_frame(payload: bytes) -> bytes:
    """Build a framing- and CRC-valid wire byte sequence for tests."""
    if len(payload) >= 40:
        raise ValueError("payload must contain fewer than 40 bytes")
    crc = crc16_cms(payload)
    return bytes([len(payload)]) + payload + crc.to_bytes(2, "little")


def _best_sync_candidates(discriminator: np.ndarray, per_pattern: int = 16) -> list[tuple]:
    candidates: list[tuple] = []
    for name, pattern, nominal_period in SYNC_PATTERNS:
        if len(discriminator) < len(pattern):
            continue
        correlations = np.correlate(discriminator, pattern, mode="valid")
        magnitudes = np.abs(correlations)
        eligible = np.flatnonzero(magnitudes >= SYNC_THRESHOLD)
        if not len(eligible):
            continue
        order = eligible[np.argsort(magnitudes[eligible])[::-1]]
        selected: list[int] = []
        for position in order:
            position = int(position)
            # One physical preamble produces several strong autocorrelation
            # sidelobes. Distinct complete frames cannot start less than one
            # preamble length apart, so suppress that whole neighbourhood.
            if any(abs(position - previous) < len(pattern) for previous in selected):
                continue
            selected.append(position)
            correlation = float(correlations[position])
            quality = 100.0 * abs(correlation) / (CLAMP * len(pattern))
            candidates.append((quality, name, position, correlation, pattern, nominal_period))
            if len(selected) == per_pattern:
                break
    return sorted(candidates, reverse=True)


def _decode_at(
    bit_correlations: np.ndarray,
    start: float,
    period: float,
    polarity: int,
    byte_count: int,
    local_radius: int = 0,
    recover_clock: bool = False,
) -> tuple[bytes, int] | None:
    symbol_count = byte_count * 16
    if local_radius <= 0:
        indices = np.rint(start + np.arange(symbol_count) * period).astype(np.int64)
        if indices[0] < 0 or indices[-1] >= len(bit_correlations):
            return None
        return hamming_decode_correlations(bit_correlations[indices] * polarity)

    recovered = np.empty(symbol_count, dtype=np.float64)
    cumulative_correction = 0.0
    weighted_offset = 0.0
    total_weight = 0.0
    offsets = np.arange(-local_radius, local_radius + 1, dtype=np.int64)
    for symbol in range(symbol_count):
        expected = start + symbol * period + cumulative_correction
        center = int(round(expected))
        indices = center + offsets
        if indices[0] < 0 or indices[-1] >= len(bit_correlations):
            return None
        values = bit_correlations[indices]
        selected = int(np.argmax(np.abs(values)))
        recovered[symbol] = values[selected] * polarity
        if recover_clock:
            weight = abs(float(values[selected]))
            weighted_offset += (float(indices[selected]) - expected) * weight
            total_weight += weight
            if symbol % 16 == 15 and total_weight > 0.0:
                cumulative_correction += weighted_offset / total_weight
                weighted_offset = 0.0
                total_weight = 0.0
    return hamming_decode_correlations(recovered)


def _recover_after_sync(
    bit_correlations: np.ndarray,
    sync: tuple,
) -> DecodeCandidate | None:
    quality, sync_name, sync_start, sync_correlation, sync_pattern, nominal_period = sync
    sync_end = sync_start + len(sync_pattern)

    quick_candidates: list[tuple[int, float, float, int, bytes]] = []
    periods = np.arange(nominal_period - 2.5, nominal_period + 2.5 + 1e-9, 0.02)
    starts = range(sync_end - 25, sync_end + 91)
    for period in periods:
        for start in starts:
            for polarity in (1, -1):
                quick = _decode_at(bit_correlations, start, float(period), polarity, 7)
                if quick is None:
                    continue
                decoded, distance = quick
                length = decoded[0]
                if not MIN_KNOWN_PAYLOAD_LENGTH <= length <= MAX_PAYLOAD_LENGTH:
                    continue
                # Byte 6 on the wire is payload byte 5, whose two nibbles are
                # product type codes.  This is a ranking hint, not a checksum.
                type_byte = decoded[6]
                product_known = (type_byte & 0x0F) in PRODUCT_TYPES
                radio_known = (type_byte >> 4) in RADIO_PRODUCT_TYPES
                penalty = 0 if product_known and radio_known else 12
                quick_candidates.append((distance + penalty, float(start), float(period), polarity, decoded))

    # Closely spaced clock hypotheses often yield the same bytes.  Trying the
    # best 4,000 keeps the search bounded while leaving broad tolerance for
    # noisy captures and clock error.
    ranked = sorted(quick_candidates)[:4000]
    for _, start, period, polarity, quick_bytes in ranked:
        length = quick_bytes[0]
        complete = _decode_at(bit_correlations, start, period, polarity, length + 3)
        if complete is None:
            continue
        wire, distance = complete
        try:
            frame = parse_wire_frame(wire)
        except DecodeError:
            continue
        return DecodeCandidate(
            frame=frame,
            sync_name=sync_name,
            sync_start=sync_start,
            sync_correlation=sync_correlation,
            sync_quality_percent=quality,
            symbol_start=start,
            symbol_period=period,
            polarity=polarity,
            hamming_distance=distance,
        )

    # If a globally linear clock is insufficient, search every symbol within
    # ±12 demod samples and update the phase once per decoded byte.  Only the
    # strongest timing hypotheses reach this more expensive pass.
    for _, start, period, polarity, quick_bytes in ranked[:500]:
        length = quick_bytes[0]
        complete = _decode_at(
            bit_correlations,
            start,
            period,
            polarity,
            length + 3,
            local_radius=12,
            recover_clock=True,
        )
        if complete is None:
            continue
        wire, distance = complete
        try:
            frame = parse_wire_frame(wire)
        except DecodeError:
            continue
        return DecodeCandidate(
            frame=frame,
            sync_name=sync_name,
            sync_start=sync_start,
            sync_correlation=sync_correlation,
            sync_quality_percent=quality,
            symbol_start=start,
            symbol_period=period,
            polarity=polarity,
            hamming_distance=distance,
        )
    return None


def _spectral_peak(samples: np.ndarray, low_hz: float = 3500.0, high_hz: float = 5400.0) -> float:
    if len(samples) < 64:
        return math.nan
    fft_size = max(16_384, 1 << math.ceil(math.log2(len(samples))))
    spectrum = np.abs(np.fft.rfft(samples * np.hanning(len(samples)), fft_size))
    frequencies = np.fft.rfftfreq(fft_size, 1.0 / ADC_RATE)
    valid = np.flatnonzero((frequencies >= low_hz) & (frequencies <= high_hz))
    peak = int(valid[np.argmax(spectrum[valid])])
    # Sub-bin interpolation of the log magnitude gives repeatable estimates
    # even though each constant-frequency preamble run is short.
    if 0 < peak < len(spectrum) - 1:
        left, middle, right = np.log(spectrum[peak - 1 : peak + 2] + 1e-15)
        denominator = left - 2.0 * middle + right
        correction = 0.5 * (left - right) / denominator if denominator else 0.0
    else:
        correction = 0.0
    return (peak + correction) * ADC_RATE / fft_size


def _estimate_sync_tones(samples: np.ndarray, candidate: DecodeCandidate) -> tuple[float | None, float | None]:
    pattern_entry = next(item for item in SYNC_PATTERNS if item[0] == candidate.sync_name)
    pattern = pattern_entry[1]
    # Recover run lengths from the selected template.  A ten-demod-sample trim
    # on both sides avoids FSK transition energy and absorbs filter delay.
    transitions = np.flatnonzero(np.diff(pattern) != 0) + 1
    boundaries = np.r_[0, transitions, len(pattern)]
    correlation_polarity = 1 if candidate.sync_correlation >= 0 else -1
    by_sign: dict[int, list[float]] = {-1: [], 1: []}
    raw_sync_start = candidate.sync_start * DECIMATION
    for start, end in zip(boundaries[:-1], boundaries[1:]):
        if end - start < 60:
            continue
        trim = 10
        raw_start = raw_sync_start + (int(start) + trim) * DECIMATION
        raw_end = raw_sync_start + (int(end) - trim) * DECIMATION
        if raw_start < 0 or raw_end > len(samples):
            continue
        sign = int(pattern[start]) * correlation_polarity
        frequency = _spectral_peak(samples[raw_start:raw_end])
        if math.isfinite(frequency):
            by_sign[sign].append(frequency)
    if not by_sign[-1] or not by_sign[1]:
        return None, None
    low = float(np.median(by_sign[-1]))
    high = float(np.median(by_sign[1]))
    return (min(low, high), max(low, high))


def decode_all_samples(samples: np.ndarray) -> list[DecodeCandidate]:
    """Recover every non-overlapping CRC-valid frame in 44.1-kHz mono PCM."""
    discriminator = demodulate(np.asarray(samples, dtype=np.float64))
    if len(discriminator) < len(BIT_TEMPLATE):
        raise DecodeError("demodulated recording is too short")
    bit_correlations = np.correlate(discriminator, BIT_TEMPLATE, mode="valid")
    sync_candidates = _best_sync_candidates(discriminator)
    if not sync_candidates:
        raise DecodeError(
            f"no Smartsonic preamble reaches the correlation threshold {SYNC_THRESHOLD}"
        )
    recovered: list[DecodeCandidate] = []
    for sync in sync_candidates:
        if any(abs(int(sync[2]) - previous.sync_start) < 100 for previous in recovered):
            continue
        candidate = _recover_after_sync(bit_correlations, sync)
        if candidate is not None:
            # SYNC1 and SYNC2 are close inverses and can both lock onto the same
            # physical preamble.  Keep one result per preamble occurrence, but
            # retain identical frames transmitted again later in the file.
            low_tone, high_tone = _estimate_sync_tones(samples, candidate)
            recovered.append(replace(candidate, low_tone_hz=low_tone, high_tone_hz=high_tone))
    if recovered:
        return sorted(recovered, key=lambda candidate: candidate.sync_start)
    best_quality = sync_candidates[0][0]
    raise DecodeError(
        "Smartsonic preamble found but no CRC-valid frame recovered "
        f"(best sync quality {best_quality:.1f}%)"
    )


def decode_samples(samples: np.ndarray) -> DecodeCandidate:
    """Return the first CRC-valid frame from 44.1-kHz mono PCM."""
    return decode_all_samples(samples)[0]


def decode_all_audio(path: Path) -> list[DecodeCandidate]:
    """Load one recording and recover all non-overlapping valid frames."""
    return decode_all_samples(load_audio(path))


def decode_audio(path: Path) -> DecodeCandidate:
    """Load and decode one recording."""
    return decode_all_audio(path)[0]


def candidate_to_dict(candidate: DecodeCandidate, reference_date: dt.date | None = None) -> dict[str, Any]:
    frame = candidate.frame
    signal: dict[str, Any] = {
        "sync": candidate.sync_name,
        "sync_start_demod_sample": candidate.sync_start,
        "sync_correlation": candidate.sync_correlation,
        "sync_quality_percent": round(candidate.sync_quality_percent, 2),
        "symbol_start_demod_sample": round(candidate.symbol_start, 3),
        "symbol_period_demod_samples": round(candidate.symbol_period, 5),
        "physical_bit_rate": round(DEMOD_RATE / candidate.symbol_period, 3),
        "polarity": candidate.polarity,
        "total_hamming_distance": candidate.hamming_distance,
    }
    if candidate.low_tone_hz is not None and candidate.high_tone_hz is not None:
        signal.update(
            {
                "low_tone_hz": round(candidate.low_tone_hz, 1),
                "high_tone_hz": round(candidate.high_tone_hz, 1),
                "tone_center_hz": round((candidate.low_tone_hz + candidate.high_tone_hz) / 2.0, 1),
                "tone_separation_hz": round(candidate.high_tone_hz - candidate.low_tone_hz, 1),
            }
        )
    return {
        "wire_hex": frame.wire.hex(),
        "payload_hex": frame.payload.hex(),
        "payload_length": frame.length,
        "crc": {
            "received_hex": f"{frame.received_crc:04x}",
            "computed_hex": f"{frame.computed_crc:04x}",
            "valid": frame.received_crc == frame.computed_crc,
            "wire_order_hex": frame.wire[-2:].hex(),
        },
        "signal": signal,
        "fields": parse_payload(frame.payload, reference_date),
    }


def _parse_date(value: str) -> dt.date:
    try:
        return dt.date.fromisoformat(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("date must use YYYY-MM-DD") from error


def _parse_hex(value: str) -> bytes:
    compact = "".join(value.split()).replace(":", "")
    try:
        return bytes.fromhex(compact)
    except ValueError as error:
        raise argparse.ArgumentTypeError("frame hex contains non-hex data or an odd digit count") from error


def _human_output(label: str, result: dict[str, Any]) -> str:
    fields = result["fields"]
    crc = result["crc"]
    lines = [
        f"{label}:",
        f"  Wire:          {result['wire_hex']}",
        f"  Payload ({result['payload_length']} B): {result['payload_hex']}",
        f"  CRC:           0x{crc['received_hex']} (valid, wire order {crc['wire_order_hex']})",
        f"  Payload[0]:    0x{fields['unknown_byte_0']:02x} (version hypothesis)",
        f"  Detector:      {fields['product_type']} (type code {fields['product_type_code']})",
        f"  Detector SN:   {fields['detector_serial']} (0x{fields['detector_serial_hex']})",
        f"  Radio module:  {fields['radio_product_type']}",
        f"  Production:    {fields['production_date']} "
        f"(age {fields['production_age_days']} days, readout {fields['readout_reference_date']})",
        f"  Removals:      {fields['deinstallation_count']}",
        f"  Alarms:        {fields['alarm_count']} "
        f"(last 3 months: {fields['alarm_count_last_3_months']})",
        f"  Last alarm:    {fields['last_alarm_date'] or 'none'}",
        f"  Self-test:     {fields['last_selftest_date'] or 'none'}",
        f"  Storage mode:  {fields['hours_in_storage_mode']} hours",
        f"  Status byte:   0x{fields['status_raw']:02x}",
        f"  Warranty flags: 0x{fields['warranty_flags_raw']:04x}",
    ]
    if "signal" in result:
        signal = result["signal"]
        lines.extend(
            [
                f"  Sync:          {signal['sync']} ({signal['sync_quality_percent']} %)",
                f"  Bit rate:      {signal['physical_bit_rate']} bit/s",
                f"  FEC distance:  {signal['total_hamming_distance']}",
            ]
        )
        if "low_tone_hz" in signal:
            lines.append(
                f"  Tones:         {signal['low_tone_hz']} / {signal['high_tone_hz']} Hz "
                f"(separation {signal['tone_separation_hz']} Hz)"
            )
    if fields["radio"] is not None:
        radio = fields["radio"]
        lines.extend(
            [
                f"  Radio SN:      {radio['serial']} (0x{radio['serial_hex']})",
                f"  Line:          {radio['line'] or 'invalid/unknown'}; ID {radio['line_id']}",
                f"  Interference:  {radio['interference_percent']} %",
            ]
        )
    return "\n".join(lines)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audio", nargs="*", type=Path, help="WAV, M4A or another GStreamer-readable recording")
    parser.add_argument("--frame-hex", type=_parse_hex, help="parse an already recovered wire frame")
    parser.add_argument(
        "--readout-date",
        type=_parse_date,
        help="date of acoustic readout (YYYY-MM-DD); defaults to today's local date",
    )
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    parser.add_argument(
        "--all-frames",
        action="store_true",
        help="enumerate every non-overlapping CRC-valid frame in each recording",
    )
    arguments = parser.parse_args(argv)
    if not arguments.audio and arguments.frame_hex is None:
        parser.error("supply at least one audio file or --frame-hex")

    outputs: list[dict[str, Any]] = []
    exit_code = 0
    if arguments.frame_hex is not None:
        try:
            frame = parse_wire_frame(arguments.frame_hex)
            result = {
                "wire_hex": frame.wire.hex(),
                "payload_hex": frame.payload.hex(),
                "payload_length": frame.length,
                "crc": {
                    "received_hex": f"{frame.received_crc:04x}",
                    "computed_hex": f"{frame.computed_crc:04x}",
                    "valid": True,
                    "wire_order_hex": frame.wire[-2:].hex(),
                },
                "fields": parse_payload(frame.payload, arguments.readout_date),
            }
            outputs.append({"input": "frame-hex", "result": result})
        except DecodeError as error:
            outputs.append({"input": "frame-hex", "error": str(error)})
            exit_code = 1

    for path in arguments.audio:
        try:
            candidates = decode_all_audio(path) if arguments.all_frames else [decode_audio(path)]
            for frame_index, candidate in enumerate(candidates, 1):
                label = f"{path}#{frame_index}" if arguments.all_frames else str(path)
                result = candidate_to_dict(candidate, arguments.readout_date)
                outputs.append({"input": label, "result": result})
        except (DecodeError, OSError) as error:
            outputs.append({"input": str(path), "error": str(error)})
            exit_code = 1

    if arguments.json:
        print(json.dumps(outputs, indent=2, ensure_ascii=False))
    else:
        for index, output in enumerate(outputs):
            if index:
                print()
            if "error" in output:
                print(f"{output['input']}: ERROR: {output['error']}")
            else:
                print(_human_output(output["input"], output["result"]))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
