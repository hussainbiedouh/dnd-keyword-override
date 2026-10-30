package com.example.dnd_override

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class DndStatusService : Service() {

    companion object {
        const val CHANNEL_ID = "dnd_override_status"
        const val NOTIF_ID = 7001

        fun start(ctx: Context) {
            val intent = Intent(ctx, DndStatusService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, DndStatusService::class.java)
            ctx.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "DnD Override Status", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Shows when DnD override is active"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("DnD Override active")
            .setContentText("Monitoring notifications for keywords")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
    }
}
