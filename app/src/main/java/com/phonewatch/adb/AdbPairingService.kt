package com.phonewatch.adb

import android.annotation.TargetApi
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.phonewatch.R
import java.net.ConnectException

@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val notificationChannel = "adb_pairing"

        private const val tag = "AdbPairingService"

        private const val notificationId = 1
        private const val replyRequestId = 1
        private const val stopRequestId = 2
        private const val retryRequestId = 3
        private const val startAction = "start"
        private const val stopAction = "stop"
        private const val replyAction = "reply"
        private const val remoteInputResultKey = "paring_code"
        private const val portKey = "paring_code"

        fun startIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(startAction)
        }

        private fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(stopAction)
        }

        private fun replyIntent(context: Context, port: Int): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(replyAction).putExtra(portKey, port)
        }
    }

    private var adbMdns: AdbMdns? = null

    private val observer = Observer<Int> { port ->
        Log.i(tag, "Pairing service port: $port")
        if (port <= 0) return@Observer

        // Since the service could be killed before user finishing input,
        // we need to put the port into Intent
        val notification = createInputNotification(port)

        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                notificationChannel,
                "Wireless debugging pairing",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            startAction -> {
                onStart()
            }
            replyAction -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputResultKey) ?: ""
                val port = intent.getIntExtra(portKey, -1)
                if (port != -1) {
                    onInput(code.toString(), port)
                } else {
                    onStart()
                }
            }
            stopAction -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        if (notification != null) {
            try {
                startForeground(notificationId, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } catch (e: Throwable) {
                Log.e(tag, "startForeground failed", e)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e is ForegroundServiceStartNotAllowedException) {
                    getSystemService(NotificationManager::class.java).notify(notificationId, notification)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
    }

    private fun onStart(): Notification {
        startSearch()
        return searchingNotification
    }

    private fun onInput(code: String, port: Int): Notification {
        GlobalScope.launch(Dispatchers.IO) {
            val host = "127.0.0.1"

            val key = try {
                AdbKey(PreferenceAdbKeyStore(getSharedPreferences("adb", MODE_PRIVATE)), "phulax")
            } catch (e: Throwable) {
                e.printStackTrace()
                return@launch
            }

            AdbPairingClient(host, port, code, key).runCatching {
                start()
            }.onFailure {
                handleResult(false, it)
            }.onSuccess {
                handleResult(it, null)
            }
        }

        return workingNotification
    }

    private fun handleResult(success: Boolean, exception: Throwable?) {
        stopForeground(STOP_FOREGROUND_REMOVE)

        val title: String
        val text: String?

        if (success) {
            AdbShell.markPaired()
            Log.i(tag, "Pair succeed")

            title = "Paired"
            text = "The helper can start now. Go back to the app."

            stopSearch()
        } else {
            title = "Pairing failed"

            text = when (exception) {
                is ConnectException -> {
                    "Couldn't reach the pairing port. Keep the pairing dialog open and try again."
                }
                is AdbInvalidPairingCodeException -> {
                    "The pairing code was wrong. Try again."
                }
                is AdbKeyException -> {
                    "The key store on this phone failed. Try again."
                }
                else -> {
                    exception?.let { Log.getStackTraceString(it) }
                }
            }

            if (exception != null) {
                Log.w(tag, "Pair failed", exception)
            } else {
                Log.w(tag, "Pair failed")
            }
        }

        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            Notification.Builder(this, notificationChannel)
                .setColor(getColor(android.R.color.holo_blue_dark))
                .setSmallIcon(R.drawable.ic_stat_eye)
                .setContentTitle(title)
                .setContentText(text)
                /*.apply {
                    if (!success) {
                        addAction(retryNotificationAction)
                    }
                }*/
                .build()
        )
        stopSelf()
    }

    private val stopNotificationAction by lazy(LazyThreadSafetyMode.NONE) {
        val pendingIntent = PendingIntent.getService(
            this,
            stopRequestId,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            "Stop",
            pendingIntent
        )
            .build()
    }

    private val retryNotificationAction by lazy(LazyThreadSafetyMode.NONE) {
        val pendingIntent = PendingIntent.getService(
            this,
            retryRequestId,
            startIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            "Retry",
            pendingIntent
        )
            .build()
    }

    private val replyNotificationAction by lazy(LazyThreadSafetyMode.NONE) {
        val remoteInput = RemoteInput.Builder(remoteInputResultKey).run {
            setLabel("Pairing code")
            build()
        }

        val pendingIntent = PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, -1),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        Notification.Action.Builder(
            null,
            "Enter pairing code",
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun replyNotificationAction(port: Int): Notification.Action {
        // Ensure pending intent is created
        val action = replyNotificationAction

        PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return action
    }

    private val searchingNotification by lazy(LazyThreadSafetyMode.NONE) {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(android.R.color.holo_blue_dark))
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("Looking for the pairing service…")
            .addAction(stopNotificationAction)
            .build()
    }

    private fun createInputNotification(port: Int): Notification {
        return Notification.Builder(this, notificationChannel)
            .setColor(getColor(android.R.color.holo_blue_dark))
            .setContentTitle("Pairing service found")
            .setSmallIcon(R.drawable.ic_stat_eye)
            .addAction(replyNotificationAction(port))
            .build()
    }

    private val workingNotification by lazy(LazyThreadSafetyMode.NONE) {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(android.R.color.holo_blue_dark))
            .setContentTitle("Pairing…")
            .setSmallIcon(R.drawable.ic_stat_eye)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
