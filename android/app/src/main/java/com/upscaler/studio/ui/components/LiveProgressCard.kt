package com.upscaler.studio.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val isIndeterminate = job.phase in setOf(
        JobPhase.PREPARING,
        JobPhase.UPLOADING,
        JobPhase.QUEUED,
        JobPhase.DOWNLOADING,
        JobPhase.RECONNECTING
    ) || job.totalFrames <= 0

    val clampedPct = job.progress.coerceIn(0f, 100f)
    val badgeColor = when (job.phase) {
        JobPhase.PAUSED, JobPhase.RECONNECTING -> StatusWarningAmber
        JobPhase.FAILED -> StatusOfflineRed
        JobPhase.SAVED -> StatusOnlineGreen
        else -> GreenIconTint
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
                    text = job.phase.label,
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

            if (isIndeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(ShapeCorner4)
                        .semantics { contentDescription = "${job.phase.label} progress" },
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
                val frameLabel = if (job.totalFrames > 0) {
                    "Frame ${job.currentFrame}/${job.totalFrames}"
                } else {
                    job.paramsSummary.ifBlank { "Working..." }
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

            if (job.phase.isActive && job.phase != JobPhase.DOWNLOADING) {
                Spacer(modifier = Modifier.height(14.dp))

                val canPauseResume = !job.controlInFlight &&
                    job.serverJobId != null &&
                    (job.phase == JobPhase.PROCESSING || job.phase == JobPhase.PAUSED || job.phase == JobPhase.QUEUED)

                val canCancel = !job.controlInFlight &&
                    job.phase != JobPhase.DOWNLOADING

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
