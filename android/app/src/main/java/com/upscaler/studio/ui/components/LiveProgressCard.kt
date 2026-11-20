package com.upscaler.studio.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.upscaler.studio.data.model.JobPhase
import com.upscaler.studio.data.model.ManagedJob
import com.upscaler.studio.ui.theme.DarkCardBorder
import com.upscaler.studio.ui.theme.DarkSurface
import com.upscaler.studio.ui.theme.GreenIconTint
import com.upscaler.studio.ui.theme.GreenPrimary
import com.upscaler.studio.ui.theme.ShapeCorner10
import com.upscaler.studio.ui.theme.ShapeCorner12
import com.upscaler.studio.ui.theme.ShapeCorner14
import com.upscaler.studio.ui.theme.ShapeCorner18
import com.upscaler.studio.ui.theme.ShapeCorner4
import com.upscaler.studio.ui.theme.StatusOfflineRed
import com.upscaler.studio.ui.theme.StatusOnlineGreen
import com.upscaler.studio.ui.theme.StatusWarningAmber
import com.upscaler.studio.ui.theme.TextSecondary

@Composable
fun LiveProgressCard(
    job: ManagedJob,
    onPauseToggle: () -> Unit,
    onCancel: () -> Unit,
    onCheckStatus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val decodedPreview = remember(job.previewB64) {
        job.previewB64?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    val isPaused = job.phase == JobPhase.PAUSED
    val isUploading = job.phase == JobPhase.PREPARING || job.phase == JobPhase.UPLOADING
    val isDownloading = job.phase == JobPhase.DOWNLOADING

    val isIndeterminate = when (job.phase) {
        JobPhase.PREPARING, JobPhase.UPLOADING, JobPhase.QUEUED, JobPhase.RECONNECTING -> true
        JobPhase.DOWNLOADING -> job.progress <= 0f || job.progress >= 100f
        JobPhase.PROCESSING -> job.totalFrames <= 0
        else -> false
    }

    val clampedPct = job.progress.coerceIn(0f, 100f)
    val badgeColor = when (job.phase) {
        JobPhase.PAUSED, JobPhase.RECONNECTING -> StatusWarningAmber
        JobPhase.FAILED -> StatusOfflineRed
        JobPhase.SAVED -> StatusOnlineGreen
        else -> GreenIconTint
    }

    val phaseTitle = when (job.phase) {
        JobPhase.PREPARING -> "Preparing Video"
        JobPhase.UPLOADING -> "Uploading to GPU"
        JobPhase.DOWNLOADING -> "Saving to Gallery"
        JobPhase.SAVED -> "Saved to Gallery"
        else -> job.phase.label
    }

    val etaFormatted = remember(job.etaSeconds) {
        val sec = job.etaSeconds.toInt().coerceAtLeast(0)
        if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, DarkCardBorder, ShapeCorner18),
        shape = ShapeCorner18,
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = phaseTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor
                )
                Text(
                    text = if (isIndeterminate && clampedPct <= 0f) "..." else "${clampedPct.toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Dedicated Loading Animations for Upload & Gallery Save
            if (isUploading) {
                UploadAnimationBlock(job = job)
                Spacer(modifier = Modifier.height(12.dp))
            } else if (isDownloading) {
                SaveGalleryAnimationBlock(job = job)
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (isIndeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(ShapeCorner4)
                        .semantics { contentDescription = "$phaseTitle progress" },
                    color = GreenPrimary,
                    trackColor = Color(0xFF132A1F)
                )
            } else {
                LinearProgressIndicator(
                    progress = { clampedPct / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(ShapeCorner4)
                        .semantics { contentDescription = "Progress ${clampedPct.toInt()} percent" },
                    color = if (isPaused) StatusWarningAmber else GreenPrimary,
                    trackColor = Color(0xFF132A1F)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val frameLabel = when {
                    job.phase == JobPhase.PROCESSING && job.totalFrames > 0 -> "Frame ${job.currentFrame}/${job.totalFrames}"
                    isUploading -> "Sending video to Colab server"
                    isDownloading -> "Exporting to Movies/UpscalerStudio"
                    else -> job.paramsSummary.ifBlank { "Working..." }
                }
                Text(
                    text = frameLabel,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                val speedLabel = if (job.fps > 0f && job.phase == JobPhase.PROCESSING) {
                    "${"%.1f".format(job.fps)} FPS • ETA $etaFormatted"
                } else {
                    ""
                }
                Text(
                    text = speedLabel,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            AnimatedVisibility(visible = decodedPreview != null) {
                decodedPreview?.let { bmp ->
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        Image(
                            bitmap = bmp,
                            contentDescription = "Live processed frame preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(ShapeCorner10),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            if (!job.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = job.errorMessage,
                    color = if (job.phase == JobPhase.RECONNECTING) StatusWarningAmber else StatusOfflineRed,
                    fontSize = 12.sp
                )
            }

            if (job.phase.isActive && !isDownloading) {
                Spacer(modifier = Modifier.height(14.dp))

                val canPauseResume = !job.controlInFlight &&
                    job.serverJobId != null &&
                    (job.phase == JobPhase.PROCESSING || job.phase == JobPhase.PAUSED || job.phase == JobPhase.QUEUED)

                val canCancel = !job.controlInFlight && !isDownloading

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (job.phase == JobPhase.RECONNECTING && job.serverJobId != null) {
                        OutlinedButton(
                            onClick = onCheckStatus,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                            shape = ShapeCorner12
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = GreenIconTint)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Check Status", color = Color.White)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onPauseToggle,
                            enabled = canPauseResume,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                            shape = ShapeCorner12
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null,
                                tint = if (isPaused) GreenIconTint else Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isPaused) "Resume" else "Pause", color = Color.White)
                        }
                    }

                    Button(
                        onClick = onCancel,
                        enabled = canCancel,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                        shape = ShapeCorner12,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadAnimationBlock(job: ManagedJob) {
    val infiniteTransition = rememberInfiniteTransition(label = "uploadPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "uploadScale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "uploadGlow"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeCorner14)
            .background(Color(0xFF0D2117))
            .border(1.dp, GreenPrimary.copy(alpha = glowAlpha), ShapeCorner14)
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .scale(pulseScale)
                        .clip(ShapeCorner12)
                        .background(Color(0xFF143825)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = "Uploading to GPU",
                        tint = GreenIconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (job.phase == JobPhase.PREPARING) "Preparing Video for Transfer..." else "Uploading to Colab GPU...",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (job.paramsSummary.isNotBlank() && job.paramsSummary.contains("MB", ignoreCase = true)) {
                            job.paramsSummary
                        } else {
                            "Transferring file over secure tunnel..."
                        },
                        color = TextSecondary,
                        fontSize = 11.5.sp
                    )
                }

                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = GreenPrimary,
                    trackColor = Color(0xFF143825),
                    strokeWidth = 2.5.dp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "⚡ Please keep app open — AI processing begins automatically after upload completes.",
                color = Color(0xFF81C784),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SaveGalleryAnimationBlock(job: ManagedJob) {
    val infiniteTransition = rememberInfiniteTransition(label = "savePulse")
    val bounceScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "saveBounce"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "saveGlow"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeCorner14)
            .background(Color(0xFF0D2117))
            .border(1.dp, GreenPrimary.copy(alpha = glowAlpha), ShapeCorner14)
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .scale(bounceScale)
                        .clip(ShapeCorner12)
                        .background(Color(0xFF143825)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Saving to Gallery",
                        tint = GreenIconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Saving to Phone Gallery...",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (job.paramsSummary.isNotBlank() && (job.paramsSummary.contains("Downloading", ignoreCase = true) || job.paramsSummary.contains("Saving", ignoreCase = true))) {
                            job.paramsSummary
                        } else {
                            "Transferring finished video to Movies/UpscalerStudio..."
                        },
                        color = TextSecondary,
                        fontSize = 11.5.sp
                    )
                }

                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = GreenPrimary,
                    trackColor = Color(0xFF143825),
                    strokeWidth = 2.5.dp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "🎬 Exporting to Movies/UpscalerStudio. Comparison player will open automatically!",
                color = Color(0xFF81C784),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
