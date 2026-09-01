package de.glichtner.rauchmelder.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Decodes Ei650i recordings when they are available as 48 kHz mono PCM WAV
 * (`audiolink-1.wav`, `audiolink-2.wav`) under the path from the
 * RAUCHMELDER_WAV_DIR environment variable; skipped otherwise.
 */
class RealRecordingTest {

    private fun loadWav(file: File): Pair<FloatArray, Int> = WavFiles.load(file)

    private fun decode(fileName: String): AudioLinkDecoder.DecodeResult? {
        val dir = System.getenv("RAUCHMELDER_WAV_DIR")
        assumeTrue("RAUCHMELDER_WAV_DIR not set", dir != null)
        val file = File(dir!!, fileName)
        assumeTrue("$file missing", file.isFile)
        val (samples, sampleRate) = loadWav(file)
        return AudioLinkDecoder.decode(samples, sampleRate)
    }

    @Test
    fun recording1() {
        val result = decode("audiolink-1.wav")
        assertNotNull(result)
        assertEquals("012549f4", result!!.fields.alarmId)
        assertEquals("Ei650i", result.fields.model)
        assertEquals(3.04, result.fields.batteryVoltage, 1e-9)
        assertEquals(1, result.fields.removal.count)
    }

    /** Recording with in-band noise prepended. */
    @Test
    fun recording1AfterNoise() {
        val dir = System.getenv("RAUCHMELDER_WAV_DIR")
        assumeTrue("RAUCHMELDER_WAV_DIR not set", dir != null)
        val file = File(dir!!, "audiolink-1.wav")
        assumeTrue("$file missing", file.isFile)
        val (recording, sampleRate) = loadWav(file)
        val random = java.util.Random(3)
        val pre = FloatArray(4 * sampleRate) {
            (random.nextGaussian() * 0.08 +
                0.3 * kotlin.math.sin(2.0 * Math.PI * 6800.0 * it / sampleRate)).toFloat()
        }
        val samples = pre + recording
        val result = AudioLinkDecoder.decode(samples, sampleRate)
        assertNotNull("frame behind noise not decoded", result)
        assertEquals("012549f4", result!!.fields.alarmId)
    }

    /**
     * Recordings 3 and 4 contain the frame amid in-band background noise that
     * shifts the discriminator baseline across the frame; decoding them
     * requires the marker-anchored local thresholds.
     */
    @Test
    fun recording3WithBackgroundNoise() {
        val result = decode("audiolink-3.wav")
        assertNotNull("frame not decoded", result)
        assertEquals("01a55d9d", result!!.fields.alarmId)
        assertEquals(3.04, result.fields.batteryVoltage, 1e-9)
    }

    @Test
    fun recording3At44100() {
        val result = decode("audiolink-3-44100.wav")
        assertNotNull("frame not decoded at 44.1 kHz", result)
        assertEquals("01a55d9d", result!!.fields.alarmId)
    }

    @Test
    fun recording4WithBackgroundNoise() {
        val result = decode("audiolink-4.wav")
        assertNotNull("frame not decoded", result)
        assertEquals("01a55d9d", result!!.fields.alarmId)
    }

    /**
     * Recordings 5-12 are voice-memo captures, two per detector (four further
     * detectors). The first capture of each pair must decode; a second,
     * heavily corrupted capture may fail, but must never yield a different
     * ID than its pair - a wrong ID would silently register a phantom
     * detector.
     */
    @Test
    fun recordingPairsYieldConsistentIds() {
        val pairs = listOf(
            Triple("audiolink-5.wav", "audiolink-6.wav", "01a55d9b"),
            Triple("audiolink-7.wav", "audiolink-8.wav", "01a55d95"),
            Triple("audiolink-9.wav", "audiolink-10.wav", "01a55d96"),
            Triple("audiolink-11.wav", "audiolink-12.wav", "01a55d98"),
        )
        var decoded = 0
        for ((first, second, id) in pairs) {
            val resultA = decode(first)
            assertNotNull("$first not decoded", resultA)
            assertEquals(id, resultA!!.fields.alarmId)
            decoded++
            val resultB = decode(second)
            if (resultB != null) {
                assertEquals("$second decoded with a wrong ID", id, resultB.fields.alarmId)
                decoded++
            }
        }
        assertTrue("expected at least 6 of 8 pair recordings to decode", decoded >= 6)
    }

    @Test
    fun recording2() {
        val result = decode("audiolink-2.wav")
        assertNotNull(result)
        assertEquals("012549ef", result!!.fields.alarmId)
        assertEquals(0, result.fields.testButton.count)
        assertEquals("2025-09-15", result.fields.manufactureDate)
    }
}
