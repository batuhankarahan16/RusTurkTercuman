package com.ceviri.rustr

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Ses motoru (internetsiz):
 *  - Mikrofonu kesintisiz dinler (bip sesi yok, arada kaçan ses yok).
 *  - Rusçayı akışlı (anlık) olarak yazıya döker.
 *  - Her cümlenin "ses izini" çıkarır: senin sesinse atlar, değilse kişiyi (1..5) belirler.
 *
 * Dinleyici fonksiyonları arka plan thread'lerinden çağrılır.
 */
class SesMotoru(private val assets: AssetManager, private val dinleyici: Dinleyici) {

    interface Dinleyici {
        fun anlikMetin(rusca: String)
        fun cumleBitti(rusca: String, kisi: Int, banaBenzerlik: Float)
        fun benimSesimAtlandi(rusca: String, banaBenzerlik: Float)
        fun sesKaydiIlerleme(saniye: Int)
        fun sesKaydiBitti(basarili: Boolean)
        fun sesimOgrenildi(ornekSayisi: Int)
        fun hata(mesaj: String)
    }

    companion object {
        const val SR = 16000
        const val BEN_ESIK = 0.40f      // bu değerin üstü = senin sesin
        const val KISI_ESIK = 0.40f     // bu değerin üstü = daha önce duyulan kişi
        const val MAX_KISI = 5
        const val KAYIT_SN = 12
        private const val MAX_IZ = 12

        /** Rusçada cümlenin bu kelimelerle bitmesi çok zordur: bunlarla biterse cümle devam ediyordur. */
        private val YARIM_KELIMELER = setOf(
            "и", "а", "но", "или", "что", "чтобы", "как", "когда", "если", "потому", "поэтому",
            "который", "которая", "которое", "которые", "которых", "которого", "которой", "где", "куда",
            "в", "во", "на", "с", "со", "к", "ко", "по", "за", "из", "от", "до", "для", "о", "об", "обо",
            "у", "при", "про", "без", "над", "под", "через", "между", "перед", "после", "около",
            "не", "ни", "бы", "же", "то", "даже", "только", "очень", "самый", "самая", "более", "менее",
            "мой", "моя", "моё", "мои", "твой", "твоя", "наш", "наша", "ваш", "ваша", "свой", "своя",
            "этот", "эта", "эти", "тот", "та", "те", "такой", "такая", "такие", "каждый", "весь", "вся", "все",
            "был", "была", "были", "будет", "будут", "буду", "есть", "хочу", "хочет", "хотел", "могу", "может",
            "можно", "нужно", "надо", "должен", "должна", "чем", "ещё", "еще", "уже", "тоже", "также", "либо"
        )
    }

    private lateinit var recognizer: OnlineRecognizer
    private lateinit var stream: OnlineStream
    private lateinit var extractor: SpeakerEmbeddingExtractor

    @Volatile var hazir = false
        private set

    // İkinci geçiş: cümle bitince büyük ve daha doğru model (GigaAM) ile yeniden dinle
    private var buyuk: OfflineRecognizer? = null
    @Volatile var buyukHazir = false
        private set

    private val isci = Executors.newSingleThreadExecutor()
    private var sesThread: Thread? = null

    @Volatile private var calisiyor = false
    @Volatile private var ceviriAcik = false
    @Volatile private var kayitModu = false
    @Volatile private var kayitIptal = false

    /** "Ben konuşuyorum" düğmesi basılıyken true. */
    @Volatile var benKonusuyorum = false

    /** Hoparlör modunda çeviri okunurken true (uygulama kendi sesini duymasın). */
    @Volatile var sustur = false

    private val benimIzlerim = ArrayList<FloatArray>()
    private class Kisi(val no: Int, var merkez: FloatArray, var sayi: Int)
    private val kisiler = ArrayList<Kisi>()
    @Volatile private var sonKisi = 1

    // ---------------- Yükleme ----------------

