package com.upscaler.studio.ui.studios

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.upscaler.studio.data.repository.UpscalerRepository

@Composable
fun DepthStudioScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { UpscalerRepository.instance }
    val draft = remember { repository.getStudioDraft("depth") }
    var selectedMediaUri by remember {
        mutableStateOf(draft["selectedMediaUri"]?.toString()?.let { android.net.Uri.parse(it) })
    }
    var selectedModel by remember { mutableStateOf(draft["selectedModel"] as? String ?: "depth_anything_v2_small") }
    var selectedColormap by remember { mutableStateOf(draft["selectedColormap"] as? String ?: "inferno") }

    LaunchedEffect(selectedMediaUri, selectedModel, selectedColormap) {
        repository.updateStudioDraft(
            "depth",
            mapOf(
                "selectedMediaUri" to selectedMediaUri?.toString(),
                "selectedModel" to selectedModel,
                "selectedColormap" to selectedColormap
            )
        )
    }

    val depthModels = remember {
        listOf(
            "depth_anything_v2_small" to "Depth Anything V2 (Small — Ultra Fast)",
            "depth_anything_v2_base" to "Depth Anything V2 (Base — High Precision)"
        )
    }
    val colormaps = remember { listOf("inferno", "turbo", "grayscale", "viridis") }

    StudioScaffold(
        title = "🌊 Depth Map Studio",
        taskType = "depth",
        ctaLabel = "Generate Depth Map",
        ctaIcon = Icons.Default.Layers,
        selectedMediaUri = selectedMediaUri,
        onSelectMediaUri = { selectedMediaUri = it },
        onBack = {
            repository.clearStudioDraft("depth")
            onBack()
        },
        onSubmit = {
            val uri = selectedMediaUri ?: return@StudioScaffold
            val params = mapOf(
                "model" to selectedModel,
                "colormap" to selectedColormap
            )
            runCatching {
                repository.submitMediaJob(
                    context = context,
                    sourceUri = uri,
                    taskType = "depth",
                    params = params,
                    paramsSummary = "$selectedModel • $selectedColormap",
                    outputPrefix = "depth"
                )
            }
        }
    ) { isLocked ->
        StudioOptionCard(title = "AI Depth Estimation Model") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                depthModels.forEach { (key, label) ->
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

        StudioOptionCard(title = "VFX Depth Palette") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colormaps.take(2).forEach { cmap ->
                        StudioChoiceChip(
                            label = cmap.replaceFirstChar { it.uppercase() },
                            selected = selectedColormap == cmap,
                            enabled = !isLocked,
                            onClick = { selectedColormap = cmap },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colormaps.drop(2).forEach { cmap ->
                        StudioChoiceChip(
                            label = cmap.replaceFirstChar { it.uppercase() },
                            selected = selectedColormap == cmap,
                            enabled = !isLocked,
                            onClick = { selectedColormap = cmap },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
