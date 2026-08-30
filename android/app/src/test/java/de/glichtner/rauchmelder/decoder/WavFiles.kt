package de.glichtner.rauchmelder.decoder

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE reader for 16-bit PCM mono test recordings. */
object WavFiles {
    fun load(file: File): Pair<FloatArray, Int> {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == 0x46464952) { "no RIFF header" }
        buffer.int // file size
        require(buffer.int == 0x45564157) { "no WAVE header" }
        var sampleRate = 0
        while (buffer.remaining() > 8) {
            val chunkId = buffer.int
            val chunkSize = buffer.int
            when (chunkId) {
                0x20746d66 -> { // "fmt "
                    val start = buffer.position()
                    buffer.short // audio format
                    val channels = buffer.short.toInt()
                    sampleRate = buffer.int
                    require(channels == 1) { "only mono supported" }
                    buffer.position(start + chunkSize)
                }
                0x61746164 -> { // "data"
                    val samples = FloatArray(chunkSize / 2)
                    for (i in samples.indices) samples[i] = buffer.short / 32768.0f
                    return samples to sampleRate
                }
                else -> buffer.position(buffer.position() + chunkSize)
            }
        }
        throw IllegalArgumentException("no data chunk found")
    }
}
