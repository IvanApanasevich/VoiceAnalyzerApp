package com.example.voiceanalyzerapp

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build // Импорт для проверки версии SDK
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi // Для условного использования API
import androidx.appcompat.app.AppCompatActivity
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.TarsosDSPAudioInputStream
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchProcessor
import be.tarsos.dsp.util.fft.FFT
import com.example.voiceanalyzerapp.databinding.ActivitySpectrogramBinding
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

class SpectrogramActivity : AppCompatActivity(), PitchDetectionHandler {
    // ... (остальные переменные класса без изменений) ...
    private lateinit var binding: ActivitySpectrogramBinding
    private var audioFilePath: String? = null
    private var dispatcher: AudioDispatcher? = null
    private var currentDetectedPitch: Double = -1.0

    private val dspBufferSize = 2048
    private val dspOverlap = 0

    private val TAG = "SpectrogramActivity"
    private lateinit var spectrogramView: SpectrogramView
    private val uiHandler = Handler(Looper.getMainLooper())


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpectrogramBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        spectrogramView = binding.spectrogramView
        audioFilePath = intent.getStringExtra("AUDIO_FILE_PATH")

        if (audioFilePath == null) {
            Toast.makeText(this, "Путь к аудиофайлу не передан.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        startAudioProcessing()
    }


    private fun startAudioProcessing() {
        val audioFile = File(audioFilePath!!)
        if (!audioFile.exists()) {
            // ... (обработка отсутствия файла)
            Log.e(TAG, "Аудиофайл не найден: $audioFilePath")
            Toast.makeText(this, "Аудиофайл не найден.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Thread {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            lateinit var tarsosFormat: TarsosDSPAudioFormat // Используем lateinit, т.к. зависит от sampleRate

            // Инициализируем переменные значениями по умолчанию
            var actualSampleRate: Float = 0f
            var trackDurationUs: Long = -1L // -1L означает неизвестную длительность

            try {
                extractor = MediaExtractor()
                extractor.setDataSource(audioFile.absolutePath)

                var audioTrackIndex = -1
                var inputFormat: MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("audio/") == true) {
                        audioTrackIndex = i
                        inputFormat = format
                        actualSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toFloat()

                        // Получение длительности с учетом версии API
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // API 29+
                            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                                trackDurationUs = format.getLong(MediaFormat.KEY_DURATION)
                            }
                        } else {
                            // Для API < 29, MediaFormat.KEY_DURATION может отсутствовать или быть Integer.
                            // Пытаемся получить, но если нет, то trackDurationUs останется -1L.
                            // Некоторые старые устройства могут хранить его как Integer (в миллисекундах, а не микро).
                            // Но MediaExtractor обычно предоставляет в микросекундах.
                            // Безопаснее предположить, что если getLong недоступен, точная инфо о длительности тоже.
                            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                                // На старых API, это может быть Integer, но getLong безопаснее, если доступен
                                // Здесь мы в else, значит getLong недоступен.
                                // Просто оставим trackDurationUs = -1L, если не можем быть уверены в формате.
                                Log.w(TAG, "KEY_DURATION присутствует, но API < 29, точное значение Long может быть недоступно или в другом формате.")
                            }
                        }
                        break // Нашли аудиодорожку, выходим из цикла
                    }
                }

                if (audioTrackIndex == -1 || inputFormat == null || actualSampleRate == 0f) {
                    Log.e(TAG, "Не удалось найти аудиодорожку или определить параметры в файле.")
                    uiHandler.post { Toast.makeText(this, "Ошибка анализа аудиофайла (нет аудиодорожки).", Toast.LENGTH_LONG).show() }
                    return@Thread
                }

                extractor.selectTrack(audioTrackIndex)

