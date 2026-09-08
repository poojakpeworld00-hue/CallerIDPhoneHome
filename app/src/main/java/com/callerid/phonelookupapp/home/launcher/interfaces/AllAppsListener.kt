package com.callerid.phonelookupapp.home.launcher.interfaces

import com.callerid.phonelookupapp.home.launcher.models.AppLauncher

interface AllAppsListener {
    fun onAppLauncherLongPressed(x: Float, y: Float, appLauncher: AppLauncher)
}
