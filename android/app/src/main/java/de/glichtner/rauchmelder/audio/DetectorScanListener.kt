package de.glichtner.rauchmelder.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import de.glichtner.rauchmelder.decoder.AudioLinkDecoder
import de.glichtner.rauchmelder.decoder.smartsonic.SmartsonicDecoder
import de.glichtner.rauchmelder.model.DetectorReading
import de.glichtner.rauchmelder.model.toReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the scan is currently doing, for display in the UI. */
enum class ScanPhase {
    /** Waiting for a detector tone. */
    LISTENING,

    /** A detector tone is being received right now. */
    RECEIVING,

    /** A tone burst has ended (or an interval elapsed) and decoding runs. */
    DECODING,
}

/**
 * Records audio from the microphone and periodically runs every supported
 * decoder over the buffer, so the protocol (Ei AudioLINK+, started by pressing
 * the test button three times within five seconds, or Hekatron Smartsonic,
 * started by holding the test button for five seconds) is detected
 * automatically from the signal.
 *
 * A lightweight Goertzel tone detector watches the FSK bands of both
 * protocols so the UI can show when a transmission is being received, and a
 * decode attempt is started immediately when a tone burst ends instead of
 * waiting for the next interval.
 *
 * With a non-null [debugDir], a failed attempt (timeout without a valid
 * frame) writes the capture buffer as WAV there so the case can be analyzed
 * offline with the reference decoders.
 */