                val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)!!
                codec = MediaCodec.createDecoderByType(mimeType)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val channels = 1
                val sampleSizeInBits = 16
                val isSigned = true
                val isBigEndian = false

                tarsosFormat = TarsosDSPAudioFormat(
                    actualSampleRate,
                    sampleSizeInBits,
                    channels,
                    isSigned,
                    isBigEndian
                )

                val frameLength: Long = if (trackDurationUs > 0) {
                    (trackDurationUs / 1_000_000.0 * actualSampleRate).toLong()
                } else {
                    -1L // Неизвестная длина
                }

                val customAudioStream = createCustomAudioInputStream(codec, extractor, tarsosFormat, frameLength)
                dispatcher = AudioDispatcher(customAudioStream, dspBufferSize, dspOverlap)

                uiHandler.post {
                    spectrogramView.setAudioSampleRate(actualSampleRate)
                    spectrogramView.clearSpectrogram()
                }

                // ... (остальная часть метода с PitchProcessor, FFTProcessor и dispatcher.run() без изменений) ...
                val pitchProcessor = PitchProcessor(
                    PitchProcessor.PitchEstimationAlgorithm.YIN,
                    actualSampleRate,
                    dspBufferSize,
                    this
                )
                dispatcher?.addAudioProcessor(pitchProcessor)

                val fftProcessor = object : AudioProcessor {
                    val fft = FFT(dspBufferSize)
                    val amplitudes = FloatArray(dspBufferSize / 2)

//                    override fun process(audioEvent: AudioEvent): Boolean {
//                        val audioFloatBuffer = audioEvent.floatBuffer.copyOf()
//                        fft.forwardTransform(audioFloatBuffer)
//                        fft.modulus(audioFloatBuffer, amplitudes)
//                        uiHandler.post {
//                            spectrogramView.drawFFTData(currentDetectedPitch, amplitudes, fft, actualSampleRate)
//                        }
//                        return true
//                    }
                    override fun process(audioEvent: AudioEvent): Boolean {
                        val audioFloatBuffer = audioEvent.floatBuffer.copyOf()
                        fft.forwardTransform(audioFloatBuffer) // audioFloatBuffer здесь содержит комплексные числа
                        fft.modulus(audioFloatBuffer, amplitudes) // amplitudes теперь содержит магнитуды

                        // Логирование для проверки амплитуд
                        val maxAmp = amplitudes.maxOrNull() ?: 0.0f
                        val avgAmp = amplitudes.average().toFloat()
                        Log.d(TAG, "FFTProcessor: Max amplitude = $maxAmp, Avg amplitude = $avgAmp, Pitch = $currentDetectedPitch")
                        // Если видишь, что maxAmp и avgAmp постоянно близки к нулю, это проблема.

                        uiHandler.post {
                            // Передаем ОРИГИНАЛЬНЫЕ amplitudes, а не audioFloatBuffer после forwardTransform
                            spectrogramView.drawFFTData(currentDetectedPitch, amplitudes, fft, actualSampleRate)
                        }
                        return true
                    }

                    override fun processingFinished() {
                        Log.d(TAG, "Обработка аудио завершена (FFT).")
                        uiHandler.post {
                            Toast.makeText(this@SpectrogramActivity, "Анализ спектрограммы завершен", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                dispatcher?.addAudioProcessor(fftProcessor)
                dispatcher?.run()


            } catch (e: IOException) {
                // ... (обработка исключений)
                Log.e(TAG, "IOException во время обработки аудио: ${e.message}", e)
                uiHandler.post { Toast.makeText(this, "Ошибка чтения аудио: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException во время обработки аудио: ${e.message}", e)
                uiHandler.post { Toast.makeText(this, "Ошибка состояния аудиокомпонентов: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                Log.e(TAG, "Непредвиденная ошибка: ${e.message}", e)
                uiHandler.post { Toast.makeText(this, "Ошибка: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            } finally {
                // ... (освобождение ресурсов)
                try {
                    codec?.stop()
                    codec?.release()
                    extractor?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при освобождении ресурсов: ${e.message}")
                }
            }
        }.start()
    }

    // createCustomAudioInputStream - без изменений по сравнению с предыдущей версией
    private fun createCustomAudioInputStream(
        codec: MediaCodec,
        extractor: MediaExtractor,
        audioFormat: TarsosDSPAudioFormat,
        streamFrameLength: Long
    ): TarsosDSPAudioInputStream {
        return object : TarsosDSPAudioInputStream {
            private val bufferInfo = MediaCodec.BufferInfo()
            private var MACC_inputEOS = false
            private var MACC_outputEOS = false
            private var remainingBytesFromPreviousOutput: ByteBuffer? = null

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (MACC_outputEOS && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining())) {
                    return -1
                }
                var bytesRead = 0
                var outputBuffer: ByteBuffer?

                if (remainingBytesFromPreviousOutput != null && remainingBytesFromPreviousOutput!!.hasRemaining()) {
                    val toRead = kotlin.math.min(len, remainingBytesFromPreviousOutput!!.remaining())
                    remainingBytesFromPreviousOutput!!.get(b, off, toRead)
                    bytesRead += toRead
                    if (!remainingBytesFromPreviousOutput!!.hasRemaining()) {
                        remainingBytesFromPreviousOutput = null
                    }
                    if (bytesRead == len) return bytesRead
                }


                while (!MACC_outputEOS && bytesRead < len) {
                    if (!MACC_inputEOS) {
                        val inputBufIndex = codec.dequeueInputBuffer(2000L)
                        if (inputBufIndex >= 0) {
                            val inputBuf = codec.getInputBuffer(inputBufIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                MACC_inputEOS = true
                            } else {
                                codec.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, 2000L)
                    if (outputBufIndex >= 0) {
                        outputBuffer = codec.getOutputBuffer(outputBufIndex)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            MACC_outputEOS = true
                        }

                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val toRead = kotlin.math.min(len - bytesRead, bufferInfo.size)
                            outputBuffer.get(b, off + bytesRead, toRead)
                            bytesRead += toRead

                            if (bufferInfo.size > toRead) {
                                val remaining = bufferInfo.size - toRead
                                val temp = ByteBuffer.allocate(remaining)
                                outputBuffer.get(temp.array())
                                remainingBytesFromPreviousOutput = temp
                            }
                        }
                        codec.releaseOutputBuffer(outputBufIndex, false)
                        if (bytesRead == len || MACC_outputEOS) break

                    } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Формат вывода декодера изменен: " + codec.outputFormat.toString())
                    } else if (outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (MACC_inputEOS && MACC_outputEOS && bytesRead == 0 && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining())) return -1
                    }
                    if (MACC_outputEOS && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining()) && bytesRead == 0 && outputBufIndex <0 ){
                        return if (bytesRead > 0) bytesRead else -1
                    }
                }
                return if (bytesRead > 0) bytesRead else if (MACC_outputEOS && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining())) -1 else 0
            }

            override fun skip(n: Long): Long {
                val buffer = ByteArray(1024.coerceAtMost(n.toInt()))
                var skipped: Long = 0
                while (skipped < n) {
                    val toSkip = kotlin.math.min(n - skipped, buffer.size.toLong()).toInt()
                    val readCount = read(buffer, 0, toSkip)
                    if (readCount == -1) {
                        break
                    }
                    skipped += readCount
                }
                return skipped
            }

            override fun close() {}

            override fun getFormat(): TarsosDSPAudioFormat = audioFormat

            override fun getFrameLength(): Long = streamFrameLength
        }
    }

    // ... (handlePitch, onSupportNavigateUp, onStop без изменений) ...
    override fun handlePitch(pitchDetectionResult: PitchDetectionResult, audioEvent: AudioEvent) {
        currentDetectedPitch = if (pitchDetectionResult.isPitched) {
            pitchDetectionResult.pitch.toDouble()
        } else {
            -1.0
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onStop() {
        super.onStop()
        dispatcher?.stop()
    }
}




//package com.example.voiceanalyzerapp
//
//import android.os.Bundle
//import android.widget.Toast // <<< IMPORT ADDED HERE
//import androidx.appcompat.app.AppCompatActivity
//import com.example.voiceanalyzerapp.databinding.ActivitySpectrogramBinding // Import ViewBinding
//
//class SpectrogramActivity : AppCompatActivity() {
//    private lateinit var binding: ActivitySpectrogramBinding
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        binding = ActivitySpectrogramBinding.inflate(layoutInflater)
//        setContentView(binding.root)
//
//        setSupportActionBar(binding.toolbar)
//        supportActionBar?.setDisplayHomeAsUpEnabled(true)
//        supportActionBar?.setDisplayShowHomeEnabled(true)
//
//        // val audioFilePath = intent.getStringExtra("AUDIO_FILE_PATH")
//        // TODO: Load audioFilePath, generate spectrogram, and display it in binding.imgSpectrogram
//        // For now, it just shows a placeholder from the XML.
//        // You would use a library or custom code to generate a Bitmap for the spectrogram.
//        // e.g., binding.imgSpectrogram.setImageBitmap(generatedSpectrogramBitmap)
//
//        Toast.makeText(this, "Окно спектрограммы (пока заглушка)", Toast.LENGTH_LONG).show()
//    }
//
//    override fun onSupportNavigateUp(): Boolean {
//        onBackPressedDispatcher.onBackPressed() // Standard back behavior
//        return true
//    }
//}