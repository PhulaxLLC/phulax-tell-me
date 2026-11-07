package com.phonewatch.data

import android.content.Context
import com.phonewatch.engine.PwVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/** Single place the UI uses to read data and change rules. */
object Repo {
    lateinit var db: AppDb; private set
    lateinit var app: Context; private set
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    const val RETENTION_MS = 30L * 24 * 3600 * 1000

    fun init(c: Context) {
        app = c.applicationContext
        db = AppDb.get(app)
        io.launch {
            TrackerDb.load(app)
            RuleEngine.load(db.rules().allNow())
            db.conn().deleteBefore(System.currentTimeMillis() - RETENTION_MS)
        }
    }

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun rulesChanged() {
        RuleEngine.load(db.rules().allNow())
        PwVpnService.reload(app)
    }

    fun addRule(kind: String, target: String, pkg: String? = null) = setRule(kind, target, pkg, true)

    fun removeRule(kind: String, target: String, pkg: String? = null) = setRule(kind, target, pkg, false)

    fun removeRuleId(id: Long) { writes.trySend { db.rules().deleteId(id); rulesChanged() } }

    /** Switches call this: the UI and blocking update instantly, then the change is saved. Writes run one at a
     *  time in tap order, so on-off-on taps can never land out of order. */
    fun setRule(kind: String, target: String, pkg: String?, on: Boolean) {
        RuleEngine.applyLocal(kind, target, pkg, on)
        PwVpnService.reload(app)
        writes.trySend { if (on) db.rules().insert(Rule(kind = kind, target = target, pkg = pkg)) else db.rules().delete(kind, target, pkg) }
    }
    private val writes = kotlinx.coroutines.channels.Channel<() -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { ch ->
        io.launch { for (w in ch) runCatching { w() } }
    }

    /** One tap preset: block Advertising + Analytics + Fingerprinting everywhere. */
    fun applyRecommended() { for (c in listOf(Cat.ADS, Cat.ANALYTICS, Cat.FINGERPRINT)) setRule("category", c.name, null, true) }

    fun settingsChanged() { RuleEngine.clearCache(); PwVpnService.reload(app) }

    fun clearLogs() = io.launch { db.conn().deleteAll() }
}
