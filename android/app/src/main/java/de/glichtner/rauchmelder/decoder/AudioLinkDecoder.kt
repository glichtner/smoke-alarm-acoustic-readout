package de.glichtner.rauchmelder.decoder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kotlin port of the reference decoder in protocol/audiolink/audiolink_decode.py
 * for the AudioLINK+ format of the Ei650i detectors documented in
 * protocol/audiolink/PROTOCOL.md:
 * binary energy FSK (1 = 5.5 kHz, 0 = 6.8 kHz), ca. 100 bit/s, 328 bits,
 * 41 wire bytes, CRC-16/CCITT-FALSE over the 30 payload bytes.
 */
object AudioLinkDecoder {

    const val FRAME_BYTES = 41
    const val FRAME_BITS = FRAME_BYTES * 8
    const val PAYLOAD_LENGTH = 30
    private const val BLOCK_MARKER = 0x72
    private const val END_MARKER = 0xAA
    private val MARKER_BYTE_OFFSETS = intArrayOf(2, 9, 16, 23, 30, 37)

    class DecodeResult(
        val wire: ByteArray,
        val payload: ByteArray,
        val fields: DecodedFields,
        val frameStartSeconds: Double,
        val symbolPeriodMs: Double,
    )

    fun crcCcittFalse(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    fun wireToPayload(wire: ByteArray): ByteArray {
        require(wire.size == FRAME_BYTES) { "wire frame must contain $FRAME_BYTES bytes" }
        val payload = ByteArray(PAYLOAD_LENGTH)
        var out = 0
        for (i in 0 until 5) {
            val offset = MARKER_BYTE_OFFSETS[i]
            wire.copyInto(payload, out, offset + 1, offset + 7)
            out += 6
        }
        return payload
    }

    fun wireIsStructurallyValid(wire: ByteArray): Boolean =
        wire.size == FRAME_BYTES &&
            wire[0].toInt() == 0 && wire[1].toInt() == 0 &&
            MARKER_BYTE_OFFSETS.all { wire[it].toInt() and 0xFF == BLOCK_MARKER } &&
            wire[FRAME_BYTES - 1].toInt() and 0xFF == END_MARKER

    fun wireCrcValid(wire: ByteArray): Boolean {
        if (!wireIsStructurallyValid(wire)) return false
        val payload = wireToPayload(wire)
        val received = ((wire[38].toInt() and 0xFF) shl 8) or (wire[39].toInt() and 0xFF)
        return crcCcittFalse(payload) == received
    }

    // The loud piezo sounder is rich in harmonics, which carry the same bit
    // information in bands that everyday interference (speech, sibilants)
    // rarely reaches. Use matched harmonic orders for both FSK classes: an
    // extra harmonic on only one side can turn high-frequency interference
    // into a systematic bit bias. Higher orders near microphone/codec cutoffs
    // are deliberately excluded.
    private val ONE_BANDS_HZ = doubleArrayOf(5500.0, 11000.0)
    private val ZERO_BANDS_HZ = doubleArrayOf(6800.0, 13600.0)

    private class BandEvidence(val normalized: FloatArray, val active: Boolean)

    /** Normalized evidence for one band plus a conservative burst-activity flag. */
    private fun bandEvidence(samples: FloatArray, sampleRate: Int, center: Double): BandEvidence {
        val envelope = envelope(samples, sampleRate, center)
        val sorted = DoubleArray(envelope.size) { envelope[it].toDouble() }.also { it.sort() }
        val p95 = percentileOfSorted(sorted, 95.0)
        val p25 = percentileOfSorted(sorted, 25.0)
        val scale = max(p95, 1e-12)
        for (i in envelope.indices) envelope[i] = (envelope[i] / scale).toFloat()
        return BandEvidence(envelope, p95 > 4.0 * p25)
    }

    private fun addEvidence(first: FloatArray, second: FloatArray): FloatArray =
        FloatArray(first.size) { first[it] + second[it] }

    /**
     * A small, predetermined set of matched FSK evidence views. The harmonic
     * pair joins the primary view only when both classes show activity. The
     * individual matched pairs are conservative fallbacks for band-confined
     * interference; every view still requires exact framing and CRC.
     */
    private fun evidenceViews(samples: FloatArray, sampleRate: Int): List<Pair<FloatArray, FloatArray>> {
        val oneFundamental = bandEvidence(samples, sampleRate, ONE_BANDS_HZ[0]).normalized
        val zeroFundamental = bandEvidence(samples, sampleRate, ZERO_BANDS_HZ[0]).normalized
        val views = ArrayList<Pair<FloatArray, FloatArray>>()
        if (ONE_BANDS_HZ.size > 1 && ZERO_BANDS_HZ.size > 1) {
            val oneHarmonic = bandEvidence(samples, sampleRate, ONE_BANDS_HZ[1])
            val zeroHarmonic = bandEvidence(samples, sampleRate, ZERO_BANDS_HZ[1])
            if (oneHarmonic.active && zeroHarmonic.active) {
                views.add(
                    addEvidence(oneFundamental, oneHarmonic.normalized) to
                        addEvidence(zeroFundamental, zeroHarmonic.normalized),
                )
                views.add(oneHarmonic.normalized to zeroHarmonic.normalized)
            }
        }
        views.add(oneFundamental to zeroFundamental)
        return views
    }

    /** Decodes a mono sample array; null if no CRC-valid frame was found. */
    fun decode(samples: FloatArray, sampleRate: Int): DecodeResult? {
        if (sampleRate < 15000) return null
        if (samples.size < sampleRate * 3) return null

        val candidate = evidenceViews(samples, sampleRate).firstNotNullOfOrNull { (lowEnv, highEnv) ->
            findFrame(lowEnv, highEnv, sampleRate)
        } ?: return null
        val payload = wireToPayload(candidate.wire)
        return DecodeResult(
            wire = candidate.wire,
            payload = payload,
            fields = decodeFields(payload),
            frameStartSeconds = candidate.startSample / sampleRate,
            symbolPeriodMs = candidate.periodSamples / sampleRate * 1000.0,
        )
    }

    /**
     * Analytic envelope around [centerHz] via quadrature demodulation with a
     * cascaded moving-average low-pass (bandwidth roughly +-250 Hz).
     */
    private fun envelope(samples: FloatArray, sampleRate: Int, centerHz: Double): FloatArray {
        val n = samples.size
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val omega = 2.0 * PI * centerHz / sampleRate
        for (i in 0 until n) {
            val phase = omega * i
            re[i] = samples[i] * cos(phase)
            im[i] = -samples[i] * sin(phase)
        }
        val window = max(1, (sampleRate / 500.0).roundToInt())
        movingAverage(re, window)
        movingAverage(re, window)
        movingAverage(im, window)
        movingAverage(im, window)
        val env = FloatArray(n)
        for (i in 0 until n) env[i] = (2.0 * sqrt(re[i] * re[i] + im[i] * im[i])).toFloat()
        return env
    }

    private fun movingAverage(data: DoubleArray, window: Int) {
        if (window <= 1) return
        val n = data.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + data[i]
        val half = window / 2
        for (i in 0 until n) {
            val from = max(0, i - half)
            val to = min(n, i - half + window)
            data[i] = (prefix[to] - prefix[from]) / (to - from)
        }
    }

    private class Candidate(
        val startSample: Double,
        val periodSamples: Double,
        val bits: ByteArray,
        val wire: ByteArray,
        val fixedScore: Int,
        val lowLevel: Double,
        val highLevel: Double,
        val crcValid: Boolean,
    ) {
        val separation: Double
            get() {
                val spread = max(max(abs(highLevel), abs(lowLevel)), 1e-12)
                return (highLevel - lowLevel) / spread
            }
    }

    private val fixedBitPositions: IntArray
    private val fixedBitValues: ByteArray

    init {
        val positions = ArrayList<Int>()
        val values = ArrayList<Byte>()
        val fixedBytes = HashMap<Int, Int>()
        fixedBytes[0] = 0x00
        fixedBytes[1] = 0x00
        fixedBytes[40] = END_MARKER
        for (offset in MARKER_BYTE_OFFSETS) fixedBytes[offset] = BLOCK_MARKER
        for ((byteOffset, value) in fixedBytes) {
            for (bitOffset in 0 until 8) {
                positions.add(byteOffset * 8 + bitOffset)
                values.add(((value shr (7 - bitOffset)) and 1).toByte())
            }
        }
        fixedBitPositions = positions.toIntArray()
        fixedBitValues = values.toByteArray()
    }

    private fun percentileOfSorted(sorted: DoubleArray, percentile: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val rank = percentile / 100.0 * (sorted.size - 1)
        val low = rank.toInt()
        val high = min(low + 1, sorted.size - 1)
        val fraction = rank - low
        return sorted[low] * (1 - fraction) + sorted[high] * fraction
    }

    private val MARKER_ONE_BITS = intArrayOf(1, 2, 3, 6) // 0x72 = 01110010
    private val MARKER_ZERO_BITS = intArrayOf(0, 4, 5, 7)
    private val END_ONE_BITS = intArrayOf(0, 2, 4, 6) // 0xAA = 10101010
    private val END_ZERO_BITS = intArrayOf(1, 3, 5, 7)

    /**
     * Per-symbol decision thresholds anchored at the known marker bytes: each
     * 0x72 marker and the closing 0xAA contain four known one and four known
     * zero bits, whose feature means give a local threshold; values between
     * anchors are interpolated linearly. This compensates baseline drift of
     * the discriminator across the frame (e.g. from in-band background
     * noise), which a single global threshold cannot.
     */
    private fun markerAnchoredThresholds(features: DoubleArray): DoubleArray {
        val anchorX = DoubleArray(MARKER_BYTE_OFFSETS.size + 1)
        val anchorY = DoubleArray(MARKER_BYTE_OFFSETS.size + 1)
        for ((index, offset) in MARKER_BYTE_OFFSETS.withIndex()) {
            var ones = 0.0
            var zeros = 0.0
            for (bit in MARKER_ONE_BITS) ones += features[offset * 8 + bit]
            for (bit in MARKER_ZERO_BITS) zeros += features[offset * 8 + bit]
            anchorX[index] = offset * 8 + 3.5
            anchorY[index] = (ones / 4 + zeros / 4) / 2
        }
        var ones = 0.0
        var zeros = 0.0
        for (bit in END_ONE_BITS) ones += features[320 + bit]
        for (bit in END_ZERO_BITS) zeros += features[320 + bit]
        anchorX[anchorX.size - 1] = 323.5
        anchorY[anchorY.size - 1] = (ones / 4 + zeros / 4) / 2

        val thresholds = DoubleArray(FRAME_BITS)
        var segment = 0
        for (i in 0 until FRAME_BITS) {
            val x = i.toDouble()
            thresholds[i] = when {
                x <= anchorX[0] -> anchorY[0]
                x >= anchorX[anchorX.size - 1] -> anchorY[anchorY.size - 1]
                else -> {
                    while (x > anchorX[segment + 1]) segment++
                    val fraction = (x - anchorX[segment]) / (anchorX[segment + 1] - anchorX[segment])
                    anchorY[segment] + (anchorY[segment + 1] - anchorY[segment]) * fraction
                }
            }
        }
        return thresholds
    }

    private fun bitsToWire(bits: ByteArray): ByteArray {
        val wire = ByteArray(FRAME_BYTES)
        for (byteIndex in 0 until FRAME_BYTES) {
            var value = 0
            for (bit in 0 until 8) value = (value shl 1) or bits[byteIndex * 8 + bit].toInt()
            wire[byteIndex] = value.toByte()
        }
        return wire
    }

    private fun candidateFromThresholds(
        features: DoubleArray,
        thresholds: DoubleArray,
        start: Int,
        period: Double,
        low: Double,
        high: Double,
    ): Candidate {
        val bits = ByteArray(FRAME_BITS)
        for (i in 0 until FRAME_BITS) bits[i] = if (features[i] >= thresholds[i]) 1 else 0
        var fixedScore = 0
        for (i in fixedBitPositions.indices) {
            if (bits[fixedBitPositions[i]] == fixedBitValues[i]) fixedScore++
        }
        val wire = bitsToWire(bits)
        val crcValid = wireCrcValid(wire)
        return Candidate(
            startSample = start.toDouble(),
            periodSamples = period,
            bits = bits,
            wire = wire,
            fixedScore = fixedScore,
            lowLevel = low,
            highLevel = high,
            crcValid = crcValid,
        )
    }

    /** Evaluates a candidate (start and period in samples) via the discriminator cumsum. */
    private fun evaluateCandidate(
        discriminatorSum: DoubleArray,
        start: Int,
        period: Double,
        features: DoubleArray,
    ): Candidate? {
        val n = discriminatorSum.size - 1
        for (symbol in 0 until FRAME_BITS) {
            val symbolStart = start + period * symbol
            val first = (symbolStart + period * 0.1375).roundToLong()
            val last = (symbolStart + period * 0.8625).roundToLong()
            if (first < 0 || last >= n) return null
            val f = first.toInt()
            val l = last.toInt()
            features[symbol] = (discriminatorSum[l] - discriminatorSum[f]) / max(1, l - f)
        }
        // two-means clustering of the symbol means into the two FSK classes
        var low = percentileUnsorted(features, 25.0)
        var high = percentileUnsorted(features, 75.0)
        repeat(12) {
            val threshold = (low + high) / 2.0
            var lowSum = 0.0
            var highSum = 0.0
            var lowCount = 0
            var highCount = 0
            for (value in features) {
                if (value <= threshold) {
                    lowSum += value; lowCount++
                } else {
                    highSum += value; highCount++
                }
            }
            val newLow = lowSum / max(1, lowCount)
            val newHigh = highSum / max(1, highCount)
            if (abs(newLow - low) < 1e-12 && abs(newHigh - high) < 1e-12) return@repeat
            low = newLow
            high = newHigh
        }
        val globalThreshold = (low + high) / 2.0
        val uniform = DoubleArray(FRAME_BITS) { globalThreshold }
        val globalCandidate = candidateFromThresholds(features, uniform, start, period, low, high)
        val localCandidate =
            candidateFromThresholds(features, markerAnchoredThresholds(features), start, period, low, high)
        return if (candidateOrder(localCandidate, globalCandidate) <= 0) localCandidate else globalCandidate
    }

    private fun percentileUnsorted(data: DoubleArray, percentile: Double): Double {
        val sorted = data.clone().also { it.sort() }
        return percentileOfSorted(sorted, percentile)
    }

    private fun candidateOrder(a: Candidate, b: Candidate): Int {
        if (a.crcValid != b.crcValid) return if (a.crcValid) -1 else 1
        if (a.fixedScore != b.fixedScore) return b.fixedScore - a.fixedScore
        return b.separation.compareTo(a.separation)
    }

    /**
     * Matched-filter score: correlation of the discriminator with the 72
     * fixed frame bits (prefix 0x0000, six 0x72 markers, closing 0xAA).
     * Expected 1 bits count positive, 0 bits negative; high values mark
     * plausible frame starts no matter where in the buffer the frame sits.
     */
    private fun matchedScore(discriminatorSum: DoubleArray, start: Int, period: Double): Double {
        val n = discriminatorSum.size - 1
        var score = 0.0
        for (i in fixedBitPositions.indices) {
            val symbolStart = start + period * fixedBitPositions[i]
            val first = (symbolStart + period * 0.1375).roundToLong()
            val last = (symbolStart + period * 0.8625).roundToLong()
            if (first < 0 || last >= n) return Double.NEGATIVE_INFINITY
            val d = (discriminatorSum[last.toInt()] - discriminatorSum[first.toInt()]) /
                max(1, (last - first).toInt())
            score += if (fixedBitValues[i].toInt() == 1) d else -d
        }
        return score
    }

    private class Seed(val start: Int, val period: Double, val score: Double)

    /**
     * Global frame search across the whole buffer: the matched filter on the
     * fixed frame bits locates the frame regardless of handling noise or the
     * lead-in pulses preceding it, without an energy-based segmentation step.
     */
    private fun findFrame(lowEnvelope: FloatArray, highEnvelope: FloatArray, sampleRate: Int): Candidate? {
        val n = lowEnvelope.size
        val totalEnergy = FloatArray(n)
        for (i in 0 until n) totalEnergy[i] = lowEnvelope[i] + highEnvelope[i]
        val sortedEnergy = DoubleArray(n) { totalEnergy[it].toDouble() }.also { it.sort() }
        val noiseFloor = max(percentileOfSorted(sortedEnergy, 25.0) * 2.0, 1e-12)
        val discriminatorSum = DoubleArray(n + 1)
        for (i in 0 until n) {
            val d = (lowEnvelope[i] - highEnvelope[i]) / (totalEnergy[i] + noiseFloor)
            discriminatorSum[i + 1] = discriminatorSum[i] + d
        }

        // stage 1: coarse matched-filter scan (step 1/8 symbol, all periods)
        val coarseStep = max(1, (sampleRate * 0.00125).roundToInt())
        val topPerPeriod = 5
        val seeds = ArrayList<Seed>()
        var period = sampleRate * 0.00970
        val periodEnd = sampleRate * 0.01030
        while (period <= periodEnd + 1e-9) {
            val bestScores = DoubleArray(topPerPeriod) { Double.NEGATIVE_INFINITY }
            val bestStarts = IntArray(topPerPeriod)
            var start = 0
            while (true) {
                val score = matchedScore(discriminatorSum, start, period)
                if (score == Double.NEGATIVE_INFINITY) break // frame end past end of buffer
                var slot = -1
                for (k in 0 until topPerPeriod) {
                    if (score > bestScores[k] &&
                        (bestScores[k] == Double.NEGATIVE_INFINITY ||
                            kotlin.math.abs(bestStarts[k] - start) > sampleRate / 20)
                    ) {
                        slot = k
                        break
                    }
                    // close to a better hit: do not record it twice
                    if (kotlin.math.abs(bestStarts[k] - start) <= sampleRate / 20 &&
                        bestScores[k] != Double.NEGATIVE_INFINITY
                    ) {
                        if (score > bestScores[k]) {
                            bestScores[k] = score
                            bestStarts[k] = start
                        }
                        slot = -1
                        break
                    }
                }
                if (slot >= 0) {
                    for (k in topPerPeriod - 1 downTo slot + 1) {
                        bestScores[k] = bestScores[k - 1]
                        bestStarts[k] = bestStarts[k - 1]
                    }
                    bestScores[slot] = score
                    bestStarts[slot] = start
                }
                start += coarseStep
            }
            for (k in 0 until topPerPeriod) {
                if (bestScores[k] != Double.NEGATIVE_INFINITY) {
                    seeds.add(Seed(bestStarts[k], period, bestScores[k]))
                }
            }
            period += 0.5
        }
        if (seeds.isEmpty()) return null

        // sort across all periods by score and merge nearby starts
        seeds.sortByDescending { it.score }
        val merged = ArrayList<Seed>()
        for (seed in seeds) {
            if (merged.none { kotlin.math.abs(it.start - seed.start) < sampleRate / 10 }) {
                merged.add(seed)
                if (merged.size >= 10) break
            }
        }

        // stage 2: refine each seed's alignment via the matched filter
        val aligned = merged.map { seed ->
            var best = seed
            var p = seed.period - 0.75
            while (p <= seed.period + 0.751) {
                var s = seed.start - 90
                while (s <= seed.start + 90) {
                    val score = matchedScore(discriminatorSum, s, p)
                    if (score > best.score) best = Seed(s, p, score)
                    s += 6
                }
                p += 0.25
            }
            best
        }.sortedByDescending { it.score }

        // stage 3: full evaluation (clustering + CRC) on a fine grid, best first
        val features = DoubleArray(FRAME_BITS)
        var bestInvalid: Candidate? = null
        for (seed in aligned) {
            val candidates = ArrayList<Candidate>()
            var p = seed.period - 0.65
            while (p <= seed.period + 0.651) {
                for (s in seed.start - 12..seed.start + 12) {
                    evaluateCandidate(discriminatorSum, s, p, features)?.let { candidates.add(it) }
                }
                p += 0.025
            }
            candidates.sortWith(::candidateOrder)
            val best = candidates.firstOrNull() ?: continue
            if (best.crcValid) return best
            if (bestInvalid == null || candidateOrder(best, bestInvalid) < 0) bestInvalid = best
        }
        return null
    }
}
