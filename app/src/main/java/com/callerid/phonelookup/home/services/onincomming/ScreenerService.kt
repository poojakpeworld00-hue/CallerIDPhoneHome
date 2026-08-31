package com.callerid.phonelookup.home.services.onincomming

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.callerid.phonelookup.home.data.BlockRosterRegistry

/**
 * Screens incoming calls while the app holds the CallScreening role (Android 10+,
 * granted from Settings as the default "Caller ID & spam" app). It does two jobs:
 *
 *  - **Blocking** — silently rejects blocked numbers *before* they ring. Unlike the
 *    PHONE_STATE receiver's endCall() fallback, the call never rings through.
 *  - **Caller-ID card** — raises [IdentFloatService] for the incoming number.
 *
 * The card is raised here and not only from [CallStateReceiver] because this is the
 * *only* path that survives a denied READ_PHONE_STATE: the ACTION_PHONE_STATE_CHANGED
 * broadcast is sent with READ_PHONE_STATE as its receiver permission, so with the
 * runtime grant withheld CallStateReceiver never fires at all. [onScreenCall] carries
 * the number itself, needs neither READ_PHONE_STATE nor READ_CALL_LOG, and holding the
 * role is itself the background-activity-start / FGS-start exemption the card needs.
 *
 * Two consequences worth knowing:
 *  - The system only screens numbers that are **not** in the user's contacts, so a known
 *    caller reaches [CallStateReceiver] or nothing at all.
 *  - [onScreenCall] fires once and the service unbinds; there is no answer/end callback
 *    here. Dismissal is [com.callerid.phonelookup.home.services.CallEndSentinel]'s job,
 *    which falls back to the permission-free audio-mode watcher on this path.
 */
class ScreenerService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        } else true

        val number = callDetails.handle?.schemeSpecificPart // tel: number
        val block = isIncoming && !number.isNullOrBlank() &&
                BlockRosterRegistry(this).isBlocked(number)

        if (block) Log.d(TAG, "blocked incoming call screened: $number")

        val response = CallResponse.Builder()
            .setDisallowCall(block)   // don't let the call through
            .setRejectCall(block)     // hang up immediately
            .setSkipCallLog(false)    // still record it in the call log
            .setSkipNotification(block) // no missed-call notification for blocked
            .build()

        // Respond first — the system only allows a few seconds before it decides for us.
        respondToCall(callDetails, response)

        if (block || !isIncoming || number.isNullOrBlank()) return
        // No SYSTEM_ALERT_WINDOW check: the app does not ask for the overlay any more
        // (see FloatKit.ASK_FOR_OVERLAY), and IdentFloatService falls back to the
        // full-screen activity, started on the role's own background-activity-start
        // exemption. Bailing out here left a role-holding user with no card at all.
        Log.d(TAG, "raising caller-ID card from screening: $number")
        IdentFloatService.start(this, number)
    }

    companion object {
        private const val TAG = "CallScreening"
    }
}
