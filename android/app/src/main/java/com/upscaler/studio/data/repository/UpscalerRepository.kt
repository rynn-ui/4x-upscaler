package com.upscaler.studio.data.repository

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.upscaler.studio.data.api.JobWebSocketClient
import com.upscaler.studio.data.api.StreamPacket
import com.upscaler.studio.data.api.UpscalerApiService
import com.upscaler.studio.data.model.CapabilitiesResponse
import com.upscaler.studio.data.model.HealthResponse
import com.upscaler.studio.data.model.JobPhase
import com.upscaler.studio.data.model.JobStatusResponse
import com.upscaler.studio.data.model.ManagedJob
import com.upscaler.studio.data.model.SavedMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class ConnectionState {
    data class Disconnected(val reason: String? = null) : ConnectionState()
    data class Connecting(val baseUrl: String) : ConnectionState()
    data class Connected(val health: HealthResponse, val capabilities: CapabilitiesResponse?) : ConnectionState()
    data class Failed(val baseUrl: String, val message: String) : ConnectionState()
}

data class BackendConnection(
    val baseUrl: String,
    val authToken: String?,
    val backendInstanceId: String?,
    val okHttpClient: OkHttpClient,
    val apiService: UpscalerApiService,
    val wsClient: JobWebSocketClient
)

class UpscalerRepository private constructor() {

    companion object {
        val instance: UpscalerRepository by lazy { UpscalerRepository() }
        private const val PREFS_NAME = "upscaler_studio_prefs"
        private const val KEY_BASE_URL = "backend_base_url"
        private const val KEY_AUTH_TOKEN = "backend_auth_token"
        private const val KEY_JOBS_JSON = "persisted_jobs_json"
    }

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val connectMutex = Mutex()
    private val stateLock = Any()

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _currentBaseUrl = MutableStateFlow("")
    val currentBaseUrl: StateFlow<String> = _currentBaseUrl.asStateFlow()

    private val _hasAuthToken = MutableStateFlow(false)
    val hasAuthToken: StateFlow<Boolean> = _hasAuthToken.asStateFlow()

    private val _jobs = MutableStateFlow<Map<String, ManagedJob>>(emptyMap())
    val jobs: StateFlow<Map<String, ManagedJob>> = _jobs.asStateFlow()

    private val connectionCache = ConcurrentHashMap<String, BackendConnection>()
    private var activeConnection: BackendConnection? = null

    private var currentAuthToken: String? = null
    val activeAuthToken: String? get() = currentAuthToken
    private var okHttpClient: OkHttpClient = buildHttpClient(null)
    private var apiService: UpscalerApiService? = null
    private var wsClient: JobWebSocketClient = JobWebSocketClient(okHttpClient)

    private val submissionJobs = ConcurrentHashMap<String, Job>()
    private val monitorJobs = ConcurrentHashMap<String, Job>()
    private val downloadGuards = ConcurrentHashMap<String, Boolean>()

    // In-memory per-studio UI draft state so selections persist across navigation within process.
    private val studioDrafts = ConcurrentHashMap<String, MutableMap<String, Any?>>()

    fun getStudioDraft(taskType: String): Map<String, Any?> = studioDrafts[taskType]?.toMap() ?: emptyMap()

    fun updateStudioDraft(taskType: String, updates: Map<String, Any?>) {
        val current = studioDrafts.getOrPut(taskType) { ConcurrentHashMap() }
        updates.forEach { (k, v) ->
            if (v == null) current.remove(k) else current[k] = v
        }
    }

    fun clearStudioDraft(taskType: String) {
        studioDrafts.remove(taskType)
    }

    fun init(context: Context) {
        synchronized(stateLock) {
            if (appContext != null) return
            appContext = context.applicationContext
            prefs = appContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val savedUrl = prefs?.getString(KEY_BASE_URL, "") ?: ""
            val savedToken = prefs?.getString(KEY_AUTH_TOKEN, null)
            if (savedUrl.isNotBlank()) {
                val parsed = BackendUrlParser.normalizeBackendUrl(savedUrl)
                if (parsed.isSuccess) {
                    val normUrl = parsed.getOrThrow()
                    val conn = getConnectionFor(normUrl, savedToken)
                    activeConnection = conn
                    currentAuthToken = savedToken
                    okHttpClient = conn.okHttpClient
                    apiService = conn.apiService
                    wsClient = conn.wsClient
                    _currentBaseUrl.value = normUrl
                    _hasAuthToken.value = !savedToken.isNullOrBlank()
                }
            }
            restoreJobsLocked()
        }

        // Resume monitoring any interrupted server jobs and verify backend connectivity
        val toRecover = _jobs.value.values.filter {
            !it.phase.isTerminal && !it.serverJobId.isNullOrBlank()
        }
        toRecover.forEach { startMonitoring(it.localId) }

        if (_currentBaseUrl.value.isNotBlank()) {
            repoScope.launch { refreshHealthIfConfigured() }
        }
    }

