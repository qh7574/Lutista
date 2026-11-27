package cn.alittlecookie.lutista.tether.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cn.alittlecookie.lutista.MainActivity
import cn.alittlecookie.lutista.tether.repository.TetherRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Starts only after USB permission. A killed process never restores a USB connection. */
class TetherService : LifecycleService(), KoinComponent {
    private val repository: TetherRepository by inject()
    private var token = 0L
    private var ownsForeground = false
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Also identify an instance stopped before Android delivers onStartCommand.
        token = repository.pendingForegroundToken() ?: 0L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val requested = intent?.getLongExtra(EXTRA_TOKEN, 0L) ?: 0L
        if (intent?.action == ACTION_STOP) {
            repository.requestDisableFromService(requested)
            if (!ownsForeground) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!repository.acceptsForegroundToken(requested)) {
            if (!ownsForeground) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (ownsForeground) return START_NOT_STICKY
        token = requested
        val manager = getSystemService(NotificationManager::class.java)
        try {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "USB 联机", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "相机联机、原片传输与停止连接"
                    setShowBadge(false)
                },
            )
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("正在连接相机"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
            ownsForeground = true
            if (!repository.onForegroundStarted(token)) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        } catch (failure: RuntimeException) {
            repository.onForegroundFailed(token, failure)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        notificationJob = lifecycleScope.launch {
            repository.uiState.map { state ->
                val transfer = state.transfer
                if (transfer.active) "${state.cameraName ?: "相机"} · 原片传输 ${transfer.completedCount}/${transfer.totalCount}"
                else "${state.cameraName ?: "USB 相机"} · ${state.connectionSummary}"
            }.distinctUntilChanged().collect { text ->
                if (repository.acceptsForegroundToken(token)) manager.notify(NOTIFICATION_ID, notification(text))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1101, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_TETHER_OPEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1102, Intent(this, TetherService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Lutista USB 联机")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止联机", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        try {
            if (ownsForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } finally {
            if (token != 0L) repository.onForegroundDestroyed(token)
            super.onDestroy()
        }
    }

    companion object {
        const val ACTION_STOP = "cn.alittlecookie.lutista.tether.STOP"
        const val EXTRA_TOKEN = "tetherForegroundToken"
        private const val CHANNEL_ID = "tether_connection"
        private const val NOTIFICATION_ID = 1100
    }
}
