package com.example.dnd_override

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "dnd_override/channel"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "getKeywords" -> result.success(DndOverrideService.getKeywords(this))
                "addKeyword" -> {
                    val kw = call.argument<String>("keyword")?.trim().orEmpty()
                    if (kw.isNotEmpty()) {
                        val set = DndOverrideService.getKeywords(this).toMutableSet()
                        set.add(kw)
                        DndOverrideService.setKeywords(this, set.toList())
                    }
                    result.success(DndOverrideService.getKeywords(this))
                }
                "removeKeyword" -> {
                    val kw = call.argument<String>("keyword").orEmpty()
                    val set = DndOverrideService.getKeywords(this).toMutableSet()
                    set.remove(kw)
                    DndOverrideService.setKeywords(this, set.toList())
                    result.success(DndOverrideService.getKeywords(this))
                }
                "setKeywords" -> {
                    val list = call.argument<List<String>>("keywords") ?: emptyList()
                    DndOverrideService.setKeywords(this, list)
                    result.success(DndOverrideService.getKeywords(this))
                }
                "isNotificationAccessGranted" -> result.success(isNotificationAccessGranted() || DndOverrideService.isServiceConnected())
                "isServiceConnected" -> result.success(DndOverrideService.isServiceConnected())
                "getLastDebugInfo" -> result.success(DndOverrideService.lastDebugInfo)
                "openNotificationAccessSettings" -> {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    result.success(true)
                }
                "isDndAccessGranted" -> {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    result.success(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) nm.isNotificationPolicyAccessGranted else true)
                }
                "openDndAccessSettings" -> {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    result.success(true)
                }
                "isIgnoringBatteryOptimizations" -> {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    result.success(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(packageName) else true)
                }
                "requestIgnoreBatteryOptimizations" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    result.success(true)
                }
                "getDefaultRingtoneTitle" -> {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    val r = RingtoneManager.getRingtone(this, uri)
                    result.success(r?.getTitle(this))
                }
                "sendTestNotification" -> {
                    val kw = call.argument<String>("keyword")?.trim().orEmpty().ifEmpty { "test" }
                    val useRingtone = call.argument<Boolean>("ringtone") ?: false
                    result.success(postTestNotification(kw, useRingtone))
                }
                "playTestSound" -> {
                    DndOverrideService.triggerOverride(this)
                    result.success(true)
                }
                "playTestRingtone" -> {
                    DndOverrideService.playRingtoneOnly(this)
                    result.success(true)
                }
                "showStatusNotification" -> {
                    DndOverrideService.showPersistentStatus(this)
                    result.success(true)
                }
                "clearStatusNotification" -> {
                    DndOverrideService.clearPersistentStatus(this)
                    result.success(true)
                }
                "requestNotificationPermission" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
                    }
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        // Robust check: match either the full component name or just the service class name.
        val cn = ComponentName(this, DndOverrideService::class.java)
        if (flat.split(":").any { it.equals(cn.flattenToString(), ignoreCase = true) }) return true
        return flat.contains("DndOverrideService", ignoreCase = true)
    }

    private fun postTestNotification(keyword: String, useRingtone: Boolean): Boolean {
        return try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel("dnd_override_test", "DnD Override Test", NotificationManager.IMPORTANCE_HIGH)
                channel.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
                nm.createNotificationChannel(channel)
            }
            val extras = Bundle()
            extras.putBoolean("dnd_override_test", true)
            extras.putBoolean(DndOverrideService.RINGTONE_EXTRA, useRingtone)
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, "dnd_override_test")
            } else {
                Notification.Builder(this)
            }
            val notification = builder
                .setContentTitle(keyword)
                .setContentText(keyword)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setExtras(extras)
                .setDefaults(Notification.DEFAULT_ALL)
                .setAutoCancel(true)
                .build()
            nm.notify(9999, notification)
            true
        } catch (e: Exception) {
            false
        }
    }
}
