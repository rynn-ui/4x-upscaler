package com.upscaler.studio.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UiButtonSize(val label: String, val height: Dp, val scale: Float) {
    SMALL("Small", 40.dp, 0.82f),
    MEDIUM("Medium", 46.dp, 0.91f),
    LARGE("Large", 52.dp, 1.0f)
}

val LocalButtonSize = compositionLocalOf { UiButtonSize.LARGE }

class SettingsManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("upscaler_settings", Context.MODE_PRIVATE)

    private val _buttonSize = MutableStateFlow(loadButtonSize())
    val buttonSize: StateFlow<UiButtonSize> = _buttonSize.asStateFlow()

    private fun loadButtonSize(): UiButtonSize {
        val saved = prefs.getString(KEY_BUTTON_SIZE, UiButtonSize.LARGE.name)
        return try {
            UiButtonSize.valueOf(saved ?: UiButtonSize.LARGE.name)
        } catch (_: Exception) {
            UiButtonSize.LARGE
        }
    }

    fun setButtonSize(size: UiButtonSize) {
        _buttonSize.value = size
        prefs.edit().putString(KEY_BUTTON_SIZE, size.name).apply()
    }

    companion object {
        private const val KEY_BUTTON_SIZE = "ui_button_size"

        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

