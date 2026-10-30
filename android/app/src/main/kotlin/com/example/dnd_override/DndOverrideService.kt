package com.example.dnd_override

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class DndOverrideService : NotificationListenerService() {

    companion object {
        const val PREFS_NAME = "dnd_override_prefs"
        const val KEY_KEYWORDS = "keywords"
        const val TEST_EXTRA = "dnd_override_test"
        const val RINGTONE_EXTRA = "dnd_override_ringtone"
        const val PERSISTENT_EXTRA = "dnd_override_persistent"
        const val STATUS_CHANNEL = "dnd_override_status"
        const val STATUS_NOTIF_ID = 7001

        @Volatile
        private var serviceConnected: Boolean = false

        @Volatile
        var lastDebugInfo: String = "none"

        fun isServiceConnected(): Boolean = serviceConnected

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getKeywords(ctx: Context): List<String> =
            prefs(ctx).getStringSet(KEY_KEYWORDS, emptySet())?.toList() ?: emptyList()

        fun setKeywords(ctx: Context, keywords: List<String>) {
            prefs(ctx).edit().putStringSet(KEY_KEYWORDS, keywords.toSet()).apply()
        }

        fun normalizeArabic(s: String): String {
            var t = s
            t = t.replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            t = t.replace("\u0640", "")
            t = t.replace(Regex("[\u0622\u0623\u0625]"), "\u0627")
            t = t.replace("\u0624", "\u0648")
            t = t.replace("\u0626", "\u064A")
            t = t.replace("\u0621", "")
            t = t.replace("\u0649", "\u064A")
            t = t.replace("\u0629", "\u0647")
            return t
        }

        fun resolveSound(ctx: Context, sbn: StatusBarNotification): android.net.Uri? {
            val n = sbn.notification
            if (n.sound != null) return n.sound
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .getNotificationChannel(n.channelId)
                if (ch?.sound != null) return ch.sound
            }
            return null
        }

        private val handler = Handler(Looper.getMainLooper())

        fun triggerOverride(ctx: Context, soundUri: android.net.Uri? = null, useRingtone: Boolean = false) {
            vibrate(ctx)
            wakeScreen(ctx)
            if (useRingtone) {
                playRingtoneOnly(ctx)
            } else {
                playSound(ctx, soundUri)
            }
        }

        private fun playSound(ctx: Context, soundUri: android.net.Uri?) {
            try {
                var uri = soundUri
                var r = if (uri != null) RingtoneManager.getRingtone(ctx.applicationContext, uri) else null
                if (r == null) {
                    uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    r = if (uri != null) RingtoneManager.getRingtone(ctx.applicationContext, uri) else null
                }
                try { r?.streamType = AudioManager.STREAM_ALARM } catch (_: Exception) { }
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                } catch (_: Exception) { }
                r?.play()
                val rr = r
                handler.postDelayed({
                    try { rr?.stop() } catch (_: Exception) { }
                }, 5000L)
            } catch (_: Exception) { }
        }

        fun playRingtoneOnly(ctx: Context) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val r = RingtoneManager.getRingtone(ctx.applicationContext, uri)
                try { r?.streamType = AudioManager.STREAM_ALARM } catch (_: Exception) { }
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                } catch (_: Exception) { }
                r?.play()
                handler.postDelayed({ try { r?.stop() } catch (_: Exception) { } }, 10000L)
            } catch (_: Exception) { }
        }

        private fun vibrate(ctx: Context) {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(3000L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(3000L)
                }
            } catch (_: Exception) { }
        }

        private fun wakeScreen(ctx: Context) {
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "dnd_override:wakelock"
                )
                wl.acquire(10 * 1000L)
            } catch (_: Exception) { }
        }

        fun createStatusChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val ch = NotificationChannel(STATUS_CHANNEL, "DnD Override Status", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "Shows when DnD override is active"
                ch.setShowBadge(false)
                ch.enableVibration(false)
                ch.setSound(null, null)
                nm.createNotificationChannel(ch)
            }
        }

        fun showPersistentStatus(ctx: Context) {
            DndStatusService.start(ctx)
        }

        fun clearPersistentStatus(ctx: Context) {
            DndStatusService.stop(ctx)
        }
    }

    private var lastKey: String? = null
    private var lastTime: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        serviceConnected = true
        // If DnD already active, show persistent notification.
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            showPersistentStatus(this)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        serviceConnected = false
        clearPersistentStatus(this)
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        super.onInterruptionFilterChanged(interruptionFilter)
        if (interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
            clearPersistentStatus(this)
        } else {
            showPersistentStatus(this)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Ensure persistent status notification is shown/cleared based on DnD state.
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            showPersistentStatus(this)
        } else {
            clearPersistentStatus(this)
        }
        val isOwn = sbn.packageName == packageName
        // Skip our persistent status notification and other own notifications that aren't tests.
        val isPersistent = sbn.notification.extras?.getBoolean(PERSISTENT_EXTRA, false) == true
        if (isOwn && isPersistent) return
        // For real (non-own) notifications, only act while DnD is active.
        if (!isOwn) {
            if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                lastDebugInfo = "SKIPPED: DnD not active (real notif)"
                return
            }
        }
        // Skip incoming calls.
        if (sbn.notification.category == Notification.CATEGORY_CALL) return
        val pkg = sbn.packageName.lowercase()
        if (!isOwn && (pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("telecom") || pkg.contains("incall"))) return

        val keywords = getKeywords(this).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) {
            lastDebugInfo = "SKIPPED: no keywords stored"
            return
        }

        val now = System.currentTimeMillis()
        if (sbn.key == lastKey && now - lastTime < 5000L) return

        val combined = normalizeArabic(extractText(sbn.notification)).lowercase()
        val matchedKw = keywords.firstOrNull { combined.contains(normalizeArabic(it).lowercase()) }
        if (matchedKw == null) {
            lastDebugInfo = "NO MATCH: combined=[$combined] keywords=$keywords"
            return
        }
        val useRingtone = sbn.notification.extras?.getBoolean(RINGTONE_EXTRA, false) == true
        val soundUri = resolveSound(this, sbn)
        lastDebugInfo = "MATCHED: [$matchedKw] sound=${soundUri ?: "default"} combined=[$combined]"

        lastKey = sbn.key
        lastTime = now
        triggerOverride(this, soundUri, useRingtone)
    }

    private fun extractText(n: Notification): String {
        val extras = n.extras
        val parts = mutableListOf<String>()
        fun add(key: String) {
            val v = extras.getCharSequence(key)
            if (v != null) parts.add(v.toString())
        }
        add(Notification.EXTRA_TITLE)
        add(Notification.EXTRA_TEXT)
        add(Notification.EXTRA_BIG_TEXT)
        add(Notification.EXTRA_SUB_TEXT)
        add(Notification.EXTRA_SUMMARY_TEXT)
        add(Notification.EXTRA_INFO_TEXT)
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null) {
            for (m in msgs) {
                val b = m as? android.os.Bundle
                val txt = b?.getCharSequence("text")
                if (txt != null) parts.add(txt.toString())
                val sender = b?.getCharSequence("sender")
                if (sender != null) parts.add(sender.toString())
            }
        }
        return parts.joinToString(" ")
    }
}
