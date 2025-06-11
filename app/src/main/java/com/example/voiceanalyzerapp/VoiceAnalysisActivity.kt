package com.example.voiceanalyzerapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.voiceanalyzerapp.databinding.ActivityVoiceAnalysisBinding
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

class VoiceAnalysisActivity : AppCompatActivity(), AudioAnalyzer.AnalysisListener {
    private lateinit var binding: ActivityVoiceAnalysisBinding
    private var currentAnalysisType: String = ""

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null

    // Основной файл для воспроизводимого/анализируемого аудио (после обработки)
    private var playableAudioFilePath: String? = null
    // Временный файл для сырой записи
    private var rawRecordingFilePath: String? = null

    private var isRecording = false

    private val RECORD_AUDIO_PERMISSION_CODE = 101
    private val TAG = "VoiceAnalysisActivity"

    private val progressUpdateHandler = Handler(Looper.getMainLooper())
    private lateinit var progressUpdateRunnable: Runnable
    private val PROGRESS_UPDATE_INTERVAL = 50L

    private lateinit var audioAnalyzer: AudioAnalyzer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        currentAnalysisType = getString(R.string.default_analysis_type)

        val cacheDir = externalCacheDir?.absolutePath ?: filesDir.absolutePath
        playableAudioFilePath = "$cacheDir/analyzed_audiorecord.wav" // Сохраняем в WAV
        rawRecordingFilePath = "$cacheDir/raw_audiorecord.3gp"

        Log.d(TAG, "Playable audio file path: $playableAudioFilePath")
        Log.d(TAG, "Raw recording file path: $rawRecordingFilePath")

        // Очистка кеша при входе
        clearCacheFiles()
        updatePlayButtonState() // Обновляем состояние кнопки Play

        binding.playerVisualizerView.updateVisualizer(null)
        binding.playerVisualizerView.updatePlayerPercent(0f)

        binding.btnPlay.setOnClickListener { playRecording() }
        binding.btnShowSpectrogram.setOnClickListener {
            val currentAudioFile = playableAudioFilePath?.let { File(it) }
            if (currentAudioFile != null && currentAudioFile.exists() && currentAudioFile.length() > 0) {
                val intent = Intent(this, SpectrogramActivity::class.java)
                intent.putExtra("AUDIO_FILE_PATH", playableAudioFilePath) // Используем обработанный файл
                startActivity(intent)
            } else {
                Toast.makeText(this, "Сначала запишите и проанализируйте аудио", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecordingAndAnalyze()
            } else {
                if (checkPermissions()) {
                    startRecording()
                } else {
                    requestPermissions()
                }
            }
        }

        audioAnalyzer = AudioAnalyzer(applicationContext)
        audioAnalyzer.setAnalysisListener(this)

        resetCharacteristicsDisplay()
        setupProgressUpdater()
    }

    private fun clearCacheFiles() {
        rawRecordingFilePath?.let { File(it).delete() }
        playableAudioFilePath?.let { File(it).delete() }
        Log.d(TAG, "Cache files cleared.")
    }

    private fun updatePlayButtonState() {
        val canPlay = playableAudioFilePath?.let { File(it).exists() && File(it).length() > 0 } == true
        binding.btnPlay.isEnabled = canPlay
        binding.btnPlay.alpha = if (canPlay) 1.0f else 0.5f // Визуальная индикация доступности
        binding.btnShowSpectrogram.isEnabled = canPlay
        binding.btnShowSpectrogram.alpha = if (canPlay) 1.0f else 0.5f
    }


    private fun resetCharacteristicsDisplay() {
        binding.tvF0Value.text = "--- Hz"
        binding.tvJitterValue.text = "---  %"
        binding.tvShimmerValue.text = "---  %"
        binding.tvNprValue.text = "--- dB"
        binding.tvIntensityValue.text = "--- dB"
        // Новые характеристики
        binding.tvPhonationTimeValue.text = "--- ms"
        binding.tvF1Value.text = "--- Hz"
        binding.tvF2Value.text = "--- Hz"
        binding.tvF3Value.text = "--- Hz"
    }

