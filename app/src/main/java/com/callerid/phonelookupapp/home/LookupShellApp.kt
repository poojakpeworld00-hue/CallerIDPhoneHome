package com.callerid.phonelookupapp.home

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetHost
import android.content.Context
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.callerid.adcast.data.AdKind
import com.callerid.adcast.domain.AdsVault
import com.callerid.adcast.domain.LauncherAdsConfig
import com.callerid.adcast.domain.LauncherPlacementAds
import com.callerid.adcast.presentation.oninterAds.InterstitialNormal
import com.callerid.adcast.presentation.AdBeaconActivity
import com.callerid.adcast.presentation.AppOpenAdRegistry
import com.callerid.adcast.presentation.AppOpenAdRegistry.isAdAvailable
import com.callerid.adcast.presentation.my_main_counter.My_Shell_Screen
import com.callerid.phonelookupapp.home.launcher.AppExitAd
import com.callerid.phonelookupapp.home.launcher.CallerLauncherAds
import com.callerid.phonelookupapp.home.launcher.CallerLauncherBridge
import com.callerid.phonelookupapp.home.launcher.UnlockAdWatcher
import com.callerid.phonelookupapp.home.ui.charging.ChargeEventWatcher
import com.callerid.phonelookupapp.home.ui.pkgresult.PackageEventWatcher
import com.callerid.phonelookupapp.home.ui.recent.RecentAdWatcher
import io.launcher.home.activities.LauncherPanel as LauncherHomeActivity
import io.launcher.home.api.LauncherRegistry
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.helpers.BaseConfig
import com.callerid.phonelookupapp.home.permission.AccessEngine
import com.callerid.phonelookupapp.home.ui.splash.StartupActivity
import com.callerid.phonelookupapp.home.util.CrashGuard
import com.callerid.phonelookupapp.home.util.GuardRail
import io.lighthouse.push.LightHouse
import io.lighthouse.push.LightHouseConfig
import io.lighthouse.push.extended.LightHouseRichPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fossify.commons.helpers.SIDELOADING_FALSE
import com.callerid.adcast.domain.LiveConfigWatcher

