package com.example.dnd_override

import android.app.Notification
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

        @Volatile
        private var serviceConnected: Boolean = false

        @Volatile
        var lastDebugInfo: String = "none"

        fun isServiceConnected(): Boolean = serviceConnected

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getKeywords(ctx: Context): List<String> =
            prefs(ctx).getStringSet(KEY_KEYWORDS, emptySet())?.toList() ?: emptyList()

        // Normalize Arabic text so dialect/spelling variants are treated as equal:
        // removes tashkeel, tatweel, normalizes alef/hamza/ya/ta-marbuta.
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

        fun setKeywords(ctx: Context, keywords: List<String>) {
            prefs(ctx).edit().putStringSet(KEY_KEYWORDS, keywords.toSet()).apply()
        }

        // --- Override actions (reusable for real + test triggers) ---
        private var ringtone: Ringtone? = null
        private var isPlaying: Boolean = false
        private val handler = Handler(Looper.getMainLooper())

        fun triggerOverride(ctx: Context) {
            vibrate(ctx)
            wakeScreen(ctx)
            playRingtone(ctx)
        }

        // Play the ringtone sound directly (for testing ringtone hardware).
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
                Handler(Looper.getMainLooper()).postDelayed({ try { r?.stop() } catch (_: Exception) { } }, 10000L)
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

        private fun playRingtone(ctx: Context) {
            // Avoid reverb: stop any currently playing ringtone before starting a new one.
            if (isPlaying) {
                try { ringtone?.stop() } catch (_: Exception) { }
                handler.removeCallbacksAndMessages(null)
                ringtone = null
                isPlaying = false
            }
            isPlaying = true
            try {
                try { ringtone?.stop() } catch (_: Exception) { }
                ringtone = null
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val r = RingtoneManager.getRingtone(ctx.applicationContext, uri)
                try { r?.streamType = AudioManager.STREAM_ALARM } catch (_: Exception) { }
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                } catch (_: Exception) { }
                ringtone = r
                r?.play()
                handler.postDelayed({
                    try { ringtone?.stop() } catch (_: Exception) { }
                    ringtone = null
                    isPlaying = false
                }, 15000L)
            } catch (_: Exception) {
                isPlaying = false
            }
        }
    }

    private var lastKey: String? = null
    private var lastTime: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        serviceConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        serviceConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isOwn = sbn.packageName == packageName
        // For real (non-own) notifications, only act while DnD is active.
        if (!isOwn) {
            if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                lastDebugInfo = "SKIPPED: DnD not active (real notif)"
                return
            }
        }
        // Skip incoming calls: let the system handle call ringing (avoids reverb/double ring).
        if (sbn.notification.category == Notification.CATEGORY_CALL) return
        val pkg = sbn.packageName.lowercase()
        if (!isOwn && (pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("telecom") || pkg.contains("incall"))) return

        val keywords = getKeywords(this).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) {
            lastDebugInfo = "SKIPPED: no keywords stored"
            return
        }

        // Dedup rapid re-posts.
        val now = System.currentTimeMillis()
        if (sbn.key == lastKey && now - lastTime < 5000L) return

        val combined = normalizeArabic(extractText(sbn.notification)).lowercase()
        val matchedKw = keywords.firstOrNull { combined.contains(normalizeArabic(it).lowercase()) }
        if (matchedKw == null) {
            lastDebugInfo = "NO MATCH: combined=[$combined] keywords=$keywords"
            return
        }
        lastDebugInfo = "MATCHED: [$matchedKw] combined=[$combined]"

        lastKey = sbn.key
        lastTime = now
        triggerOverride(this)
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
