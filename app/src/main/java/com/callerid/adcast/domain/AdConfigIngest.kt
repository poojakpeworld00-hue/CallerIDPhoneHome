package com.callerid.adcast.domain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.callerid.adcast.presentation.CustomAdsRegistry
import com.callerid.phonelookupapp.home.BuildConfig
import com.callerid.phonelookupapp.home.util.NATIVE_THEME_KEY
import com.callerid.phonelookupapp.home.util.applyNativeAdTheme
import com.callerid.phonelookupapp.home.util.nativeThemeMode
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import org.json.JSONObject

/**
 * Reads a getData blob into [AdsVault].
 *
 * Lifted out of AdBeaconActivity so it is not tied to the splash: the same ingest has to run
 * when Remote Config pushes a change to a running app (see LiveConfigWatcher), and duplicating
 * it would leave two lists of keys to keep in step.
 *
 * Facebook SDK initialisation stays with the caller — it needs an Activity and only makes
 * sense once per process — so [ingest] hands the credentials back instead of applying them.
 */
object AdConfigIngest {

    private const val CONFIG_TAG = "AdConfig"

    /**
     * The blob parameter this build reads. Debug and release are separate on purpose.
     *
     * `_1`: the placement-based ad flow reads a schema the builds already in users' hands do not
     * understand, so it lives in its own parameters and the old `GET_DATA_LIST` /
     * `DEBUG_GET_DATA_LIST` keep serving those builds untouched. Every reader of the blob goes
     * through [readBlob].
     */
    val blobKey: String get() = if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST_1" else "GET_DATA_LIST_1"

    private val legacyBlobKey: String get() = if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST" else "GET_DATA_LIST"

    /** Whether a Remote Config key is the blob this build reads (new or legacy). */
    fun isBlobKey(key: String): Boolean = key == blobKey || key == legacyBlobKey

    /**
     * The activated blob and the key it came from. Falls back to the legacy key while the `_1`
     * parameter is not published yet, so a new build is never left on code defaults (every ad
     * off) just because the console has not caught up. Empty when neither has anything — the
     * caller then keeps whatever an earlier run cached.
     */
    fun readBlob(remoteConfig: FirebaseRemoteConfig): Pair<String, String> {
        val raw = runCatching { remoteConfig.getString(blobKey) }.getOrDefault("")
        if (raw.isNotBlank()) return blobKey to raw
        val legacy = runCatching { remoteConfig.getString(legacyBlobKey) }.getOrDefault("")
        if (legacy.isNotBlank()) Log.w(CONFIG_TAG, "$blobKey is empty → falling back to $legacyBlobKey")
        return legacyBlobKey to legacy
    }

    /** The Facebook credentials found in the blob; empty when the blob carries none. */
    data class FacebookKeys(val appId: String, val clientToken: String) {
        val usable: Boolean get() = appId.isNotEmpty() && clientToken.isNotEmpty()
    }