    private fun stopRecordingAndAnalyze() {
        if (!isRecording) return

        mediaRecorder?.apply {
            try {
                stop()
                Log.d(TAG, "MediaRecorder stopped successfully. Raw file: $rawRecordingFilePath")
            } catch (e: RuntimeException) {
                Log.e(TAG, "stopRecording() failed: ${e.message}")
                rawRecordingFilePath?.let { File(it).delete() }
            } finally {
                release()
            }
        }
        mediaRecorder = null
        isRecording = false
        binding.btnRecord.setImageResource(R.drawable.ic_record)
        binding.btnRecord.contentDescription = getString(R.string.start_recording)


        val rawFile = rawRecordingFilePath?.let { File(it) }
        if (rawFile != null && rawFile.exists() && rawFile.length() > 0) {
            Toast.makeText(this, "Запись остановлена. Анализ...", Toast.LENGTH_SHORT).show()
            runOnUiThread {
                binding.tvF0Value.text = "Анализ..."
                binding.tvJitterValue.text = "Анализ..."
                binding.tvShimmerValue.text = "Анализ..."
                binding.tvNprValue.text = "Анализ..."
                binding.tvIntensityValue.text = "Анализ..."
                binding.tvPhonationTimeValue.text = "Анализ..."
                binding.tvF1Value.text = "Анализ..."
                binding.tvF2Value.text = "Анализ..."
                binding.tvF3Value.text = "Анализ..."
            }
            audioAnalyzer.analyze(rawFile.absolutePath, playableAudioFilePath)
        } else {
            Toast.makeText(this, "Ошибка записи или файл пуст. Попробуйте снова.", Toast.LENGTH_LONG).show()
            binding.playerVisualizerView.updateVisualizer(null)
            resetCharacteristicsDisplay()
            updatePlayButtonState()
        }
    }

    override fun onAnalysisComplete(result: AnalysisResult, processedAudioPath: String?) {
        runOnUiThread {
            rawRecordingFilePath?.let { File(it).delete() }
            Log.d(TAG, "Raw recording file deleted after analysis.")

            if (result.errorMessage != null) {
                Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show()
                resetCharacteristicsDisplay()
                if (processedAudioPath == null) {
                    playableAudioFilePath?.let { File(it).delete() }
                }
                updatePlayButtonState()
                binding.playerVisualizerView.updateVisualizer(null)
                return@runOnUiThread
            }

            if (processedAudioPath != null) {
                this.playableAudioFilePath = processedAudioPath
                val audioFile = File(processedAudioPath)
                if (audioFile.exists() && audioFile.length() > 0) {
                    val audioBytes = fileToBytes(audioFile)
                    binding.playerVisualizerView.updateVisualizer(audioBytes.takeIf { it.isNotEmpty() })
                    binding.playerVisualizerView.updatePlayerPercent(0f)
                } else {
                    binding.playerVisualizerView.updateVisualizer(null)
                }
            } else {
                Toast.makeText(this, "Анализ успешен, но не удалось сохранить обработанный файл.", Toast.LENGTH_LONG).show()
                playableAudioFilePath?.let { File(it).delete() }
                binding.playerVisualizerView.updateVisualizer(null)
            }

            binding.tvF0Value.text = if (result.f0 > 0) String.format("%.2f Hz", result.f0) else "--- Hz"
            binding.tvJitterValue.text = if (result.jitter > 0) String.format("%.2f %%", result.jitter) else "--- %"
            binding.tvShimmerValue.text = if (result.shimmer > 0) String.format("%.2f %%", result.shimmer) else "--- %"
            binding.tvNprValue.text = if (result.hnr != 0.0) String.format("%.2f dB", result.hnr) else "--- dB" // HNR может быть 0
            binding.tvIntensityValue.text = String.format("%.2f dB", result.intensity) // Интенсивность может быть 0 или отрицательной

            // Новые характеристики
            binding.tvPhonationTimeValue.text = if (result.phonationTimeMs > 0) String.format("%.0f ms", result.phonationTimeMs) else "--- ms"
            binding.tvF1Value.text = if (result.f1 > 0) String.format("%.2f Hz", result.f1) else "--- Hz"
            binding.tvF2Value.text = if (result.f2 > 0) String.format("%.2f Hz", result.f2) else "--- Hz"
            binding.tvF3Value.text = if (result.f3 > 0) String.format("%.2f Hz", result.f3) else "--- Hz"


            Toast.makeText(this, "Анализ завершен!", Toast.LENGTH_SHORT).show()
            updatePlayButtonState()
        }
    }

