package com.callerid.adcast.presentation.oninterAds

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import com.callerid.adcast.domain.AdsVault
import com.callerid.phonelookupapp.home.R

object FullScreenSpinner {

    private var dialog: Dialog? = null

    /** The activity the dialog is attached to; weak, so a lingering dialog cannot keep it alive. */
    private var owner: java.lang.ref.WeakReference<Activity>? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** However a load ends - or never ends - the user is never left behind the loader for longer. */
    private const val MAX_SHOW_MS = 12_000L
    private val autoHide = Runnable {
        Log.w("FullScreenSpinner", "loader still up after ${MAX_SHOW_MS}ms - hidden")
        dismissSafely()
    }

    /**
     * Whether the full-screen loader may show while an ad loads — see [InterLoader]. The loader is
     * only ever shown for an ad that is being loaded; an ad already in hand shows at once.
     */
    fun isEnabled(context: Context): Boolean = InterLoader.enabled(AdsVault.getInstance(context))

    /**
     * Shows a full-screen transparent loader.
     * Checks if activity is alive before showing.
     */
    @Suppress("DEPRECATION")
    fun show(activity: Activity, isLoader: Boolean) {
        if (!isLoader) return

        try {
            if (activity.isFinishing || activity.isDestroyed) return

            // Dismiss existing one if still active
            dismissSafely()

            dialog = Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCancelable(false)
                setContentView(R.layout.piece_fullscreen)
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

                window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

                window?.let(::coverWholeScreen)

                // FLAG_NOT_FOCUSABLE before show() prevents status-bar flicker
                window?.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )

                // Immersive sticky — hide status bar + nav bar
                window?.decorView?.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

                show()
                owner = java.lang.ref.WeakReference(activity)
                main.removeCallbacks(autoHide)
                main.postDelayed(autoHide, MAX_SHOW_MS)

                // Restore focusability after show so touches register
                window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }

        } catch (e: Exception) {
            Log.e("FullScreenSpinner", "Error showing loader", e)
        }
    }

    fun hide() {
        dismissSafely()
    }

    /**
     * Lets a loader dialog's window cover the whole screen, status bar and camera cutout included.
     * Without it the window stops below the cutout strip and the screen behind (its toolbar) shows
     * through above the loader.
     */
    fun coverWholeScreen(window: Window) {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun dismissSafely() {
        try {
            dialog?.let {
                if (it.isShowing) {
                    // The dialog's own context is a theme wrapper, never the Activity itself.
                    val host = owner?.get()
                    if (host == null || (!host.isFinishing && !host.isDestroyed)) it.dismiss()
                }
            }
        } catch (e: Exception) {
            // Log.e("FullScreenSpinner", "Error dismissing loader", e)
        } finally {
            main.removeCallbacks(autoHide)
            dialog = null
            owner = null
        }
    }
}


