package com.upscaler.studio.data.model

import com.google.gson.annotations.SerializedName

data class GpuInfo(
    @SerializedName("available") val available: Boolean,
    @SerializedName("device") val device: String,
    @SerializedName("vram_total_mb") val vramTotalMb: Double,
    @SerializedName("vram_free_mb") val vramFreeMb: Double
)

data class HealthResponse(
    @SerializedName("status") val status: String,
    @SerializedName("version") val version: String,
    @SerializedName("gpu") val gpu: GpuInfo,
    @SerializedName("active_jobs") val activeJobs: Int = 0,
    @SerializedName("active_uploads") val activeUploads: Int = 0,
    @SerializedName("queue_capacity") val queueCapacity: Int = 8,
    @SerializedName("backend_instance_id") val backendInstanceId: String? = null
)

data class StudioMetadata(
    @SerializedName("title") val title: String,
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("models") val models: Any? = null,
    @SerializedName("qualities") val qualities: List<String>? = null,
    @SerializedName("multipliers") val multipliers: List<Int>? = null,
    @SerializedName("modes") val modes: List<String>? = null,
    @SerializedName("colormaps") val colormaps: List<String>? = null,
    @SerializedName("presets") val presets: List<String>? = null
)

data class CapabilitiesResponse(
    @SerializedName("studios") val studios: Map<String, StudioMetadata>
)

data class JobSubmitResponse(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("status") val status: String
)

data class JobStatusResponse(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("task_type") val taskType: String,
    @SerializedName("status") val status: String,
    @SerializedName("progress") val progress: Double,
    @SerializedName("current_frame") val currentFrame: Int,
    @SerializedName("total_frames") val totalFrames: Int,
    @SerializedName("fps") val fps: Double,
    @SerializedName("eta_seconds") val etaSeconds: Double,
    @SerializedName("download_url") val downloadUrl: String? = null,
    @SerializedName("error_message") val errorMessage: String? = null
)

data class ProgressEvent(
    @SerializedName("type") val type: String, // "progress", "status", "complete", "error", "cancelled"
    @SerializedName("status") val status: String? = null,
    @SerializedName("progress") val progress: Double = 0.0,
    @SerializedName("current_frame") val currentFrame: Int = 0,
    @SerializedName("total_frames") val totalFrames: Int = 0,
    @SerializedName("fps") val fps: Double = 0.0,
    @SerializedName("eta_seconds") val etaSeconds: Double = 0.0,
    @SerializedName("preview_b64") val previewB64: String? = null,
    @SerializedName("download_url") val downloadUrl: String? = null,
    @SerializedName("message") val message: String? = null
)

enum class JobPhase(val label: String, val isTerminal: Boolean, val isActive: Boolean) {
    IDLE("Idle", false, false),
    PREPARING("Preparing video", false, true),
    UPLOADING("Uploading to GPU", false, true),
    QUEUED("Queued on GPU", false, true),
    PROCESSING("Processing", false, true),
    PAUSED("Paused", false, true),
    RECONNECTING("Reconnecting stream", false, true),
    DOWNLOADING("Saving to gallery", false, true),
    SAVED("Saved", true, false),
    FAILED("Failed", true, false),
    CANCELLED("Cancelled", true, false)
}

data class ManagedJob(
    val localId: String,
    val serverJobId: String? = null,
    val taskType: String,
    val sourceUriString: String,
    val sourceDisplayName: String = "",
    val paramsSummary: String = "",
    val outputFileName: String = "",
    val phase: JobPhase = JobPhase.PREPARING,
    val progress: Float = 0f,
    val currentFrame: Int = 0,
    val totalFrames: Int = 0,
    val fps: Float = 0f,
    val etaSeconds: Float = 0f,
    val previewB64: String? = null,
    val errorMessage: String? = null,
    val savedUriString: String? = null,
    val canRetryDownload: Boolean = false,
    val controlInFlight: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val originBaseUrl: String = "",
    val originAuthToken: String = "",
    val originSessionId: String = "",
    val clientSubmissionId: String = "",
    val cancellationRequested: Boolean = false
)

data class SavedMediaItem(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val isSavedToGallery: Boolean = true,
    val localJobId: String? = null
)
