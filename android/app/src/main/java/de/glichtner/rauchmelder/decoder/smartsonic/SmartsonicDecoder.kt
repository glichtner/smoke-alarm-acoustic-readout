package de.glichtner.rauchmelder.decoder.smartsonic

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Kotlin port of protocol/smartsonic/smartsonic_decode.py: Hekatron Genius
 * Smartsonic (two-tone FSK/biphase around 4,449.1 Hz, ~83.8 physical bit/s,
 * Hamming(8,4) FEC, `length + payload + CRC-16/CMS` framing). DSP chain: I/Q
 * down-mix, IIR filtering, decimation by 8 to 5,512.5 Hz, phase discriminator,
 * median + IIR smoothing, clamp to +-400 (see protocol/smartsonic/PROTOCOL.md).
 */
object SmartsonicDecoder {

    const val ADC_RATE = 44100
    const val CARRIER_HZ = 4449.1
    const val DECIMATION = 8
    const val DEMOD_RATE = ADC_RATE / DECIMATION.toDouble()
    private const val CLAMP = 400.0
    private const val SYNC_THRESHOLD = 300000.0
    private const val MAX_PAYLOAD_LENGTH = 39
    private const val MIN_KNOWN_PAYLOAD_LENGTH = 20

    val HAMMING_CODEBOOK = intArrayOf(
        0x00, 0x87, 0x99, 0x1E, 0xAA, 0x2D, 0x33, 0xB4,
        0x4B, 0xCC, 0xD2, 0x55, 0xE1, 0x66, 0x78, 0xFF,
    )

    /** Run-length preambles: (sign, length) at 5,512.5 Hz. */
    val SYNC1_RLE = arrayOf(
        1 to 65, -1 to 132, 1 to 33, -1 to 33, 1 to 163, -1 to 132, 1 to 98, -1 to 66, 1 to 33, -1 to 33,
        1 to 32, -1 to 33, 1 to 66, -1 to 99, 1 to 130, -1 to 166, 1 to 32, -1 to 33, 1 to 131, -1 to 66,
    )
    val SYNC2_RLE = arrayOf(
        -1 to 67, 1 to 128, -1 to 34, 1 to 32, -1 to 168, 1 to 129, -1 to 101, 1 to 64, -1 to 34, 1 to 32,
        -1 to 34, 1 to 32, -1 to 67, 1 to 96, -1 to 135, 1 to 161, -1 to 33, 1 to 33, -1 to 134, 1 to 64,
    )

    private class SyncPattern(val name: String, val rle: Array<Pair<Int, Int>>, val nominalPeriod: Double) {
        val length = rle.sumOf { it.second }
    }

    private val SYNC_PATTERNS = listOf(
        SyncPattern("SYNC1", SYNC1_RLE, 65.708),
        SyncPattern("SYNC2", SYNC2_RLE, 65.802),
    )

    class WireFrame(val length: Int, val payload: ByteArray, val receivedCrc: Int, val computedCrc: Int, val wire: ByteArray)

    class Result(
        val frame: WireFrame,
        val fields: SmartsonicFields,
        val syncName: String,
        val syncQualityPercent: Double,
        val symbolPeriod: Double,
        val polarity: Int,
        val hammingDistance: Int,
    )

    // ------------------------------------------------------------------ framing

