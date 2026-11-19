package com.upscaler.studio.ui.studios

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.upscaler.studio.data.repository.UpscalerRepository
import com.upscaler.studio.ui.theme.TextSecondary

@Composable
fun DenoiserStudioScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { UpscalerRepository.instance }
    val draft = remember { repository.getStudioDraft("denoise") }
    var selectedMediaUri by remember {
        mutableStateOf(draft["selectedMediaUri"]?.toString()?.let { android.net.Uri.parse(it) })
    }
    var selectedModel by remember { mutableStateOf(draft["selectedModel"] as? String ?: "1x_archiver_antilines") }

    LaunchedEffect(selectedMediaUri, selectedModel) {
        repository.updateStudioDraft(
            "denoise",
            mapOf(
                "selectedMediaUri" to selectedMediaUri?.toString(),
                "selectedModel" to selectedModel
            )
        )
    }

    val denoiseModels = remember {
        listOf(
            "1x_archiver_antilines" to "1x-Archiver-AntiLines (Eradicates Lines & Noise)"
        )
    }

    StudioScaffold(
        title = "🧹 AI Anti-Line Denoiser",
        taskType = "denoise",
        ctaLabel = "Start AI Denoise",
        ctaIcon = Icons.Default.CleaningServices,
        selectedMediaUri = selectedMediaUri,
        onSelectMediaUri = { selectedMediaUri = it },
        onBack = {
            repository.clearStudioDraft("denoise")
            onBack()
        },
        onSubmit = {
            val uri = selectedMediaUri ?: return@StudioScaffold
            runCatching {
                repository.submitMediaJob(
                    context = context,
                    sourceUri = uri,
                    taskType = "denoise",
                    params = mapOf("model" to selectedModel),
                    paramsSummary = selectedModel,
                    outputPrefix = "denoised"
                )
            }
        }
    ) { isLocked ->
        StudioOptionCard(title = "Restoration Model") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                denoiseModels.forEach { (key, label) ->
                    StudioOptionRow(
                        label = label,
                        selected = selectedModel == key,
                        enabled = !isLocked,
                        onClick = { selectedModel = key }
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Specialized neural network that wipes out low-bitrate banding, horizontal tears, and compression noise while preserving crisp line art.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}
