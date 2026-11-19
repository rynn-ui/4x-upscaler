package com.upscaler.studio

import android.app.Application
import com.upscaler.studio.data.repository.UpscalerRepository
import com.upscaler.studio.service.JobNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UpscalerApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationActive = false

    override fun onCreate() {
        super.onCreate()
        UpscalerRepository.instance.init(this)

        // Surface running jobs in the notification panel and keep monitoring alive
        // even when the user edits settings or leaves the app.
        appScope.launch {
            UpscalerRepository.instance.jobs.collect { jobs ->
                val hasActiveJob = jobs.values.any { it.phase.isActive }
                // Only launch the service on the idle -> active transition; progress
                // updates arrive several times per second.
                if (hasActiveJob != notificationActive) {
                    notificationActive = hasActiveJob
                    if (hasActiveJob) JobNotificationService.start(this@UpscalerApp)
                }
            }
        }
    }
}