    /** CRC-16/CMS: poly 0x8005, init 0xffff, non-reflected, xorout 0. */
    fun crc16Cms(data: ByteArray): Int {
        var crc = 0xFFFF
        for (value in data) {
            var byte = value.toInt() and 0xFF
            repeat(8) {
                val feedback = ((crc shr 8) xor byte) and 0x80
                crc = if (feedback != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF
                byte = (byte shl 1) and 0xFF
            }
        }
        return crc
    }

    /** Validates `length + payload + CRC-low + CRC-high`; null if invalid. */
    fun parseWireFrame(wire: ByteArray): WireFrame? {
        if (wire.size < 3) return null
        val length = wire[0].toInt() and 0xFF
        if (length >= 40 || wire.size != length + 3) return null
        val payload = wire.copyOfRange(1, 1 + length)
        val received = (wire[wire.size - 2].toInt() and 0xFF) or ((wire[wire.size - 1].toInt() and 0xFF) shl 8)
        val computed = crc16Cms(payload)
        if (received != computed) return null
        return WireFrame(length, payload, received, computed, wire.copyOf())
    }

    /** Builds a framing- and CRC-valid wire sequence (tests, synthesis). */
    fun buildWireFrame(payload: ByteArray): ByteArray {
        require(payload.size < 40)
        val crc = crc16Cms(payload)
        return byteArrayOf(payload.size.toByte()) + payload + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
    }

    /** Encodes bytes to physical bit order (low nibble first, bit 0 first). */
    fun hammingEncode(data: ByteArray): IntArray {
        val bits = ArrayList<Int>()
        for (value in data) {
            val byte = value.toInt() and 0xFF
            for (nibble in intArrayOf(byte and 0x0F, byte shr 4)) {
                val codeword = HAMMING_CODEBOOK[nibble]
                for (bit in 0 until 8) bits.add((codeword shr bit) and 1)
            }
        }
        return bits.toIntArray()
    }

    // ------------------------------------------------------------------ DSP

    private fun iir(x: DoubleArray, a: DoubleArray, b: DoubleArray): DoubleArray {
        val order = a.size - 1
        val state = DoubleArray(a.size)
        val y = DoubleArray(x.size)
        for (i in x.indices) {
            val s = x[i]
            val v = b[0] * s + state[1]
            for (tap in 1 until order) state[tap] = state[tap + 1] + b[tap] * s - a[tap] * v
            state[order] = b[order] * s - a[order] * v
            y[i] = v
        }
        return y
    }

    private fun decimate(x: DoubleArray): DoubleArray {
        val out = DoubleArray((x.size + DECIMATION - 1) / DECIMATION)
        for (i in out.indices) out[i] = x[i * DECIMATION]
        return out
    }

    private fun rollingMedian5(x: DoubleArray): DoubleArray {
        val history = DoubleArray(5)
        val scratch = DoubleArray(5)
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            for (k in 4 downTo 1) history[k] = history[k - 1]
            history[0] = x[i]
            history.copyInto(scratch)
            scratch.sort()
            out[i] = scratch[2]
        }
        return out
    }

    private val FIRST_A = doubleArrayOf(1.0, -1.85716065, 0.86670342)
    private val FIRST_B = doubleArrayOf(0.00238569, 0.00477138, 0.00238569)
    private val SECOND_A = doubleArrayOf(1.0, -1.99194039, 0.99197274)
    private val SECOND_B = doubleArrayOf(0.99597828, -1.99195657, 0.99597828)
    private val SMOOTH_A = doubleArrayOf(1.0, -3.22692923, 3.9658094, -2.19293563, 0.45943954)
    private val SMOOTH_B = doubleArrayOf(0.00033651, 0.00134602, 0.00201903, 0.00134602, 0.00033651)

    /** 44.1-kHz PCM to the clamped 5,512.5-Hz frequency discriminator. */
    fun demodulate(samples: DoubleArray): DoubleArray {
        val n = samples.size
        val inPhase = DoubleArray(n)
        val quadrature = DoubleArray(n)
        val omega = 2.0 * PI * CARRIER_HZ / ADC_RATE
        for (i in 0 until n) {
            val phase = omega * i
            inPhase[i] = samples[i] * cos(phase)
            quadrature[i] = samples[i] * -sin(phase)
        }
        var i = decimate(iir(inPhase, FIRST_A, FIRST_B))
        var q = decimate(iir(quadrature, FIRST_A, FIRST_B))
        i = iir(i, SECOND_A, SECOND_B)
        q = iir(q, SECOND_A, SECOND_B)

        val m = i.size
        var disc = DoubleArray(m)
        for (k in 2 until m) {
            val numerator = i[k - 1] * (q[k] - q[k - 2]) - q[k - 1] * (i[k] - i[k - 2])
            val denominator = max(i[k] * i[k] + q[k] * q[k], 1e-12)
            disc[k] = numerator / denominator
        }
        disc = rollingMedian5(disc)
        disc = iir(disc, SMOOTH_A, SMOOTH_B)
        disc = iir(disc, SECOND_A, SECOND_B)
        for (k in 0 until m) disc[k] = Math.rint(disc[k] * 1000.0).coerceIn(-CLAMP, CLAMP)
        return disc
    }

    private fun resampleLinear(samples: FloatArray, sourceRate: Int, targetRate: Int): DoubleArray {
        if (sourceRate == targetRate) return DoubleArray(samples.size) { samples[it].toDouble() }
        val outputLength = Math.rint(samples.size.toDouble() * targetRate / sourceRate).toInt()
        val out = DoubleArray(outputLength)
        val ratio = sourceRate.toDouble() / targetRate
        val last = samples.size - 1
        for (k in 0 until outputLength) {
            val position = k * ratio
            val index = position.toInt()
            if (index >= last) {
                out[k] = samples[last].toDouble()
            } else {
                val fraction = position - index
                out[k] = samples[index] * (1.0 - fraction) + samples[index + 1] * fraction
            }
        }
        return out
    }

    // ------------------------------------------------------------------ sync and bits

    private class SyncCandidate(
        val quality: Double,
        val pattern: SyncPattern,
        val position: Int,
        val correlation: Double,
    )