class LookupShellApp : Application() , Application.ActivityLifecycleCallbacks,
    LifecycleObserver{
    private var currentActivity: Activity? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** Application context, set in [onCreate] — used where only a Context is needed
         *  (e.g. building the OkHttp client's Chucker interceptor). */
        lateinit var appContext: Context
            private set

        // One-time cleanup of the in-app launcher the :launcher module replaced.
        private const val LEGACY_LAUNCHER_DROPPED = "legacy_launcher_dropped_v1"
        private const val LEGACY_WIDGET_HOST_ID = 12345
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        AdsVault.getInstance(this)

        // App install / removal → the result screen, shown from whichever of our screens is
        // foreground (queued otherwise). Also seeds the package metadata cache.
        PackageEventWatcher.register(this)

        // Charger plugged in / pulled out → the charging screen. Inert unless `system_ads.charge` /
        // `discharge` is enabled in Remote Config.
        ChargeEventWatcher.register(this)

        // An ad after unlock, on our own home. Inert unless `launcher_config.unlock_ads.enabled`.
        UnlockAdWatcher.register(this)

        // The app reopened from the Recents list. Inert unless `recent_ad.enabled` is on in
        // Remote Config — registering it costs a dormant install almost nothing.
        RecentAdWatcher.register(this)

        // Fossify Commons runs an anti-clone heuristic that probes one of its own drawable ids
        // and, on a lookup miss, wedges the app behind a permanent "download the original"
        // dialog. This is a legitimate rebuild of Fossify's GPL sources, so the check is a false
        // positive here — recording the result up front means the probe never runs. It has to be
        // in onCreate rather than attachBaseContext: Context.config is not safe to read earlier.
        BaseConfig.newInstance(this).appSideloadingStatus = SIDELOADING_FALSE

        dropLegacyLauncherState()
        // Home grid, drawer and side panels come from the :launcher module; its gesture ads and
        // inline slots are served by this app's own ad layer (CallerLauncherAds). The right-hand
        // panel hosts this app's own home (CallerLauncherBridge.panelFragment).
        LauncherRegistry.install(
            context = this,
            bridge = CallerLauncherBridge(),
            ads = CallerLauncherAds(),
        )

        // Register the splash + rich-push activities so the SDK can forward a
        // push-launched cold start from the splash (see StartupActivity.handleFromSplash).
        LightHouseRichPush.setActivities(
            splashActivity = StartupActivity::class.java,
            richPushActivity = My_Shell_Screen::class.java,
        )
        LightHouse.initialize(
            context = this,
            config = LightHouseConfig(
                apiKey = Scrambled.s(BuildConfig.LH_API_KEY),
                baseUrl = Scrambled.s(BuildConfig.LH_BASE_URL),
                // How long the audience gate waits for the Play install-referrer verdict
                // before settling for what it has. First launch only — the SDK caches it
                // afterwards. One number for every attribution wait in the app: the
                // disclosure gate and AdBeaconActivity's audience read both use it.
                attributionWaitMs = AdBeaconActivity.ATTRIBUTION_WAIT_MS,
                richPushActivity = My_Shell_Screen::class.java,
            ),
        )
        // A sideloaded build has no Play install referrer, so the SDK would classify it
        // organic while AdBeaconActivity runs whichever half DEBUG_AUDIENCE_MARKETING picks.
        // Forcing the SDK to the same side keeps the disclosure screen and the config under
        // test in agreement.
        //
        // Gated on AdBeaconActivity.isAudienceForced, not on BuildConfig.DEBUG: a release APK
        // needs the same treatment to be testable against the paid audience, and both sides
        // of the decision have to be driven by one switch or they disagree. Real attribution
        // stands again the moment FORCE_AUDIENCE_IN_RELEASE goes back to false.
        //
        // The forced source alone is not enough for MARKETING: a device the SDK has flagged as a
        // reinstall / cross-app install is classified ORGANIC *before* the forced source is
        // consulted ("installSource → ORGANIC (device flagged …)" under LH_Disclosure). On any
        // phone that has had this app installed before — every test device — that silently turns
        // the pinned MARKETING audience into an organic disclosure decision, and the organic
        // disclosure spec is the one that shows the consent screen. Clearing the flag first makes
        // the forced source win, so the SDK and AdBeaconActivity settle on the same audience.
        if (AdBeaconActivity.isAudienceForced) {
            if (AdBeaconActivity.DEBUG_AUDIENCE_MARKETING) LightHouse.debugForceFlagged(false)
            LightHouse.debugForceInstallSource(
                if (AdBeaconActivity.DEBUG_AUDIENCE_MARKETING) "paid" else "organic"
            )
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                FirebaseApp.initializeApp(this@LookupShellApp)
                // Global permission engine — fetches the latest `permission_engine`
                // Remote Config so every screen can be gated dynamically. Requires
                // FirebaseApp to be initialised first (above).
                // No subscribeAsync() here: StartupActivity does it from the
                // ensureDataDisclosure callback, which is the one place that knows the
                // user has acknowledged the disclosure. Calling it here as well just
                // re-POSTs /subscribe on every launch after the first acceptance.
                AccessEngine.init(this@LookupShellApp)
            } catch (e: Exception) {
                GuardRail.log("CallerPhoneLookApp", "LightHouse init failed: ${e.message}")
            }
        }

        registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    handleAppForeground()
                    // Remote Config follows the PROCESS, not one screen and not onCreate.
                    //
                    // Started once at startup the channel was a live server connection that
                    // outlived every backgrounding; hung off a screen it would only ever
                    // reach a user sitting on that screen. Owning it here covers every
                    // screen: live for as long as the app is foreground, gone with it.
                    LiveConfigWatcher.start(this@LookupShellApp)
                    // Backstop for the push channel (offline when the template was published,
                    // or a device it never reached). Throttled by `Config_Sync_Hrs`, so
                    // repeat foregrounds inside the window cost nothing; a zero window means
                    // every foreground fetches.
                    LiveConfigWatcher.refreshIfStale(this@LookupShellApp)
                }

                override fun onStop(owner: LifecycleOwner) {
                    // A live server connection has no business outliving the foreground.
                    LiveConfigWatcher.stop()
                }
            }
        )

        // Last: it wraps whichever UncaughtExceptionHandler is already installed (Crashlytics',
        // via Firebase's init provider — content providers are created before this method).
        // Process lifecycle, not currentActivity, is the foreground signal: currentActivity is
        // kept for the app-open ad and is only cleared on destroy, so it stays set while the app
        // sits in the background.
        CrashGuard.install(this) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
    }

    /**
     * One-time, on the first start after the in-app launcher was replaced by the :launcher module.
     *
     * The old launcher kept its home grid in a hand-rolled SQLite `apps.db` and marked it built
     * with `was_home_screen_init` in fossify's shared prefs. The module uses the same file name for
     * its Room database and the same pref key, so left alone Room would try to adopt a schema it did
     * not create and the module would skip seeding a grid it never built.
     */
    private fun dropLegacyLauncherState() {
        val prefs = getSharedPrefs()
        if (prefs.getBoolean(LEGACY_LAUNCHER_DROPPED, false)) return
        deleteDatabase("apps.db")
        // Both launchers bind widgets under host id 12345. The ids the old one allocated are still
        // held by the system with no grid row pointing at them; release them all so the module
        // starts on a clean host.
        runCatching { AppWidgetHost(this, LEGACY_WIDGET_HOST_ID).deleteHost() }
        prefs.edit()
            .remove("was_home_screen_init")
            // Grid sizes come from the module's OEM layout match on first run, not the old defaults.
            .remove("home_column_count")
            .remove("home_row_count")
            .remove("drawer_column_count")
            .putBoolean(LEGACY_LAUNCHER_DROPPED, true)
            .apply()
    }

    // ---------------- APP FOREGROUND ----------------
    // --------------------------------------------------
    // APP FOREGROUND HANDLER (APP OPEN AD)
    // --------------------------------------------------
    private fun handleAppForeground() {

        GuardRail.log("AppOpen", "handleAppForeground() called")

        val activity = currentActivity
        if (activity == null) {
            GuardRail.log("AppOpen", "❌ No RESUMED activity")
            return
        }

        GuardRail.log("AppOpen", "Activity = ${activity::class.java.simpleName}")

        if (activity.isFinishing || activity.isDestroyed) {
            GuardRail.log("AppOpen", "❌ Activity invalid")
            return
        }

        // The splash owns its own ad path and must never be monetised on foreground.
        if (activity is StartupActivity) {
            GuardRail.log("AppOpen", "⛔ Excluded screen (splash)")
            return
        }

        // One-shot skip for app-initiated returns (e.g. the overlay-permission
        // flow opens system Settings itself — that return must not be monetised).
        if (AppOpenAdRegistry.skipNextAppOpenAd) {
            AppOpenAdRegistry.skipNextAppOpenAd = false
            // A programmatic return is not monetised, so drop the launch flag too.
            AppOpenAdRegistry.consumeExpectReturnAd()
            GuardRail.log("AppOpen", "⛔ Skipped (app-initiated settings return)")
            return
        }

        // Coming back from an app the launcher just opened is the one time an ad shows on the
        // launcher home: `gestures.app_exit` on → the app-exit flow on the `appExit` placement;
        // off → the `launcher_ads.app_drawer` sequence (App Open / interstitial / direct link,
        // counter-gated). A direct link that wins opens on the way back, never alongside the app.
        if (AppOpenAdRegistry.consumeExpectReturnAd()) {
            GuardRail.log("AppOpen", "↩ other_app_return")
            activity.runWhenWindowFocused {
                if (activity.isFinishing || activity.isDestroyed) return@runWhenWindowFocused
                if (!AppExitAd.run(activity)) LauncherAdsConfig.runDrawerAdFlow(activity) {}
            }
            return
        }

        // Ordinary returns never monetise the always-foreground launcher home or the post-call
        // screen: the launcher is resumed on every press of Home, which is not an app launch.
        if (activity is LauncherHomeActivity || activity is My_Shell_Screen) {
            GuardRail.log("AppOpen", "⛔ Excluded screen")
            return
        }

        // `appOpen_ad_flow` / an `appOpen_` link chain: the dynamic flow instead of the App Open ad.
        if (LauncherPlacementAds.hasOwnFlow(activity, "appOpen")) {
            if (!AdsVault.getInstance(activity).getBoolean("IsAdsON") ||
                !LauncherPlacementAds.placementEnabled(activity, "appOpen") ||
                AppOpenAdRegistry.isShowingAd || InterstitialNormal.isInterShow
            ) return
            GuardRail.log("AppOpen", "🚀 appOpen flow (appOpen_*)")
            activity.runWhenWindowFocused {
                if (activity.isFinishing || activity.isDestroyed) return@runWhenWindowFocused
                LauncherPlacementAds.showInterstitial(activity, "appOpen") {}
            }
            return
        }

        val adType = AdKind.fromString(
            AdsVault.getInstance(activity).getString("IsAdType")
        )

        GuardRail.log(
            "AppOpen",
            "AdType=$adType | available=${isAdAvailable} | showing=${AppOpenAdRegistry.isShowingAd}"
        )

        if (
            adType == AdKind.GOOGLE &&
            isAdAvailable &&
            !AppOpenAdRegistry.isShowingAd
        ) {

            activity.runWhenWindowFocused {

                if (activity.isFinishing || activity.isDestroyed) return@runWhenWindowFocused

                GuardRail.log("AppOpen", "🚀 Showing App Open Ad")

                AppOpenAdRegistry.showAdIfAvailable(
                    activity,
                    object : AppOpenAdRegistry.OnShowAdCompleteListener {
                        override fun onShowAdComplete() {
                            GuardRail.log("AppOpen", "✅ App Open Ad closed safely")
                        }
                    }
                )
            }

        } else {
            GuardRail.log("AppOpen", "❌ Ad NOT shown (conditions failed)")
        }
    }

    override fun onActivityCreated(p0: Activity, p1: Bundle?) {

    }

    // --------------------------------------------------
    // ACTIVITY LIFECYCLE
    // --------------------------------------------------
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        // NOTE: the AccessEngine is no longer auto-triggered here. Trigger it
        // where you want it (e.g. a button click) with `AccessEngine.check(this)`.
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) {
            currentActivity = null
        }
    }

    override fun onActivityPaused(p0: Activity) {

    }

    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {

    }

    override fun onActivityStarted(p0: Activity) {

    }

    override fun onActivityStopped(p0: Activity) {

    }

    // --------------------------------------------------
    // WINDOW FOCUS SAFE EXECUTION
    // --------------------------------------------------
    private fun Activity.runWhenWindowFocused(action: () -> Unit) {
        if (hasWindowFocus()) {
            action()
        } else {
            val decorView = window.decorView
            val listener =
                object : ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (hasFocus) {
                            decorView.viewTreeObserver
                                .removeOnWindowFocusChangeListener(this)
                            action()
                        }
                    }
                }
            decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        }
    }

}