    override fun onAnalysisError(errorMessage: String) {
        runOnUiThread {
            rawRecordingFilePath?.let { File(it).delete() }
            playableAudioFilePath?.let { File(it).delete() }

            Toast.makeText(this, "Ошибка анализа: $errorMessage", Toast.LENGTH_LONG).show()
            resetCharacteristicsDisplay()
            binding.playerVisualizerView.updateVisualizer(null)
            updatePlayButtonState()
        }
    }


    private fun startRecording() {
        clearCacheFiles()
        resetCharacteristicsDisplay()
        binding.playerVisualizerView.updateVisualizer(null)
        binding.playerVisualizerView.updatePlayerPercent(0f)
        mediaPlayer?.release()
        mediaPlayer = null
        stopProgressUpdater()
        updatePlayButtonState()

        val mr: MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
        mr.setOutputFile(rawRecordingFilePath)

        this.mediaRecorder = mr
        Log.d(TAG, "Attempting to record raw audio to: $rawRecordingFilePath")
        try {
            mr.prepare()
            mr.start()
            isRecording = true
            binding.btnRecord.setImageResource(android.R.drawable.ic_media_pause)
            binding.btnRecord.contentDescription = getString(R.string.stop_recording)
            Toast.makeText(this@VoiceAnalysisActivity, "Запись началась...", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e(TAG, "MediaRecorder prepare() failed for $rawRecordingFilePath: ${e.message}")
            Toast.makeText(this@VoiceAnalysisActivity, "Ошибка начала записи: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
            binding.btnRecord.setImageResource(R.drawable.ic_record)
            this.mediaRecorder?.release()
            this.mediaRecorder = null
            rawRecordingFilePath?.let { File(it).delete() }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaRecorder start failed for $rawRecordingFilePath: ${e.message}")
            Toast.makeText(this@VoiceAnalysisActivity, "Ошибка старта записи: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
            binding.btnRecord.setImageResource(R.drawable.ic_record)
            this.mediaRecorder?.release()
            this.mediaRecorder = null
            rawRecordingFilePath?.let { File(it).delete() }
        }
    }

    private fun playRecording() {
        if (isRecording) {
            Toast.makeText(this, "Сначала остановите запись", Toast.LENGTH_SHORT).show()
            return
        }
        val audioFileToPlay = playableAudioFilePath?.let { File(it) }

        if (audioFileToPlay != null && audioFileToPlay.exists() && audioFileToPlay.length() > 0) {
            mediaPlayer?.release()
            stopProgressUpdater()
            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(audioFileToPlay.absolutePath)
                    prepare()
                    start()
                    if (binding.playerVisualizerView.getBytes() == null && audioFileToPlay.exists() && audioFileToPlay.length() > 0) {
                        Log.w(TAG, "Visualizer was empty during play, attempting to load bytes for: ${audioFileToPlay.absolutePath}")
                        val audioBytes = fileToBytes(audioFileToPlay)
                        binding.playerVisualizerView.updateVisualizer(audioBytes.takeIf { it.isNotEmpty() })
                    }

                    binding.playerVisualizerView.updatePlayerPercent(0f)
                    startProgressUpdater()
                    Toast.makeText(this@VoiceAnalysisActivity, "Воспроизведение...", Toast.LENGTH_SHORT).show()
                    setOnCompletionListener {
                        Toast.makeText(this@VoiceAnalysisActivity, "Воспроизведение завершено", Toast.LENGTH_SHORT).show()
                        it.release()
                        mediaPlayer = null
                        stopProgressUpdater()
                        binding.playerVisualizerView.updatePlayerPercent(1.0f)
                    }
                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer Error: what $what, extra $extra")
                        Toast.makeText(this@VoiceAnalysisActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
                        mp.release()
                        mediaPlayer = null
                        stopProgressUpdater()
                        binding.playerVisualizerView.updatePlayerPercent(0f)
                        true
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "MediaPlayer setup failed: ${e.message}", e)
                    Toast.makeText(this@VoiceAnalysisActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
                    release()
                    mediaPlayer = null
                    stopProgressUpdater()
                }
            }
        } else {
            Toast.makeText(this, "Нет записи для воспроизведения", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        mediaRecorder?.release()
        mediaRecorder = null
        mediaPlayer?.release()
        mediaPlayer = null
        stopProgressUpdater()
        audioAnalyzer.stopAnalysis()
    }

    private fun setupProgressUpdater() {
        progressUpdateRunnable = Runnable {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    try {
                        val currentPosition = it.currentPosition
                        val duration = it.duration
                        if (duration > 0) {
                            val percent = currentPosition.toFloat() / duration.toFloat()
                            binding.playerVisualizerView.updatePlayerPercent(percent)
                        }
                        progressUpdateHandler.postDelayed(progressUpdateRunnable, PROGRESS_UPDATE_INTERVAL)
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Error updating progress: ${e.message}")
                        stopProgressUpdater()
                    }
                } else {
                    if (it.duration > 0 && it.currentPosition >= it.duration - PROGRESS_UPDATE_INTERVAL*2) {
                        binding.playerVisualizerView.updatePlayerPercent(1.0f)
                    }
                    stopProgressUpdater()
                }
            }
        }
    }

    private fun startProgressUpdater() {
        stopProgressUpdater()
        progressUpdateHandler.post(progressUpdateRunnable)
    }

    private fun stopProgressUpdater() {
        progressUpdateHandler.removeCallbacks(progressUpdateRunnable)
    }

    private fun fileToBytes(file: File): ByteArray {
        val size = file.length().toInt()
        val bytes = ByteArray(size)
        try {
            BufferedInputStream(FileInputStream(file)).use { buf ->
                buf.read(bytes, 0, bytes.size)
            }
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "File not found: ${e.message}")
            return ByteArray(0)
        } catch (e: IOException) {
            Log.e(TAG, "Error reading file: ${e.message}")
            return ByteArray(0)
        }
        return bytes
    }

    override fun onSupportNavigateUp(): Boolean {
        val intent = Intent(this, MainMenuActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        Log.d(TAG, "Back pressed, navigating to MainMenuActivity.")
        val intent = Intent(this, MainMenuActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }
    private fun showAnalysisTypeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_analysis_type, null)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.rgAnalysisTypes)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmSelection)
        when (currentAnalysisType) {
            getString(R.string.speech) -> radioGroup.check(R.id.rbSpeech)
            getString(R.string.letter_a) -> radioGroup.check(R.id.rbLetterA)
            getString(R.string.letter_e) -> radioGroup.check(R.id.rbLetterE)
            getString(R.string.letter_u) -> radioGroup.check(R.id.rbLetterU)
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_VoiceAnalyzerApp_Dialog).setView(dialogView).create()
        btnConfirm.setOnClickListener {
            val selectedId = radioGroup.checkedRadioButtonId
            if (selectedId != -1) {
                val radioButton = radioGroup.findViewById<RadioButton>(selectedId)
                currentAnalysisType = radioButton.text.toString()
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                Toast.makeText(this, "Разрешение на запись отклонено", Toast.LENGTH_SHORT).show()
            }
        }
    }
}