    /** Modelleri yükler. Ana thread'de çağırmayın (birkaç saniye sürer). */
    fun yukle() {
        val d = "asr"
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$d/encoder.int8.onnx",
                    decoder = "$d/decoder.onnx",
                    joiner = "$d/joiner.int8.onnx",
                ),
                tokens = "$d/tokens.txt",
                numThreads = 2,
                modelType = "zipformer2",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.0f, 0.0f),  // hiç konuşma yoksa 2 sn sonra sıfırla
                rule2 = EndpointRule(true, 30.0f, 0.0f),  // cümle sonunu aşağıda kendimiz belirliyoruz
                rule3 = EndpointRule(false, 0.0f, 30.0f), // güvenlik sınırı
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        recognizer = OnlineRecognizer(assets, config)
        stream = recognizer.createStream()
        extractor = SpeakerEmbeddingExtractor(
            assets,
            SpeakerEmbeddingExtractorConfig(model = "spk.onnx", numThreads = 2),
        )
        hazir = true
    }

    /** Büyük (doğru) Rusça modeli yükler. yukle()'den sonra, arka planda çağırın. */
    fun buyukModeliYukle() {
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = "giga/model.int8.onnx"),
                tokens = "giga/tokens.txt",
                numThreads = 4,
            ),
            decodingMethod = "greedy_search",
        )
        buyuk = OfflineRecognizer(assets, cfg)
        buyukHazir = true
    }

    private fun buyukTani(ses: FloatArray): String? {
        val r = buyuk ?: return null
        val s = r.createStream()
        try {
            s.acceptWaveform(ses, SR)
            r.decode(s)
            return r.getResult(s).text.trim()
        } finally {
            s.release()
        }
    }

    // ---------------- Dışarıdan kontrol ----------------

    fun ceviriBaslat(): Boolean {
        ceviriAcik = true
        return mikrofonuAc()
    }

    fun ceviriDurdur() {
        ceviriAcik = false
        if (!kayitModu) mikrofonuKapat()
    }

    fun sesKaydiBaslat(): Boolean {
        kayitIptal = false
        kayitModu = true
        return mikrofonuAc()
    }

    fun sesKaydiIptal() {
        kayitIptal = true
    }

    fun kisileriSifirla() {
        synchronized(kisiler) { kisiler.clear() }
        sonKisi = 1
    }

    fun benimIzSayim(): Int = synchronized(benimIzlerim) { benimIzlerim.size }

    fun benimIzlerimMetin(): String = synchronized(benimIzlerim) {
        benimIzlerim.joinToString(";") { iz -> iz.joinToString(",") }
    }

    fun benimIzlerimiYukle(metin: String) {
        if (metin.isBlank()) return
        val liste = metin.split(";").mapNotNull { parca ->
            try {
                parca.split(",").map { it.toFloat() }.toFloatArray()
            } catch (_: Exception) {
                null
            }
        }
        synchronized(benimIzlerim) {
            benimIzlerim.clear()
            benimIzlerim.addAll(liste)
        }
    }

    fun kapat() {
        ceviriAcik = false
        kayitModu = false
        mikrofonuKapat()
        isci.shutdownNow()
    }

    // ---------------- Mikrofon döngüsü ----------------

    @SuppressLint("MissingPermission")
    private fun mikrofonuAc(): Boolean {
        if (calisiyor) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SR * 2)
            )
        } catch (e: Exception) {
            dinleyici.hata("Mikrofon açılamadı: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            dinleyici.hata("Mikrofon açılamadı (başka bir uygulama kullanıyor olabilir).")
            return false
        }
        calisiyor = true
        sesThread = Thread { dongu(rec) }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    private fun mikrofonuKapat() {
        calisiyor = false
        val t = sesThread
        if (t != null && t !== Thread.currentThread()) t.join(1000)
        sesThread = null
    }

    private fun dongu(rec: AudioRecord) {
        val buf = ShortArray(SR / 10) // 100 ms
        val parca = ArrayList<FloatArray>()
        var parcaOrnek = 0
        val benTampon = ArrayList<FloatArray>()
        val kayitTampon = ArrayList<FloatArray>()
        var kayitOrnek = 0
        var sonSaniye = -1
        var sonMetin = ""
        var sonDegisim = SystemClock.elapsedRealtime()
        val araliklar = ArrayDeque<Long>() // konuşma içindeki duraklama süreleri (konuşmacıya uyum için)

        fun parcayiSifirla() {
            recognizer.reset(stream)
            parca.clear()
            parcaOrnek = 0
            if (sonMetin.isNotEmpty()) {
                sonMetin = ""
                dinleyici.anlikMetin("")
            }
        }

        try {
            rec.startRecording()
            while (calisiyor) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val f = FloatArray(n) { buf[it] / 32768f }

                // 1) Ses izi kaydı
                if (kayitModu) {
                    if (kayitIptal) {
                        kayitModu = false
                        kayitTampon.clear(); kayitOrnek = 0; sonSaniye = -1
                        dinleyici.sesKaydiBitti(false)
                        if (!ceviriAcik) break else continue
                    }
                    kayitTampon.add(f); kayitOrnek += n
                    val sn = kayitOrnek / SR
                    if (sn != sonSaniye) {
                        sonSaniye = sn
                        dinleyici.sesKaydiIlerleme(sn)
                    }
                    if (kayitOrnek >= KAYIT_SN * SR) {
                        val ses = birlestir(kayitTampon)
                        kayitTampon.clear(); kayitOrnek = 0; sonSaniye = -1
                        kayitModu = false
                        isci.execute { sesKaydiniIsle(ses) }
                        parcayiSifirla()
                        if (!ceviriAcik) break
                    }
                    continue
                }
                if (!ceviriAcik) break

                // 2) "Ben konuşuyorum" düğmesi basılı: çevirme, sesini öğren
                if (benKonusuyorum) {
                    benTampon.add(f)
                    if (parcaOrnek > 0) parcayiSifirla()
                    continue
                }
                if (benTampon.isNotEmpty()) {
                    val ses = birlestir(benTampon)
                    benTampon.clear()
                    isci.execute { benimSesimiOgren(ses) }
                }

                // 3) Hoparlör modunda çeviri okunuyor: kendi sesimizi dinleme
                if (sustur) {
                    if (parcaOrnek > 0) parcayiSifirla()
                    continue
                }

                // 4) Akışlı Rusça tanıma
                stream.acceptWaveform(f, SR)
                parca.add(f); parcaOrnek += n
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val metin = recognizer.getResult(stream).text.trim()
                val simdi = SystemClock.elapsedRealtime()
                if (metin != sonMetin) {
                    val bosluk = simdi - sonDegisim
                    if (sonMetin.isNotEmpty() && metin.isNotEmpty() && bosluk in 150L..1500L) {
                        araliklar.addLast(bosluk)
                        if (araliklar.size > 40) araliklar.removeFirst()
                    }
                    sonMetin = metin
                    sonDegisim = simdi
                    dinleyici.anlikMetin(metin)
                }
                // Cümle sonunu otomatik anla:
                //  - konuşmacının kendi duraklama alışkanlığına göre bekleme süresi,
                //  - cümle "ve, ama, için, ki…" gibi yarım kalan bir kelimeyle bitiyorsa daha uzun bekle.
                //  - cümle yeterince uzunsa ve tamamlanmış görünüyorsa kısa bir duraklama da yeter
                //    (akıcı konuşmada cümleler birikip toplu çevrilmesin).
                val durgun = simdi - sonDegisim
                val yarim = yarimKaldi(metin)
                val parcaSn = parcaOrnek / SR
                val gerekli = if (yarim) 2200L else otomatikBekleme(araliklar)
                val cumleBitti = metin.isNotEmpty() && (
                    durgun > gerekli ||
                        (!yarim && parcaSn >= 5 && durgun > 400) ||
                        (parcaSn >= 10 && durgun > 300)
                    )
                if (cumleBitti || recognizer.isEndpoint(stream)) {
                    if (metin.isNotEmpty()) {
                        val ses = birlestir(parca)
                        isci.execute { cumleyiIsle(metin, ses) }
                    }
                    recognizer.reset(stream)
                    parca.clear(); parcaOrnek = 0
                    sonMetin = ""
                }
            }
        } catch (e: Throwable) {
            dinleyici.hata("Ses işleme hatası: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) { }
            rec.release()
            calisiyor = false
        }
    }

    // ---------------- Otomatik cümle sonu ----------------

    private fun otomatikBekleme(araliklar: ArrayDeque<Long>): Long {
        if (araliklar.size < 5) return 900L
        val sirali = araliklar.sorted()
        val p75 = sirali[(sirali.size * 3) / 4]
        return (p75 * 3 / 2 + 150).coerceIn(600L, 1200L)
    }

    private fun yarimKaldi(metin: String): Boolean {
        val kelimeler = metin.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (kelimeler.isEmpty()) return false
        return kelimeler.last().lowercase() in YARIM_KELIMELER
    }

    // ---------------- Ses izi (konuşmacı tanıma) ----------------

    private fun cumleyiIsle(metin: String, hamSes: FloatArray) {
        try {
            val ses = sessizligiKirp(hamSes)
            val sure = ses.size.toFloat() / SR
            val iz = if (sure >= 0.5f) izCikar(ses) else null
            val banaBenzerlik = if (iz != null) benimleBenzerlik(iz) else -1f
            if (iz != null && banaBenzerlik >= BEN_ESIK) {
                dinleyici.benimSesimAtlandi(metin, banaBenzerlik)
                return
            }
            // Senin sesin değil: cümleyi büyük modelle yeniden dinle (çok daha doğru)
            val dogruMetin = if (buyukHazir && sure >= 0.3f) {
                try { buyukTani(hamSes) } catch (_: Throwable) { null }
            } else null
            val sonMetin = if (!dogruMetin.isNullOrBlank()) dogruMetin else metin
            dinleyici.cumleBitti(sonMetin, kisiBul(iz, sure), banaBenzerlik)
        } catch (e: Throwable) {
            dinleyici.cumleBitti(metin, sonKisi, -1f)
        }
    }

    private fun sesKaydiniIsle(ses: FloatArray) {
        try {
            val pencere = 3 * SR
            val yeniIzler = ArrayList<FloatArray>()
            var i = 0
            while (i + pencere <= ses.size) {
                val p = sessizligiKirp(ses.copyOfRange(i, i + pencere))
                if (p.size >= (1.5f * SR).toInt()) izCikar(p)?.let { yeniIzler.add(it) }
                i += pencere
            }
            if (yeniIzler.size >= 2) {
                synchronized(benimIzlerim) {
                    benimIzlerim.clear()
                    benimIzlerim.addAll(yeniIzler)
                }
                dinleyici.sesKaydiBitti(true)
            } else {
                dinleyici.sesKaydiBitti(false)
            }
        } catch (e: Throwable) {
            dinleyici.sesKaydiBitti(false)
        }
    }

    private fun benimSesimiOgren(hamSes: FloatArray) {
        try {
            val ses = sessizligiKirp(hamSes)
            if (ses.size < (1.5f * SR).toInt()) return
            val iz = izCikar(ses) ?: return
            val sayi = synchronized(benimIzlerim) {
                benimIzlerim.add(iz)
                while (benimIzlerim.size > MAX_IZ) benimIzlerim.removeAt(0)
                benimIzlerim.size
            }
            dinleyici.sesimOgrenildi(sayi)
        } catch (_: Throwable) { }
    }

    private fun izCikar(ses: FloatArray): FloatArray? {
        val s = extractor.createStream()
        try {
            s.acceptWaveform(ses, SR)
            s.inputFinished()
            if (!extractor.isReady(s)) return null
            return normalize(extractor.compute(s))
        } finally {
            s.release()
        }
    }

    private fun benimleBenzerlik(iz: FloatArray): Float = synchronized(benimIzlerim) {
        var enIyi = -1f
        for (b in benimIzlerim) {
            if (b.size == iz.size) enIyi = maxOf(enIyi, dot(b, iz))
        }
        enIyi
    }

    private fun kisiBul(iz: FloatArray?, sure: Float): Int {
        if (iz == null) return sonKisi
        synchronized(kisiler) {
            var enIyi: Kisi? = null
            var enIyiSim = -2f
            for (k in kisiler) {
                val sim = dot(k.merkez, iz)
                if (sim > enIyiSim) {
                    enIyiSim = sim
                    enIyi = k
                }
            }
            val secilen: Kisi = when {
                enIyi != null && enIyiSim >= KISI_ESIK -> {
                    guncelle(enIyi, iz)
                    enIyi
                }
                enIyi != null && sure < 1.0f -> enIyi            // kısa cümleyle yeni kişi açma
                kisiler.size < MAX_KISI -> Kisi(kisiler.size + 1, iz, 1).also { kisiler.add(it) }
                else -> enIyi!!
            }
            sonKisi = secilen.no
            return secilen.no
        }
    }

    private fun guncelle(k: Kisi, iz: FloatArray) {
        val w = minOf(k.sayi, 20).toFloat()
        k.merkez = normalize(FloatArray(iz.size) { k.merkez[it] * w + iz[it] })
        k.sayi++
    }

    // ---------------- Yardımcılar ----------------

    private fun sessizligiKirp(x: FloatArray): FloatArray {
        val pencere = SR / 50 // 20 ms
        val sayi = x.size / pencere
        if (sayi == 0) return x
        val rms = FloatArray(sayi) { i ->
            var s = 0f
            for (j in i * pencere until (i + 1) * pencere) s += x[j] * x[j]
            sqrt(s / pencere)
        }
        val esik = maxOf(0.005f, (rms.maxOrNull() ?: 0f) * 0.1f)
        val ilk = rms.indexOfFirst { it > esik }
        val son = rms.indexOfLast { it > esik }
        if (ilk < 0) return FloatArray(0)
        val bas = maxOf(0, (ilk - 5) * pencere)
        val bit = minOf(x.size, (son + 6) * pencere)
        return x.copyOfRange(bas, bit)
    }

    private fun birlestir(liste: List<FloatArray>): FloatArray {
        val toplam = liste.sumOf { it.size }
        val out = FloatArray(toplam)
        var o = 0
        for (a in liste) {
            System.arraycopy(a, 0, out, o, a.size)
            o += a.size
        }
        return out
    }

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s)
        return if (n > 0f) FloatArray(v.size) { v[it] / n } else v
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
