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

        // tone detector: chunk fraction of energy that must sit in one FSK bin
        private const val TONE_RATIO_THRESHOLD = 0.25
        private const val TONE_START_CHUNKS = 2 // 0.2 s of tone starts RECEIVING
        private const val TONE_END_CHUNKS = 5 // 0.5 s of silence ends the burst
        private const val MIN_BURST_CHUNKS = 10 // bursts >= 1 s trigger a decode

        // AudioLINK+ tones plus a grid over the Smartsonic tone range
        private val TONE_BINS_HZ = doubleArrayOf(
            5500.0, 6800.0,
            4000.0, 4150.0, 4300.0, 4450.0, 4600.0, 4750.0, 4900.0,
        )

        /** Runs all decoders over one buffer; first valid frame wins. */
        fun decodeAny(samples: FloatArray, sampleRate: Int): DetectorReading? {
            AudioLinkDecoder.decode(samples, sampleRate)?.let { return it.fields.toReading() }
            SmartsonicDecoder.decode(samples, sampleRate, LocalDate.now())?.let { return it.fields.toReading() }
            return null
        }

        /**
         * Fraction of the chunk's energy captured by the strongest tone bin,
         * measured per 10 ms Goertzel window (bandwidth ~100 Hz, so the
         * device-dependent tone offsets stay inside a bin's main lobe).
         * Close to 1 for a pure detector tone, near 0 for speech and noise.
         */
        fun toneRatio(chunk: ShortArray, length: Int, sampleRate: Int): Double {
            val window = sampleRate / 100
            if (length < window) return 0.0
            var meanSquare = 0.0
            for (i in 0 until length) {
                val value = chunk[i] / 32768.0
                meanSquare += value * value
            }
            meanSquare /= length
            if (meanSquare < 1e-10) return 0.0
            var best = 0.0
            val windows = length / window
            for (frequency in TONE_BINS_HZ) {
                val omega = 2.0 * PI * frequency / sampleRate
                var power = 0.0
                for (w in 0 until windows) {
                    var re = 0.0
                    var im = 0.0
                    val base = w * window
                    for (i in 0 until window) {
                        val value = chunk[base + i] / 32768.0
                        val phase = omega * i
                        re += value * cos(phase)
                        im -= value * sin(phase)
                    }
                    val magnitude = (re * re + im * im) / (window.toDouble() * window)
                    power += 2.0 * magnitude
                }
                power /= windows
                if (power > best) best = power
            }
            return best / meanSquare
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
            var activeChunks = 0
            var inactiveChunks = 0
            var burstChunks = 0
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
                val next = when {
                    toneActive -> ScanPhase.RECEIVING
                    decodeJob?.isActive == true || decodePending -> ScanPhase.DECODING
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

                    // tone detector with hysteresis
                    val ratio = toneRatio(chunk, read, sampleRate)
                    if (ratio >= TONE_RATIO_THRESHOLD) {
                        activeChunks++
                        inactiveChunks = 0
                        if (!toneActive && activeChunks >= TONE_START_CHUNKS) {
                            toneActive = true
                            burstChunks = activeChunks
                            Log.i(TAG, "tone burst started (ratio %.2f)".format(ratio))
                        } else if (toneActive) {
                            burstChunks++
                        }
                    } else {
                        inactiveChunks++
                        if (toneActive && inactiveChunks >= TONE_END_CHUNKS) {
                            toneActive = false
                            Log.i(TAG, "tone burst ended after ~${burstChunks * 100} ms")
                            // decode right away once a plausible transmission ended
                            if (burstChunks >= MIN_BURST_CHUNKS) decodePending = true
                            burstChunks = 0
                        }
                        if (!toneActive) activeChunks = 0
                    }

                    // decode in parallel with recording so record.read() keeps
                    // running and the AudioRecord buffer does not overflow
                    val intervalElapsed = totalSamples >= sampleRate * MIN_AUDIO_SECONDS &&
                        samplesSinceDecode >= sampleRate * DECODE_INTERVAL_SECONDS
                    if ((decodePending || intervalElapsed) && decodeJob?.isActive != true) {
                        decodePending = false
                        samplesSinceDecode = 0
                        val snapshot = snapshotBuffer()
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
