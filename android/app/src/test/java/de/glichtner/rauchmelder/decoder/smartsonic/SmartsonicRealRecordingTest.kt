package de.glichtner.rauchmelder.decoder.smartsonic

import de.glichtner.rauchmelder.decoder.AudioLinkDecoder
import de.glichtner.rauchmelder.decoder.WavFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Decodes a Genius Plus recording when it is available as mono PCM WAV
 * (`smartsonic-44100.wav`, `smartsonic-48000.wav`) under the path from the
 * RAUCHMELDER_WAV_DIR environment variable; skipped otherwise.
 */
class SmartsonicRealRecordingTest {

    private fun load(fileName: String): Pair<FloatArray, Int> {
        val dir = System.getenv("RAUCHMELDER_WAV_DIR")
        assumeTrue("RAUCHMELDER_WAV_DIR not set", dir != null)
        val file = File(dir!!, fileName)
        assumeTrue("$file missing", file.isFile)
        return WavFiles.load(file)
    }

    @Test
    fun recordingAt44100() {
        val (samples, rate) = load("smartsonic-44100.wav")
        val result = SmartsonicDecoder.decode(samples, rate, LocalDate.of(2026, 8, 30))
        assertNotNull("frame not decoded", result)
        assertArrayEquals(WIRE_FRAME_B, result!!.frame.wire)
        assertEquals("SYNC2", result.syncName)
        assertEquals(2_227_920_818L, result.fields.detectorSerial)
        assertEquals(0, result.hammingDistance)
    }

    @Test
    fun recordingAt48000IsResampled() {
        val (samples, rate) = load("smartsonic-48000.wav")
        val result = SmartsonicDecoder.decode(samples, rate, LocalDate.of(2026, 8, 30))
        assertNotNull("frame not decoded at 48 kHz", result)
        assertArrayEquals(WIRE_FRAME_B, result!!.frame.wire)
    }

    /** Protocol auto-detection: each decoder must reject the other manufacturer's recording. */
    @Test
    fun decodersDoNotCrossDetect() {
        val (smartsonic, smartsonicRate) = load("smartsonic-44100.wav")
        assertNull(AudioLinkDecoder.decode(smartsonic, smartsonicRate))
        val (audiolink, audiolinkRate) = load("audiolink-1.wav")
        assertNull(SmartsonicDecoder.decode(audiolink, audiolinkRate, LocalDate.of(2026, 8, 30)))
    }
}
