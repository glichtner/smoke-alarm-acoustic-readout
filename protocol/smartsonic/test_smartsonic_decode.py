import datetime as dt
import os
import tempfile
import unittest
import wave
from pathlib import Path

import numpy as np

from smartsonic_decode import (
    ADC_RATE,
    CARRIER_HZ,
    DECIMATION,
    DecodeError,
    REFERENCE_SYNTH_DEVIATION_HZ,
    HAMMING_CODEBOOK,
    SYNC1_RLE,
    _decode_at,
    build_wire_frame,
    candidate_to_dict,
    crc16_cms,
    decode_audio,
    decode_all_audio,
    decode_samples,
    hamming_decode_correlations,
    hamming_encode,
    parse_payload,
    parse_wire_frame,
)


FRAME_A_WIRE = bytes.fromhex(
    "14 00 83 b4 98 f4 02 05 00 00 ff ff 4c 00 26 07 "
    "46 00 00 00 00 ca 5a"
)
FRAME_B_WIRE = bytes.fromhex(
    "14 00 84 cb 5f b2 02 05 00 00 ff ff 50 00 22 07 "
    "4f 00 00 00 00 30 7e"
)

# Payload with header byte 0x05 used as a parser/FEC vector.
V5_NO_RADIO_PAYLOAD = bytes.fromhex(
    "05 12 a1 00 0b 03 00 00 00 ff ff da 02 00 00 bc 02 00 00 00"
)


def _synthesize_cp_fsk(wire: bytes) -> np.ndarray:
    """Small independent forward modulator for the end-to-end regression."""
    controls: list[float] = [0.0] * 2200
    for sign, count in SYNC1_RLE:
        controls.extend([float(sign)] * count)

    bits = hamming_encode(wire)
    symbol_period = 65.708
    symbol_samples = int(round(len(bits) * symbol_period))
    for sample in range(symbol_samples):
        bit_index = min(int(sample / symbol_period), len(bits) - 1)
        within = sample / symbol_period - bit_index
        first_sign = -1.0 if bits[bit_index] else 1.0
        controls.append(first_sign if within < 0.5 else -first_sign)
    controls.extend([0.0] * 2200)

    frequency_control = np.repeat(np.asarray(controls), DECIMATION)
    frequencies = CARRIER_HZ + REFERENCE_SYNTH_DEVIATION_HZ * frequency_control
    phase = np.cumsum(2.0 * np.pi * frequencies / ADC_RATE)
    return 0.70 * np.sin(phase)


