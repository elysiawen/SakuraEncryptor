package com.sakura.encryptor.core.openlist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sakura.encryptor.MainActivity

/**
 * Keeps the process alive while OpenList serves files.
 *
 * The server itself is started by [LocalOpenListManager] — this only owns the
 * foreground notification, so the system treats the process as doing visible
 * work and does not freeze it the moment the app leaves the screen.
 */
class LocalOpenListService : Service() {

    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "内置服务器",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "在本机运行 OpenList 时显示" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("内置服务器运行中")
                .setContentText("OpenList 正在本机提供文件服务")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Android 15 limits `dataSync` services; when the system asks us to go, the
     * server stops with the process and the switch is turned off next launch.
     */
    override fun onTimeout(startId: Int) = stopSelf()

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "openlist_server"
        private const val NOTIFICATION_ID = 4103

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LocalOpenListService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LocalOpenListService::class.java)) }
        }
    }
}