    fun buildHttpClient(token: String?): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val builder = chain.request().newBuilder()
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
            chain.proceed(builder.build())
        }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()
    }

    fun getConnectionFor(baseUrl: String, authToken: String?): BackendConnection {
        val normUrl = BackendUrlParser.normalizeBackendUrl(baseUrl).getOrNull() ?: baseUrl.trimEnd('/')
        val cleanToken = authToken?.trim()?.takeIf { it.isNotEmpty() }
        val cacheKey = "$normUrl|${cleanToken ?: ""}"
        return connectionCache.computeIfAbsent(cacheKey) {
            val client = buildHttpClient(cleanToken)
            val retrofit = Retrofit.Builder()
                .baseUrl("$normUrl/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            val service = retrofit.create(UpscalerApiService::class.java)
            val ws = JobWebSocketClient(client)
            BackendConnection(
                baseUrl = normUrl,
                authToken = cleanToken,
                backendInstanceId = null,
                okHttpClient = client,
                apiService = service,
                wsClient = ws
            )
        }
    }

    fun getConnectionForJob(job: ManagedJob): BackendConnection? {
        if (job.originBaseUrl.isBlank()) return null
        return getConnectionFor(job.originBaseUrl, job.originAuthToken.takeIf { it.isNotBlank() })
    }

    /**
     * Candidate Connection Pattern:
     * Parses, validates, and verifies candidate backend health & capabilities in isolation.
     * Failed pairing leaves existing active connection, credentials, and state 100% UNTOUCHED.
     * Reconnecting to the same host with blank token retains the existing token.
     * Connecting to a different host does NOT inherit old credentials.
     */
    suspend fun connectAndVerify(rawInput: String, manualToken: String? = null): Result<HealthResponse> {
        val parsed = BackendUrlParser.parsePairingInput(rawInput)
        if (parsed.isFailure) {
            val err = parsed.exceptionOrNull() ?: IllegalArgumentException("Invalid backend URL")
            return Result.failure(err)
        }
        val config = parsed.getOrThrow()

        val currentUrl = _currentBaseUrl.value
        val existingToken = currentAuthToken
        val candidateToken = when {
            !manualToken.isNullOrBlank() -> manualToken.trim()
            !config.authToken.isNullOrBlank() -> config.authToken.trim()
            config.baseUrl.equals(currentUrl, ignoreCase = true) -> existingToken // Retain on same host!
            else -> null // Different host does NOT inherit credentials!
        }

        return connectMutex.withLock {
            withContext(Dispatchers.IO) {
                _connectionState.value = ConnectionState.Connecting(config.baseUrl)

                // Build isolated candidate connection without mutating active state
                val candidateClient = buildHttpClient(candidateToken)
                val candidateRetrofit = Retrofit.Builder()
                    .baseUrl("${config.baseUrl}/")
                    .client(candidateClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                val candidateService = candidateRetrofit.create(UpscalerApiService::class.java)

                // Cloudflare tunnel edge routes may take 1-2 seconds after startup to settle.
                // Retry getHealth up to 3 times with 1-second delay before declaring failure.
                var health: HealthResponse? = null
                var lastErr: Exception? = null
                for (attempt in 1..3) {
                    try {
                        health = candidateService.getHealth()
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastErr = e
                        if (attempt < 3) {
                            kotlinx.coroutines.delay(1000L)
                        }
                    }
                }

                if (health == null) {
                    val finalErr = lastErr ?: IllegalStateException("Failed to connect to backend")
                    val friendly = formatNetworkError(finalErr)
                    _connectionState.value = ConnectionState.Failed(config.baseUrl, friendly)
                    return@withContext Result.failure(IllegalStateException(friendly, finalErr))
                }

                try {
                    val caps = try {
                        candidateService.getCapabilities()
                    } catch (_: Exception) {
                        null
                    }

                    // Candidate verification SUCCEEDED! Commit active connection atomically
                    val candidateWs = JobWebSocketClient(candidateClient)
                    val newConnection = BackendConnection(
                        baseUrl = config.baseUrl,
                        authToken = candidateToken,
                        backendInstanceId = health.backendInstanceId,
                        okHttpClient = candidateClient,
                        apiService = candidateService,
                        wsClient = candidateWs
                    )

                    synchronized(stateLock) {
                        activeConnection = newConnection
                        val cacheKey = "${config.baseUrl}|${candidateToken ?: ""}"
                        connectionCache[cacheKey] = newConnection

                        currentAuthToken = candidateToken
                        okHttpClient = candidateClient
                        apiService = candidateService
                        wsClient = candidateWs

                        _currentBaseUrl.value = config.baseUrl
                        _hasAuthToken.value = !candidateToken.isNullOrBlank()

                        prefs?.edit()
                            ?.putString(KEY_BASE_URL, config.baseUrl)
                            ?.apply {
                                if (candidateToken != null) putString(KEY_AUTH_TOKEN, candidateToken)
                                else remove(KEY_AUTH_TOKEN)
                            }
                            ?.apply()
                    }

                    _connectionState.value = ConnectionState.Connected(health, caps)
                    Result.success(health)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val friendly = formatNetworkError(e)
                    _connectionState.value = ConnectionState.Failed(config.baseUrl, friendly)
                    Result.failure(IllegalStateException(friendly, e))
                }
            }
        }
    }

    suspend fun refreshHealthIfConfigured(): Result<HealthResponse> = withContext(Dispatchers.IO) {
        connectMutex.withLock {
            val conn = synchronized(stateLock) { activeConnection }
            if (conn == null || conn.baseUrl.isBlank()) {
                _connectionState.value = ConnectionState.Disconnected()
                return@withLock Result.failure(IllegalStateException("No backend URL configured."))
            }
            _connectionState.value = ConnectionState.Connecting(conn.baseUrl)
            try {
                val health = conn.apiService.getHealth()
                val caps = try {
                    conn.apiService.getCapabilities()
                } catch (_: Exception) {
                    null
                }
                if (health.backendInstanceId != null && conn.backendInstanceId != null && health.backendInstanceId != conn.backendInstanceId) {
                    val updatedConn = conn.copy(backendInstanceId = health.backendInstanceId)
                    synchronized(stateLock) {
                        activeConnection = updatedConn
                        connectionCache["${conn.baseUrl}|${conn.authToken ?: ""}"] = updatedConn
                    }
                }
                _connectionState.value = ConnectionState.Connected(health, caps)
                Result.success(health)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val friendly = formatNetworkError(e)
                _connectionState.value = ConnectionState.Failed(conn.baseUrl, friendly)
                Result.failure(IllegalStateException(friendly, e))
            }
        }
    }

    fun disconnect() {
        synchronized(stateLock) {
            activeConnection = null
            currentAuthToken = null
            _currentBaseUrl.value = ""
            _hasAuthToken.value = false
            _connectionState.value = ConnectionState.Disconnected()
            prefs?.edit()
                ?.remove(KEY_BASE_URL)
                ?.remove(KEY_AUTH_TOKEN)
                ?.apply()
        }
    }

    fun latestJobForTask(taskType: String): ManagedJob? {
        return _jobs.value.values
            .filter { it.taskType == taskType }
            .maxByOrNull { it.createdAtMs }
    }

    fun dismissCompletedOrFailedJob(localId: String) {
        updateJobs { map ->
            val current = map[localId]
            if (current != null && current.phase.isTerminal) {
                map - localId
            } else {
                map
            }
        }
    }

    fun removeJob(localId: String) {
        val current = _jobs.value[localId]
        if (current != null && !current.phase.isTerminal) {
            cancelManagedJob(localId)
        }
        updateJobs { it - localId }
    }

    /**
     * Submits a durable job on repoScope so navigation does not cancel preparation, upload, or processing.
     * Binds the ManagedJob immutably to its originating backend connection (originBaseUrl, originAuthToken, originSessionId).
     */
    fun submitMediaJob(
        context: Context,
        sourceUri: Uri,
        taskType: String,
        params: Map<String, Any>,
        paramsSummary: String,
        outputPrefix: String
    ): String {
        init(context)

        val conn = synchronized(stateLock) { activeConnection }
            ?: throw IllegalStateException("Backend is offline. Connect to Colab first.")

        val ctx = context.applicationContext
        val localId = UUID.randomUUID().toString()
        val displayName = resolveDisplayName(ctx, sourceUri)
        val outFileName = "${outputPrefix}_${System.currentTimeMillis()}.mp4"
        val clientSubmissionId = UUID.randomUUID().toString()

        // Best-effort persistable permission so Comparison Player can reopen original video after navigation
        try {
            ctx.contentResolver.takePersistableUriPermission(
                sourceUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }

        val initial = ManagedJob(
            localId = localId,
            taskType = taskType,
            sourceUriString = sourceUri.toString(),
            sourceDisplayName = displayName,
            paramsSummary = paramsSummary,
            outputFileName = outFileName,
            phase = JobPhase.PREPARING,
            originBaseUrl = conn.baseUrl,
            originAuthToken = conn.authToken ?: "",
            originSessionId = conn.backendInstanceId ?: "",
            clientSubmissionId = clientSubmissionId
        )
        putJob(initial)

        val submissionCoro = repoScope.launch {
            var tempUploadFile: File? = null
            try {
                tempUploadFile = copyUriToTempFile(ctx, sourceUri, taskType)
                if (!tempUploadFile.exists() || tempUploadFile.length() == 0L) {
                    throw IllegalStateException("Selected video could not be read or is empty.")
                }

                val fileLenMb = tempUploadFile.length() / (1024 * 1024)
                if (fileLenMb >= 95 && conn.baseUrl.contains("trycloudflare.com", ignoreCase = true)) {
                    throw IllegalStateException(
                        "Video is ${fileLenMb}MB, which exceeds Cloudflare tunnel's 100MB limit. " +
                        "Please trim the video or run Colab with an Ngrok token for unlimited upload sizes."
                    )
                }

                val preUpload = _jobs.value[localId]
                if (preUpload == null || preUpload.cancellationRequested || preUpload.phase == JobPhase.CANCELLED) {
                    return@launch
                }

                val fileLenFormatted = if (fileLenMb > 0) "${fileLenMb}MB" else "${tempUploadFile.length() / 1024}KB"
                mutateJob(localId) {
                    it.copy(
                        phase = JobPhase.UPLOADING,
                        paramsSummary = "Uploading $fileLenFormatted to GPU...",
                        errorMessage = null
                    )
                }

                val requestFile = tempUploadFile.asRequestBody("application/octet-stream".toMediaType())
                val filePart = MultipartBody.Part.createFormData("file", displayName, requestFile)
                val taskBody = taskType.toRequestBody("text/plain".toMediaType())
                val paramsBody = gson.toJson(params).toRequestBody("application/json".toMediaType())
                val clientSubBody = clientSubmissionId.toRequestBody("text/plain".toMediaType())

                val submitResp = conn.apiService.submitJob(filePart, taskBody, paramsBody, clientSubBody)

                val postUpload = _jobs.value[localId]
                if (postUpload == null || postUpload.cancellationRequested || postUpload.phase == JobPhase.CANCELLED) {
                    return@launch
                }

                mutateJob(localId) {
                    it.copy(
                        serverJobId = submitResp.jobId,
                        phase = if (submitResp.status == "processing") JobPhase.PROCESSING else JobPhase.QUEUED
                    )
                }
                startMonitoring(localId)
            } catch (e: CancellationException) {
                val latest = _jobs.value[localId]
                if (latest != null && (latest.cancellationRequested || latest.phase == JobPhase.CANCELLED)) {
                    // Already marked cancelled
                } else {
                    mutateJob(localId) { it.copy(phase = JobPhase.CANCELLED) }
                }
                throw e
            } catch (e: Exception) {
                val latest = _jobs.value[localId]
                if (latest != null && (latest.cancellationRequested || latest.phase == JobPhase.CANCELLED)) {
                    return@launch
                }
                mutateJob(localId) {
                    it.copy(
                        phase = JobPhase.FAILED,
                        errorMessage = formatNetworkError(e)
                    )
                }
            } finally {
                submissionJobs.remove(localId)
                try {
                    if (tempUploadFile != null && tempUploadFile.exists()) {
                        tempUploadFile.delete()
                    }
                } catch (_: Exception) {
                }
            }
        }
        submissionJobs[localId] = submissionCoro

        return localId
    }

    fun startMonitoring(localId: String) {
        monitorJobs[localId]?.cancel()
        val monitor = repoScope.launch {
            val jobSnapshot = _jobs.value[localId] ?: return@launch
            val serverJobId = jobSnapshot.serverJobId ?: return@launch

            val conn = getConnectionForJob(jobSnapshot) ?: run {
                mutateJob(localId) {
                    it.copy(
                        phase = JobPhase.FAILED,
                        errorMessage = "Job origin backend is not configured."
                    )
                }
                return@launch
            }

            var consecutiveDisconnects = 0

            while (true) {
                val current = _jobs.value[localId] ?: break
                if (current.phase.isTerminal) break

                var sawTerminal = false

                conn.wsClient.streamProgress(conn.baseUrl, serverJobId, conn.authToken).collect { packet ->
                    when (packet) {
                        is StreamPacket.Event -> {
                            consecutiveDisconnects = 0
                            val evt = packet.event
                            when (evt.type) {
                                "complete" -> {
                                    sawTerminal = true
                                    handleCompletionIdempotent(localId, serverJobId, evt.previewB64)
                                }
                                "cancelled" -> {
                                    sawTerminal = true
                                    mutateJob(localId) {
                                        it.copy(
                                            phase = JobPhase.CANCELLED,
                                            controlInFlight = false,
                                            errorMessage = evt.message ?: "Job cancelled."
                                        )
                                    }
                                }
                                "error" -> {
                                    sawTerminal = true
                                    mutateJob(localId) {
                                        it.copy(
                                            phase = JobPhase.FAILED,
                                            controlInFlight = false,
                                            errorMessage = evt.message ?: "Processing failed on server."
                                        )
                                    }
                                }
                                else -> {
                                    val nextPhase = when (evt.status?.lowercase()) {
                                        "paused" -> JobPhase.PAUSED
                                        "queued" -> JobPhase.QUEUED
                                        else -> JobPhase.PROCESSING
                                    }
                                    mutateJob(localId) { prev ->
                                        prev.copy(
                                            phase = nextPhase,
                                            progress = evt.progress.toFloat().coerceIn(0f, 100f),
                                            currentFrame = evt.currentFrame,
                                            totalFrames = evt.totalFrames,
                                            fps = evt.fps.toFloat().coerceAtLeast(0f),
                                            etaSeconds = evt.etaSeconds.toFloat().coerceAtLeast(0f),
                                            previewB64 = evt.previewB64 ?: prev.previewB64,
                                            errorMessage = null
                                        )
                                    }
                                }
                            }
                        }
                        is StreamPacket.Disconnected -> {
                            // Stream disconnected
                        }
                    }
                }

                if (sawTerminal) break

                val latestAfterWs = _jobs.value[localId] ?: break
                if (latestAfterWs.phase.isTerminal) break

                consecutiveDisconnects++
                mutateJob(localId) {
                    if (it.phase.isTerminal) it else it.copy(phase = JobPhase.RECONNECTING)
                }

                // Exponential backoff on WebSocket disconnect before HTTP status recovery
                val backoffMs = (2000L * (1 shl (consecutiveDisconnects - 1).coerceAtMost(3))).coerceIn(2000L, 8000L)
                delay(backoffMs)

                val recoveredStatus = try {
                    conn.apiService.getJobStatus(serverJobId)
                } catch (_: Exception) {
                    null
                }

                if (recoveredStatus != null) {
                    consecutiveDisconnects = 0
                    val handledTerminal = applyRecoveredStatus(localId, recoveredStatus)
                    if (handledTerminal) break
                }

                // Allow up to 20 consecutive status polling attempts (~2-3 mins of disconnection) before declaring failure
                if (consecutiveDisconnects >= 20) {
                    mutateJob(localId) {
                        it.copy(
                            phase = JobPhase.FAILED,
                            errorMessage = "Lost connection to backend while monitoring job. Tap Check Status when online."
                        )
                    }
                    break
                }
            }
        }
        monitorJobs[localId] = monitor
    }

    fun checkAndRecoverJob(localId: String) {
        val current = _jobs.value[localId] ?: return
        val serverJobId = current.serverJobId ?: return
        val conn = getConnectionForJob(current) ?: run {
            mutateJob(localId) { it.copy(errorMessage = "Job's origin backend is unavailable.") }
            return
        }
        repoScope.launch {
            try {
                val status = conn.apiService.getJobStatus(serverJobId)
                if (!applyRecoveredStatus(localId, status)) {
                    startMonitoring(localId)
                }
            } catch (e: Exception) {
                mutateJob(localId) {
                    it.copy(errorMessage = "Status check failed: ${formatNetworkError(e)}")
                }
            }
        }
    }

    private suspend fun applyRecoveredStatus(localId: String, status: JobStatusResponse): Boolean {
        return when (status.status.lowercase()) {
            "completed" -> {
                handleCompletionIdempotent(localId, status.jobId, null)
                true
            }
            "cancelled" -> {
                mutateJob(localId) {
                    it.copy(
                        phase = JobPhase.CANCELLED,
                        controlInFlight = false,
                        errorMessage = status.errorMessage ?: "Job cancelled."
                    )
                }
                true
            }
            "failed" -> {
                mutateJob(localId) {
                    it.copy(
                        phase = JobPhase.FAILED,
                        controlInFlight = false,
                        errorMessage = status.errorMessage ?: "Processing failed on server."
                    )
                }
                true
            }
            else -> {
                val mappedPhase = when (status.status.lowercase()) {
                    "paused" -> JobPhase.PAUSED
                    "queued" -> JobPhase.QUEUED
                    else -> JobPhase.PROCESSING
                }
                mutateJob(localId) { prev ->
                    prev.copy(
                        phase = mappedPhase,
                        progress = status.progress.toFloat().coerceIn(0f, 100f),
                        currentFrame = status.currentFrame,
                        totalFrames = status.totalFrames,
                        fps = status.fps.toFloat().coerceAtLeast(0f),
                        etaSeconds = status.etaSeconds.toFloat().coerceAtLeast(0f),
                        errorMessage = null
                    )
                }
                false
            }
        }
    }

    private suspend fun handleCompletionIdempotent(localId: String, serverJobId: String, previewB64: String?) {
        if (downloadGuards.putIfAbsent(localId, true) != null) {
            return
        }
        try {
            val existing = _jobs.value[localId] ?: return
            if (existing.phase == JobPhase.SAVED && !existing.savedUriString.isNullOrBlank()) return

            mutateJob(localId) {
                it.copy(
                    phase = JobPhase.DOWNLOADING,
                    progress = 100f,
                    etaSeconds = 0f,
                    previewB64 = previewB64 ?: it.previewB64,
                    controlInFlight = false,
                    errorMessage = null
                )
            }
            performVerifiedDownload(localId, serverJobId, existing.outputFileName)
        } finally {
            downloadGuards.remove(localId)
        }
    }

    fun retryDownload(localId: String) {
        val current = _jobs.value[localId] ?: return
        val serverJobId = current.serverJobId ?: return
        repoScope.launch {
            handleCompletionIdempotent(localId, serverJobId, current.previewB64)
        }
    }

    private suspend fun performVerifiedDownload(localId: String, serverJobId: String, fileName: String) {
        val ctx = appContext ?: return
        val existing = _jobs.value[localId] ?: return
        val conn = getConnectionForJob(existing)
        val service = conn?.apiService ?: synchronized(stateLock) { apiService }
        if (service == null) {
            mutateJob(localId) {
                it.copy(
                    phase = JobPhase.FAILED,
                    canRetryDownload = true,
                    errorMessage = "Job origin backend is unavailable."
                )
            }
            return
        }
        mutateJob(localId) {
            it.copy(paramsSummary = "Downloading finished video from GPU...")
        }
        val result = downloadToInternalStorage(ctx, service, serverJobId, fileName) { pct, statusText ->
            mutateJob(localId) {
                it.copy(
                    progress = pct,
                    paramsSummary = statusText
                )
            }
        }
        result.onSuccess { internalUri ->
            mutateJob(localId) {
                it.copy(paramsSummary = "Saving to Gallery (Movies/UpscalerStudio)...")
            }
            // Automatically save to the device Gallery (Movies/UpscalerStudio)
            val galleryRes = runCatching { saveVideoToGallery(ctx, internalUri, fileName) }.getOrNull()
            val finalUri = galleryRes?.getOrNull() ?: internalUri
            mutateJob(localId) {
                it.copy(
                    phase = JobPhase.SAVED,
                    savedUriString = finalUri.toString(),
                    canRetryDownload = false,
                    errorMessage = null
                )
            }
        }.onFailure { err ->
            mutateJob(localId) {
                it.copy(
                    phase = JobPhase.FAILED,
                    canRetryDownload = true,
                    errorMessage = err.localizedMessage ?: "Failed to download output file."
                )
            }
        }
    }

    fun togglePauseResume(localId: String) {
        val current = _jobs.value[localId] ?: return
        val serverJobId = current.serverJobId ?: return
        if (current.controlInFlight || current.phase.isTerminal) return

        val conn = getConnectionForJob(current) ?: run {
            mutateJob(localId) { it.copy(errorMessage = "Job's origin backend is unavailable.") }
            return
        }

        val wantResume = current.phase == JobPhase.PAUSED
        mutateJob(localId) { it.copy(controlInFlight = true, errorMessage = null) }

        repoScope.launch {
            try {
                val resp = if (wantResume) conn.apiService.resumeJob(serverJobId) else conn.apiService.pauseJob(serverJobId)
                if (!resp.isSuccessful) {
                    val code = resp.code()
                    if (code == 409) {
                        val snapshot = runCatching { conn.apiService.getJobStatus(serverJobId) }.getOrNull()
                        if (snapshot != null) applyRecoveredStatus(localId, snapshot)
                    }
                    throw IllegalStateException("Action rejected by server (HTTP $code).")
                }
                mutateJob(localId) {
                    it.copy(
                        phase = if (wantResume) JobPhase.PROCESSING else JobPhase.PAUSED,
                        controlInFlight = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutateJob(localId) {
                    it.copy(
                        controlInFlight = false,
                        errorMessage = formatNetworkError(e)
                    )
                }
            }
        }
    }

    fun cancelManagedJob(localId: String) {
        val current = _jobs.value[localId] ?: return
        if (current.controlInFlight || current.phase.isTerminal) return

        mutateJob(localId) {
            it.copy(
                phase = JobPhase.CANCELLED,
                cancellationRequested = true,
                controlInFlight = false,
                errorMessage = "Cancelled by user."
            )
        }

        // Cancel local submission coroutine immediately
        submissionJobs.remove(localId)?.cancel()
        monitorJobs.remove(localId)?.cancel()

        val conn = getConnectionForJob(current) ?: return

        // Fire server cancellation
        repoScope.launch {
            try {
                if (!current.serverJobId.isNullOrBlank()) {
                    conn.apiService.cancelJob(current.serverJobId)
                } else if (current.clientSubmissionId.isNotBlank()) {
                    conn.apiService.cancelClientSubmission(current.clientSubmissionId)
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun downloadToInternalStorage(
        context: Context,
        service: UpscalerApiService,
        jobId: String,
        fileName: String,
        onProgress: ((Float, String) -> Unit)? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val response = service.downloadOutput(jobId)
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException("Download rejected (HTTP ${response.code()}).")
                )
            }
            val body = response.body()
                ?: return@withContext Result.failure(IllegalStateException("Empty download response body."))

            val outputsDir = File(context.filesDir, "outputs").apply { mkdirs() }
            val destFile = File(outputsDir, fileName)

            val totalBytes = body.contentLength()
            var written = 0L
            val buffer = ByteArray(64 * 1024)
            var lastEmitTime = 0L

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { out ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        written += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= 200L) {
                            lastEmitTime = now
                            val writtenMb = written.toFloat() / (1024 * 1024)
                            val text = if (totalBytes > 0L) {
                                val totalMb = totalBytes.toFloat() / (1024 * 1024)
                                "Downloading: %.1f / %.1f MB".format(writtenMb, totalMb)
                            } else {
                                "Downloading: %.1f MB".format(writtenMb)
                            }
                            val pct = if (totalBytes > 0L) {
                                (written.toFloat() / totalBytes * 100f).coerceIn(0f, 99f)
                            } else {
                                100f
                            }
                            onProgress?.invoke(pct, text)
                        }
                    }
                    out.flush()
                }
            }
            if (written <= 0L || !destFile.exists() || destFile.length() == 0L) {
                if (destFile.exists()) destFile.delete()
                throw IllegalStateException("Downloaded file was empty or failed to write.")
            }

            val providerUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )
            return@withContext Result.success(providerUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun saveVideoToGallery(
        context: Context,
        sourceUri: Uri,
        fileName: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        var pendingUri: Uri? = null
        try {
            val resolver = context.contentResolver
            val isImage = fileName.endsWith(".png", ignoreCase = true) || fileName.endsWith(".jpg", ignoreCase = true)
            val mimeType = if (isImage) "image/png" else "video/mp4"

            // Direct local file check so we don't depend on ContentResolver/FileProvider permissions
            val localFile = when {
                sourceUri.scheme == "file" -> sourceUri.path?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                else -> null
            } ?: File(File(context.filesDir, "outputs"), fileName).takeIf { it.exists() && it.length() > 0L }

            val openInput: () -> java.io.InputStream? = {
                if (localFile != null && localFile.exists()) {
                    FileInputStream(localFile)
                } else {
                    resolver.openInputStream(sourceUri)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isImage) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val relDir = if (isImage) {
                    Environment.DIRECTORY_PICTURES + "/UpscalerStudio/"
                } else {
                    Environment.DIRECTORY_MOVIES + "/UpscalerStudio/"
                }

                // Check if file is already present in MediaStore with content
                val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf(fileName, "%UpscalerStudio%")
                var existingUri: Uri? = null
                var existingSize = 0L

                try {
                    resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                            val id = cursor.getLong(idCol)
                            existingSize = cursor.getLong(sizeCol)
                            existingUri = Uri.withAppendedPath(collection, id.toString())
                        }
                    }
                } catch (_: Exception) {}

                val foundUri = existingUri
                val expectedLength = localFile?.length() ?: 0L
                if (foundUri != null && existingSize > 0L && (expectedLength == 0L || existingSize == expectedLength)) {
                    return@withContext Result.success(foundUri)
                }

                if (foundUri != null) {
                    try {
                        resolver.delete(foundUri, null, null)
                    } catch (_: Exception) {}
                }

                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = resolver.insert(collection, cv)
                    ?: return@withContext Result.failure(IllegalStateException("MediaStore failed to allocate gallery entry for $fileName."))
                pendingUri = uri

                var written = 0L
                val inStream = openInput() ?: throw IllegalStateException("Unable to open source video input stream for $fileName.")
                inStream.use { input ->
                    resolver.openOutputStream(uri)?.use { out ->
                        written = input.copyTo(out, bufferSize = 64 * 1024)
                    } ?: throw IllegalStateException("Unable to open gallery output stream.")
                }

                if (written <= 0L) {
                    cleanupPendingMediaStore(context, uri)
                    throw IllegalStateException("Copied video file was 0 bytes.")
                }

                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                pendingUri = null

                // Ensure MediaStore scanner indexes the file so phone galleries refresh immediately
                var realPath: String? = null
                try {
                    resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                            if (dataIdx >= 0) realPath = cursor.getString(dataIdx)
                        }
                    }
                } catch (_: Exception) {}

                if (!realPath.isNullOrBlank()) {
                    MediaScannerConnection.scanFile(context, arrayOf(realPath), arrayOf(mimeType), null)
                }
                try {
                    resolver.notifyChange(uri, null)
                    context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                } catch (_: Exception) {}

                return@withContext Result.success(uri)
            } else {
                val hasWritePerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                val baseDir = if (hasWritePerm) {
                    @Suppress("DEPRECATION")
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "UpscalerStudio")
                } else {
                    File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "UpscalerStudio")
                }
                if (!baseDir.exists() && !baseDir.mkdirs()) {
                    throw IllegalStateException("Unable to create output directory: ${baseDir.absolutePath}")
                }
                val destFile = File(baseDir, fileName)
                var written = 0L
                val inStream = openInput() ?: throw IllegalStateException("Unable to open source video input stream for $fileName.")
                inStream.use { input ->
                    FileOutputStream(destFile).use { out ->
                        written = input.copyTo(out, bufferSize = 64 * 1024)
                    }
                }

                if (written <= 0L || !destFile.exists() || destFile.length() == 0L) {
                    if (destFile.exists()) destFile.delete()
                    throw IllegalStateException("Copied file was empty or failed to write.")
                }

                MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)
                val destUri = Uri.fromFile(destFile)
                return@withContext Result.success(destUri)
            }
        } catch (e: Exception) {
            cleanupPendingMediaStore(context, pendingUri)
            return@withContext Result.failure(e)
        }
    }

    suspend fun downloadAndSaveToGallery(
        context: Context,
        service: UpscalerApiService,
        jobId: String,
        fileName: String
    ): Result<Uri> = withContext(Dispatchers.IO) {

        var pendingUri: Uri? = null
        try {
            val response = service.downloadOutput(jobId)
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException("Download rejected (HTTP ${response.code()}).")
                )
            }
            val body = response.body()
                ?: return@withContext Result.failure(IllegalStateException("Empty download response body."))

            val resolver = context.contentResolver
            val isImage = fileName.endsWith(".png", ignoreCase = true) || fileName.endsWith(".jpg", ignoreCase = true)
            val mimeType = if (isImage) "image/png" else "video/mp4"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isImage) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val relDir = if (isImage) {
                    Environment.DIRECTORY_PICTURES + "/UpscalerStudio"
                } else {
                    Environment.DIRECTORY_MOVIES + "/UpscalerStudio"
                }
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = resolver.insert(collection, cv)
                    ?: return@withContext Result.failure(IllegalStateException("MediaStore failed to allocate gallery entry."))
                pendingUri = uri

                var written = 0L
                body.byteStream().use { input ->
                    resolver.openOutputStream(uri)?.use { out ->
                        written = input.copyTo(out)
                    } ?: throw IllegalStateException("Unable to open gallery output stream.")
                }

                if (written <= 0L) {
                    throw IllegalStateException("Downloaded file was 0 bytes.")
                }

                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                pendingUri = null
                return@withContext Result.success(uri)
            } else {
                val hasWritePerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                val baseDir = if (hasWritePerm) {
                    @Suppress("DEPRECATION")
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "UpscalerStudio")
                } else {
                    File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "UpscalerStudio")
                }
                if (!baseDir.exists() && !baseDir.mkdirs()) {
                    throw IllegalStateException("Unable to create output directory: ${baseDir.absolutePath}")
                }
                val destFile = File(baseDir, fileName)
                var written = 0L
                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { out ->
                        written = input.copyTo(out)
                    }
                }
                if (written <= 0L || !destFile.exists() || destFile.length() == 0L) {
                    if (destFile.exists()) destFile.delete()
                    throw IllegalStateException("Downloaded file was empty or failed to write.")
                }

                if (hasWritePerm) {
                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)
                }
                val providerUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    destFile
                )
                return@withContext Result.success(providerUri)
            }
        } catch (e: CancellationException) {
            cleanupPendingMediaStore(context, pendingUri)
            throw e
        } catch (e: Exception) {
            cleanupPendingMediaStore(context, pendingUri)
            return@withContext Result.failure(e)
        }
    }

    private fun cleanupPendingMediaStore(context: Context, uri: Uri?) {
        if (uri != null) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun loadSavedMedia(context: Context): List<SavedMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SavedMediaItem>()
        val galleryNames = mutableSetOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED
            )
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%UpscalerStudio%")
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
            runCatching {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "output.mp4"
                        val size = cursor.getLong(sizeCol)
                        val added = cursor.getLong(dateCol)
                        if (size > 0L) {
                            galleryNames.add(name)
                            val contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                            items.add(
                                SavedMediaItem(
                                    uriString = contentUri.toString(),
                                    displayName = name,
                                    sizeBytes = size,
                                    dateAddedSec = added,
                                    isSavedToGallery = true,
                                    localJobId = _jobs.value.values.find { it.outputFileName == name }?.localId
                                )
                            )
                        }
                    }
                }
            }
        } else {
            val dirs = listOfNotNull(
                @Suppress("DEPRECATION")
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "UpscalerStudio"),
                File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "UpscalerStudio")
            )
            dirs.filter { it.exists() }.forEach { dir ->
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
                    if (file.isFile && file.length() > 0L) {
                        galleryNames.add(file.name)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        items.add(
                            SavedMediaItem(
                                uriString = uri.toString(),
                                displayName = file.name,
                                sizeBytes = file.length(),
                                dateAddedSec = file.lastModified() / 1000L,
                                isSavedToGallery = true,
                                localJobId = _jobs.value.values.find { it.outputFileName == file.name }?.localId
                            )
                        )
                    }
                }
            }
        }

        // Include internal outputs/ (completed video downloads ready to be saved to gallery)
        val internalOutputsDir = File(context.filesDir, "outputs")
        if (internalOutputsDir.exists()) {
            internalOutputsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.length() > 0L) {
                    val matchingJob = _jobs.value.values.find { it.outputFileName == file.name }
                    val alreadyInGallery = galleryNames.contains(file.name)
                    if (!alreadyInGallery) {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        items.add(
                            SavedMediaItem(
                                uriString = uri.toString(),
                                displayName = file.name,
                                sizeBytes = file.length(),
                                dateAddedSec = file.lastModified() / 1000L,
                                isSavedToGallery = false,
                                localJobId = matchingJob?.localId
                            )
                        )
                    }
                }
            }
        }

        items.sortedByDescending { it.dateAddedSec }
    }

    suspend fun deleteSavedMedia(context: Context, item: SavedMediaItem): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(item.uriString)
            var deleted = false
            if (uri.scheme == "content") {
                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    deleted = rows > 0
                } catch (_: Exception) {
                    deleted = false
                }
            }
            if (!deleted && (uri.scheme == "file" || uri.path != null)) {
                val file = File(uri.path ?: "")
                if (file.exists()) {
                    deleted = file.delete()
                }
            }
            // Clean up matching internal cache/output file if present
            val internalFile = File(File(context.filesDir, "outputs"), item.displayName)
            if (internalFile.exists()) {
                val internalDeleted = internalFile.delete()
                deleted = deleted || internalDeleted
            }
            // Clean up matching job in _jobs
            val targetJobId = item.localJobId ?: _jobs.value.values.find { it.outputFileName == item.displayName }?.localId
            if (targetJobId != null) {
                updateJobs { it - targetJobId }
            }
            deleted
        }
    }

    private fun copyUriToTempFile(context: Context, uri: Uri, taskType: String): File {
        val ext = resolveExtension(context, uri)
        val tempFile = File.createTempFile("upload_${taskType}_", ext, context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Unable to read selected video stream.")
        return tempFile
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "clip.mp4"
    }

    private fun resolveExtension(context: Context, uri: Uri): String {
        val name = resolveDisplayName(context, uri).lowercase()
        val dot = name.lastIndexOf('.')
        if (dot >= 0) {
            val ext = name.substring(dot)
            if (ext in setOf(".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v")) {
                return ext
            }
        }
        return ".mp4"
    }

    private fun putJob(job: ManagedJob) {
        updateJobs { it + (job.localId to job) }
    }

    private fun mutateJob(localId: String, transform: (ManagedJob) -> ManagedJob) {
        updateJobs { map ->
            val existing = map[localId] ?: return@updateJobs map
            map + (localId to transform(existing))
        }
    }

    private fun updateJobs(transform: (Map<String, ManagedJob>) -> Map<String, ManagedJob>) {
        synchronized(stateLock) {
            val updated = transform(_jobs.value)
            _jobs.value = updated
            persistJobsLocked(updated)
        }
    }

    private fun persistJobsLocked(map: Map<String, ManagedJob>) {
        // Avoid storing large base64 preview blobs in SharedPreferences
        val stripped = map.values
            .sortedByDescending { it.createdAtMs }
            .take(25)
            .map { it.copy(previewB64 = null, controlInFlight = false) }
        prefs?.edit()?.putString(KEY_JOBS_JSON, gson.toJson(stripped))?.apply()
    }

    private fun restoreJobsLocked() {
        val raw = prefs?.getString(KEY_JOBS_JSON, null) ?: return
        runCatching {
            val listType = object : TypeToken<List<ManagedJob>>() {}.type
            val list: List<ManagedJob> = gson.fromJson(raw, listType) ?: emptyList()
            val reconciled = list.map { job ->
                if ((job.phase == JobPhase.PREPARING || job.phase == JobPhase.UPLOADING) && job.serverJobId.isNullOrBlank()) {
                    job.copy(
                        phase = JobPhase.FAILED,
                        errorMessage = "Submission interrupted before reaching server."
                    )
                } else if (job.originBaseUrl.isBlank() && !job.phase.isTerminal) {
                    job.copy(
                        phase = JobPhase.FAILED,
                        errorMessage = "Job unbound from backend origin; please resubmit."
                    )
                } else {
                    job
                }
            }
            _jobs.value = reconciled.associateBy { it.localId }
        }
    }

    private fun formatNetworkError(e: Throwable): String {
        return when (e) {
            is HttpException -> when (e.code()) {
                401 -> "Authentication failed (401). Re-pair via Colab button or QR code."
                413 -> "Video exceeds backend upload size limit."
                422 -> extractServerDetail(e) ?: "Unsupported model or processing setting."
                429 -> "Server GPU queue is full. Please wait a moment."
                else -> extractServerDetail(e) ?: "Server error (HTTP ${e.code()})."
            }
            else -> e.localizedMessage ?: "Network communication error."
        }
    }

    private fun extractServerDetail(e: HttpException): String? {
        return try {
            val raw = e.response()?.errorBody()?.string()?.trim().orEmpty()
            if (raw.isBlank()) return null
            val json = runCatching { gson.fromJson(raw, com.google.gson.JsonObject::class.java) }.getOrNull()
            val detail = json?.get("detail")
            when {
                detail == null || detail.isJsonNull -> raw.take(180)
                detail.isJsonPrimitive -> detail.asString?.take(180)
                else -> detail.toString().take(180)
            }?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
