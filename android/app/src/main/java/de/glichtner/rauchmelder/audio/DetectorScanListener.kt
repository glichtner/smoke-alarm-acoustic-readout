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

/**
 * Records audio from the microphone and periodically runs every supported
 * decoder over the buffer, so the protocol (Ei AudioLINK+, started by pressing
 * the test button three times within five seconds, or Hekatron Smartsonic,
 * started by holding the test button for five seconds) is detected
 * automatically from the signal.
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
        fun onLevel(rms: Float)
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

        /** Runs all decoders over one buffer; first valid frame wins. */
        fun decodeAny(samples: FloatArray, sampleRate: Int): DetectorReading? {
            AudioLinkDecoder.decode(samples, sampleRate)?.let { return it.fields.toReading() }
            SmartsonicDecoder.decode(samples, sampleRate, LocalDate.now())?.let { return it.fields.toReading() }
            return null
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
            val decodeResult = java.util.concurrent.atomic.AtomicReference<DetectorReading?>(null)

            fun snapshotBuffer(): FloatArray {
                val available = minOf(written, maxSamples.toLong()).toInt()
                val snapshot = FloatArray(available)
                val startIndex = ((written - available) % maxSamples).toInt()
                for (i in 0 until available) {
                    snapshot[i] = ring[(startIndex + i) % maxSamples]
                }
                return snapshot
            }

            try {
                record.startRecording()
                while (isActive && totalSamples < sampleRate.toLong() * TIMEOUT_SECONDS) {
                    decodeResult.get()?.let { result ->
                        notify(callback) { onResult(result) }
                        return@launch
                    }
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    var sumSquares = 0.0
                    for (i in 0 until read) {
                        val value = chunk[i] / 32768.0f
                        ring[(written % maxSamples).toInt()] = value
                        written++
                        sumSquares += value * value
                    }
                    totalSamples += read
                    samplesSinceDecode += read
                    notify(callback) { onLevel(kotlin.math.sqrt(sumSquares / read).toFloat()) }

                    // decode in parallel with recording so record.read() keeps
                    // running and the AudioRecord buffer does not overflow
                    if (totalSamples >= sampleRate * MIN_AUDIO_SECONDS &&
                        samplesSinceDecode >= sampleRate * DECODE_INTERVAL_SECONDS &&
                        decodeJob?.isActive != true
                    ) {
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
