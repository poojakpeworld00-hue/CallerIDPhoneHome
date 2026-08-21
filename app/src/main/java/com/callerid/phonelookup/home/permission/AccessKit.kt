package com.callerid.phonelookup.home.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.callerid.adcast.domain.AdsVault

/**
 * Central registry + grant helpers for the Permission Engine.
 *
 * [CATALOG] is the single source of truth for which OS permissions the engine
 * can request. Adding a new permission = adding one line here (future-proof).
 */
object AccessKit {

    /**
     * Registry of supported permissions, keyed by the Remote Config key.
     *
     * `minSdk` is the SDK level at/above which the permission is a *runtime*
     * permission. Below that level the OS grants it at install time, so the
     * engine treats it as already-granted and never prompts.
     */
    val CATALOG: Map<String, AccessSpec> = listOf(
        AccessSpec(
            key = "notification",
            androidPermission = Manifest.permission.POST_NOTIFICATIONS,
            minSdk = Build.VERSION_CODES.TIRAMISU, // 33
        ),
        AccessSpec(
            key = "phone_state",
            androidPermission = Manifest.permission.READ_PHONE_STATE,
            minSdk = Build.VERSION_CODES.M, // 23
            // Respect AdBeaconActivity's geo gate: READ_PHONE_STATE is only asked
            // when HD_VBC_Show is true (it is forced false in allow-listed
            // regions during the splash config flow).
            enabledPrefGate = "HD_VBC_Show",
        ),
        AccessSpec(
            key = "call_log",
            androidPermission = Manifest.permission.READ_CALL_LOG,
            minSdk = Build.VERSION_CODES.M,
        ),
        AccessSpec(
            key = "contacts",
            androidPermission = Manifest.permission.READ_CONTACTS,
            minSdk = Build.VERSION_CODES.M,
        ),
    ).associateBy { it.key }

    /** Returns the spec for a Remote Config key, or null if the key is unknown. */
    fun spec(key: String): AccessSpec? = CATALOG[key]

    /** True when this permission is even applicable on the current OS version. */
    fun isApplicableOnThisSdk(spec: AccessSpec): Boolean =
        Build.VERSION.SDK_INT >= spec.minSdk

    /**
     * True when the spec's optional business gate allows requesting it. A spec
     * with no [AccessSpec.enabledPrefGate] is always allowed; otherwise the
     * named `AdsVault` boolean must be true (defaults to false when unset).
     */
    fun isPrefGateOpen(context: Context, spec: AccessSpec): Boolean {
        val gate = spec.enabledPrefGate ?: return true
        return AdsVault.getInstance(context).getBoolean(gate)
    }

    /**
     * True when the engine is allowed to offer [key] at all on this device right now:
     * the key is known, the permission is still a runtime one on this SDK, its business
     * gate is open, and Remote Config has not switched the rule off.
     *
     * This is the same set of gates [AccessEngine.request] applies before it will ask —
     * including `enabled`, which is the remote off-switch. UI that *lists* engine-managed
     * permissions must consult this rather than re-deriving a subset of the gates, or it
     * ends up showing a row for a permission the engine will silently refuse to request:
     * the Allow button does nothing and the row never clears.
     *
     * A missing rule means Remote Config says nothing about this key, so the spec alone
     * decides — matching [AccessEngine.request].
     */
    fun isOfferable(context: Context, key: String): Boolean {
        val spec = spec(key) ?: return false
        if (!isApplicableOnThisSdk(spec)) return false
        if (!isPrefGateOpen(context, spec)) return false
        val rule = runCatching { AccessSource.rules().firstOrNull { it.key == key } }.getOrNull()
        return rule == null || rule.enabled
    }

    /**
     * True when the permission is already granted (or not required on this SDK).
     * Callers should skip requesting when this returns true.
     */
    fun isGranted(context: Context, spec: AccessSpec): Boolean {
        if (Build.VERSION.SDK_INT < spec.minSdk) return true
        return ContextCompat.checkSelfPermission(context, spec.androidPermission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
