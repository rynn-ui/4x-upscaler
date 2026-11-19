package com.upscaler.studio.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.upscaler.studio.MainActivity
import com.upscaler.studio.R
import com.upscaler.studio.data.model.JobPhase
import com.upscaler.studio.data.model.ManagedJob
import com.upscaler.studio.data.repository.UpscalerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps running jobs visible in the notification panel (and keeps the process alive
 * while the user edits or leaves the app) until every job reaches a terminal state.
 * Supports live percentage tracking, Pause/Resume, Stop, and Remove actions directly
 * from the notification shade.
 */
class JobNotificationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collecting = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val targetJobId = intent?.getStringExtra(EXTRA_LOCAL_JOB_ID)

        if (!targetJobId.isNullOrBlank()) {
            when (action) {
                ACTION_PAUSE_RESUME -> UpscalerRepository.instance.togglePauseResume(targetJobId)
                ACTION_STOP -> UpscalerRepository.instance.cancelManagedJob(targetJobId)
                ACTION_REMOVE -> UpscalerRepository.instance.removeJob(targetJobId)
            }
        }

        // Android requires startForeground within a few seconds of startForegroundService
        val currentJobs = UpscalerRepository.instance.jobs.value.values
            .filter { it.phase.isActive }
            .sortedByDescending { it.createdAtMs }

        startForeground(NOTIFICATION_ID, buildNotification(currentJobs))

        if (!collecting) {
            collecting = true
            scope.launch {
                UpscalerRepository.instance.jobs.collect { jobs ->
                    val active = jobs.values
                        .filter { it.phase.isActive }
                        .sortedByDescending { it.createdAtMs }
                    if (active.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            ?.notify(NOTIFICATION_ID, buildNotification(active))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(active: List<ManagedJob>): android.app.Notification {
        val latest = active.firstOrNull()
        val pct = latest?.progress?.toInt()?.coerceIn(0, 100) ?: 0
        val title = when {
            active.isEmpty() -> "Upscaler Studio"
            active.size == 1 -> "${titleForTask(latest!!.taskType)} • $pct%"
            else -> "${active.size} jobs in progress • $pct%"
        }
        val text = when {
            latest == null -> "Starting…"
            active.size > 1 -> "${titleForTask(latest.taskType)} • ${latest.phase.label} ($pct%)"
            latest.totalFrames > 0 -> "${latest.phase.label} ($pct%) • ${latest.currentFrame}/${latest.totalFrames} frames"
            else -> "${latest.phase.label} ($pct%)"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lightning_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("$pct%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())

        if (latest != null) {
            val isIndeterminate = (latest.phase == JobPhase.PREPARING || latest.phase == JobPhase.UPLOADING) && pct == 0
            builder.setProgress(100, pct, isIndeterminate)

            // 1. Pause / Resume action
            val pauseTitle = if (latest.phase == JobPhase.PAUSED) "Resume" else "Pause"
            val pauseIntent = actionPendingIntent(ACTION_PAUSE_RESUME, latest.localId, 101)
            builder.addAction(android.R.drawable.ic_media_pause, pauseTitle, pauseIntent)

            // 2. Stop action
            val stopIntent = actionPendingIntent(ACTION_STOP, latest.localId, 102)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)

            // 3. Remove action
            val removeIntent = actionPendingIntent(ACTION_REMOVE, latest.localId, 103)
            builder.addAction(android.R.drawable.ic_menu_delete, "Remove", removeIntent)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun actionPendingIntent(action: String, localId: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, JobNotificationService::class.java).apply {
            this.action = action
            putExtra(EXTRA_LOCAL_JOB_ID, localId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Job progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live percentage and controls for running AI video jobs."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun titleForTask(taskType: String): String = when (taskType) {
        "upscale" -> "2X Super-Resolution"
        "rife" -> "RIFE Frame Interpolation"
        "denoise" -> "AI Anti-Line Denoise"
        "depth" -> "Depth Map"
        else -> taskType
    }

    companion object {
        const val CHANNEL_ID = "job_progress"
        const val NOTIFICATION_ID = 4711

        const val ACTION_PAUSE_RESUME = "com.upscaler.studio.ACTION_PAUSE_RESUME"
        const val ACTION_STOP = "com.upscaler.studio.ACTION_STOP"
        const val ACTION_REMOVE = "com.upscaler.studio.ACTION_REMOVE"
        const val EXTRA_LOCAL_JOB_ID = "extra_local_job_id"

        fun start(context: Context) {
            val intent = Intent(context, JobNotificationService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
