// PcmToWavConverter.kt
package com.example.voiceanalyzerapp

import android.util.Log
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PcmToWavConverter {

    private const val TAG = "PcmToWavConverter"

    /**
     * Converts a PCM audio file to a WAV file.
     *
     * @param pcmFile The input PCM file.
     * @param wavFile The output WAV file.
     * @param channels Number of channels (1 for mono, 2 for stereo).
     * @param sampleRate Sample rate in Hz (e.g., 44100).
     * @param bitDepth Bits per sample (e.g., 16).
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    fun convertPcmToWav(pcmFile: File, wavFile: File, channels: Int, sampleRate: Int, bitDepth: Int) {
        if (!pcmFile.exists()) {
            Log.e(TAG, "PCM file does not exist: ${pcmFile.absolutePath}")
            throw FileNotFoundException("PCM file not found: ${pcmFile.absolutePath}")
        }
        Log.d(TAG, "Converting PCM (${pcmFile.length()} bytes) to WAV: ${wavFile.absolutePath}")
        Log.d(TAG, "Params: Channels=$channels, SampleRate=$sampleRate, BitDepth=$bitDepth")


        val pcmData = readPcmData(pcmFile)
        val dataLength = pcmData.size
        val overallSize = dataLength + 36 // 36 bytes for the header (without "data" chunk size itself)
        val byteRate = sampleRate * channels * (bitDepth / 8)
        val blockAlign = (channels * (bitDepth / 8)).toShort()

        val header = ByteBuffer.allocate(44)
        header.order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        header.put("RIFF".toByteArray())
        header.putInt(overallSize) // fileSize - 8, or dataLength + 36
        header.put("WAVE".toByteArray())

        // "fmt " sub-chunk
        header.put("fmt ".toByteArray())
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1) // AudioFormat (1 for PCM)
        header.putShort(channels.toShort()) // NumChannels
        header.putInt(sampleRate) // SampleRate
        header.putInt(byteRate) // ByteRate
        header.putShort(blockAlign) // BlockAlign
        header.putShort(bitDepth.toShort()) // BitsPerSample

        // "data" sub-chunk
        header.put("data".toByteArray())
        header.putInt(dataLength) // Subchunk2Size (data size)

        FileOutputStream(wavFile).use { fos ->
            fos.write(header.array())
            fos.write(pcmData)
        }
        Log.d(TAG, "WAV file created successfully: ${wavFile.absolutePath}, Size: ${wavFile.length()}")
    }

    @Throws(IOException::class)
    private fun readPcmData(pcmFile: File): ByteArray {
        FileInputStream(pcmFile).use { fis ->
            val pcmBytes = ByteArray(pcmFile.length().toInt())
            var bytesRead = 0
            var n: Int
            while (fis.read(pcmBytes, bytesRead, pcmBytes.size - bytesRead).also { n = it } > 0) {
                bytesRead += n
            }
            if (bytesRead != pcmBytes.size) {
                Log.w(TAG, "Could not read the entire PCM file. Expected ${pcmBytes.size}, read $bytesRead")
                // Можно выбросить исключение или вернуть усеченный массив
            }
            return pcmBytes
        }
    }
}