package com.sakura.encryptor.core.local

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Lightweight progress notifications for long-running encrypt / decrypt jobs.
 *
 * Notification permission is optional: when it is missing the calls simply do
 * nothing and the in-app progress UI remains the source of truth.
 */
class CryptoNotifications(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "crypto_tasks"
        const val ID_ENCRYPT = 4101
        const val ID_DECRYPT = 4102
    }

    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "加密任务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "文件加密与解密进度" }
        manager.createNotificationChannel(channel)
    }

    private fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun progress(id: Int, title: String, text: String, percent: Int) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { manager.notify(id, notification) }
    }

    fun finished(id: Int, title: String, text: String) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { manager.notify(id, notification) }
    }

    fun cancel(id: Int) = runCatching { manager.cancel(id) }.let { }
}
