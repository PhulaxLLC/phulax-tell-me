package com.phonewatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.phonewatch.data.Prefs
import com.phonewatch.engine.PwVpnService

/** Turns PhoneWatch back on after a reboot/update if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Prefs.vpnWanted && VpnService.prepare(c) == null) PwVpnService.start(c)
    }
}
