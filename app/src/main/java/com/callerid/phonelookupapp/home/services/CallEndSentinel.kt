package com.callerid.phonelookupapp.home.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Watches the device call state and fires [onEnded] once a call that was ringing or
 * active returns to IDLE.
 *
 * The manifest [com.callerid.phonelookupapp.home.services.onincomming.CallStateReceiver]
 * also dismisses the caller-ID card on the IDLE broadcast, but that broadcast is only
 * delivered while READ_PHONE_STATE is granted, and even then the OS can delay or drop it.
 * The card owns this listener so it disappears the moment the call is over either way.
 *
 * Two backends, picked at [start]:
 *
 *  1. **Telephony** — [TelephonyCallback.CallStateListener] (API 31+, needs
 *     READ_PHONE_STATE) or [PhoneStateListener] (below 31, no permission for the state
 *     alone). Exact, and the preferred path.
 *  2. **Audio mode** — when the telephony path is unavailable because READ_PHONE_STATE
 *     was denied. [AudioManager.getMode] needs no permission and still tracks the call:
 *     RINGTONE / CALL_SCREENING / IN_CALL while a call is up, NORMAL once it is gone.
 *     This is what keeps the card dismissable on the role-only (CallScreening) path.
 *
 * A watchdog covers the case where the card was raised for a call that never actually
 * reached the device — without it the audio-mode backend would have nothing to observe
 * and the card would sit on screen forever.
 *
 * [start]/[stop] must be called on a Looper thread (main).
 */
class CallEndSentinel(context: Context, private val onEnded: () -> Unit) {

    private val appContext = context.applicationContext
    private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var legacy: PhoneStateListener? = null
    private var modern: TelephonyCallback? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var poller: Runnable? = null

    /** Becomes true once we've seen RINGING/OFFHOOK, so the initial state isn't mistaken for an end. */
    private var sawActive = false
    private var fired = false

    /** Nothing ever went active — the call never reached us, so drop the card. */
    private val watchdog = Runnable {
        if (!sawActive) {
            Log.w(TAG, "no call activity within ${WATCHDOG_MS}ms — dismissing")
            fire()
        }
    }

    fun start() {
        if (!startTelephony()) startAudioMode()
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun stop() {
        handler.removeCallbacks(watchdog)
        poller?.let { handler.removeCallbacks(it) }
        poller = null

        val tm = tm
        if (tm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modern?.let { runCatching { tm.unregisterTelephonyCallback(it) } }
            } else {
                @Suppress("DEPRECATION")
                legacy?.let { runCatching { tm.listen(it, PhoneStateListener.LISTEN_NONE) } }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeListener?.let { l -> runCatching { audio?.removeOnModeChangedListener(l) } }
        }
        modern = null
        legacy = null
        modeListener = null
    }

    // -------------------- Backend 1: telephony --------------------

    /** @return true when a call-state listener was actually registered. */
    @SuppressLint("MissingPermission")
    private fun startTelephony(): Boolean {
        val tm = tm ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ gates CallStateListener behind READ_PHONE_STATE. Check first rather
            // than letting registration throw, so the caller-ID card raised from the
            // CallScreening role still gets a working dismissal path.
            if (!hasPhoneState()) {
                Log.d(TAG, "READ_PHONE_STATE denied — falling back to the audio-mode watcher")
                return false
            }
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleCallState(state)
            }
            runCatching { tm.registerTelephonyCallback(appContext.mainExecutor, cb) }
                .onSuccess { modern = cb }
                .isSuccess
        } else {
            // Below API 31 LISTEN_CALL_STATE carries no permission requirement of its own —
            // only the incoming number does, and we already have that from the caller.
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleCallState(state)
            }
            @Suppress("DEPRECATION")
            runCatching { tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE) }
                .onSuccess { legacy = l }
                .isSuccess
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> sawActive = true
            TelephonyManager.CALL_STATE_IDLE -> if (sawActive) fire()
        }
    }

    // -------------------- Backend 2: audio mode (no permission) --------------------

    private fun startAudioMode() {
        val audio = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val l = AudioManager.OnModeChangedListener { mode -> handleAudioMode(mode) }
            runCatching { audio.addOnModeChangedListener(appContext.mainExecutor, l) }
                .onSuccess { modeListener = l }
                .onFailure { startPolling() }
            // The mode may already have moved before we attached.
            handleAudioMode(audio.mode)
        } else {
            startPolling()
        }
    }

    private fun startPolling() {
        val audio = audio ?: return
        val r = object : Runnable {
            override fun run() {
                handleAudioMode(audio.mode)
                if (!fired) handler.postDelayed(this, POLL_MS)
            }
        }
        poller = r
        handler.post(r)
    }

    private fun handleAudioMode(mode: Int) {
        when (mode) {
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            MODE_CALL_SCREENING -> sawActive = true

            AudioManager.MODE_NORMAL -> if (sawActive) fire()
            // MODE_INVALID / MODE_CURRENT and anything else: not a verdict either way.
        }
    }

    private fun fire() {
        if (fired) return
        fired = true
        handler.removeCallbacks(watchdog)
        onEnded()
    }

    private fun hasPhoneState(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CallEndSentinel"

        /** [AudioManager.MODE_CALL_SCREENING], inlined because it only exists from API 30. */
        private const val MODE_CALL_SCREENING = 4

        private const val POLL_MS = 500L

        /**
         * How long the card may stand without the device ever entering a call mode. Long
         * enough to cover a slow ringtone start after the CallScreening response, short
         * enough that a card raised for a call that never arrived doesn't strand the user.
         */
        private const val WATCHDOG_MS = 20_000L
    }
}
