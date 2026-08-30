package de.glichtner.rauchmelder.decoder.smartsonic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin

private fun String.hexToByteArray(): ByteArray =
    replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Example frames A and B from protocol/smartsonic/PROTOCOL.md. */
val WIRE_FRAME_A = "14 00 83 b4 98 f4 02 05 00 00 ff ff 4c 00 26 07 46 00 00 00 00 ca 5a".hexToByteArray()
val WIRE_FRAME_B = "14 00 84 cb 5f b2 02 05 00 00 ff ff 50 00 22 07 4f 00 00 00 00 30 7e".hexToByteArray()
val V5_NO_RADIO_PAYLOAD = "05 12 a1 00 0b 03 00 00 00 ff ff da 02 00 00 bc 02 00 00 00".hexToByteArray()

/**
 * Small independent forward modulator (CPFSK, +-250 Hz around the carrier,
 * SYNC1 preamble, 65.708 demod samples per physical bit) at 44.1 kHz.
 */
fun synthesizeCpFsk(wire: ByteArray, sampleRate: Int = SmartsonicDecoder.ADC_RATE): FloatArray {
    val controls = ArrayList<Double>()
    repeat(2200) { controls.add(0.0) }
    for ((sign, count) in SmartsonicDecoder.SYNC1_RLE) repeat(count) { controls.add(sign.toDouble()) }
    val bits = SmartsonicDecoder.hammingEncode(wire)
    val symbolPeriod = 65.708
    val symbolSamples = Math.round(bits.size * symbolPeriod).toInt()
    for (sample in 0 until symbolSamples) {
        val bitIndex = minOf((sample / symbolPeriod).toInt(), bits.size - 1)
        val within = sample / symbolPeriod - bitIndex
        val firstSign = if (bits[bitIndex] != 0) -1.0 else 1.0
        controls.add(if (within < 0.5) firstSign else -firstSign)
    }
    repeat(2200) { controls.add(0.0) }
    // controls are at the 5,512.5-Hz demod rate; expand to 44.1 kHz, then
    // resample to the requested rate by phase accumulation
    val nativeLength = controls.size * SmartsonicDecoder.DECIMATION
    val outputLength = Math.round(nativeLength.toDouble() * sampleRate / SmartsonicDecoder.ADC_RATE).toInt()
    val samples = FloatArray(outputLength)
    var phase = 0.0
    for (k in 0 until outputLength) {
        val nativeIndex = (k.toDouble() * SmartsonicDecoder.ADC_RATE / sampleRate).toInt()
        val control = controls[minOf(nativeIndex / SmartsonicDecoder.DECIMATION, controls.size - 1)]
        val frequency = SmartsonicDecoder.CARRIER_HZ + 250.0 * control
        phase += 2.0 * PI * frequency / sampleRate
        samples[k] = (0.70 * sin(phase)).toFloat()
    }
    return samples
}

