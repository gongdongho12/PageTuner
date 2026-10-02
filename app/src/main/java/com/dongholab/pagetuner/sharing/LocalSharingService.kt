package com.dongholab.pagetuner.sharing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dongholab.pagetuner.MainActivity
import com.dongholab.pagetuner.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalSharingState(val starting: Boolean = false, val session: SharingSessionInfo? = null, val message: Int? = null)

object LocalSharingControl {
    internal val mutableState = MutableStateFlow(LocalSharingState())
    val state = mutableState.asStateFlow()

    fun start(context: Context, address: String) {
        if (state.value.starting || state.value.session != null) return
        if (!isSharingAddress(address)) { mutableState.value = LocalSharingState(message = R.string.sharing_no_network); return }
        mutableState.value = LocalSharingState(starting = true)
        try {
            ContextCompat.startForegroundService(context, Intent(context, LocalSharingService::class.java).putExtra("address", address))
        } catch (_: Exception) {
            mutableState.value = LocalSharingState(message = R.string.sharing_start_failed)
        }
    }

    fun stop(context: Context) { context.stopService(Intent(context, LocalSharingService::class.java)) }
}

/** Lifetime belongs to a visible, explicitly started foreground service, never to a composable. */
class LocalSharingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: LocalSharingServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var starting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (starting || server != null) return START_NOT_STICKY
        val address = intent?.getStringExtra("address")
        if (address == null || !isSharingAddress(address) || sharingAddresses().none { it.address == address }) {
            LocalSharingControl.mutableState.value = LocalSharingState(message = R.string.sharing_no_network)
            stopSelf()
            return START_NOT_STICKY
        }
        starting = true
        try {
            createForegroundNotification()
        } catch (_: Exception) {
            LocalSharingControl.mutableState.value = LocalSharingState(message = R.string.sharing_start_failed)
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                val runtime = LocalSharingServer(address, library = AndroidSharingLibrary(applicationContext),
                    webAssets = AndroidSharingWebAssets(applicationContext), sessionDurationMillis = SHARING_DURATION_MILLIS)
                // Assign before switching dispatcher so cancellation always closes a partially started listener.
                server = runtime
                val info = withContext(Dispatchers.IO) { runtime.startSharing() }
                val power = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PageTuner:LocalSharing").apply {
                    setReferenceCounted(false)
                    acquire(SHARING_DURATION_MILLIS)
                }
                LocalSharingControl.mutableState.value = LocalSharingState(session = info)
                while (isActive) {
                    delay(5_000)
                    if (shouldStopSharing(System.currentTimeMillis(), info.expiresAt, info.address, sharingAddresses()) || !runtime.isRunning) {
                        LocalSharingControl.mutableState.value = LocalSharingState(message =
                            if (System.currentTimeMillis() >= info.expiresAt) R.string.sharing_expired else R.string.sharing_network_lost)
                        stopSelf()
                        break
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                LocalSharingControl.mutableState.value = LocalSharingState(message = R.string.sharing_start_failed)
                stopSelf()
            } finally { starting = false }
        }
        return START_NOT_STICKY
    }

    private fun createForegroundNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.sharing_title), NotificationManager.IMPORTANCE_LOW))
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), immutable)
        val stop = PendingIntent.getService(this, 1, Intent(this, LocalSharingService::class.java).setAction(ACTION_STOP), immutable)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.sharing_notification_title))
            .setContentText(getString(R.string.sharing_notification_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.sharing_stop), stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        server?.stopSharing()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        LocalSharingControl.mutableState.value = LocalSharingControl.state.value.copy(starting = false, session = null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.dongholab.pagetuner.STOP_LOCAL_SHARING"
        private const val CHANNEL = "local-sharing"
        private const val NOTIFICATION_ID = 4091
    }
}