def _write_wav(path: Path, samples: np.ndarray) -> None:
    pcm = np.clip(np.rint(samples * 32767.0), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(ADC_RATE)
        wav_file.writeframes(pcm.tobytes())


class SmartsonicDecoderTests(unittest.TestCase):
    def test_crc_standard_check_value(self) -> None:
        self.assertEqual(crc16_cms(b"123456789"), 0xAEE7)

    def test_frame_a_crc_and_fields(self) -> None:
        frame = parse_wire_frame(FRAME_A_WIRE)
        self.assertEqual(frame.length, 20)
        self.assertEqual(frame.received_crc, 0x5ACA)
        fields = parse_payload(frame.payload, dt.date(2020, 5, 20))
        self.assertEqual(fields["unknown_byte_0"], 0)
        self.assertEqual(fields["detector_serial"], 2_209_650_932)
        self.assertEqual(fields["detector_serial_hex"], "83b498f4")
        self.assertEqual(fields["product_type"], "Genius Plus")
        self.assertEqual(fields["production_date"], "2020-03-05")
        self.assertEqual(fields["last_selftest_date"], "2020-05-14")
        self.assertEqual(fields["deinstallation_count"], 5)
        self.assertEqual(fields["hours_in_storage_mode"], 1830)
        self.assertTrue(fields["warranty_possible"])
        self.assertIsNone(fields["radio"])

    def test_v5_style_payload_and_crc(self) -> None:
        wire = build_wire_frame(V5_NO_RADIO_PAYLOAD)
        self.assertEqual(wire[-2:].hex(), "e058")
        frame = parse_wire_frame(wire)
        fields = parse_payload(frame.payload, dt.date(2026, 1, 1))
        self.assertEqual(fields["unknown_byte_0"], 5)
        self.assertEqual(fields["detector_serial"], 0x12A1000B)
        self.assertEqual(fields["product_type"], "Genius Plus X")
        self.assertFalse(fields["has_radio_extension"])

    def test_hamming_all_byte_values_round_trip(self) -> None:
        data = bytes(range(256))
        physical_bits = hamming_encode(data)
        correlations = np.where(physical_bits != 0, 100.0, -100.0)
        decoded, distance = hamming_decode_correlations(correlations)
        self.assertEqual(decoded, data)
        self.assertEqual(distance, 0)

    def test_hamming_corrects_one_bit_per_nibble(self) -> None:
        data = b"\x14\x83\xb4\x98\xf4"
        physical_bits = hamming_encode(data)
        for codeword in range(len(physical_bits) // 8):
            physical_bits[codeword * 8 + (codeword % 8)] ^= 1
        correlations = np.where(physical_bits != 0, 100.0, -100.0)
        decoded, distance = hamming_decode_correlations(correlations)
        self.assertEqual(decoded, data)
        self.assertEqual(distance, len(physical_bits) // 8)

    def test_hamming_distance_two_uses_soft_confidence(self) -> None:
        # Nibble 1 is codeword 0x87. Flipping bits 0 and 1 yields 0x84,
        # whose hard-distance tie would otherwise select nibble 0.
        physical_bits = hamming_encode(b"\x01")
        physical_bits[0] ^= 1
        physical_bits[1] ^= 1
        correlations = np.where(physical_bits != 0, 100.0, -100.0)
        correlations[0] = -1.0  # identify bit 0 as the least reliable error
        decoded, distance = hamming_decode_correlations(correlations)
        self.assertEqual(decoded, b"\x01")
        self.assertEqual(distance, 2)

    def test_local_symbol_search_tracks_per_byte_phase_drift(self) -> None:
        data = b"\x14\x83\xb4\x98"
        bits = hamming_encode(data)
        start = 40.0
        period = 66.0
        correlations = np.zeros(5000, dtype=np.float64)
        for symbol, bit in enumerate(bits):
            byte_phase = (symbol // 16) * 3
            position = int(round(start + symbol * period + byte_phase))
            correlations[position] = 100.0 if bit else -100.0
        recovered = _decode_at(
            correlations,
            start,
            period,
            1,
            len(data),
            local_radius=12,
            recover_clock=True,
        )
        self.assertIsNotNone(recovered)
        self.assertEqual(recovered[0], data)

    def test_radio_extension_bit_order(self) -> None:
        payload = bytearray(V5_NO_RADIO_PAYLOAD)
        payload[5] = 0x43  # Genius Plus X + FM.Basis X
        payload.extend(
            bytes.fromhex("01 ab 00 10 01 00 00 00 01 12 21 19")
        )
        fields = parse_payload(bytes(payload), dt.date(2026, 1, 1))
        radio = fields["radio"]
        self.assertEqual(radio["state_flags"], ["remote_alarm"])
        self.assertEqual(radio["serial"], 0xAB001001)
        self.assertEqual(radio["line_id"], 1)
        self.assertEqual(radio["line"], "B2")
        self.assertEqual(
            radio["switch_flags"],
            ["suppress_warnings", "reduced_transmitting_power"],
        )
        self.assertEqual(radio["interference_percent"], 2.5)

    def test_crc_rejects_mutated_payload(self) -> None:
        damaged = bytearray(FRAME_A_WIRE)
        damaged[3] ^= 1
        with self.assertRaisesRegex(Exception, "CRC mismatch"):
            parse_wire_frame(bytes(damaged))

    def test_payload_validity_constraints(self) -> None:
        bad_drift = bytearray(V5_NO_RADIO_PAYLOAD)
        bad_drift[19] = 9 << 3
        with self.assertRaisesRegex(DecodeError, "drift state"):
            parse_payload(bytes(bad_drift))

        radio = bytearray(V5_NO_RADIO_PAYLOAD)
        radio.extend(bytes.fromhex("00 ab 00 10 01 00 00 00 01 aa 00 00"))
        with self.assertRaisesRegex(DecodeError, "radio line"):
            parse_payload(bytes(radio))
        radio[29] = 0x12
        radio[30] = 0x40
        with self.assertRaisesRegex(DecodeError, "radio switch"):
            parse_payload(bytes(radio))

    def test_white_noise_fails_at_sync_threshold(self) -> None:
        samples = np.random.default_rng(12345).normal(0.0, 0.1, ADC_RATE * 6)
        with self.assertRaisesRegex(DecodeError, "correlation threshold"):
            decode_samples(samples)

    def test_synthetic_waveform_end_to_end(self) -> None:
        wire = build_wire_frame(V5_NO_RADIO_PAYLOAD)
        samples = _synthesize_cp_fsk(wire)
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "synthetic.wav"
            _write_wav(path, samples)
            candidate = decode_audio(path)
        self.assertEqual(candidate.frame.wire, wire)
        self.assertGreater(candidate.sync_quality_percent, 30.0)

    def test_additional_recording_when_available(self) -> None:
        value = os.environ.get("SMARTSONIC_REAL_RECORDING")
        if not value:
            self.skipTest("SMARTSONIC_REAL_RECORDING is not set")
        candidate = decode_audio(Path(value))
        self.assertEqual(candidate.frame.wire, FRAME_A_WIRE)

    def test_local_recording_end_to_end(self) -> None:
        path = Path(__file__).resolve().parent.parent.parent / "data" / "flur.m4a"
        if not path.exists():
            self.skipTest("user-supplied data/flur.m4a is not present")
        candidate = decode_audio(path)
        self.assertEqual(candidate.frame.wire, FRAME_B_WIRE)
        self.assertEqual(candidate.sync_name, "SYNC2")
        self.assertAlmostEqual(candidate.low_tone_hz, 4078.2, delta=15.0)
        self.assertAlmostEqual(candidate.high_tone_hz, 4776.5, delta=15.0)
        fields = parse_payload(candidate.frame.payload, dt.date(2026, 8, 30))
        self.assertEqual(fields["detector_serial"], 2_227_920_818)
        self.assertEqual(fields["alarm_count"], 0)
        self.assertEqual(fields["alarm_count_last_3_months"], 0)
        self.assertIsNone(fields["last_alarm_date"])
        self.assertFalse(fields["battery_low_fault"])
        self.assertFalse(fields["device_fault"])
        self.assertFalse(fields["radio_network_fault"])
        self.assertEqual(fields["drift_state"], 0)
        self.assertEqual(len(decode_all_audio(path)), 1)


if __name__ == "__main__":
    unittest.main()
