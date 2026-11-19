package com.upscaler.studio.ui.studios

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.upscaler.studio.data.repository.UpscalerRepository
import com.upscaler.studio.ui.components.VideoMeta
import kotlin.math.roundToInt

@Composable
fun RifeStudioScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { UpscalerRepository.instance }
    val draft = remember { repository.getStudioDraft("rife") }
    var selectedMediaUri by remember {
        mutableStateOf(draft["selectedMediaUri"]?.toString()?.let { android.net.Uri.parse(it) })
    }
    var videoMeta by remember { mutableStateOf<VideoMeta?>(null) }
    var selectedModel by remember { mutableStateOf(draft["selectedModel"] as? String ?: "rife_v4.6") }
    var selectedMultiplier by remember { mutableIntStateOf((draft["selectedMultiplier"] as? Number)?.toInt() ?: 2) }
    var selectedMode by remember { mutableStateOf(draft["selectedMode"] as? String ?: "interpolate") }

    LaunchedEffect(selectedMediaUri, selectedModel, selectedMultiplier, selectedMode) {
        repository.updateStudioDraft(
            "rife",
            mapOf(
                "selectedMediaUri" to selectedMediaUri?.toString(),
                "selectedModel" to selectedModel,
                "selectedMultiplier" to selectedMultiplier,
                "selectedMode" to selectedMode
            )
        )
    }

    val rifeModels = remember {
        listOf(
            "rife_v4.6" to "RIFE v4.6 (Anime & AMVs — Recommended)",
            "rife_v4.25" to "RIFE v4.25 (Fast Motion & Camera Pans)",
            "rife_v4.22_lite" to "RIFE v4.22 Lite (Ultra Fast)",
            "rife_v4.22" to "RIFE v4.22 (Realistic Quality)",
            "rife_v4.26" to "RIFE v4.26 (Heavy Ultra-Quality)"
        )
    }

    val srcFps = videoMeta?.fps ?: 30f
    val fpsAssumed = videoMeta?.fps == null

    StudioScaffold(
        title = "🎞️ RIFE 60/120 FPS",
        taskType = "rife",
        ctaLabel = "Start RIFE Interpolation",
        ctaIcon = Icons.Default.MovieFilter,
        selectedMediaUri = selectedMediaUri,
        onSelectMediaUri = { selectedMediaUri = it },
        onMetaLoaded = { videoMeta = it },
        onBack = {
            repository.clearStudioDraft("rife")
            onBack()
        },
        onSubmit = {
            val uri = selectedMediaUri ?: return@StudioScaffold
            val params = mapOf(
                "model_key" to selectedModel,
                "multiplier" to selectedMultiplier,
                "mode" to selectedMode
            )
            val modeLabel = if (selectedMode == "slow_motion") "${selectedMultiplier}× Slow-Mo" else "${selectedMultiplier}× FPS"
            runCatching {
                repository.submitMediaJob(
                    context = context,
                    sourceUri = uri,
                    taskType = "rife",
                    params = params,
                    paramsSummary = "$selectedModel • $modeLabel",
                    outputPrefix = "rife"
                )
            }
        }
    ) { isLocked ->
        StudioOptionCard(title = "RIFE Optical Flow Model") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rifeModels.forEach { (key, label) ->
                    StudioOptionRow(
                        label = label,
                        selected = selectedModel == key,
                        enabled = !isLocked,
                        onClick = { selectedModel = key }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        StudioOptionCard(title = "Output Timing Mode") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioChoiceChip(
                    label = "High FPS (Keep Audio)",
                    selected = selectedMode == "interpolate",
                    enabled = !isLocked,
                    onClick = { selectedMode = "interpolate" },
                    modifier = Modifier.weight(1f)
                )
                StudioChoiceChip(
                    label = "Slow Motion (Muted)",
                    selected = selectedMode == "slow_motion",
                    enabled = !isLocked,
                    onClick = { selectedMode = "slow_motion" },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        StudioOptionCard(title = "Frame Multiplier") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4, 8).forEach { mult ->
                    val targetFps = (srcFps * mult).roundToInt()
                    val chipText = if (selectedMode == "slow_motion") {
                        "${mult}× Slower"
                    } else {
                        "${mult}× (${targetFps} FPS)"
                    }
                    StudioChoiceChip(
                        label = chipText,
                        selected = selectedMultiplier == mult,
                        enabled = !isLocked,
                        onClick = { selectedMultiplier = mult },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (videoMeta != null) {
            Spacer(modifier = Modifier.height(12.dp))
            val fpsPrefix = if (fpsAssumed) "~${srcFps.roundToInt()} FPS (assumed)" else "${srcFps.roundToInt()} FPS"
            val summary = if (selectedMode == "slow_motion") {
                "Source $fpsPrefix → ${selectedMultiplier}× longer slow-motion at ${srcFps.roundToInt()} FPS (audio muted)"
            } else {
                "Source $fpsPrefix → ${(srcFps * selectedMultiplier).roundToInt()} FPS (same duration, audio preserved)"
            }
            ExpectedOutputBanner(summary = summary)
        }
    }
}
