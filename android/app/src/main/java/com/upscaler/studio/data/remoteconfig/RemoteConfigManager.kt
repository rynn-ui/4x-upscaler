package com.upscaler.studio.data.remoteconfig

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.google.firebase.remoteconfig.remoteConfig
import com.upscaler.studio.BuildConfig
import com.upscaler.studio.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

sealed interface AppUpdateStatus {
    data object UpToDate : AppUpdateStatus

    data class ForceUpdate(
        val title: String,
        val message: String,
        val updateUrl: String,
        val minVersionCode: Long,
        val minVersionName: String
    ) : AppUpdateStatus

    data class OptionalUpdate(
        val title: String,
        val message: String,
        val updateUrl: String,
        val latestVersionCode: Long,
        val latestVersionName: String
    ) : AppUpdateStatus

    data class Maintenance(
        val message: String
    ) : AppUpdateStatus
}

/**
 * Manages Firebase Remote Config fetching, in-app defaults, and version/maintenance enforcement.
 */
class RemoteConfigManager private constructor(context: Context) {

    private val remoteConfig: FirebaseRemoteConfig by lazy {
        try {
            Firebase.remoteConfig.apply {
                val configSettings = FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(if (BuildConfig.DEBUG) 0L else 3600L)
                    .build()
                setConfigSettingsAsync(configSettings)
                setDefaultsAsync(R.xml.remote_config_defaults)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize Firebase Remote Config: ${e.message}")
            FirebaseRemoteConfig.getInstance()
        }
    }

    private val _updateStatus = MutableStateFlow<AppUpdateStatus>(AppUpdateStatus.UpToDate)
    val updateStatus: StateFlow<AppUpdateStatus> = _updateStatus.asStateFlow()

    suspend fun fetchAndCheck(): AppUpdateStatus {
        return try {
            remoteConfig.fetchAndActivate().await()
            evaluateUpdateStatus()
        } catch (e: Exception) {
            Log.w(TAG, "Remote Config fetch failed (using defaults/cached): ${e.message}")
            evaluateUpdateStatus()
        }
    }

    fun evaluateUpdateStatus(): AppUpdateStatus {
        return try {
            val isMaintenance = remoteConfig.getBoolean(KEY_IS_MAINTENANCE_MODE)
            if (isMaintenance) {
                val msg = remoteConfig.getString(KEY_MAINTENANCE_MESSAGE).ifBlank {
                    "Upscaler Studio is currently undergoing scheduled maintenance. Please check back shortly."
                }
                val status = AppUpdateStatus.Maintenance(msg)
                _updateStatus.value = status
                return status
            }

            val currentCode = BuildConfig.VERSION_CODE.toLong()
            val minCode = remoteConfig.getLong(KEY_MIN_VERSION_CODE)
            val minName = remoteConfig.getString(KEY_MIN_VERSION_NAME).ifBlank { "1.0.0" }
            val latestCode = remoteConfig.getLong(KEY_LATEST_VERSION_CODE)
            val latestName = remoteConfig.getString(KEY_LATEST_VERSION_NAME).ifBlank { "1.0.0" }
            val updateUrl = remoteConfig.getString(KEY_UPDATE_URL).ifBlank {
                "https://github.com/rynn-ui/upscaler-studio/releases"
            }
            val title = remoteConfig.getString(KEY_FORCE_UPDATE_TITLE).ifBlank {
                "Update Required"
            }
            val message = remoteConfig.getString(KEY_FORCE_UPDATE_MESSAGE).ifBlank {
                "A newer version of Upscaler Studio is required to continue. Please update to the latest release."
            }

            val status = when {
                currentCode < minCode -> {
                    AppUpdateStatus.ForceUpdate(
                        title = title,
                        message = message,
                        updateUrl = updateUrl,
                        minVersionCode = minCode,
                        minVersionName = minName
                    )
                }
                currentCode < latestCode -> {
                    AppUpdateStatus.OptionalUpdate(
                        title = "Update Available",
                        message = "A new version of Upscaler Studio ($latestName) is available.",
                        updateUrl = updateUrl,
                        latestVersionCode = latestCode,
                        latestVersionName = latestName
                    )
                }
                else -> AppUpdateStatus.UpToDate
            }

            _updateStatus.value = status
            status
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating remote config status: ${e.message}", e)
            AppUpdateStatus.UpToDate.also { _updateStatus.value = it }
        }
    }

    fun getColabNotebookUrl(): String {
        return try {
            val url = remoteConfig.getString(KEY_COLAB_NOTEBOOK_URL)
            if (url.isNotBlank()) url else DEFAULT_COLAB_NOTEBOOK_URL
        } catch (_: Exception) {
            DEFAULT_COLAB_NOTEBOOK_URL
        }
    }

    fun getVideoGenColabNotebookUrl(): String {
        return try {
            val url = remoteConfig.getString(KEY_VIDEOGEN_COLAB_NOTEBOOK_URL)
            if (url.isNotBlank()) url else DEFAULT_VIDEOGEN_COLAB_NOTEBOOK_URL
        } catch (_: Exception) {
            DEFAULT_VIDEOGEN_COLAB_NOTEBOOK_URL
        }
    }

    companion object {
        private const val TAG = "RemoteConfigManager"

        const val KEY_MIN_VERSION_CODE = "min_version_code"
        const val KEY_MIN_VERSION_NAME = "min_version_name"
        const val KEY_LATEST_VERSION_CODE = "latest_version_code"
        const val KEY_LATEST_VERSION_NAME = "latest_version_name"
        const val KEY_FORCE_UPDATE_TITLE = "force_update_title"
        const val KEY_FORCE_UPDATE_MESSAGE = "force_update_message"
        const val KEY_UPDATE_URL = "update_url"
        const val KEY_IS_MAINTENANCE_MODE = "is_maintenance_mode"
        const val KEY_MAINTENANCE_MESSAGE = "maintenance_message"
        const val KEY_COLAB_NOTEBOOK_URL = "colab_notebook_url"
        const val KEY_VIDEOGEN_COLAB_NOTEBOOK_URL = "videogen_colab_notebook_url"

        const val DEFAULT_COLAB_NOTEBOOK_URL = "https://colab.research.google.com/drive/1i1WCx7EPUrMkc-jjME60AMMRDrw8XvkX"
        const val DEFAULT_VIDEOGEN_COLAB_NOTEBOOK_URL = "https://colab.research.google.com/drive/14c1pNkl92NtprlFvLTtcGBYEUkDe8r_8"

        @Volatile private var instance: RemoteConfigManager? = null

        fun getInstance(context: Context): RemoteConfigManager {
            return instance ?: synchronized(this) {
                instance ?: RemoteConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

