package com.ceviri.rustr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Ekran kapalıyken / başka uygulamaya geçince de mikrofonun çalışmaya devam etmesi için
 * bildirimli "ön plan servisi". Çeviri açıkken çalışır, kapatınca durur.
 */
class TercumanServisi : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kanal = "tercuman"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(kanal, "Tercüman", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val ac = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 0, ac, PendingIntent.FLAG_IMMUTABLE)
        val bildirim = NotificationCompat.Builder(this, kanal)
            .setContentTitle("Tercüman dinliyor")
            .setContentText("Rusça → Türkçe çeviri açık. Durdurmak için dokunun.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, bildirim, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, bildirim)
            }
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
