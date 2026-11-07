package com.phonewatch

import android.app.Application
import com.phonewatch.data.Apps
import com.phonewatch.data.Prefs
import com.phonewatch.data.Repo

class PhoneWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Apps.init(this)
        Repo.init(this)
        com.phonewatch.scan.Radar.init(this)
        com.phonewatch.scan.Voice.init(this)
        com.phonewatch.inspect.DeepInspect.init(this)
        com.phonewatch.adb.AdbShell.init(this)
        if (com.phonewatch.adb.AdbShell.paired) Thread { com.phonewatch.adb.AdbShell.connect() }.start()   // built-in helper back on by itself
    }
}