class SmartsonicFramingTest {
    @Test
    fun crcCheckValue() {
        assertEquals(0xAEE7, SmartsonicDecoder.crc16Cms("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun frameA() {
        val frame = SmartsonicDecoder.parseWireFrame(WIRE_FRAME_A)
        assertNotNull(frame)
        assertEquals(20, frame!!.length)
        assertEquals(0x5ACA, frame.receivedCrc)
        val fields = SmartsonicPayload.parse(frame.payload, LocalDate.of(2020, 5, 20))
        assertEquals(0, fields.unknownByte0)
        assertEquals(2_209_650_932L, fields.detectorSerial)
        assertEquals("83b498f4", fields.detectorSerialHex)
        assertEquals("Genius Plus", fields.productType)
        assertEquals(LocalDate.of(2020, 3, 5), fields.productionDate)
        assertEquals(LocalDate.of(2020, 5, 14), fields.lastSelftestDate)
        assertEquals(5, fields.deinstallationCount)
        assertEquals(1830, fields.hoursInStorageMode)
        assertTrue(fields.warrantyPossible)
        assertNull(fields.radio)
    }

    @Test
    fun frameB() {
        val frame = SmartsonicDecoder.parseWireFrame(WIRE_FRAME_B)!!
        val fields = SmartsonicPayload.parse(frame.payload, LocalDate.of(2026, 8, 30))
        assertEquals(2_227_920_818L, fields.detectorSerial)
        assertEquals(LocalDate.of(2026, 6, 11), fields.productionDate)
        assertEquals(LocalDate.of(2026, 8, 29), fields.lastSelftestDate)
        assertNull(fields.lastAlarmDate)
        assertFalse(fields.batteryLowFault)
        assertFalse(fields.deviceFault)
        assertEquals(0, fields.driftState)
    }

    @Test
    fun v5StylePayloadAndCrc() {
        val wire = SmartsonicDecoder.buildWireFrame(V5_NO_RADIO_PAYLOAD)
        assertEquals("e058", wire.takeLast(2).joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        val frame = SmartsonicDecoder.parseWireFrame(wire)!!
        val fields = SmartsonicPayload.parse(frame.payload, LocalDate.of(2026, 1, 1))
        assertEquals(5, fields.unknownByte0)
        assertEquals(0x12A1000BL, fields.detectorSerial)
        assertEquals("Genius Plus X", fields.productType)
        assertFalse(fields.hasRadioExtension)
    }

    @Test
    fun crcRejectsMutatedPayload() {
        val damaged = WIRE_FRAME_A.clone()
        damaged[3] = (damaged[3].toInt() xor 1).toByte()
        assertNull(SmartsonicDecoder.parseWireFrame(damaged))
    }

    @Test
    fun radioExtensionBitOrder() {
        val payload = V5_NO_RADIO_PAYLOAD.clone()
        payload[5] = 0x43 // Genius Plus X + FM.Basis X
        val full = payload + "01 ab 00 10 01 00 00 00 01 12 21 19".hexToByteArray()
        val fields = SmartsonicPayload.parse(full, LocalDate.of(2026, 1, 1))
        val radio = fields.radio!!
        assertEquals(listOf("remote_alarm"), radio.stateFlags)
        assertEquals(0xAB001001L, radio.serial)
        assertEquals(1L, radio.lineId)
        assertEquals("B2", radio.line)
        assertEquals(listOf("suppress_warnings", "reduced_transmitting_power"), radio.switchFlags)
        assertEquals(2.5, radio.interferencePercent, 1e-9)
        assertEquals("FM.Basis X", fields.radioProductType)
    }

    @Test(expected = SmartsonicPayloadException::class)
    fun invalidDriftStateIsRejected() {
        val bad = V5_NO_RADIO_PAYLOAD.clone()
        bad[19] = (9 shl 3).toByte()
        SmartsonicPayload.parse(bad, LocalDate.of(2026, 1, 1))
    }
}

class SmartsonicHammingTest {
    @Test
    fun allByteValuesRoundTrip() {
        val data = ByteArray(256) { it.toByte() }
        val bits = SmartsonicDecoder.hammingEncode(data)
        val correlations = DoubleArray(bits.size) { if (bits[it] != 0) 100.0 else -100.0 }
        val (decoded, distance) = SmartsonicDecoder.hammingDecodeCorrelations(correlations)
        assertArrayEquals(data, decoded)
        assertEquals(0, distance)
    }

    @Test
    fun correctsOneBitPerNibble() {
        val data = "14 83 b4 98 f4".hexToByteArray()
        val bits = SmartsonicDecoder.hammingEncode(data)
        for (codeword in 0 until bits.size / 8) bits[codeword * 8 + codeword % 8] = bits[codeword * 8 + codeword % 8] xor 1
        val correlations = DoubleArray(bits.size) { if (bits[it] != 0) 100.0 else -100.0 }
        val (decoded, distance) = SmartsonicDecoder.hammingDecodeCorrelations(correlations)
        assertArrayEquals(data, decoded)
        assertEquals(bits.size / 8, distance)
    }

    @Test
    fun distanceTwoUsesSoftConfidence() {
        val bits = SmartsonicDecoder.hammingEncode(byteArrayOf(0x01))
        bits[0] = bits[0] xor 1
        bits[1] = bits[1] xor 1
        val correlations = DoubleArray(bits.size) { if (bits[it] != 0) 100.0 else -100.0 }
        correlations[0] = -1.0 // bit 0 is the least reliable one
        val (decoded, distance) = SmartsonicDecoder.hammingDecodeCorrelations(correlations)
        assertArrayEquals(byteArrayOf(0x01), decoded)
        assertEquals(2, distance)
    }

    @Test
    fun localSymbolSearchTracksPerBytePhaseDrift() {
        val data = "14 83 b4 98".hexToByteArray()
        val bits = SmartsonicDecoder.hammingEncode(data)
        val start = 40.0
        val period = 66.0
        val correlations = DoubleArray(5000)
        for ((symbol, bit) in bits.withIndex()) {
            val bytePhase = (symbol / 16) * 3
            val position = Math.round(start + symbol * period + bytePhase).toInt()
            correlations[position] = if (bit != 0) 100.0 else -100.0
        }
        val recovered = SmartsonicDecoder.decodeAt(correlations, start, period, 1, data.size, localRadius = 12, recoverClock = true)
        assertNotNull(recovered)
        assertArrayEquals(data, recovered!!.first)
    }
}

class SmartsonicEndToEndTest {
    @Test
    fun syntheticWaveformAt44100() {
        val wire = SmartsonicDecoder.buildWireFrame(V5_NO_RADIO_PAYLOAD)
        val result = SmartsonicDecoder.decode(synthesizeCpFsk(wire), 44100, LocalDate.of(2026, 1, 1))
        assertNotNull("synthetic frame not decoded", result)
        assertArrayEquals(wire, result!!.frame.wire)
        assertEquals("SYNC1", result.syncName)
        assertTrue(result.syncQualityPercent > 30.0)
        assertEquals(0x12A1000BL, result.fields.detectorSerial)
    }

    @Test
    fun syntheticWaveformAt48000IsResampled() {
        val wire = SmartsonicDecoder.buildWireFrame(V5_NO_RADIO_PAYLOAD)
        val result = SmartsonicDecoder.decode(synthesizeCpFsk(wire, 48000), 48000, LocalDate.of(2026, 1, 1))
        assertNotNull("synthetic frame not decoded at 48 kHz", result)
        assertArrayEquals(wire, result!!.frame.wire)
    }

    @Test
    fun whiteNoiseFailsAtSyncThreshold() {
        val random = java.util.Random(12345)
        val samples = FloatArray(44100 * 6) { (random.nextGaussian() * 0.1).toFloat() }
        assertNull(SmartsonicDecoder.decode(samples, 44100))
    }
}