    /**
     * Reads every getData key from [root] into AdsVault (batched). [root] is
     * either the flat response or one of its `marketing` / `organic` sub-objects
     * (see [audienceRoot]). Safe to call again (funOnAdsLoad re-applies the correct
     * audience once the referrer settles OnMaketing).
     */
    fun ingest(context: Context, root: JSONObject): FacebookKeys {
        val adsPref = AdsVault.getInstance(context)
        adsPref.update {
            // --- Booleans ---
            listOf(
                "IsAdsON", "IsFail_FB", "isLoaderForFB", "inter_loader", "IsCustomADS", "IsBack",
                "NativeBanner", "BannerAds", "In_App_Update_Show", "In_App_Update_Force_Show",
                "Iscountry_Counter", "Iscountry_Marketing_Counter", "HD_VBC_Show",
                "HD_VBC_Native", "is_preload_ads",
                "is_splash_inter_show", "is_splash_ads", "InterAds", "AppopenAds",
                "NativeAd", "is_rateus", "is_share", "Perm_Sheet_Show",
                "screen_wise_ad", "screen_wise_default",
                // First-session Recents → Play Store home (RecentAdWatcher).
                "recent_playstore",
                // App-wide "display over other apps" prompts on/off (FloatKit). Absent = on.
                "Overlay_Permission_Show",
                // Interstitial loader on/off (FullScreenSpinner). Absent = isLoaderForFB.
                "Inter_Loader_Show"
            ).forEach { key -> if (root.has(key)) putBoolean(key, root.optBoolean(key, false)) }

            // The reference config's names for the same two switches, so its config pastes across
            // unchanged. This app's name wins when both are present.
            mapOf("BannerAdPresenter" to "BannerAds", "NativeBannerPresenter" to "NativeBanner")
                .forEach { (alias, key) ->
                    if (root.has(alias) && !root.has(key)) putBoolean(key, root.optBoolean(alias, false))
                }

            // --- Strings ---
            listOf(
                "IsAdType", "In_App_Update_Link", "CountryList_Counter_NShow",
                "CountryList_Marketing_Counter_NShow", "PrivacyPolicy", "TermLink",
                "DirectLink", "MarketLink", "HD_VBC_Native_ID", "HD_VBC_Banner_ID",
                "googleS_Inter", "googleBackInter", "googleInter", "googleAppopen",
                "googleNative", "googleBanner", "googleRewarded", "faceB_InterAds",
                "faceB_NativeAds", "faceB_NativeBannerAds", "faceB_BannerAds",
                // NativeTheme is not here: storeNativeTheme resolves it, and the four
                // Native*Color keys are only ever derived from it (applyNativeAdTheme).
                "HD_VBC_Type", "Perm_Sheet_Mode",
                // API origin — see RetrofitClient, which falls back to its compiled-in default
                // when this is absent or malformed. Renamed from `api_base_url` with the move to
                // contact-saver, so a config still carrying the old key cannot pin the retired host.
                "contacts_base_url",
                // Where onboarding ends: "app" = this app's own home, else the launcher home.
                "onboarding_home",
                // Nested JSON objects stored as text (read back via JSONObject).
                "intro_display", "ScreenAds", "launcher_ads",
                // App Home's game-quiz icon (QuizIcon).
                "quiz_icon",
                // The :launcher module's own config; CallerLauncherBridge overlays it on the
                // top-level Remote Config parameter of the same name.
                "launcher_config",
                // Every placement's own ad settings, nested (LauncherPlacementAds).
                LauncherPlacementAds.PLACEMENTS_KEY
            ).forEach { key -> if (root.has(key)) putString(key, root.optString(key, "")) }

            // How a direct link opens (webview / custom_tab / browser) is one setting, `link_open_in`.
            // DirectLinkOpener reads it from the flat `DirectLinkType` pref, so that pref follows it;
            // a config that still sends only `DirectLinkType` is stored as before.
            if (root.has("DirectLinkType")) putString("DirectLinkType", root.optString("DirectLinkType", ""))
            if (root.has("link_open_in")) putString("DirectLinkType", root.optString("link_open_in", ""))

            // The launcher's per-placement ad keys (`leftPanel_googleInter`, `drawer_link_first_then`,
            // …) and the link-first switches. Always stored as strings, whatever their JSON type, so
            // a key that is a boolean in one config and a string in the next never clashes in prefs.
            root.keys().forEach { key ->
                if (LauncherPlacementAds.isPlacementKey(key)) putString(key, root.optString(key, ""))
            }

            // --- Integers ---
            listOf(
                "InterCounter", "InterBackCounter", "MarketInterCounter", "MarketBackCounter",
                "NativeCounter", "MarketNativeCounter", "MidNativeCounter", "BannerCounter",
                "MarketBannerCounter", "MarketAppopenCounter", "AppopenCounter",
                "Perm_Sheet_Interval_Days", "HD_VBC_Hrs",
                // Backstop-fetch window for LiveConfigWatcher, in hours. 0 = fetch on every
                // foreground (testing only); absent falls back to its own default.
                "Config_Sync_Hrs",
                "recent_playstore_window_sec",
                // Loader beat before a preloaded interstitial, in ms (FullScreenSpinner). 0 = none.
                "Inter_Loader_Ms"
            ).forEach { key -> if (root.has(key)) putInt(key, root.optInt(key, 0)) }

            storeNativeTheme(this, adsPref, root)

            // --- Custom Ads ---
            val customAdsArray = root.optJSONArray("custom_ads")
            if (customAdsArray != null) {
                putString("CUSTOM_ADS", customAdsArray.toString())
                CustomAdsRegistry.clearCache()
            }
        }
        // After the batch above is applied, so it reads the palette just stored.
        context.applyNativeAdTheme()

        // Facebook Ad initialization parameters
        val fbAppId = root.optString("FbAppId", "")
        val fbClientToken = root.optString("FbClientToken", "")

        if (BuildConfig.DEBUG) Log.d(
            CONFIG_TAG,
            "ingested → IsAdsON=${adsPref.getBoolean("IsAdsON")}, IsAdType=${adsPref.getString("IsAdType")}, " +
                "InterAds=${adsPref.getBoolean("InterAds")}, AppopenAds=${adsPref.getBoolean("AppopenAds")}, " +
                "NativeAd=${adsPref.getBoolean("NativeAd")}, BannerAds=${adsPref.getBoolean("BannerPromo")}, " +
                "HD_VBC_Show=${adsPref.getBoolean("HD_VBC_Show")}, HD_VBC_Hrs=${adsPref.getInt("HD_VBC_Hrs")}, " +
                "screen_wise_ad=${adsPref.getBoolean("screen_wise_ad")}, " +
                "customAds=${root.optJSONArray("custom_ads")?.length() ?: 0}, " +
                "fbInit=${fbAppId.isNotEmpty() && fbClientToken.isNotEmpty()}, " +
                "appOpenId=${adsPref.getString("googleAppopen")}"
        )

        return FacebookKeys(fbAppId, fbClientToken)
    }

