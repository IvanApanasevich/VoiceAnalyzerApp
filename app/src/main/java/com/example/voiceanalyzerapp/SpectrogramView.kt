package com.example.voiceanalyzerapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log // Убедись, что импорт есть
import android.view.View
import be.tarsos.dsp.util.PitchConverter
import be.tarsos.dsp.util.fft.FFT
import java.lang.Math.log1p
import java.lang.Math.pow
import kotlin.math.ln1p
import kotlin.math.max

// import androidx.core.graphics.createBitmap // createBitmap из KTX уже должен быть доступен без явного импорта если core-ktx подключен

class SpectrogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var spectrogramBitmap: Bitmap? = null
    private var spectrogramCanvas: Canvas? = null
    private var currentXPosition: Int = 0
    private val paint = Paint()
    private var currentPitchInfo: String = "Pitch: --- Hz"

    private val xStep = 18
    private val minDisplayFrequency = 50.0
    private var maxDisplayFrequency = 4000.0 // Будет обновлено
    private val useLogarithmicYAxis = true

    private val pitchLineColor = Color.RED
    private val axisAndTextColor = Color.WHITE
    private val backgroundColor = Color.BLACK

    private val axisTextPaint = Paint().apply {
        color = axisAndTextColor
        textSize = 20f
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }
    private val pitchInfoTextPaint = Paint().apply {
        color = axisAndTextColor
        textSize = 24f
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    // Добавим TAG для логов во View
    private val TAG = "SpectrogramView"

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            spectrogramBitmap?.recycle()
            // Используем стандартный Bitmap.createBitmap
            spectrogramBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            spectrogramCanvas = Canvas(spectrogramBitmap!!)
            clearSpectrogramCanvas()
            currentXPosition = 0
        }
    }

    fun setAudioSampleRate(sampleRate: Float) {
        maxDisplayFrequency = (sampleRate / 2.0).coerceAtMost(11000.0)
        Log.d(TAG, "SampleRate=$sampleRate, MaxDisplayFreq=$maxDisplayFrequency") // Лог
        clearSpectrogram()
    }

    private fun frequencyToYCoordinate(frequency: Double): Int {
        if (height == 0) return 0
        val y: Int
        if (frequency < minDisplayFrequency) {
            y = height - 1
        } else if (frequency > maxDisplayFrequency) {
            y = 0
        } else {
            val binEstimate: Double = if (useLogarithmicYAxis) {
                val minCent = PitchConverter.hertzToAbsoluteCent(minDisplayFrequency)
                val maxCent = PitchConverter.hertzToAbsoluteCent(maxDisplayFrequency)
                val currentCent = PitchConverter.hertzToAbsoluteCent(frequency)
                (currentCent - minCent) / (maxCent - minCent)
            } else {
                (frequency - minDisplayFrequency) / (maxDisplayFrequency - minDisplayFrequency)
            }
            y = height - 1 - (binEstimate * height).toInt()
        }
        return y.coerceIn(0, height - 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        spectrogramBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }
        drawFrequencyAxisMarkers(canvas)
        drawPitchInfoText(canvas)
    }

    private fun drawPitchInfoText(canvas: Canvas) {
        val textBgPaint = Paint().apply { color = backgroundColor }
        canvas.drawRect(10f, 5f, 350f, pitchInfoTextPaint.textSize + 15f, textBgPaint)
        canvas.drawText(currentPitchInfo, 15f, pitchInfoTextPaint.textSize + 5f, pitchInfoTextPaint)
    }

    private fun drawFrequencyAxisMarkers(canvas: Canvas) {
        if (height == 0 || spectrogramBitmap == null) return
        val frequenciesToLabel = mutableListOf<Double>()
        var currentFreq = minDisplayFrequency
        while (currentFreq < 1000 && currentFreq < maxDisplayFrequency) {
            if (currentFreq >= minDisplayFrequency) frequenciesToLabel.add(currentFreq)
            currentFreq += if (currentFreq < 200) 50.0 else if (currentFreq < 500) 100.0 else 250.0
        }
        currentFreq = 1000.0
        while (currentFreq <= maxDisplayFrequency) {
            if (currentFreq >= minDisplayFrequency) frequenciesToLabel.add(currentFreq)
            currentFreq += if (currentFreq < 4000) 500.0 else 1000.0
        }
        if (maxDisplayFrequency > minDisplayFrequency && !frequenciesToLabel.contains(maxDisplayFrequency)) { // Убедимся, что не дублируем
            frequenciesToLabel.add(maxDisplayFrequency)
        }

        paint.color = axisAndTextColor
        paint.strokeWidth = 1f

        for (freq in frequenciesToLabel.distinct().sorted()) {
            val yPos = frequencyToYCoordinate(freq)
            canvas.drawLine(0f, yPos.toFloat(), 20f, yPos.toFloat(), paint)
            val label = if (freq >= 1000) "${"%.1f".format(freq / 1000.0)}k" else "${freq.toInt()}"
            canvas.drawText(label, 25f, (yPos + axisTextPaint.textSize / 3).coerceIn(axisTextPaint.textSize, height - 5f), axisTextPaint)
        }
    }

    fun drawFFTData(pitchInHz: Double, fftAmplitudes: FloatArray, fftInstance: FFT, audioSampleRate: Float) {
        if (spectrogramBitmap == null || spectrogramCanvas == null || height == 0 || width == 0) return

        // val canvasToDrawOn = spectrogramCanvas!! // spectrogramCanvas может измениться
        // Логика сдвига битмапа
        if (currentXPosition + xStep > width) {
            val tempBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val tempCanvas = Canvas(tempBitmap)
            tempCanvas.drawColor(backgroundColor)
            val sourceRect = Rect(xStep, 0, width, height)
            val destRect = Rect(0, 0, width - xStep, height)
            spectrogramBitmap?.let { tempCanvas.drawBitmap(it, sourceRect, destRect, null) }
            spectrogramBitmap?.recycle()
            spectrogramBitmap = tempBitmap
            this.spectrogramCanvas = Canvas(spectrogramBitmap!!) // Обновляем spectrogramCanvas
            currentXPosition = width - xStep
        }

        // Рисуем новый столбец на this.spectrogramCanvas (который мог быть только что обновлен)
        this.spectrogramCanvas?.let { canvas ->
            drawColumnOnCanvas(canvas, pitchInHz, fftAmplitudes, audioSampleRate)
        }

        if (!(currentXPosition + xStep > width)) { // Увеличиваем currentXPosition только если не было сдвига
            currentXPosition += xStep
        }


        currentPitchInfo = if (pitchInHz != -1.0) "Pitch: ${pitchInHz.toInt()} Hz" else "Pitch: --- Hz"
        postInvalidate()
    }

    private fun drawColumnOnCanvas(
        targetCanvas: Canvas,
        pitchInHz: Double,
        fftAmplitudes: FloatArray,
        audioSampleRate: Float
    ) {
        var maxAmplitudeInColumn = 0.0f
        val amplitudesPerPixelY = FloatArray(height)

        val numBins = fftAmplitudes.size
        for (i in 0 until numBins) {
            val frequency = i * audioSampleRate / (2 * numBins)
            if (frequency.toDouble() > maxDisplayFrequency) break
            val y = frequencyToYCoordinate(frequency.toDouble())
            if (y in amplitudesPerPixelY.indices) {
                amplitudesPerPixelY[y] += fftAmplitudes[i]
                maxAmplitudeInColumn = max(amplitudesPerPixelY[y], maxAmplitudeInColumn)
            }
        }

        Log.d(TAG, "drawColumnOnCanvas: X=$currentXPosition, maxAmpInCol = $maxAmplitudeInColumn, Pitch = $pitchInHz, InputMaxFFTAmp=${fftAmplitudes.maxOrNull()}")
        val nonEmptyPixels = amplitudesPerPixelY.count { it > 0.0001f }
        Log.d(TAG, "drawColumnOnCanvas: X=$currentXPosition, NonEmptyPixelsY = $nonEmptyPixels / ${amplitudesPerPixelY.size}")
        if (nonEmptyPixels > 0 && maxAmplitudeInColumn < 0.01f && (fftAmplitudes.maxOrNull() ?: 0f) > 1.0f) {
            Log.w(TAG, "Warning: Max input FFT amp is high, but maxAmplitudeInColumn is low.")
        }

        for (y in amplitudesPerPixelY.indices) {
            var greyValue = 0
            val currentPixelAmplitude = amplitudesPerPixelY[y]

            if (maxAmplitudeInColumn > 0.00001f && currentPixelAmplitude > 0.000001f) {
                // Нормализуем амплитуду пикселя относительно максимума в столбце
                val normalizedIntensity = currentPixelAmplitude / maxAmplitudeInColumn

                // Применяем логарифм для сжатия диапазона.
                // Math.log1p(x) это log(1+x), что хорошо для значений x от 0.
                // Делитель Math.log1p(1.0f) нормализует результат так,
                // что если normalizedIntensity = 1.0, то результат дроби будет 1.0.
                // Если normalizedIntensity = 0, то результат дроби будет 0.
                // Коэффициент 1.0000001f в Math.log1p(1.0000001f) из оригинального кода Tarsos выглядел немного странно,
                // возможно, для предотвращения деления на ноль или для очень тонкой настройки.
                // Попробуем с Math.log1p(1.0f) для начала.
                // Если Math.log1p(1.0f) -> Math.log(2.0) ~ 0.693
                //
                // Оригинальный код Tarsos, который ты приводил в самом начале, имел:
                // final int greyValue = (int) (Math.log1p(pixeledAmplitudes[i] / maxAmplitude) / Math.log1p(1.0000001) * 255);
                // Это очень чувствительная функция. `Math.log1p(1.0000001)` очень близко к `1.0000001`.
                // Деление на очень маленькое число (если `maxAmplitude` не 1) может дать странные результаты.
                // Давай адаптируем идею.

                // Попробуем так:
                // Чем больше 'factor', тем "контрастнее" (быстрее уходит в черный).
                // Чем меньше 'factor', тем "светлее" (больше серых оттенков).
                val factor = 0.1f // Поэкспериментируй с этим значением (0.05, 0.1, 0.2, 0.5, 1.0)
                val logIntensity = ln1p(normalizedIntensity / factor) / ln1p(1.0f / factor)
                // logIntensity теперь должен быть в диапазоне ~[0, 1]

                greyValue = (logIntensity * 255.0f).toInt().coerceIn(0, 255)


                if (currentXPosition % 50 == 0 && y % 20 == 0 && greyValue > 10) { // Логируем не слишком часто и только видимые
                    Log.d("SpectrogramView", "X=$currentXPosition, Y=$y, PxAmp=$currentPixelAmplitude, NormInt=$normalizedIntensity, LogInt=$logIntensity, Grey=$greyValue, maxInCol=$maxAmplitudeInColumn")
                }

            } else {
                greyValue = 0
            }
            paint.color = Color.rgb(greyValue, greyValue, greyValue)
            // Рисуем один пиксель (или узкий прямоугольник) столбца спектрограммы
            targetCanvas.drawRect(
                currentXPosition.toFloat(),
                y.toFloat(),
                (currentXPosition + xStep).toFloat(), // Ширина столбца xStep
                (y + 1).toFloat(),      // Высота пикселя 1
                paint
            )
        }

        // Рисуем линию основного тона (pitch) поверх столбца спектрограммы
        if (pitchInHz != -1.0 && pitchInHz >= minDisplayFrequency && pitchInHz <= maxDisplayFrequency) {
            val pitchY = frequencyToYCoordinate(pitchInHz)
            paint.color = pitchLineColor
            targetCanvas.drawRect(
                currentXPosition.toFloat(),
                pitchY.toFloat(),
                (currentXPosition + xStep).toFloat(),
                (pitchY + 1).toFloat(),
                paint
            )
        }
    }

    fun clearSpectrogram() {
        clearSpectrogramCanvas()
        currentXPosition = 0
        currentPitchInfo = "Pitch: --- Hz"
        invalidate()
    }

    private fun clearSpectrogramCanvas() {
        spectrogramCanvas?.drawColor(backgroundColor)
    }
}
