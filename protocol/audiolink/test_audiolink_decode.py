import tempfile
import unittest
import wave
from pathlib import Path

from audiolink_decode import (
    DecodeError,
    canonical_frame,
    crc_ccitt_false,
    decode_fields,
    decode_file,
    wire_crc_valid,
    wire_to_payload,
)


FRAME_A_WIRE = bytes.fromhex(
    "00 00 72 5e 1a 02 00 0f 5e 72 1a 01 00 00 00 00 "
    "72 00 00 00 00 00 00 72 00 00 5e 1a 02 01 "
    "72 25 49 f4 7c ad 00 72 4b 02 aa"
)

FRAME_B_WIRE = bytes.fromhex(
    "00 00 72 5e 1a 02 00 0f 00 72 00 00 00 00 00 00 "
    "72 00 00 00 00 00 00 72 00 00 5e 1a 01 01 "
    "72 25 49 ef 7c ad 00 72 91 07 aa"
)


class AudioLinkDecoderTests(unittest.TestCase):
    def test_standard_crc_check_value(self) -> None:
        self.assertEqual(crc_ccitt_false(b"123456789"), 0x29B1)

    def test_short_wav_fails_cleanly(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".wav") as handle:
            with wave.open(handle.name, "wb") as wav_file:
                wav_file.setnchannels(1)
                wav_file.setsampwidth(2)
                wav_file.setframerate(48000)
                wav_file.writeframes(b"\x00\x00" * 4800)
            with self.assertRaisesRegex(DecodeError, "too short"):
                decode_file(Path(handle.name))

    def test_both_example_frames_have_valid_crc(self) -> None:
        self.assertTrue(wire_crc_valid(FRAME_A_WIRE))
        self.assertTrue(wire_crc_valid(FRAME_B_WIRE))
        self.assertEqual(crc_ccitt_false(wire_to_payload(FRAME_A_WIRE)), 0x4B02)
        self.assertEqual(crc_ccitt_false(wire_to_payload(FRAME_B_WIRE)), 0x9107)

    def test_app_canonical_normalization(self) -> None:
        frame = canonical_frame(FRAME_A_WIRE)
        self.assertEqual(len(frame), 34)
        self.assertEqual(frame[0], 0xAA)
        self.assertEqual(frame[-1], 0xAA)
        self.assertEqual(frame[24:28].hex(), "012549f4")

    def test_frame_a_fields(self) -> None:
        fields = decode_fields(wire_to_payload(FRAME_A_WIRE))
        self.assertEqual(fields["alarm_id"], "012549f4")
        self.assertEqual(fields["model"], "Ei650i")
        self.assertEqual(fields["uptime_days"], 0)
        self.assertEqual(fields["battery_voltage_v"], 3.04)
        self.assertEqual(fields["test_button"]["count"], 1)
        self.assertEqual(fields["removal"]["count"], 1)
        self.assertEqual(fields["manufacture_date"], "2025-09-15")

    def test_frame_b_fields(self) -> None:
        fields = decode_fields(wire_to_payload(FRAME_B_WIRE))
        self.assertEqual(fields["alarm_id"], "012549ef")
        self.assertEqual(fields["test_button"]["count"], 0)
        self.assertEqual(fields["removal"]["count"], 0)

    def test_original_recordings_end_to_end(self) -> None:
        root = Path(__file__).resolve().parent.parent.parent / "data"
        cases = (
            (root / "sg Schlafzimmer.m4a", "012549f4", "4b02"),
            (root / "Wohnzimmer.m4a", "012549ef", "9107"),
            # two recordings of one detector amid in-band background noise;
            # they require the marker-anchored local thresholds
            (root / "kz1.m4a", "01a55d9d", "e92c"),
            (root / "kz2.m4a", "01a55d9d", "c3e0"),
        )
        cases = tuple(case for case in cases if case[0].exists())
        if not cases:
            self.skipTest("original regression recordings are not present")
        for path, alarm_id, crc in cases:
            with self.subTest(path=path.name):
                result = decode_file(path)
                self.assertEqual(result["fields"]["alarm_id"], alarm_id)
                self.assertEqual(result["crc"]["received_hex"], crc)
                self.assertTrue(result["crc"]["valid"])
                self.assertEqual(result["framing_score"], "72/72")


if __name__ == "__main__":
    unittest.main()