    /**
     * The audience-specific sub-object of a getData response — `marketing` or
     * `organic` per [isMarketing], falling back to the other audience, then to the
     * flat [response] itself (legacy, un-split config → unchanged behaviour).
     */
    fun audienceRoot(response: JSONObject, isMarketing: Boolean): JSONObject {
        val preferred = if (isMarketing) "marketing" else "organic"
        val fallback = if (isMarketing) "organic" else "marketing"
        response.optJSONObject(preferred)?.let {
            if (BuildConfig.DEBUG) Log.d(CONFIG_TAG, "audienceRoot → using '$preferred' segment")
            return it
        }
        response.optJSONObject(fallback)?.let {
            if (BuildConfig.DEBUG) Log.d(CONFIG_TAG, "audienceRoot → '$preferred' missing, fell back to '$fallback' segment")
            return it
        }
        if (BuildConfig.DEBUG) Log.d(CONFIG_TAG, "audienceRoot → no marketing/organic wrapper, using flat config")
        return response
    }

    /** `NativeLight` / `NativeDark`, per the user's theme choice. */
    fun nativeThemeKey(context: Context): String = context.nativeThemeMode()

    /**
     * Stores [root]'s native-ad palette under [NATIVE_THEME_KEY]. [root] is already the user's
     * audience block, so its `NativeTheme` is that audience's own palette:
     * `{ "NativeLight": {…}, "NativeDark": {…} }` — organic and marketing are configured apart.
     *
     * A config still in the old shape (`NativeTheme: { "marketing": {…}, "default": {…} }`, the
     * same two palettes repeated in both audiences) is reduced to the same thing here: marketing
     * users take `marketing` (else `default`), organic users `default`.
     */
    private fun storeNativeTheme(editor: SharedPreferences.Editor, adsPref: AdsVault, root: JSONObject) {
        val theme = root.optJSONObject(NATIVE_THEME_KEY) ?: return
        val onMarketing = adsPref.getBoolean("OnMaketing")
        val palette = if (theme.has("NativeLight") || theme.has("NativeDark")) {
            theme
        } else {
            val legacy = if (onMarketing) theme.optJSONObject("marketing") ?: theme.optJSONObject("default")
            else theme.optJSONObject("default")
            if (BuildConfig.DEBUG) Log.d(CONFIG_TAG, "native theme: legacy marketing/default wrapper")
            legacy ?: return
        }
        editor.putString(NATIVE_THEME_KEY, palette.toString())
        // The per-audience copies the old shape kept; nothing reads them any more.
        editor.remove("NativeTheme_marketing")
        editor.remove("NativeTheme_default")
        if (BuildConfig.DEBUG) Log.d(CONFIG_TAG, "native theme ← ${if (onMarketing) "marketing" else "organic"} palette")
    }
}
