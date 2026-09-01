package de.glichtner.rauchmelder.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Example frames A and B from protocol/audiolink/PROTOCOL.md. */
private val WIRE_FRAME_A = (
    "0000" + "725e1a02000f5e" + "721a0100000000" + "72000000000000" +
        "7200005e1a0201" + "722549f47cad00" + "724b02aa"
    ).hexToByteArray()

private val WIRE_FRAME_B = (
    "0000" + "725e1a02000f00" + "72000000000000" + "72000000000000" +
        "7200005e1a0101" + "722549ef7cad00" + "729107aa"
    ).hexToByteArray()

private fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class CrcTest {
    @Test
    fun checkValue() {
        assertEquals(0x29b1, AudioLinkDecoder.crcCcittFalse("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun bothReferenceWiresAreValid() {
        assertTrue(AudioLinkDecoder.wireCrcValid(WIRE_FRAME_A))
        assertTrue(AudioLinkDecoder.wireCrcValid(WIRE_FRAME_B))
    }

    @Test
    fun corruptedWireIsRejected() {
        val corrupted = WIRE_FRAME_A.clone()
        corrupted[4] = (corrupted[4].toInt() xor 0x01).toByte()
        assertFalse(AudioLinkDecoder.wireCrcValid(corrupted))
    }
}

class FieldDecodingTest {
    @Test
    fun frameAFields() {
        val fields = decodeFields(AudioLinkDecoder.wireToPayload(WIRE_FRAME_A))
        assertEquals("012549f4", fields.alarmId)
        assertEquals("Ei650i", fields.model)
        assertEquals(0, fields.uptimeHours)
        assertEquals(3.04, fields.batteryVoltage, 1e-9)
        assertEquals(BatteryStatus.GREEN, fields.batteryStatus)
        assertTrue(fields.sensorOk)
        assertEquals(0.0, fields.contaminationLevel!!, 1e-9)
        assertEquals(1, fields.testButton.count)
        assertEquals(0, fields.testButton.ageHours!!)
        assertEquals(0, fields.smokeAlarm.count)
        assertEquals(0, fields.lowBattery.count)
        assertEquals(1, fields.removal.count)
        assertEquals(2, fields.removal.countRaw)
        assertEquals("2025-09-15", fields.manufactureDate)
        assertEquals("2036-09", fields.replacementMonth)
    }

    @Test
    fun frameBFields() {
        val fields = decodeFields(AudioLinkDecoder.wireToPayload(WIRE_FRAME_B))
        assertEquals("012549ef", fields.alarmId)
        assertEquals(0, fields.testButton.count)
        assertNull(fields.testButton.ageHours)
        assertEquals(0, fields.removal.count)
        assertEquals(1, fields.removal.countRaw)
        assertEquals("2025-09-15", fields.manufactureDate)
    }
}

class EndToEndDecodeTest {

    /** Synthesizes the FSK signal of a wire frame (1 = 5.5 kHz, 0 = 6.8 kHz, 10 ms/bit). */
    private fun synthesize(wire: ByteArray, sampleRate: Int): FloatArray {
        val symbolSamples = sampleRate / 100
        val lead = sampleRate // 1 s of silence before
        val tail = sampleRate // 1 s of silence after
        val samples = FloatArray(lead + wire.size * 8 * symbolSamples + tail)
        var index = lead
        for (byte in wire) {
            for (bit in 7 downTo 0) {
                val one = (byte.toInt() shr bit) and 1 == 1
                val freq = if (one) 5500.0 else 6800.0
                for (i in 0 until symbolSamples) {
                    samples[index++] = (0.5 * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
                }
            }
        }
        return samples
    }

    @Test
    fun decodesSynthesizedFrameA() {
        val samples = synthesize(WIRE_FRAME_A, 48000)
        val result = AudioLinkDecoder.decode(samples, 48000)
        assertNotNull("frame not decoded", result)
        assertEquals("012549f4", result!!.fields.alarmId)
        assertEquals(3.04, result.fields.batteryVoltage, 1e-9)
    }

    @Test
    fun decodesSynthesizedFrameBWithNoise() {
        val samples = synthesize(WIRE_FRAME_B, 48000)
        val random = java.util.Random(42)
        for (i in samples.indices) {
            samples[i] += (random.nextGaussian() * 0.05).toFloat()
        }
        val result = AudioLinkDecoder.decode(samples, 48000)
        assertNotNull("frame not decoded", result)
        assertEquals("012549ef", result!!.fields.alarmId)
    }

    /**
     * Seconds of handling noise and loud tones with energy in the FSK bands
     * precede the frame; the global search must still locate and decode it.
     */
    @Test
    fun decodesFrameAfterLoudInBandNoise() {
        val sampleRate = 48000
        val frame = synthesize(WIRE_FRAME_A, sampleRate)
        val random = java.util.Random(1)
        val preSeconds = 5
        val samples = FloatArray(preSeconds * sampleRate + frame.size)
        // noise floor plus loud tones in both FSK bands before the frame
        for (i in 0 until preSeconds * sampleRate) {
            val burstTone = if ((i / (sampleRate / 4)) % 2 == 0) 5500.0 else 6800.0
            samples[i] = (
                random.nextGaussian() * 0.1 +
                    0.4 * sin(2.0 * PI * burstTone * i / sampleRate)
                ).toFloat()
        }
        frame.copyInto(samples, preSeconds * sampleRate)
        for (i in samples.indices) {
            samples[i] += (random.nextGaussian() * 0.02).toFloat()
        }
        val result = AudioLinkDecoder.decode(samples, sampleRate)
        assertNotNull("frame behind noise not decoded", result)
        assertEquals("012549f4", result!!.fields.alarmId)
    }

    /** The app records at 44.1 kHz (Smartsonic's native rate); AudioLINK+ must decode there too. */
    @Test
    fun decodesSynthesizedFrameAt44100() {
        val samples = synthesize(WIRE_FRAME_B, 44100)
        val result = AudioLinkDecoder.decode(samples, 44100)
        assertNotNull("frame not decoded at 44.1 kHz", result)
        assertEquals("012549ef", result!!.fields.alarmId)
    }

    @Test
    fun rejectsPureNoise() {
        val random = java.util.Random(7)
        val samples = FloatArray(48000 * 6) { (random.nextGaussian() * 0.2).toFloat() }
        assertNull(AudioLinkDecoder.decode(samples, 48000))
    }
}

class ToneDetectorTest {
    @Test
    fun pureDetectorToneScoresHigh() {
        val rate = 44100
        val chunk = ShortArray(rate / 10) {
            (12000 * sin(2.0 * PI * 5480.0 * it / rate)).toInt().toShort()
        }
        assertTrue(de.glichtner.rauchmelder.audio.DetectorScanListener.toneRatio(chunk, chunk.size, rate) > 0.6)
    }

    @Test
    fun noiseScoresLow() {
        val rate = 44100
        val random = java.util.Random(5)
        val chunk = ShortArray(rate / 10) { (random.nextGaussian() * 6000).toInt().toShort() }
        assertTrue(de.glichtner.rauchmelder.audio.DetectorScanListener.toneRatio(chunk, chunk.size, rate) < 0.1)
    }
}
