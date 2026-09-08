package com.callerid.phonelookupapp.home.services

import android.content.Context
import android.content.res.ColorStateList
import android.telephony.TelephonyManager
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.callerid.phonelookupapp.home.R
import com.callerid.phonelookupapp.home.data.CallLogSource
import com.callerid.phonelookupapp.home.data.lookup.CallerDirectory
import com.callerid.phonelookupapp.home.data.PeopleSource
import com.callerid.phonelookupapp.home.ui.common.CallPresenter
import com.callerid.phonelookupapp.home.ui.lookup.DigitInfo
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Resolves caller details and renders them into [R.layout.piece_caller_id].
 *
 * Shared by [com.callerid.phonelookupapp.home.services.onincomming.IdentFloatService] (floating window, device unlocked) and
 * IncomingRingActivity (full screen, device locked) so the card looks and reads
 * identically in both states.
 */
object IdentCard {

    /** The bits we surface on the card; resolved off the main thread. */
    data class Info(
        val name: String?,
        val known: Boolean,
        val callCount: Int,
        val network: String?,
        /**
         * How the caller-ID network identifies this number, or null when it has never been
         * asked ([enrich] has not run) or the network has no name for it.
         */
        val reportedName: String? = null,
        val isSpam: Boolean = false,
    )

    /**
     * Blocking lookup — call from a background thread.
     * Combines the contact name, how many times this number appears in the call
     * log, and the SIM operator name.
     *
     * Deliberately offline, and deliberately first: this is everything the card can show
     * without waiting on a server, and a ringing phone cannot wait. [enrich] adds the
     * network's answer afterwards.
     */
    fun resolve(context: Context, number: String): Info {
        val name = runCatching { PeopleSource(context).lookupNameByNumber(number) }.getOrNull()

        val callCount = runCatching {
            val target = digitsTail(number)
            CallLogSource(context).getCalls(limit = 2000)
                .count { digitsTail(it.number) == target }
        }.getOrDefault(0)

        val network = runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.networkOperatorName?.takeIf { it.isNotBlank() }
        }.getOrNull()