class DetectorScanListener(
    private val scope: CoroutineScope,
    private val debugDir: File? = null,
) {

    interface Callback {
        fun onPhase(phase: ScanPhase)
        fun onResult(reading: DetectorReading)
        fun onTimeout()
        fun onError(message: String)
    }

    private var job: Job? = null

    companion object {
        private const val TAG = "DetectorScanListener"
        private const val MAX_BUFFER_SECONDS = 15
        private const val TIMEOUT_SECONDS = 90
        private const val DECODE_INTERVAL_SECONDS = 2.5
        private const val MIN_AUDIO_SECONDS = 5.0

        // Tone detector, calibrated on the real recordings of both protocols:
        // in-frame chunks stay above the threshold (5th percentile 0.28,
        // Smartsonic minimum 0.25) with only single-chunk dips, while
        // interference exceeds it only briefly. Requiring 0.8 s of sustained
        // tone rejects fiddling with the detector, and bridging single quiet
        // chunks keeps the indicator steady during a frame; on all real
        // recordings this yields exactly one burst per transmission and none
        // outside.
        private const val TONE_RATIO_THRESHOLD = 0.2
        private const val TONE_START_CHUNKS = 8 // 0.8 s sustained tone starts RECEIVING
        private const val TONE_DROPOUT_CHUNKS = 1 // single quiet chunks are bridged
        private const val TONE_END_CHUNKS = 5 // 0.5 s of silence ends the burst
        private const val MIN_BURST_CHUNKS = 25 // bursts >= 2.5 s trigger a decode

        // AudioLINK+ tones, the Smartsonic tone range, and the 3 kHz comb
        // component that runs through the whole AudioLINK+ frame (its FSK
        // tones alone dip during long zero runs). The 5 ms analysis windows
        // have a ~±200 Hz main lobe, so three bins cover the Smartsonic range.
        private val TONE_BINS_HZ = doubleArrayOf(5500.0, 6800.0, 4100.0, 4450.0, 4800.0, 3000.0)

        /** Runs all decoders over one buffer; first valid frame wins. */
        fun decodeAny(samples: FloatArray, sampleRate: Int): DetectorReading? {
            AudioLinkDecoder.decode(samples, sampleRate)?.let { return it.fields.toReading() }
            SmartsonicDecoder.decode(samples, sampleRate, LocalDate.now())?.let { return it.fields.toReading() }
            return null
        }

        /**
         * Fraction of the chunk's energy captured by detector tones: per 5 ms
         * Goertzel window the strongest bin is taken, and those maxima are
         * averaged over the chunk. Taking the per-window maximum first is
         * essential for FSK - the active tone changes within a chunk (every
         * ~6 ms for Smartsonic's biphase symbols), so averaging per bin over
         * the chunk would dilute the ratio below any usable threshold. Close
         * to 1 for detector tones, near 0 for speech and noise.
         */
        fun toneRatio(chunk: ShortArray, length: Int, sampleRate: Int): Double {
            val window = sampleRate / 200
            if (length < window) return 0.0
            var meanSquare = 0.0
            for (i in 0 until length) {
                val value = chunk[i] / 32768.0
                meanSquare += value * value
            }
            meanSquare /= length
            if (meanSquare < 1e-10) return 0.0
            val windows = length / window
            var sumOfBest = 0.0
            for (w in 0 until windows) {
                val base = w * window
                var best = 0.0
                for (frequency in TONE_BINS_HZ) {
                    val omega = 2.0 * PI * frequency / sampleRate
                    var re = 0.0
                    var im = 0.0
                    for (i in 0 until window) {
                        val value = chunk[base + i] / 32768.0
                        val phase = omega * i
                        re += value * cos(phase)
                        im -= value * sin(phase)
                    }
                    val power = 2.0 * (re * re + im * im) / (window.toDouble() * window)
                    if (power > best) best = power
                }
                sumOfBest += best
            }
            return (sumOfBest / windows) / meanSquare
        }
    }

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Prefer an unprocessed source: per the API contract, UNPROCESSED and
     * VOICE_RECOGNITION disable AGC/noise suppression, which on some devices
     * (e.g. Samsung) can distort the signal bands when using MIC.
     */
    private fun openRecord(sampleRate: Int, bufferBytes: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            try {
                @SuppressLint("MissingPermission")
                val record = AudioRecord(
                    source, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "AudioRecord opened: source $source, $sampleRate Hz")
                    return record
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "source $source unavailable: ${e.message}")
            }
        }
        return null
    }

    fun start(callback: Callback) {
        if (isRunning) return
        job = scope.launch(Dispatchers.Default) {
            // 44.1 kHz is Smartsonic's native rate; AudioLINK+ is rate-agnostic.
            val sampleRate = intArrayOf(44100, 48000).firstOrNull {
                AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
            } ?: run {
                notify(callback) { onError("Kein unterstütztes Aufnahmeformat gefunden") }
                return@launch
            }
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            // 2 s buffer (bytes) so no samples are lost while decode attempts run
            val record = openRecord(sampleRate, maxOf(minBuffer * 4, sampleRate * 4))
            if (record == null) {
                notify(callback) { onError("Mikrofon konnte nicht initialisiert werden") }
                return@launch
            }

            val maxSamples = sampleRate * MAX_BUFFER_SECONDS
            val ring = FloatArray(maxSamples)
            var written = 0L
            val chunk = ShortArray(sampleRate / 10)
            var samplesSinceDecode = 0L
            var totalSamples = 0L
            var decodeJob: Job? = null
            var decodePending = false
            val decodeResult = java.util.concurrent.atomic.AtomicReference<DetectorReading?>(null)

            var toneActive = false
            var activeStreak = 0
            var quietStreak = 0
            var burstChunks = 0
            var burstDecode = false
            var phase = ScanPhase.LISTENING

            fun snapshotBuffer(): FloatArray {
                val available = minOf(written, maxSamples.toLong()).toInt()
                val snapshot = FloatArray(available)
                val startIndex = ((written - available) % maxSamples).toInt()
                for (i in 0 until available) {
                    snapshot[i] = ring[(startIndex + i) % maxSamples]
                }
                return snapshot
            }

            suspend fun updatePhase() {
                // interval decode attempts run silently; only a burst-triggered
                // attempt is shown as DECODING
                val next = when {
                    toneActive -> ScanPhase.RECEIVING
                    burstDecode && (decodeJob?.isActive == true || decodePending) -> ScanPhase.DECODING
                    else -> ScanPhase.LISTENING
                }
                if (next != phase) {
                    phase = next
                    notify(callback) { onPhase(next) }
                }
            }

            try {
                record.startRecording()
                notify(callback) { onPhase(ScanPhase.LISTENING) }
                while (isActive && totalSamples < sampleRate.toLong() * TIMEOUT_SECONDS) {
                    decodeResult.get()?.let { result ->
                        notify(callback) { onResult(result) }
                        return@launch
                    }
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    for (i in 0 until read) {
                        ring[(written % maxSamples).toInt()] = chunk[i] / 32768.0f
                        written++
                    }
                    totalSamples += read
                    samplesSinceDecode += read

                    // tone detector: sustained-tone start, dip bridging, quiet end
                    val ratio = toneRatio(chunk, read, sampleRate)
                    if (ratio >= TONE_RATIO_THRESHOLD) {
                        activeStreak += 1 + if (activeStreak > 0) quietStreak else 0
                        quietStreak = 0
                    } else {
                        quietStreak++
                        if (quietStreak > TONE_DROPOUT_CHUNKS) activeStreak = 0
                    }
                    if (!toneActive && activeStreak >= TONE_START_CHUNKS) {
                        toneActive = true
                        burstChunks = activeStreak
                        Log.i(TAG, "tone burst started (ratio %.2f)".format(ratio))
                    } else if (toneActive) {
                        if (quietStreak == 0 || quietStreak <= TONE_DROPOUT_CHUNKS) burstChunks++
                        if (quietStreak >= TONE_END_CHUNKS) {
                            toneActive = false
                            Log.i(TAG, "tone burst ended after ~${burstChunks * 100} ms")
                            // decode right away once a plausible transmission ended
                            if (burstChunks >= MIN_BURST_CHUNKS) {
                                decodePending = true
                                burstDecode = true
                            }
                            burstChunks = 0
                        }
                    }

                    // decode in parallel with recording so record.read() keeps
                    // running and the AudioRecord buffer does not overflow
                    val intervalElapsed = totalSamples >= sampleRate * MIN_AUDIO_SECONDS &&
                        samplesSinceDecode >= sampleRate * DECODE_INTERVAL_SECONDS
                    if ((decodePending || intervalElapsed) && decodeJob?.isActive != true) {
                        decodePending = false
                        samplesSinceDecode = 0
                        val snapshot = snapshotBuffer()
                        val fromBurst = burstDecode
                        decodeJob = launch(Dispatchers.Default) {
                            val startedAt = System.currentTimeMillis()
                            val result = decodeAny(snapshot, sampleRate)
                            Log.i(
                                TAG,
                                "decode attempt over ${snapshot.size / sampleRate} s: " +
                                    "${result?.let { "OK (${it.protocol} ${it.id})" } ?: "no frame"}, " +
                                    "${System.currentTimeMillis() - startedAt} ms",
                            )
                            result?.let { decodeResult.set(it) }
                            if (fromBurst) burstDecode = false
                        }
                    }
                    updatePhase()
                }
                decodeJob?.join()
                decodeResult.get()?.let { result ->
                    notify(callback) { onResult(result) }
                    return@launch
                }
                if (isActive) {
                    writeDebugWav(snapshotBuffer(), sampleRate)
                    notify(callback) { onTimeout() }
                }
            } finally {
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                }
                record.release()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Writes the capture buffer as 16-bit PCM WAV into the debug directory. */
    private fun writeDebugWav(samples: FloatArray, sampleRate: Int) {
        val dir = debugDir ?: return
        try {
            dir.mkdirs()
            val file = File(dir, "last-scan.wav")
            val dataBytes = samples.size * 2
            java.io.DataOutputStream(file.outputStream().buffered()).use { out ->
                fun writeIntLe(value: Int) {
                    out.write(value and 0xFF)
                    out.write((value shr 8) and 0xFF)
                    out.write((value shr 16) and 0xFF)
                    out.write((value shr 24) and 0xFF)
                }
                fun writeShortLe(value: Int) {
                    out.write(value and 0xFF)
                    out.write((value shr 8) and 0xFF)
                }
                out.writeBytes("RIFF")
                writeIntLe(36 + dataBytes)
                out.writeBytes("WAVE")
                out.writeBytes("fmt ")
                writeIntLe(16)
                writeShortLe(1) // PCM
                writeShortLe(1) // mono
                writeIntLe(sampleRate)
                writeIntLe(sampleRate * 2)
                writeShortLe(2)
                writeShortLe(16)
                out.writeBytes("data")
                writeIntLe(dataBytes)
                for (sample in samples) {
                    val value = (sample * 32767f).toInt().coerceIn(-32768, 32767)
                    writeShortLe(value)
                }
            }
            Log.i(TAG, "debug WAV written: ${file.absolutePath} (${samples.size / sampleRate} s)")
        } catch (e: Exception) {
            Log.w(TAG, "could not write debug WAV: ${e.message}")
        }
    }

    private suspend fun notify(callback: Callback, block: Callback.() -> Unit) {
        withContext(Dispatchers.Main) { callback.block() }
    }
}
