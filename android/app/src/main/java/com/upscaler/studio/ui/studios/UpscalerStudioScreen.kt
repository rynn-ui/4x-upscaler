package com.upscaler.studio.ui.studios

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.upscaler.studio.data.repository.UpscalerRepository
import com.upscaler.studio.ui.components.VideoMeta

@Composable
fun UpscalerStudioScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { UpscalerRepository.instance }
    val draft = remember { repository.getStudioDraft("upscale") }
    var selectedMediaUri by remember {
        mutableStateOf(draft["selectedMediaUri"]?.toString()?.let { Uri.parse(it) })
    }
    var videoMeta by remember { mutableStateOf<VideoMeta?>(null) }
    var selectedModel by remember { mutableStateOf(draft["selectedModel"] as? String ?: "2x-sudo-UltraCompact") }
    var crispOutline by remember { mutableStateOf(draft["crispOutline"] as? Boolean ?: false) }

    LaunchedEffect(selectedMediaUri, selectedModel, crispOutline) {
        repository.updateStudioDraft(
            "upscale",
            mapOf(
                "selectedMediaUri" to selectedMediaUri?.toString(),
                "selectedModel" to selectedModel,
                "selectedPass" to 2,
                "crispOutline" to crispOutline
            )
        )
    }

    val models = remember {
        listOf(
            "2x-sudo-UltraCompact" to "2x-sudo-UltraCompact (Ultra-Fast Anime 2X)",
            "Real-ESRGAN Anime" to "Real-ESRGAN Anime v3 2X Compact",
            "Real-ESRGAN General" to "Real-ESRGAN Movie Model 2X"
        )
    }

    val expectedDims = remember(videoMeta) {
        val m = videoMeta
        if (m != null && m.width > 0 && m.height > 0) {
            val outW = ((m.width * 2 + 1) / 2) * 2
            val outH = ((m.height * 2 + 1) / 2) * 2
            outW to outH
        } else {
            null
        }
    }

    StudioScaffold(
        title = "⚡ 2X Super-Resolution",
        taskType = "upscale",
        ctaLabel = "Start 2X Upscale",
        ctaIcon = Icons.Default.Bolt,
        selectedMediaUri = selectedMediaUri,
        onSelectMediaUri = { selectedMediaUri = it },
        onMetaLoaded = { videoMeta = it },
        onBack = {
            repository.clearStudioDraft("upscale")
            onBack()
        },
        onSubmit = {
            val uri = selectedMediaUri ?: return@StudioScaffold
            val params = mapOf(
                "model" to selectedModel,
                "target_quality" to "2×",
                "passes" to 2,
                "crisp_outline" to crispOutline
            )
            runCatching {
                repository.submitMediaJob(
                    context = context,
                    sourceUri = uri,
                    taskType = "upscale",
                    params = params,
                    paramsSummary = "$selectedModel • 2×" + if (crispOutline) " • Crisp Outlines" else "",
                    outputPrefix = "upscaled"
                )
            }
        }
    ) { isLocked ->
        StudioOptionCard(title = "AI Super-Resolution Model (2X)") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                models.forEach { (key, label) ->
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

        StudioOptionCard(
            title = "Line Art Enhancement",
            subtitle = "Deepens character outlines and line art without white halos — applied before AI upscaling."
        ) {
            StudioOptionRow(
                label = "🗡️ Crisp Line Art & Outlines",
                selected = crispOutline,
                enabled = !isLocked,
                onClick = { crispOutline = !crispOutline }
            )
        }

        if (expectedDims != null && videoMeta != null) {
            Spacer(modifier = Modifier.height(16.dp))
            ExpectedOutputBanner(
                summary = "Output preview: ${videoMeta!!.width}×${videoMeta!!.height} -> ${expectedDims.first}×${expectedDims.second} (2× Native)"
            )
        }
    }
}