    /** Correlates the piecewise-constant preambles via prefix sums; top 16 per pattern. */
    private fun bestSyncCandidates(disc: DoubleArray, perPattern: Int = 16): List<SyncCandidate> {
        val n = disc.size
        val prefix = DoubleArray(n + 1)
        for (k in 0 until n) prefix[k + 1] = prefix[k] + disc[k]
        val candidates = ArrayList<SyncCandidate>()
        for (pattern in SYNC_PATTERNS) {
            val length = pattern.length
            if (n < length) continue
            val positions = n - length + 1
            val correlations = DoubleArray(positions)
            val runStarts = IntArray(pattern.rle.size)
            val runEnds = IntArray(pattern.rle.size)
            var offset = 0
            for ((index, run) in pattern.rle.withIndex()) {
                runStarts[index] = offset
                offset += run.second
                runEnds[index] = offset
            }
            for (p in 0 until positions) {
                var sum = 0.0
                for (index in pattern.rle.indices) {
                    sum += pattern.rle[index].first * (prefix[p + runEnds[index]] - prefix[p + runStarts[index]])
                }
                correlations[p] = sum
            }
            val eligible = (0 until positions).filter { abs(correlations[it]) >= SYNC_THRESHOLD }
                .sortedByDescending { abs(correlations[it]) }
            val selected = ArrayList<Int>()
            for (position in eligible) {
                if (selected.any { abs(position - it) < length }) continue
                selected.add(position)
                val correlation = correlations[position]
                candidates.add(SyncCandidate(100.0 * abs(correlation) / (CLAMP * length), pattern, position, correlation))
                if (selected.size == perPattern) break
            }
        }
        return candidates.sortedWith(
            compareByDescending<SyncCandidate> { it.quality }
                .thenByDescending { it.pattern.name }
                .thenByDescending { it.position },
        )
    }

    /** Correlation with the 64-sample bit template [0x3, -1x26, 0x6, +1x26, 0x3]. */
    private fun bitCorrelations(disc: DoubleArray): DoubleArray {
        val n = disc.size
        if (n < 64) return DoubleArray(0)
        val prefix = DoubleArray(n + 1)
        for (k in 0 until n) prefix[k + 1] = prefix[k] + disc[k]
        val out = DoubleArray(n - 64 + 1)
        for (i in out.indices) {
            out[i] = -(prefix[i + 29] - prefix[i + 3]) + (prefix[i + 61] - prefix[i + 35])
        }
        return out
    }

    private fun nearestNibble(codeword: Int): Pair<Int, Int> {
        var bestNibble = 0
        var bestDistance = 9
        for (nibble in 0 until 16) {
            val distance = Integer.bitCount(codeword xor HAMMING_CODEBOOK[nibble])
            if (distance < bestDistance) {
                bestNibble = nibble
                bestDistance = distance
            }
        }
        return bestNibble to bestDistance
    }

    private fun decodeNibble(correlations: DoubleArray, offset: Int): Pair<Int, Int> {
        var codeword = 0
        for (bit in 0 until 8) if (correlations[offset + bit] > 0) codeword = codeword or (1 shl bit)
        var (nibble, distance) = nearestNibble(codeword)
        if (distance == 2) {
            var weakest = 0
            var weakestValue = Double.MAX_VALUE
            for (bit in 0 until 8) {
                val magnitude = abs(correlations[offset + bit])
                if (magnitude < weakestValue) {
                    weakestValue = magnitude
                    weakest = bit
                }
            }
            nibble = nearestNibble(codeword xor (1 shl weakest)).first
        }
        return nibble to distance
    }

    /** Decodes groups of 16 physical-bit correlations into bytes, low nibble first. */
    fun hammingDecodeCorrelations(correlations: DoubleArray): Pair<ByteArray, Int> {
        require(correlations.size % 16 == 0)
        val decoded = ByteArray(correlations.size / 16)
        var totalDistance = 0
        for (index in decoded.indices) {
            val (low, lowDistance) = decodeNibble(correlations, index * 16)
            val (high, highDistance) = decodeNibble(correlations, index * 16 + 8)
            decoded[index] = (low or (high shl 4)).toByte()
            totalDistance += lowDistance + highDistance
        }
        return decoded to totalDistance
    }

