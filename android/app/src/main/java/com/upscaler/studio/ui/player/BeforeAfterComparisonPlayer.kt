package com.upscaler.studio.ui.player

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.upscaler.studio.R
import com.upscaler.studio.data.repository.UpscalerRepository
import com.upscaler.studio.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Plays the finished upscaled output.
 */
@Composable
fun BeforeAfterComparisonPlayer(
    outputUri: Uri,
    savedFileName: String?,
    onProcessAgain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isAlreadyInGallery = remember(outputUri) {
        outputUri.toString().contains("content://media/external/") ||
        outputUri.toString().contains("content://media/")
    }
    var isPlaying by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    val scope = rememberCoroutineScope()
    val repository = remember { UpscalerRepository.instance }
    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember(outputUri) { mutableStateOf(isAlreadyInGallery) }

    // Resolve outputUri to local direct file if it exists in internal storage,
    // which bypasses any FileProvider / ContentResolver issues for ExoPlayer
    val resolvedPlaybackUri = remember(outputUri, savedFileName) {
        val internalFile = savedFileName?.let { File(File(context.filesDir, "outputs"), it) }
        when {
            internalFile != null && internalFile.exists() && internalFile.length() > 0L -> {
                Uri.fromFile(internalFile)
            }
            outputUri.scheme == "file" -> outputUri
            else -> outputUri
        }
    }

    val outputPlayer = remember(resolvedPlaybackUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(resolvedPlaybackUri))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    LaunchedEffect(outputPlayer) {
        while (true) {
            isPlaying = outputPlayer.isPlaying
            val dur = outputPlayer.duration
            if (dur > 0L) durationMs = dur
            positionMs = outputPlayer.currentPosition.coerceAtLeast(0L)
            delay(100)
        }
    }

    DisposableEffect(lifecycleOwner, outputPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("BeforeAfterPlayer", "ExoPlayer error on $resolvedPlaybackUri", error)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) isPlaying = false
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        outputPlayer.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                outputPlayer.pause()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                if (isPlaying) outputPlayer.play()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            outputPlayer.removeListener(listener)
            outputPlayer.release()
        }
    }

    fun togglePlayPause() {
        when (outputPlayer.playbackState) {
            Player.STATE_ENDED -> {
                outputPlayer.seekTo(0L)
                outputPlayer.play()
                isPlaying = true
            }
            Player.STATE_IDLE -> {
                outputPlayer.prepare()
                outputPlayer.play()
                isPlaying = true
            }
            else -> {
                if (outputPlayer.isPlaying) {
                    outputPlayer.pause()
                    isPlaying = false
                } else {
                    outputPlayer.play()
                    isPlaying = true
                }
            }
        }
    }

    fun seekTo(targetMs: Long) {
        outputPlayer.seekTo(targetMs)
        positionMs = targetMs
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, DarkCardBorder, ShapeCorner18),
        shape = ShapeCorner18,
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Verified save status banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeCorner10)
                    .background(if (isSaved) StatusOnlineBg else Color(0xFF14241B))
                    .border(1.dp, if (isSaved) Color(0xFF163E2C) else Color(0xFF1B4D36), ShapeCorner10)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = StatusOnlineGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isSaved) "Saved to Movies/UpscalerStudio" else "Output Video Ready",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    if (!savedFileName.isNullOrBlank()) {
                        Text(
                            text = savedFileName,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Final upscaled video
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(ShapeCorner12)
                    .background(Color.Black)
                    .clickable { togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(R.layout.texture_player_view, null) as PlayerView).apply {
                            player = outputPlayer
                            useController = false
                        }
                    },
                    update = { pv ->
                        if (pv.player != outputPlayer) pv.player = outputPlayer
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (!isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play video preview",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        val newMuted = !isMuted
                        outputPlayer.volume = if (newMuted) 0f else 1f
                        isMuted = newMuted
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute audio" else "Mute audio",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Playback controls & seek slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { togglePlayPause() },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(GreenPillBg)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause preview" else "Play preview",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Slider(
                    value = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                    onValueChange = { frac ->
                        if (durationMs > 0L) {
                            seekTo((frac * durationMs).toLong())
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = GreenLight,
                        activeTrackColor = GreenPrimary,
                        inactiveTrackColor = Color(0xFF1A2620)
                    )
                )

                Text(
                    text = "${formatTimeMs(positionMs)} / ${formatTimeMs(durationMs)}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action row: Save to Gallery, Share, New Edit
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Button 1: Save to Gallery
                Button(
                    onClick = {
                        if (!isSaving && !isSaved) {
                            scope.launch {
                                isSaving = true
                                val fileName = savedFileName ?: "enhanced_${System.currentTimeMillis()}.mp4"
                                val res = repository.saveVideoToGallery(context, outputUri, fileName)
                                isSaving = false
                                if (res.isSuccess) {
                                    isSaved = true
                                    Toast.makeText(context, "Saved to Gallery (Movies/UpscalerStudio)", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Save failed: ${res.exceptionOrNull()?.message ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else if (isSaved) {
                            Toast.makeText(context, "Already saved to Gallery (Movies/UpscalerStudio)", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = ShapeCorner12,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSaved) Color(0xFF14241B) else GreenPrimary,
                        disabledContainerColor = Color(0xFF14241B)
                    ),
                    border = if (isSaved) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1B4D36)) else null,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Saving...",
                                color = Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (isSaved) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = GreenIconTint,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Saved",
                                color = Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Save",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Button 2: Share
                OutlinedButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/*"
                            putExtra(Intent.EXTRA_STREAM, outputUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(Intent.createChooser(shareIntent, "Share Video")) }
                    },
                    shape = ShapeCorner12,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF141724),
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Share",
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Button 3: New Edit
                OutlinedButton(
                    onClick = onProcessAgain,
                    shape = ShapeCorner12,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF141724),
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "New Edit",
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSec / 60L, totalSec % 60L)
}
