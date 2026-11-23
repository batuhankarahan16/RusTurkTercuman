package com.ceviri.rustr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * Akış:  Mikrofon (Rusça konuşma) -> SpeechRecognizer (ru-RU) -> ML Kit çeviri (ru -> tr)
 *        -> TextToSpeech (Türkçe) -> hoparlör / kulaklık
 *
 * Kulaklık takılıyken: dinleme hiç durmaz, çeviri kulağına okunurken yeni Rusça konuşma da yakalanır.
 * Kulaklık yokken (hoparlör): uygulama kendi Türkçe sesini duymasın diye okuma bitene kadar dinlemeyi bekletir.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusView: TextView
    private lateinit var audioModeView: TextView
    private lateinit var toggleButton: Button
    private lateinit var partialView: TextView
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView

    private lateinit var audioManager: AudioManager
    private lateinit var tts: TextToSpeech
    private lateinit var translator: Translator
    private var recognizer: SpeechRecognizer? = null

    private var ttsReady = false
    private var modelReady = false
    private var running = false
    private var isListening = false

    /** Çevirisi veya seslendirmesi henüz bitmemiş cümle sayısı. */
    private var pending = 0
    private var utteranceCounter = 0

    private val handler = Handler(Looper.getMainLooper())

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else setStatus("Mikrofon izni verilmedi. Ayarlardan izin verin.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        audioModeView = findViewById(R.id.audioMode)
        toggleButton = findViewById(R.id.toggle)
        partialView = findViewById(R.id.partial)
        logView = findViewById(R.id.log)
        scrollView = findViewById(R.id.scroll)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        tts = TextToSpeech(this, this)
        setupTranslator()

        toggleButton.setOnClickListener { if (running) stop() else requestStart() }
        updateAudioModeLabel()
    }

    // ---------------- Çeviri modeli ----------------

    private fun setupTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.RUSSIAN)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        translator = Translation.getClient(options)

        setStatus("Çeviri paketi indiriliyor (ilk seferde ~30 MB, internet gerekir)…")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady = true
                setStatus(readyText())
            }
            .addOnFailureListener {
                setStatus("Çeviri paketi indirilemedi. İnternete bağlanıp uygulamayı yeniden açın.")
            }
    }

    // ---------------- Seslendirme (Türkçe) ----------------

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            setStatus("Telefonda seslendirme motoru bulunamadı (Google Konuşma Hizmetleri gerekli).")
            return
        }
        val result = tts.setLanguage(Locale("tr", "TR"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            setStatus("Türkçe ses paketi yok. Açılan ekrandan Türkçe sesi indirin.")
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (_: Exception) { }
            return
        }
        tts.setSpeechRate(1.15f)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = runOnUiThread { onUtteranceFinished() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = runOnUiThread { onUtteranceFinished() }
            override fun onError(utteranceId: String?, errorCode: Int) = runOnUiThread { onUtteranceFinished() }
        })
        ttsReady = true
        if (modelReady) setStatus(readyText())
    }

    private fun onUtteranceFinished() {
        pending = (pending - 1).coerceAtLeast(0)
        scheduleListen(100)
    }

    // ---------------- Başlat / Durdur ----------------

    private fun requestStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) start() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Bu telefonda konuşma tanıma yok. Google uygulamasını yükleyip güncelleyin.")
            return
        }
        running = true
        pending = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toggleButton.text = "DURDUR"
        updateAudioModeLabel()
        startListening()
    }

    private fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        isListening = false
        tts.stop()
        pending = 0
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toggleButton.text = "BAŞLAT"
        partialView.text = ""
        setStatus("Durduruldu.")
    }

    // ---------------- Rusça dinleme ----------------

    /** Kulaklık yoksa, Türkçe okuma bitene kadar mikrofonu açmayız (yankı olmasın). */
    private fun mustWaitForSpeech(): Boolean = pending > 0 && !headphonesConnected()

    private fun scheduleListen(delayMs: Long) {
        handler.postDelayed({
            if (running && !isListening && !mustWaitForSpeech()) startListening()
        }, delayMs)
    }

    private fun startListening() {
        if (!running) return
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // Kısa duraklamada cümleyi bitmiş say -> daha hızlı çeviri
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        }
        isListening = true
        rec.startListening(intent)
        updateAudioModeLabel()
        setStatus("🎤 Dinleniyor (Rusça)…")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) partialView.text = "🇷🇺 $text…"
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            partialView.text = ""
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) translateAndSpeak(text)
            scheduleListen(50)
        }

        override fun onError(error: Int) {
            isListening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleListen(50)

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    recognizer?.destroy()
                    recognizer = null
                    scheduleListen(300)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stop()
                    setStatus("Mikrofon izni yok.")
                }

                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    stop()
                    setStatus(
                        "Rusça konuşma tanıma bu telefonda yok. Ayarlar > Google > Ses > " +
                            "Çevrimdışı konuşma tanıma bölümünden Rusçayı indirin."
                    )
                }

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    setStatus("İnternet sorunu, yeniden deneniyor…")
                    scheduleListen(1000)
                }

                else -> scheduleListen(500)
            }
        }
    }

    // ---------------- Çeviri + seslendirme ----------------

    private fun translateAndSpeak(russian: String) {
        if (!modelReady) {
            appendLog(russian, "(çeviri paketi henüz hazır değil)")
            return
        }
        pending++ // hoparlör modunda dinleme, bu cümle okunana kadar bekler
        translator.translate(russian)
            .addOnSuccessListener { turkish ->
                appendLog(russian, turkish)
                speak(turkish)
            }
            .addOnFailureListener {
                appendLog(russian, "(çeviri hatası)")
                onUtteranceFinished()
            }
    }

    private fun speak(text: String) {
        if (!ttsReady || !running) {
            onUtteranceFinished()
            return
        }
        val id = "u${utteranceCounter++}"
        val r = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (r != TextToSpeech.SUCCESS) onUtteranceFinished()
    }

    // ---------------- Yardımcılar ----------------

    private fun headphonesConnected(): Boolean {
        val headphoneTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            26 // AudioDeviceInfo.TYPE_BLE_HEADSET (Android 12+)
        )
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type in headphoneTypes }
    }

    private fun updateAudioModeLabel() {
        audioModeView.text = if (headphonesConnected())
            "🎧 Kulaklık modu: kesintisiz dinleme"
        else
            "🔊 Hoparlör modu: çeviri okunurken dinleme bekler"
    }

    private fun readyText(): String =
        if (modelReady && ttsReady) "Hazır. BAŞLAT'a basın." else "Hazırlanıyor…"

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun appendLog(ru: String, tr: String) {
        logView.append("🇷🇺 $ru\n🇹🇷 $tr\n\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        stop()
        tts.shutdown()
        translator.close()
        super.onDestroy()
    }
}
