package com.example.voiceanalyzerapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.voiceanalyzerapp.database.AppDatabase
import com.example.voiceanalyzerapp.database.Patient
import com.example.voiceanalyzerapp.database.TrainingSession
import com.example.voiceanalyzerapp.databinding.ActivitySessionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

class SessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionBinding
    private var patientId: Long = -1L
    private var currentPatient: Patient? = null
    private var currentLetter: String = "А" // Default letter
    private var currentTrainingSession: TrainingSession? = null

    private var mediaRecorder: MediaRecorder? = null
    private var currentMediaPlayer: MediaPlayer? = null
    private var referenceMediaPlayer: MediaPlayer? = null

    private var currentAudioFilePath: String? = null
    private var referenceAudioFilePath: String? = null

    private var isRecording = false

    private val RECORD_AUDIO_PERMISSION_CODE = 202
    private val TAG = "SessionActivity"

    private val db by lazy { AppDatabase.getDatabase(applicationContext) }

    private val currentProgressUpdateHandler = Handler(Looper.getMainLooper())
    private lateinit var currentProgressUpdateRunnable: Runnable
    private val referenceProgressUpdateHandler = Handler(Looper.getMainLooper())
    private lateinit var referenceProgressUpdateRunnable: Runnable
    private val PROGRESS_UPDATE_INTERVAL = 50L

    // Activity Result Launchers
    private val saveAudioFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                currentAudioFilePath?.let { sourcePath ->
                    copyFileToUri(File(sourcePath), uri)
                } ?: Toast.makeText(this, getString(R.string.file_save_failed) + " (Нет исходного файла)", Toast.LENGTH_SHORT).show()
            } ?: Toast.makeText(this, getString(R.string.file_save_failed) + " (Не выбран путь)", Toast.LENGTH_SHORT).show()
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            // Toast.makeText(this, getString(R.string.file_save_cancelled), Toast.LENGTH_SHORT).show() // Optional: user might cancel often
        }
    }

    private val loadAudioFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleLoadedReferenceFile(uri)
            } ?: Toast.makeText(this, getString(R.string.audio_file_load_failed) + " (Не выбран файл)", Toast.LENGTH_SHORT).show()
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            // Toast.makeText(this, getString(R.string.audio_file_load_cancelled), Toast.LENGTH_SHORT).show() // Optional
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarSession)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        patientId = intent.getLongExtra(getString(R.string.patient_id_extra), -1L)
        if (patientId == -1L) {
            Toast.makeText(this, getString(R.string.no_patient_id_found), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.visualizerCurrent.updateVisualizer(null)
        binding.visualizerCurrent.updatePlayerPercent(0f)
        binding.visualizerReference.updateVisualizer(null)
        binding.visualizerReference.updatePlayerPercent(0f)
        updatePlayButtonStates()

        loadPatientData()
        setupClickListeners()
        setupProgressUpdaters()
    }

    private fun setupClickListeners() {
        binding.toolbarSession.setNavigationOnClickListener {
            finish()
        }

        binding.btnChangeLetter.setOnClickListener {
            showChangeLetterDialog()
        }

        binding.btnRecordSession.setOnClickListener {
            if (isRecording) {
                stopCurrentRecording()
            } else {
                if (checkPermissions()) {
                    startNewCurrentRecording()
                } else {
                    requestPermissions()
                }
            }
        }

        binding.btnPlayCurrent.setOnClickListener {
            playAudio(currentAudioFilePath, binding.visualizerCurrent, PlayerType.CURRENT)
        }

        binding.btnPlayReference.setOnClickListener {
            playAudio(referenceAudioFilePath, binding.visualizerReference, PlayerType.REFERENCE)
        }

        binding.btnMakeReference.setOnClickListener {
            makeCurrentRecordingReference()
        }

        binding.btnSaveCurrentRecording.setOnClickListener {
            saveCurrentAudioToFilePicker()
        }

        binding.btnLoadReferenceFile.setOnClickListener {
            loadReferenceAudioFromFilePicker()
        }
    }

    private fun loadPatientData() {
        lifecycleScope.launch {
            currentPatient = withContext(Dispatchers.IO) {
                db.patientDao().getPatientById(patientId)
            }
            if (currentPatient == null) {
                Toast.makeText(this@SessionActivity, getString(R.string.patient_not_found_in_db), Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            binding.tvPatientName.text = "${currentPatient!!.lastName} ${currentPatient!!.firstName}"
            binding.tvPatientAge.text = getFormattedAgeString(currentPatient!!.age)
            loadOrCreateSessionData()
        }
    }

    private fun getFormattedAgeString(age: Int): String {
        return when {
            age % 10 == 1 && age % 100 != 11 -> getString(R.string.year_1_format, age)
            age % 10 in 2..4 && age % 100 !in 12..14 -> getString(R.string.year_2_4_format, age)
            else -> getString(R.string.year_5_0_format, age)
        }
    }

    private fun loadOrCreateSessionData() {
        lifecycleScope.launch {
            var session = withContext(Dispatchers.IO) {
                db.trainingSessionDao().getSessionSuspending(patientId, currentLetter)
            }
            if (session == null) {
                val newSession = TrainingSession(patientId = patientId, letter = currentLetter)
                val newSessionId = withContext(Dispatchers.IO) {
                    db.trainingSessionDao().insertSession(newSession)
                }
                session = if (newSessionId != -1L) {
                    newSession.copy(id = newSessionId)
                } else {
                    withContext(Dispatchers.IO) {
                        db.trainingSessionDao().getSessionSuspending(patientId, currentLetter)
                    }
                }
                if (session == null) {
                    Toast.makeText(this@SessionActivity, getString(R.string.error_loading_session_data) + " (Ошибка создания сессии)", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Toast.makeText(this@SessionActivity, getString(R.string.new_session_created), Toast.LENGTH_SHORT).show()
            }

            currentTrainingSession = session
            binding.tvCurrentLetter.text = currentLetter
            currentAudioFilePath = session.currentRecordingPath
            referenceAudioFilePath = session.referenceRecordingPath

            updateCurrentRecordingUI()
            updateReferenceRecordingUI()
        }
    }

    private fun showChangeLetterDialog() {
        val editText = EditText(this).apply {
            filters = arrayOf(InputFilter.LengthFilter(1))
            hint = getString(R.string.prompt_enter_letter)
            setText(currentLetter)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.prompt_enter_letter))
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val newLetter = editText.text.toString().trim().uppercase()
                if (isValidRussianLetter(newLetter)) {
                    if (newLetter != currentLetter) {
                        currentMediaPlayer?.safeStopAndRelease()
                        currentMediaPlayer = null
                        referenceMediaPlayer?.safeStopAndRelease()
                        referenceMediaPlayer = null
                        stopProgressUpdater(PlayerType.CURRENT)
                        stopProgressUpdater(PlayerType.REFERENCE)

                        currentAudioFilePath = null
                        referenceAudioFilePath = null
                        updateCurrentRecordingUI()
                        updateReferenceRecordingUI()

                        currentLetter = newLetter
                        loadOrCreateSessionData()
                    }
                } else {
                    Toast.makeText(this, getString(R.string.invalid_letter_toast), Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isValidRussianLetter(letter: String): Boolean {
        if (letter.length != 1) return false
        val char = letter[0]
        return char in 'А'..'Я' && char != 'Ь' && char != 'Ъ'
    }

    private fun generateNewFilePath(type: String): String {
        val sessionSpecificDir = File(filesDir, "session_audio")
        if (!sessionSpecificDir.exists()) {
            sessionSpecificDir.mkdirs()
        }
        return "${sessionSpecificDir.absolutePath}/session_${patientId}_${currentLetter}_${type}_${System.currentTimeMillis()}.3gp"
    }

    private fun startNewCurrentRecording() {
        if (currentTrainingSession == null) {
            Toast.makeText(this, getString(R.string.error_loading_session_data) + " (Нет сессии для записи)", Toast.LENGTH_SHORT).show()
            return
        }
        currentAudioFilePath?.let {
            deleteAudioFile(it)
            updateDbPath(currentTrainingSession!!.id, null, PathType.CURRENT)
        }
        currentAudioFilePath = null
        updateCurrentRecordingUI()

        currentMediaPlayer?.safeStopAndRelease()
        currentMediaPlayer = null
        stopProgressUpdater(PlayerType.CURRENT)

        val newPath = generateNewFilePath("current")
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(newPath)
            try {
                prepare()
                start()
                isRecording = true
                currentAudioFilePath = newPath
                binding.btnRecordSession.setImageResource(android.R.drawable.ic_media_pause)
                binding.btnRecordSession.contentDescription = getString(R.string.stop_recording_session)
            } catch (e: IOException) {
                Log.e(TAG, "MediaRecorder prepare() failed: ${e.message}")
                Toast.makeText(this@SessionActivity, "Ошибка начала записи: ${e.message}", Toast.LENGTH_SHORT).show()
                releaseMediaRecorder()
                currentAudioFilePath = null
            } catch (e: IllegalStateException) {
                Log.e(TAG, "MediaRecorder start failed: ${e.message}")
                Toast.makeText(this@SessionActivity, "Ошибка старта записи: ${e.message}", Toast.LENGTH_SHORT).show()
                releaseMediaRecorder()
                currentAudioFilePath = null
            }
        }
        updatePlayButtonStates()
    }

    private fun stopCurrentRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            Log.e(TAG, "MediaRecorder stop() failed: ${e.message}")
        } finally {
            releaseMediaRecorder()
        }
        isRecording = false
        binding.btnRecordSession.setImageResource(R.drawable.ic_record)
        binding.btnRecordSession.contentDescription = getString(R.string.start_recording_session)

        if (currentTrainingSession != null && currentAudioFilePath != null && File(currentAudioFilePath!!).exists()) {
            updateDbPath(currentTrainingSession!!.id, currentAudioFilePath, PathType.CURRENT)
            updateCurrentRecordingUI()
        } else {
            currentAudioFilePath = null
            if (currentTrainingSession != null) {
                updateDbPath(currentTrainingSession!!.id, null, PathType.CURRENT)
            }
            updateCurrentRecordingUI()
        }
    }

    private fun makeCurrentRecordingReference() {
        if (currentTrainingSession == null || currentAudioFilePath == null || !File(currentAudioFilePath!!).exists()) {
            Toast.makeText(this, getString(R.string.no_current_recording_to_make_reference), Toast.LENGTH_SHORT).show()
            return
        }

        referenceAudioFilePath?.let { oldRefPath ->
            if (oldRefPath != currentAudioFilePath) {
                deleteAudioFile(oldRefPath)
            }
        }

        val newReferencePath = generateNewFilePath("reference_from_current")
        try {
            File(currentAudioFilePath!!).copyTo(File(newReferencePath), overwrite = true)
            referenceAudioFilePath = newReferencePath
            updateDbPath(currentTrainingSession!!.id, newReferencePath, PathType.REFERENCE)
            updateReferenceRecordingUI()
            Toast.makeText(this, getString(R.string.reference_set_successfully), Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e(TAG, "Error copying file to reference: ${e.message}")
            Toast.makeText(this, getString(R.string.error_setting_reference), Toast.LENGTH_SHORT).show()
            // Revert to original if copy failed, or clear if there was no original for this session
            val originalDbRefPath = currentTrainingSession?.referenceRecordingPath
            if (referenceAudioFilePath != originalDbRefPath) { // if the failed path was different
                referenceAudioFilePath = originalDbRefPath
                updateDbPath(currentTrainingSession!!.id, referenceAudioFilePath, PathType.REFERENCE)
            }
            updateReferenceRecordingUI()
        }
    }

    private fun playAudio(filePath: String?, visualizer: PlayerVisualizerView, playerType: PlayerType) {
        if (isRecording) {
            Toast.makeText(this, "Сначала остановите запись", Toast.LENGTH_SHORT).show()
            return
        }
        if (filePath == null || !File(filePath).exists() || File(filePath).length() == 0L) {
            Toast.makeText(this, getString(R.string.file_not_found_for_playback), Toast.LENGTH_SHORT).show()
            visualizer.updatePlayerPercent(0f)
            return
        }

        val playerToStop: MediaPlayer?
        val visualizerToResetProgress: PlayerVisualizerView?

        if (playerType == PlayerType.CURRENT) {
            playerToStop = referenceMediaPlayer
            visualizerToResetProgress = binding.visualizerReference
            currentMediaPlayer?.safeStopAndRelease()
            currentMediaPlayer = null
            stopProgressUpdater(PlayerType.CURRENT)
            currentMediaPlayer = MediaPlayer()
        } else { // PlayerType.REFERENCE
            playerToStop = currentMediaPlayer
            visualizerToResetProgress = binding.visualizerCurrent
            referenceMediaPlayer?.safeStopAndRelease()
            referenceMediaPlayer = null
            stopProgressUpdater(PlayerType.REFERENCE)
            referenceMediaPlayer = MediaPlayer()
        }

        try {
            if (playerToStop?.isPlaying == true) {
                playerToStop.stop()
                playerToStop.reset()
                if (playerType == PlayerType.CURRENT) stopProgressUpdater(PlayerType.REFERENCE)
                else stopProgressUpdater(PlayerType.CURRENT)
                visualizerToResetProgress?.updatePlayerPercent(0f)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Error trying to stop other player (might have been released): ${e.message}")
            if (playerToStop == currentMediaPlayer) currentMediaPlayer = null
            if (playerToStop == referenceMediaPlayer) referenceMediaPlayer = null
        }

        val mediaPlayerToUse = if (playerType == PlayerType.CURRENT) currentMediaPlayer else referenceMediaPlayer

        mediaPlayerToUse?.apply {
            try {
                setDataSource(filePath)
                prepare()
                start()
                visualizer.updatePlayerPercent(0f)
                startProgressUpdater(playerType)

                setOnCompletionListener { mp ->
                    stopProgressUpdater(playerType)
                    visualizer.updatePlayerPercent(1.0f)
                    mp.safeStopAndRelease()
                    if (playerType == PlayerType.CURRENT) currentMediaPlayer = null
                    else referenceMediaPlayer = null
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer Error: what $what, extra $extra for $filePath")
                    Toast.makeText(this@SessionActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
                    stopProgressUpdater(playerType)
                    visualizer.updatePlayerPercent(0f)
                    mp.safeStopAndRelease()
                    if (playerType == PlayerType.CURRENT) currentMediaPlayer = null
                    else referenceMediaPlayer = null
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaPlayer setup failed for $filePath: ${e.message}")
                Toast.makeText(this@SessionActivity, "Ошибка подготовки воспроизведения", Toast.LENGTH_SHORT).show()
                stopProgressUpdater(playerType)
                visualizer.updatePlayerPercent(0f)
                this.safeStopAndRelease()
                if (playerType == PlayerType.CURRENT) currentMediaPlayer = null
                else referenceMediaPlayer = null
            }
        }
    }

    private fun updateCurrentRecordingUI() {
        currentAudioFilePath?.let { path ->
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                val audioBytes = fileToBytes(file)
                binding.visualizerCurrent.updateVisualizer(audioBytes)
            } else {
                binding.visualizerCurrent.updateVisualizer(null)
            }
        } ?: binding.visualizerCurrent.updateVisualizer(null)
        binding.visualizerCurrent.updatePlayerPercent(0f)
        updatePlayButtonStates()
    }

    private fun updateReferenceRecordingUI() {
        referenceAudioFilePath?.let { path ->
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                val audioBytes = fileToBytes(file)
                binding.visualizerReference.updateVisualizer(audioBytes)
            } else {
                binding.visualizerReference.updateVisualizer(null)
            }
        } ?: binding.visualizerReference.updateVisualizer(null)
        binding.visualizerReference.updatePlayerPercent(0f)
        updatePlayButtonStates()
    }

    private fun updatePlayButtonStates() {
        val currentRecordingExists = currentAudioFilePath?.let { File(it).exists() && File(it).length() > 0 } ?: false
        val referenceRecordingExists = referenceAudioFilePath?.let { File(it).exists() && File(it).length() > 0 } ?: false

        binding.btnPlayCurrent.isEnabled = currentRecordingExists
        binding.btnMakeReference.isEnabled = currentRecordingExists
        binding.btnSaveCurrentRecording.isEnabled = currentRecordingExists
        binding.btnPlayReference.isEnabled = referenceRecordingExists
        binding.btnLoadReferenceFile.isEnabled = true // Always enabled
    }

    private fun saveCurrentAudioToFilePicker() {
        if (currentAudioFilePath == null || !File(currentAudioFilePath!!).exists()) {
            Toast.makeText(this, "Нет записи для сохранения.", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/3gpp"
            val patientNamePart = currentPatient?.firstName?.filter { it.isLetterOrDigit() } ?: "patient"
            val fileName = "record_${patientNamePart}_${currentLetter}_${System.currentTimeMillis()}.3gp"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        try {
            saveAudioFileLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch save file picker", e)
            Toast.makeText(this, "Не удалось открыть выбор места сохранения.", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyFileToUri(sourceFile: File, destinationUri: Uri) {
        try {
            contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                Toast.makeText(this, getString(R.string.file_saved_successfully_to, "выбранному месту"), Toast.LENGTH_LONG).show()
            } ?: throw IOException("Failed to open output stream for URI: $destinationUri")
        } catch (e: Exception) { // Catch broader exception for more safety
            Log.e(TAG, "Error copying file to URI: $destinationUri", e)
            Toast.makeText(this, getString(R.string.file_save_failed) + ": ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadReferenceAudioFromFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        try {
            loadAudioFileLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch load file picker", e)
            Toast.makeText(this, "Не удалось открыть выбор файла.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleLoadedReferenceFile(sourceUri: Uri) {
        if (currentTrainingSession == null) {
            Toast.makeText(this, getString(R.string.error_loading_session_data) + " (Не могу назначить эталон)", Toast.LENGTH_SHORT).show()
            return
        }

        // Stop and release any currently playing reference player
        referenceMediaPlayer?.safeStopAndRelease()
        referenceMediaPlayer = null
        stopProgressUpdater(PlayerType.REFERENCE)

        referenceAudioFilePath?.let {
            deleteAudioFile(it)
        }
        val newInternalRefPath = generateNewFilePath("reference_loaded")
        var success = false
        try {
            contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                FileOutputStream(File(newInternalRefPath)).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IOException("Не удалось открыть поток из URI: $sourceUri")
            referenceAudioFilePath = newInternalRefPath
            success = true
        } catch (e: Exception) { // Catch broader exception
            Log.e(TAG, "Error copying loaded file to internal storage: $sourceUri", e)
            Toast.makeText(this, getString(R.string.error_copying_loaded_file) + ": ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            deleteAudioFile(newInternalRefPath)
            referenceAudioFilePath = null
        }

        lifecycleScope.launch {
            updateDbPath(currentTrainingSession!!.id, referenceAudioFilePath, PathType.REFERENCE)
            if (success) {
                Toast.makeText(this@SessionActivity, getString(R.string.audio_file_loaded_as_reference), Toast.LENGTH_SHORT).show()
            }
            updateReferenceRecordingUI()
        }
    }

    private enum class PathType { CURRENT, REFERENCE }
    private enum class PlayerType { CURRENT, REFERENCE }

    private fun updateDbPath(sessionId: Long, path: String?, type: PathType) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (type == PathType.CURRENT) {
                    db.trainingSessionDao().updateCurrentRecordingPath(sessionId, path)
                    // Update local cache on the main thread if accessed from there
                    withContext(Dispatchers.Main) {
                        currentTrainingSession = currentTrainingSession?.copy(currentRecordingPath = path)
                    }
                } else {
                    db.trainingSessionDao().updateReferenceRecordingPath(sessionId, path)
                    withContext(Dispatchers.Main) {
                        currentTrainingSession = currentTrainingSession?.copy(referenceRecordingPath = path)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating $type path in DB for session $sessionId: ${e.message}")
                withContext(Dispatchers.Main){
                    Toast.makeText(this@SessionActivity, getString(R.string.error_clearing_path_in_db), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteAudioFile(filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            if (file.delete()) {
                Log.d(TAG, getString(R.string.audio_file_deleted, filePath))
            } else {
                Log.w(TAG, getString(R.string.audio_file_not_deleted, filePath))
            }
        }
    }

    private fun MediaPlayer.safeStopAndRelease() {
        try {
            if (this.isPlaying) {
                this.stop()
            }
            this.reset()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaPlayer stop/reset warning (might be in invalid state): ${e.message}")
        } finally {
            try {
                this.release()
            } catch (e: Exception) {
                Log.e(TAG, "MediaPlayer final release error: ${e.message}")
            }
        }
    }

    private fun releaseMediaRecorder() {
        mediaRecorder?.apply {
            try {
                release()
            } catch (e: Exception) {
                Log.e(TAG, "MediaRecorder release error: ${e.message}")
            }
        }
        mediaRecorder = null
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
                startNewCurrentRecording()
            } else {
                Toast.makeText(this, "Разрешение на запись отклонено", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupProgressUpdaters() {
        currentProgressUpdateRunnable = Runnable {
            currentMediaPlayer?.let { player ->
                try {
                    if (player.isPlaying) {
                        val currentPosition = player.currentPosition
                        val duration = player.duration
                        if (duration > 0) {
                            binding.visualizerCurrent.updatePlayerPercent(currentPosition.toFloat() / duration.toFloat())
                        }
                        currentProgressUpdateHandler.postDelayed(currentProgressUpdateRunnable, PROGRESS_UPDATE_INTERVAL)
                    } else {
                        if (player.duration > 0 && player.currentPosition >= player.duration - PROGRESS_UPDATE_INTERVAL * 2) {
                            binding.visualizerCurrent.updatePlayerPercent(1.0f)
                        }
                        stopProgressUpdater(PlayerType.CURRENT)
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Error updating current progress (player likely released): ${e.message}")
                    stopProgressUpdater(PlayerType.CURRENT)
                    binding.visualizerCurrent.updatePlayerPercent(0f)
                }
            } ?: stopProgressUpdater(PlayerType.CURRENT)
        }

        referenceProgressUpdateRunnable = Runnable {
            referenceMediaPlayer?.let { player ->
                try {
                    if (player.isPlaying) {
                        val currentPosition = player.currentPosition
                        val duration = player.duration
                        if (duration > 0) {
                            binding.visualizerReference.updatePlayerPercent(currentPosition.toFloat() / duration.toFloat())
                        }
                        referenceProgressUpdateHandler.postDelayed(referenceProgressUpdateRunnable, PROGRESS_UPDATE_INTERVAL)
                    } else {
                        if (player.duration > 0 && player.currentPosition >= player.duration - PROGRESS_UPDATE_INTERVAL * 2) {
                            binding.visualizerReference.updatePlayerPercent(1.0f)
                        }
                        stopProgressUpdater(PlayerType.REFERENCE)
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Error updating reference progress (player likely released): ${e.message}")
                    stopProgressUpdater(PlayerType.REFERENCE)
                    binding.visualizerReference.updatePlayerPercent(0f)
                }
            } ?: stopProgressUpdater(PlayerType.REFERENCE)
        }
    }

    private fun startProgressUpdater(playerType: PlayerType) {
        if (playerType == PlayerType.CURRENT) {
            stopProgressUpdater(PlayerType.CURRENT)
            currentProgressUpdateHandler.post(currentProgressUpdateRunnable)
        } else {
            stopProgressUpdater(PlayerType.REFERENCE)
            referenceProgressUpdateHandler.post(referenceProgressUpdateRunnable)
        }
    }

    private fun stopProgressUpdater(playerType: PlayerType) {
        if (playerType == PlayerType.CURRENT) {
            currentProgressUpdateHandler.removeCallbacks(currentProgressUpdateRunnable)
        } else {
            referenceProgressUpdateHandler.removeCallbacks(referenceProgressUpdateRunnable)
        }
    }

    private fun fileToBytes(file: File): ByteArray {
        return try {
            BufferedInputStream(FileInputStream(file)).use { bis ->
                bis.readBytes()
            }
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "File not found: ${file.absolutePath} - ${e.message}")
            ByteArray(0)
        } catch (e: IOException) {
            Log.e(TAG, "Error reading file: ${file.absolutePath} - ${e.message}")
            ByteArray(0)
        }
    }

    override fun onStop() {
        super.onStop()
        releaseMediaRecorder()

        currentMediaPlayer?.safeStopAndRelease()
        currentMediaPlayer = null
        referenceMediaPlayer?.safeStopAndRelease()
        referenceMediaPlayer = null

        stopProgressUpdater(PlayerType.CURRENT)
        stopProgressUpdater(PlayerType.REFERENCE)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}