package com.ceviri.rustr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, SesMotoru.Dinleyici {

    private lateinit var durumView: TextView
    private lateinit var modView: TextView
    private lateinit var sesIziView: TextView
    private lateinit var btnSesimiTanit: Button
    private lateinit var btnKisiSifirla: Button
    private lateinit var btnBaslat: Button
    private lateinit var btnBen: Button
    private lateinit var canliRu: TextView
    private lateinit var canliTr: TextView
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView

    private lateinit var audioManager: AudioManager
    private lateinit var tts: TextToSpeech
    private lateinit var translator: Translator
    private lateinit var motor: SesMotoru

    private var ttsHazir = false
    private var ceviriPaketiHazir = false
    private var motorHazir = false
    private var calisiyor = false

    private var trSesler: List<Voice> = emptyList()
    private val perdeler = floatArrayOf(1.0f, 0.8f, 1.2f, 0.9f, 1.1f)
    private val renkler = intArrayOf(
        0xFF4FC3F7.toInt(), 0xFFFFB74D.toInt(), 0xFF81C784.toInt(),
        0xFFF06292.toInt(), 0xFFBA68C8.toInt()
    )
    private var bekleyenKonusma = 0
    private var konusmaSayac = 0

    private var anlikCeviriSuruyor = false
    private var bekleyenAnlik: String? = null

    private var kayitDialog: AlertDialog? = null
    private var kayitDialogText: TextView? = null
    private var izinSonrasi: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ayarlar", MODE_PRIVATE) }

    private val micIzni =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { verildi ->
            if (verildi) izinSonrasi?.invoke()
            else durum("Mikrofon izni verilmedi. Ayarlardan izin verin.")
            izinSonrasi = null
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        durumView = findViewById(R.id.durum)
        modView = findViewById(R.id.mod)
        sesIziView = findViewById(R.id.sesIzi)
        btnSesimiTanit = findViewById(R.id.btnSesimiTanit)
        btnKisiSifirla = findViewById(R.id.btnKisiSifirla)
        btnBaslat = findViewById(R.id.btnBaslat)
        btnBen = findViewById(R.id.btnBen)
        canliRu = findViewById(R.id.canliRu)
        canliTr = findViewById(R.id.canliTr)
        logView = findViewById(R.id.log)
        scrollView = findViewById(R.id.scroll)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        motor = SesMotoru(assets, this)
        ceviriciyiHazirla()
        motoruYukle()

        btnBaslat.setOnClickListener { if (calisiyor) durdur() else izinleCalistir { baslat() } }
        btnSesimiTanit.setOnClickListener { izinleCalistir { sesKaydiBaslat() } }
        btnKisiSifirla.setOnClickListener {
            motor.kisileriSifirla()
            logEkle(SpannableStringBuilder("— Yeni konuşma: kişiler sıfırlandı —\n\n").also {
                it.setSpan(ForegroundColorSpan(Color.GRAY), 0, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            })
        }
        btnBen.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    motor.benKonusuyorum = true
                    v.isPressed = true
                    btnBen.text = "🙊 Seni dinlemiyorum… (bırakınca devam)"
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    motor.benKonusuyorum = false
                    v.isPressed = false
                    btnBen.text = getString(R.string.ben_konusuyorum)
                }
            }
            true
        }

        sesIziDurumu()
        modEtiketi()
        durumGuncelle()
    }

    // ---------------- Hazırlık ----------------

    private fun ceviriciyiHazirla() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.RUSSIAN)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        translator = Translation.getClient(options)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                ceviriPaketiHazir = true
                durumGuncelle()
            }
            .addOnFailureListener {
                durum("Çeviri paketi indirilemedi. İnternete bağlanıp uygulamayı yeniden açın.")
            }
    }

    private fun motoruYukle() {
        Thread {
            try {
                motor.yukle()
                motor.benimIzlerimiYukle(prefs.getString("benim_izlerim", "") ?: "")
                runOnUiThread {
                    motorHazir = true
                    sesIziDurumu()
                    durumGuncelle()
                }
            } catch (e: Throwable) {
                runOnUiThread { durum("Ses modeli yüklenemedi: ${e.message}") }
            }
        }.start()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            durum("Seslendirme motoru bulunamadı (Google Konuşma Hizmetleri gerekli).")
            return
        }
        val r = tts.setLanguage(Locale("tr", "TR"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            durum("Türkçe ses paketi yok. Açılan ekrandan Türkçe sesi indirin.")
            try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Exception) { }
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        trSesler = try {
            tts.voices
                ?.filter { it.locale.language == "tr" && !it.isNetworkConnectionRequired }
                ?.sortedBy { it.name }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (!kulaklikTakili()) motor.sustur = true
            }
            override fun onDone(utteranceId: String?) = runOnUiThread { konusmaBitti() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = runOnUiThread { konusmaBitti() }
            override fun onError(utteranceId: String?, errorCode: Int) = runOnUiThread { konusmaBitti() }
        })
        ttsHazir = true
        durumGuncelle()
    }

    private fun konusmaBitti() {
        bekleyenKonusma = (bekleyenKonusma - 1).coerceAtLeast(0)
        if (bekleyenKonusma == 0) {
            // Hoparlörün yankısı bitsin diye kısa bir bekleme
            handler.postDelayed({ if (bekleyenKonusma == 0) motor.sustur = false }, 300)
        }
    }

    // ---------------- Başlat / Durdur ----------------

    private fun izinleCalistir(is_: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            is_()
        } else {
            izinSonrasi = is_
            micIzni.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun baslat() {
        if (!motorHazir || !ceviriPaketiHazir) {
            durum("Henüz hazır değil, birkaç saniye bekleyin…")
            return
        }
        if (!motor.ceviriBaslat()) return
        calisiyor = true
        bekleyenKonusma = 0
        motor.sustur = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnBaslat.text = "DURDUR"
        modEtiketi()
        durum("🎤 Dinleniyor (Rusça)…")
    }

    private fun durdur() {
        calisiyor = false
        motor.ceviriDurdur()
        tts.stop()
        bekleyenKonusma = 0
        motor.sustur = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnBaslat.text = "BAŞLAT"
        canliRu.text = ""
        canliTr.text = ""
        durum("Durduruldu.")
    }

    // ---------------- Ses izi kaydı ----------------

    private fun sesKaydiBaslat() {
        if (!motorHazir) {
            durum("Ses modeli yükleniyor, birkaç saniye bekleyin…")
            return
        }
        val tv = TextView(this).apply {
            setPadding(60, 40, 60, 20)
            textSize = 17f
        }
        kayitDialogText = tv
        kayitDialogMetni(0)
        kayitDialog = AlertDialog.Builder(this)
            .setTitle("Sesimi tanıt")
            .setView(tv)
            .setCancelable(false)
            .setNegativeButton("İptal") { _, _ -> motor.sesKaydiIptal() }
            .show()
        if (!motor.sesKaydiBaslat()) kayitDialog?.dismiss()
    }

    private fun kayitDialogMetni(saniye: Int) {
        kayitDialogText?.text =
            "Telefonu normal tuttuğunuz gibi tutun ve ${SesMotoru.KAYIT_SN} saniye boyunca " +
                "kendi sesinizle konuşun. Örneğin şunu okuyabilirsiniz:\n\n" +
                "\"Merhaba, benim adım … . Bu uygulama Rusça konuşulanları Türkçeye çeviriyor. " +
                "Benim sesimi tanıyıp çevirmemesi gerekiyor. Bugün hava güzel, birazdan dışarı çıkacağım.\"\n\n" +
                "⏺ Kayıt: $saniye / ${SesMotoru.KAYIT_SN} sn"
    }

    private fun sesIziDurumu() {
        val n = motor.benimIzSayim()
        sesIziView.text = if (n > 0)
            "✅ Ses iziniz kayıtlı ($n örnek). Kendi sesiniz çevrilmeyecek."
        else
            "⚠️ Ses iziniz kayıtlı değil. Önce \"Sesimi tanıt\"a basın."
    }

    private fun izleriKaydet() {
        prefs.edit().putString("benim_izlerim", motor.benimIzlerimMetin()).apply()
    }

    // ---------------- Motor olayları (arka plan thread'inden gelir) ----------------

    override fun anlikMetin(rusca: String) = runOnUiThread {
        canliRu.text = if (rusca.isEmpty()) "" else "🇷🇺 $rusca"
        anlikCevir(rusca)
    }

    override fun cumleBitti(rusca: String, kisi: Int, banaBenzerlik: Float) = runOnUiThread {
        canliRu.text = ""
        canliTr.text = ""
        bekleyenAnlik = null
        translator.translate(rusca)
            .addOnSuccessListener { turkce ->
                cumleyiYaz(kisi, rusca, turkce, banaBenzerlik)
                if (calisiyor) konus(turkce, kisi)
            }
            .addOnFailureListener { cumleyiYaz(kisi, rusca, "(çeviri hatası)", banaBenzerlik) }
    }

    override fun benimSesimAtlandi(rusca: String, banaBenzerlik: Float) = runOnUiThread {
        canliRu.text = ""
        canliTr.text = ""
        bekleyenAnlik = null
        val s = SpannableStringBuilder("🙊 Sizin sesiniz, çevrilmedi (benzerlik %.2f)\n\n".format(banaBenzerlik))
        s.setSpan(ForegroundColorSpan(Color.GRAY), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(RelativeSizeSpan(0.8f), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        logEkle(s)
    }

    override fun sesKaydiIlerleme(saniye: Int) = runOnUiThread { kayitDialogMetni(saniye) }

    override fun sesKaydiBitti(basarili: Boolean) = runOnUiThread {
        kayitDialog?.dismiss()
        kayitDialog = null
        kayitDialogText = null
        if (basarili) {
            izleriKaydet()
            Toast.makeText(this, "Sesiniz kaydedildi ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Ses kaydı alınamadı, tekrar deneyin.", Toast.LENGTH_SHORT).show()
        }
        sesIziDurumu()
    }

    override fun sesimOgrenildi(ornekSayisi: Int) = runOnUiThread {
        izleriKaydet()
        sesIziDurumu()
    }

    override fun hata(mesaj: String) = runOnUiThread {
        durum(mesaj)
        if (calisiyor) durdur()
    }

    // ---------------- Çeviri ve seslendirme ----------------

    private fun anlikCevir(rusca: String) {
        if (rusca.isEmpty()) {
            canliTr.text = ""
            bekleyenAnlik = null
            return
        }
        bekleyenAnlik = rusca
        if (!anlikCeviriSuruyor) anlikCeviriyiCalistir()
    }

    private fun anlikCeviriyiCalistir() {
        val m = bekleyenAnlik ?: return
        bekleyenAnlik = null
        anlikCeviriSuruyor = true
        translator.translate(m).addOnCompleteListener { t ->
            anlikCeviriSuruyor = false
            if (t.isSuccessful && canliRu.text.isNotEmpty()) canliTr.text = "🇹🇷 ${t.result}"
            if (bekleyenAnlik != null) anlikCeviriyiCalistir()
        }
    }

    private fun konus(turkce: String, kisi: Int) {
        if (!ttsHazir) return
        val i = (kisi - 1).coerceAtLeast(0)
        if (trSesler.isNotEmpty()) {
            try { tts.setVoice(trSesler[i % trSesler.size]) } catch (_: Exception) { }
        }
        tts.setPitch(perdeler[i % perdeler.size])
        // Konuşmalar birikirse biraz hızlan, geride kalma
        tts.setSpeechRate(if (bekleyenKonusma >= 2) 1.4f else 1.15f)
        bekleyenKonusma++
        val r = tts.speak(turkce, TextToSpeech.QUEUE_ADD, null, "u${konusmaSayac++}")
        if (r != TextToSpeech.SUCCESS) konusmaBitti()
    }

    // ---------------- Ekran yardımcıları ----------------

    private fun cumleyiYaz(kisi: Int, rusca: String, turkce: String, banaBenzerlik: Float) {
        val renk = renkler[(kisi - 1).coerceAtLeast(0) % renkler.size]
        val s = SpannableStringBuilder()
        val bas = s.length
        s.append("Kişi $kisi: ")
        s.setSpan(ForegroundColorSpan(renk), bas, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(StyleSpan(android.graphics.Typeface.BOLD), bas, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.append(turkce).append("\n")
        val alt = s.length
        val benz = if (banaBenzerlik >= -0.5f) " · size benzerlik %.2f".format(banaBenzerlik) else ""
        s.append("🇷🇺 $rusca$benz\n\n")
        s.setSpan(ForegroundColorSpan(Color.GRAY), alt, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(RelativeSizeSpan(0.8f), alt, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        logEkle(s)
    }

    private fun logEkle(s: CharSequence) {
        logView.append(s)
        if (logView.text.length > 30000) {
            logView.text = logView.text.subSequence(logView.text.length - 20000, logView.text.length)
        }
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun kulaklikTakili(): Boolean {
        val tipler = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            26 // TYPE_BLE_HEADSET
        )
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in tipler }
    }

    private fun modEtiketi() {
        modView.text = if (kulaklikTakili())
            "🎧 Kulaklık modu: kesintisiz dinleme"
        else
            "🔊 Hoparlör modu: çeviri okunurken dinleme bekler (kulaklık önerilir)"
    }

    private fun durumGuncelle() {
        if (calisiyor) return
        durum(
            when {
                !motorHazir -> "Ses modeli yükleniyor…"
                !ceviriPaketiHazir -> "Çeviri paketi indiriliyor (ilk seferde internet gerekir)…"
                !ttsHazir -> "Seslendirme hazırlanıyor…"
                else -> "Hazır. BAŞLAT'a basın."
            }
        )
    }

    private fun durum(m: String) {
        durumView.text = m
    }

    override fun onDestroy() {
        try { motor.kapat() } catch (_: Exception) { }
        tts.shutdown()
        translator.close()
        super.onDestroy()
    }
}
