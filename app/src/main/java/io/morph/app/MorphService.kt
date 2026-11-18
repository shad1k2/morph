package io.morph.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Keeps the receive side alive (mDNS + TCP) while the user is in other apps,
 * so the QS tile and incoming drops work like AirDrop.
 */
class MorphService : Service() {

    companion object {
        const val CHANNEL_ID = "morph_receive"
        const val NOTIFICATION_ID = 42
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        MorphEngine.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MorphEngine.start(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MORPH receive",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps MORPH visible to nearby phones"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("MORPH")
            .setContentText("Visible to nearby phones on this Wi-Fi")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