        return Info(name = name, known = !name.isNullOrBlank(), callCount = callCount, network = network)
    }

    /**
     * Asks the caller-ID network who [number] belongs to and folds the answer into [local].
     *
     * The same endpoint and the same records the Lookup screen searches, through
     * [CallerDirectory] — so a number identified there is identified here too.
     *
     * Two deliberate differences from the Lookup screen:
     *
     *  - **A saved contact is never looked up.** Lookup prefers the network's name on purpose,
     *    to show how *others* identify a number you already named. At ring time that is the
     *    wrong answer: you want to see "Mom", not what strangers filed her under. A contact is
     *    also not what the spam list is for, so the whole round trip is skipped — no data, no
     *    delay, on the calls that need neither.
     *  - **It is time-boxed.** OkHttp's own timeouts are generous enough that an answer could
     *    land after the call is over; [LOOKUP_TIMEOUT_MS] gives up while the result could still
     *    matter and leaves the offline card standing.
     *
     * Returns [local] unchanged whenever there is nothing to add, so callers can skip a
     * pointless re-bind by identity.
     */
    suspend fun enrich(context: Context, number: String, local: Info): Info {
        if (local.known) return local

        // On IO for the E.164 step as much as the request: PhoneNumberUtil.getInstance() loads
        // its metadata on first touch, and the first touch in this process is very often right
        // here, on a ringing phone, from the main thread.
        val records = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { CallerDirectory.lookup(toE164(context, number)) }
        }.orEmpty()
        if (records.isEmpty()) return local

        val reported = records.firstNotNullOfOrNull { it.name?.trim()?.takeIf { n -> n.isNotBlank() } }
        // Either flag counts: is_spam is the network's own verdict, is_user_spam is what
        // other people reported. The Lookup screen treats them the same way.
        val spam = records.any { it.is_spam || it.is_user_spam }

        if (reported == null && !spam) return local
        return local.copy(reportedName = reported, isSpam = spam)
    }

    /**
     * Binds [number] + resolved [info] into an inflated overlay card [root].
     *
     * Safe to call twice for one call — once with the offline [resolve] result and again with
     * the [enrich]ed one — which is exactly how the card fills in without making the caller
     * wait for the network.
     */
    fun bind(context: Context, root: View, number: String, info: Info) {
        // Your own contact name wins; the network's name is what turns "Unknown" into
        // something useful. Neither is a reason to keep showing "Unknown".
        val resolvedName = info.name?.takeIf { it.isNotBlank() }
            ?: info.reportedName?.takeIf { it.isNotBlank() }
        val displayName = resolvedName ?: context.getString(R.string.incall_unknown)

        root.findViewById<TextView>(R.id.tvIncallAvatar).text =
            CallPresenter.initials(resolvedName, number)
        root.findViewById<TextView>(R.id.tvIncallName).text = displayName
        root.findViewById<TextView>(R.id.tvIncallNumber).text = number

        bindStatusPill(context, root.findViewById(R.id.tvIncallStatus), info)

        root.findViewById<TextView>(R.id.tvIncallWhen).text =
            context.getString(R.string.incall_now)
        root.findViewById<TextView>(R.id.tvIncallCalls).text =
            context.getString(R.string.incall_calls, info.callCount)
        root.findViewById<TextView>(R.id.tvIncallNetwork).text =
            info.network?.takeIf { it.isNotBlank() } ?: "—"
    }

    /**
     * Red "Spam risk", green "Known Contact", or neutral "Unknown".
     *
     * Spam outranks everything the pill could otherwise say — it is the one verdict worth
     * interrupting someone mid-ring for. It can only ever apply to a number that is not in
     * your contacts, because [enrich] does not look those up.
     */
    private fun bindStatusPill(context: Context, pill: TextView, info: Info) {
        val known = info.known
        val textRes = when {
            info.isSpam -> R.string.lookup_spam_risk
            known -> R.string.incall_known
            else -> R.string.incall_unknown
        }
        val fgRes = when {
            info.isSpam -> R.color.danger
            known -> R.color.success
            else -> R.color.on_surface_variant
        }
        val bgRes = when {
            info.isSpam -> R.color.danger_soft
            known -> R.color.success_soft
            else -> R.color.neutral_soft
        }
        val iconRes = when {
            info.isSpam -> R.drawable.glyph_warning
            known -> R.drawable.glyph_verified
            else -> R.drawable.glyph_info
        }

        val fg = ContextCompat.getColor(context, fgRes)
        // Always a string resource, never the server's own spamType text: this pill has no
        // maxLines and the card is narrow, and a server-supplied label would be untranslated
        // in the ten locales this app ships. SearchBriefActivity draws its pill the same way.
        pill.setText(textRes)
        pill.setTextColor(fg)
        pill.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, bgRes))
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        TextViewCompat.setCompoundDrawableTintList(pill, ColorStateList.valueOf(fg))
    }

    /** Last 9 digits — tolerant comparison that ignores country code / formatting. */
    private fun digitsTail(number: String): String =
        number.filter { it.isDigit() }.takeLast(9)

    /**
     * The number in E.164 (`+<country><subscriber>`), which is the form the Lookup screen
     * sends and therefore the only form the endpoint is known to match.
     *
     * This is not cosmetic. A domestic call arrives from telephony in national form —
     * `07016414568`, trunk prefix and all — and sending that verbatim asks the server about a
     * number that does not exist. The Lookup screen never has the problem because its country
     * picker supplies the dialing code; here the SIM stands in for the picker, and
     * libphonenumber does the rest (it knows to drop the trunk prefix, which naive
     * concatenation does not).
     *
     * Falls back to the number as-is whenever it cannot do better — a lookup that misses is
     * no worse than the no-lookup behaviour this replaced.
     */
    private fun toE164(context: Context, raw: String): String {
        val normalized = DigitInfo.normalize(raw)
        if (normalized.startsWith("+")) return normalized
        val region = deviceRegion(context) ?: return normalized
        return runCatching {
            val util = PhoneNumberUtil.getInstance()
            util.format(util.parse(normalized, region), PhoneNumberUtil.PhoneNumberFormat.E164)
        }.getOrDefault(normalized)
    }

    /**
     * The region to read a national number against: the SIM's country, then the network's,
     * then the device locale. None of the three needs a runtime permission, which matters —
     * the card has to work on the CallScreening-role path where READ_PHONE_STATE was refused.
     */
    private fun deviceRegion(context: Context): String? = runCatching {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        (tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country.takeIf { it.isNotBlank() })
            ?.uppercase(Locale.US)
    }.getOrNull()

    /**
     * How long [enrich] waits on the network before giving up and leaving the offline card.
     *
     * Sized against the ring, not against the request: a name that arrives after the user has
     * already answered or declined has not identified anybody.
     */
    private const val LOOKUP_TIMEOUT_MS = 4_000L
}