    fun decodeAt(
        bitCorr: DoubleArray,
        start: Double,
        period: Double,
        polarity: Int,
        byteCount: Int,
        localRadius: Int = 0,
        recoverClock: Boolean = false,
    ): Pair<ByteArray, Int>? {
        val symbolCount = byteCount * 16
        val values = DoubleArray(symbolCount)
        if (localRadius <= 0) {
            for (symbol in 0 until symbolCount) {
                val index = Math.rint(start + symbol * period).toInt()
                if (index < 0 || index >= bitCorr.size) return null
                values[symbol] = bitCorr[index] * polarity
            }
            return hammingDecodeCorrelations(values)
        }
        var cumulativeCorrection = 0.0
        var weightedOffset = 0.0
        var totalWeight = 0.0
        for (symbol in 0 until symbolCount) {
            val expected = start + symbol * period + cumulativeCorrection
            val center = Math.rint(expected).toInt()
            if (center - localRadius < 0 || center + localRadius >= bitCorr.size) return null
            var selectedIndex = center - localRadius
            var selectedMagnitude = -1.0
            for (index in center - localRadius..center + localRadius) {
                val magnitude = abs(bitCorr[index])
                if (magnitude > selectedMagnitude) {
                    selectedMagnitude = magnitude
                    selectedIndex = index
                }
            }
            values[symbol] = bitCorr[selectedIndex] * polarity
            if (recoverClock) {
                val weight = abs(bitCorr[selectedIndex])
                weightedOffset += (selectedIndex - expected) * weight
                totalWeight += weight
                if (symbol % 16 == 15 && totalWeight > 0.0) {
                    cumulativeCorrection += weightedOffset / totalWeight
                    weightedOffset = 0.0
                    totalWeight = 0.0
                }
            }
        }
        return hammingDecodeCorrelations(values)
    }

    private class QuickCandidate(val score: Int, val start: Double, val period: Double, val polarity: Int, val length: Int)

    private fun recoverAfterSync(bitCorr: DoubleArray, sync: SyncCandidate, referenceDate: LocalDate): Result? {
        val syncEnd = sync.position + sync.pattern.length
        val nominal = sync.pattern.nominalPeriod
        val quick = ArrayList<QuickCandidate>()
        for (periodStep in 0..250) {
            val period = nominal - 2.5 + periodStep * 0.02
            for (start in syncEnd - 25..syncEnd + 90) {
                for (polarity in intArrayOf(1, -1)) {
                    val (decoded, distance) = decodeAt(bitCorr, start.toDouble(), period, polarity, 7) ?: continue
                    val length = decoded[0].toInt() and 0xFF
                    if (length < MIN_KNOWN_PAYLOAD_LENGTH || length > MAX_PAYLOAD_LENGTH) continue
                    // wire byte 6 is payload byte 5: product type nibbles (ranking hint only)
                    val typeByte = decoded[6].toInt() and 0xFF
                    val known = SmartsonicPayload.PRODUCT_TYPES.containsKey(typeByte and 0x0F) &&
                        SmartsonicPayload.RADIO_PRODUCT_TYPES.containsKey(typeByte shr 4)
                    quick.add(QuickCandidate(distance + if (known) 0 else 12, start.toDouble(), period, polarity, length))
                }
            }
        }
        val ranked = quick.sortedWith(
            compareBy<QuickCandidate> { it.score }.thenBy { it.start }.thenBy { it.period }.thenBy { it.polarity },
        ).take(4000)

        fun attempt(candidate: QuickCandidate, localRadius: Int, recoverClock: Boolean): Result? {
            val (wire, distance) = decodeAt(
                bitCorr, candidate.start, candidate.period, candidate.polarity,
                candidate.length + 3, localRadius, recoverClock,
            ) ?: return null
            val frame = parseWireFrame(wire) ?: return null
            val fields = try {
                SmartsonicPayload.parse(frame.payload, referenceDate)
            } catch (_: SmartsonicPayloadException) {
                return null
            }
            return Result(frame, fields, sync.pattern.name, sync.quality, candidate.period, candidate.polarity, distance)
        }

        for (candidate in ranked) attempt(candidate, 0, false)?.let { return it }
        // per-symbol tracking with per-byte clock correction for the strongest hypotheses
        for (candidate in ranked.take(500)) attempt(candidate, 12, true)?.let { return it }
        return null
    }

    /**
     * Decodes mono PCM at any sample rate (resampled to 44.1 kHz when needed).
     * Returns the first CRC-valid frame, or null.
     */
    fun decode(samples: FloatArray, sampleRate: Int, referenceDate: LocalDate = LocalDate.now()): Result? {
        if (sampleRate <= 0) return null
        val pcm = resampleLinear(samples, sampleRate, ADC_RATE)
        if (pcm.size < ADC_RATE / 2) return null
        val disc = demodulate(pcm)
        val bitCorr = bitCorrelations(disc)
        if (bitCorr.isEmpty()) return null
        val syncs = bestSyncCandidates(disc)
        if (syncs.isEmpty()) return null
        for (sync in syncs) {
            recoverAfterSync(bitCorr, sync, referenceDate)?.let { return it }
        }
        return null
    }
}
