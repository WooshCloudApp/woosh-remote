// The only door into the remote client: a service the Woosh agent starts to
// open and close a session. Protected by a signature permission, so only apps
// signed with the same (platform) key - i.e. the Woosh agent - can reach it.
//
// Place in flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/
// (the upstream package; applicationId is renamed separately).
//
// UNTESTED. Written against upstream `master`: MainService,
// PermissionRequestTransparentActivity and ACT_REQUEST_MEDIA_PROJECTION are
// RustDesk's own; confirm the names at your pinned tag.

package com.carriez.flutter_hbb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.util.Log
import ffi.FFI

class WooshRemoteControlService : Service() {

    companion object {
        const val ACTION_START = "au.com.wooshpay.remote.action.START_SESSION"
        const val ACTION_STOP = "au.com.wooshpay.remote.action.STOP_SESSION"

        const val EXTRA_SESSION = "session"          // e.g. RS/2026/00012
        const val EXTRA_PASSWORD = "password"        // one-time, from Odoo
        const val EXTRA_MAX_MINUTES = "max_minutes"  // hard stop, from Odoo
        const val EXTRA_REASON = "reason"
        const val EXTRA_MODE = "mode"                // view | control
        const val EXTRA_RESULT = "result_receiver"   // ResultReceiver

        const val RESULT_OK = 0
        const val RESULT_ERROR = 1

        private const val TAG = "WooshRemote"
        private const val CHANNEL = "woosh_remote_session"
        private const val NOTIFICATION_ID = 0x5750
        private const val INPUT_SERVICE = "com.carriez.flutter_hbb.InputService"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: String? = null
    private val timeLimit = Runnable { stopSession("time limit reached") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // A crash or reboot mid-session must not leave a known password live.
        FFI.wooshClearSessionPassword()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first: it keeps the process alive AND is the on-screen
        // sign, for as long as the session lasts, that someone is connected.
        startForegroundCompat(intent?.getStringExtra(EXTRA_SESSION))
        val receiver = resultReceiver(intent)
        when (intent?.action) {
            ACTION_START -> startSession(intent, receiver)
            ACTION_STOP -> {
                stopSession(intent.getStringExtra(EXTRA_REASON) ?: "ended")
                receiver?.send(RESULT_OK, Bundle())
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent, receiver: ResultReceiver?) {
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        val maxMinutes = intent.getIntExtra(EXTRA_MAX_MINUTES, 30).coerceIn(1, 240)
        session = intent.getStringExtra(EXTRA_SESSION)

        if (!FFI.wooshSetSessionPassword(password)) {
            return fail(receiver, "password_rejected")
        }
        // Control needs RustDesk's accessibility input service. In firmware we
        // switch it on ourselves (WRITE_SECURE_SETTINGS). As an ordinary app we
        // can't, unless ADB granted that permission once, or staff turned the
        // service on in Settings -> Accessibility. Without it the session still
        // opens, view only, rather than failing: seeing the screen is most of
        // what support needs.
        val wantControl = intent.getStringExtra(EXTRA_MODE) != "view"
        val inputEnabled = wantControl && ensureInputService()
        if (wantControl && !inputEnabled) {
            Log.w(TAG, "session $session: input service unavailable, opening view only")
        }
        // RustDesk's own screen-capture flow. With the PROJECT_MEDIA app-op
        // pre-granted (see README, section 5) Android returns the projection
        // without showing its "start recording?" dialog.
        startActivity(
            Intent(this, PermissionRequestTransparentActivity::class.java)
                .setAction(ACT_REQUEST_MEDIA_PROJECTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        handler.removeCallbacks(timeLimit)
        handler.postDelayed(timeLimit, maxMinutes * 60_000L)

        val id = FFI.wooshGetId()
        Log.i(TAG, "session $session open as $id for up to $maxMinutes min")
        receiver?.send(RESULT_OK, Bundle().apply {
            putString("peer_id", id)
            putBoolean("input_enabled", inputEnabled)
        })
    }

    private fun stopSession(reason: String) {
        handler.removeCallbacks(timeLimit)
        // Upstream has no "disconnect every peer" call. Clearing the password
        // and stopping MainService ends capture and input, which ends the
        // session in practice; the technician's window goes black.
        FFI.wooshClearSessionPassword()
        stopService(Intent(this, MainService::class.java))
        Log.i(TAG, "session $session closed: $reason")
        // Tell the Woosh agent, so Odoo closes the session record too. Only apps
        // signed with our key receive it (the agent's receiver requires the permission).
        session?.let {
            sendBroadcast(
                Intent("au.com.wooshpay.agent.action.REMOTE_SESSION_ENDED")
                    .setPackage("au.com.wooshpay.agent")
                    .putExtra("session", it)
                    .putExtra("reason", reason),
                "au.com.wooshpay.permission.REMOTE_SESSION",
            )
        }
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Make sure RustDesk's accessibility input service is on. Already on (staff
     * enabled it in Settings) needs no permission; switching it on ourselves
     * needs WRITE_SECURE_SETTINGS (firmware, or granted once over ADB).
     */
    private fun ensureInputService(): Boolean {
        val component = "$packageName/$INPUT_SERVICE"
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val enabled = Settings.Secure.getString(contentResolver, key).orEmpty()
        val on = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (on && enabled.split(':').contains(component)) return true
        return try {
            if (!enabled.split(':').contains(component)) {
                val value = if (enabled.isEmpty()) component else "$enabled:$component"
                Settings.Secure.putString(contentResolver, key, value)
            }
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot enable input service: not privileged and not enabled in Settings", e)
            false
        }
    }

    private fun fail(receiver: ResultReceiver?, error: String) {
        Log.w(TAG, "session $session failed: $error")
        receiver?.send(RESULT_ERROR, Bundle().apply { putString("error", error) })
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun resultReceiver(intent: Intent?): ResultReceiver? =
        if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_RESULT, ResultReceiver::class.java)
        else intent?.getParcelableExtra(EXTRA_RESULT)

    private fun startForegroundCompat(sessionName: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Remote support", NotificationManager.IMPORTANCE_HIGH)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Woosh support is connected")
            .setContentText(sessionName ?: "Remote support session")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
