package com.upscaler.studio.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.LayoutInflater
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.upscaler.studio.R
import com.upscaler.studio.ui.theme.DarkCardBorder
import com.upscaler.studio.ui.theme.DarkSurface
import com.upscaler.studio.ui.theme.GreenIconBox
import com.upscaler.studio.ui.theme.GreenIconTint
import com.upscaler.studio.ui.theme.GreenPrimary
import com.upscaler.studio.ui.theme.ShapeCircle
import com.upscaler.studio.ui.theme.ShapeCorner12
import com.upscaler.studio.ui.theme.ShapeCorner14
import com.upscaler.studio.ui.theme.ShapeCorner18
import com.upscaler.studio.ui.theme.ShapeCorner20
import com.upscaler.studio.ui.theme.ShapeCorner6
import com.upscaler.studio.ui.theme.StatusOfflineRed
import com.upscaler.studio.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class VideoMeta(
    val thumbnail: Bitmap? = null,
    val durationSeconds: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float? = null,
    val filename: String = "clip.mp4"
)

private suspend fun extractVideoMeta(context: Context, uri: Uri): VideoMeta = withContext(Dispatchers.IO) {
    var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val col = c.getString(0)
                if (!col.isNullOrBlank()) displayName = col
            }
        }
    }

    var thumb: Bitmap? = null
    var durMs = 0L
    var w = 0
    var h = 0
    var fps: Float? = null

    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
        }
        thumb = retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0L)
    } catch (_: Exception) {
    } finally {
        runCatching { retriever.release() }
    }

    if (fps == null || fps <= 0f) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    if (w == 0 && fmt.containsKey(MediaFormat.KEY_WIDTH)) w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    if (h == 0 && fmt.containsKey(MediaFormat.KEY_HEIGHT)) h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val extracted = runCatching { fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                            .getOrElse { fmt.getFloat(MediaFormat.KEY_FRAME_RATE) }
                        if (extracted in 1f..240f) {
                            fps = extracted
                        }
                    }
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }
    }

    VideoMeta(
        thumbnail = thumb,
        durationSeconds = durMs / 1000L,
        width = w,
        height = h,
        fps = fps,
        filename = displayName
    )
}

@Composable
fun MediaImportPreviewCard(
    selectedUri: Uri?,
    isImporting: Boolean,
    onPickMedia: () -> Unit,
    onClearMedia: (() -> Unit)? = null,
    onMetaLoaded: (VideoMeta?) -> Unit = {},
    isActiveJobRunning: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var meta by remember(selectedUri) { mutableStateOf<VideoMeta?>(null) }
    var isExtracting by remember(selectedUri) { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(true) }
    var positionMs by remember(selectedUri) { mutableLongStateOf(0L) }
    var durationMs by remember(selectedUri) { mutableLongStateOf(0L) }

    val previewPlayer = remember(selectedUri) {
        selectedUri?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                playWhenReady = true
                prepare()
            }
        }
    }

    DisposableEffect(lifecycleOwner, previewPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                previewPlayer?.pause()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                if (isPlaying && !isActiveJobRunning) {
                    previewPlayer?.play()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            previewPlayer?.release()
        }
    }

    LaunchedEffect(isActiveJobRunning, previewPlayer) {
        if (isActiveJobRunning) {
            previewPlayer?.pause()
        } else if (isPlaying) {
            previewPlayer?.play()
        }
    }

    // Drive the import preview timeline.
    LaunchedEffect(previewPlayer, selectedUri) {
        val player = previewPlayer ?: return@LaunchedEffect
        while (true) {
            val dur = player.duration
            if (dur > 0L) durationMs = dur
            positionMs = player.currentPosition.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            delay(100)
        }
    }

    LaunchedEffect(selectedUri) {
        if (selectedUri != null) {
            isExtracting = true
            val extracted = extractVideoMeta(context, selectedUri)
            meta = extracted
            isExtracting = false
            onMetaLoaded(extracted)
        } else {
            meta = null
            isExtracting = false
            onMetaLoaded(null)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShapeCorner18)
            .background(DarkSurface)
            .border(1.dp, DarkCardBorder, ShapeCorner18)
            .padding(16.dp)
    ) {
        if (isImporting || (selectedUri != null && isExtracting && meta == null)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = GreenPrimary,
                    modifier = Modifier.size(38.dp),
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Importing video...",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Reading resolution, FPS & frame preview",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        } else if (selectedUri != null) {
            val activeMeta = meta
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Selected Video",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = activeMeta?.filename ?: "clip.mp4",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = onPickMedia,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text("Change", color = GreenIconTint, fontWeight = FontWeight.SemiBold)
                        }
                        if (onClearMedia != null) {
                            TextButton(
                                onClick = onClearMedia,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Text("Remove", color = StatusOfflineRed, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(ShapeCorner12)
                        .background(Color.Black)
                        .clickable {
                            previewPlayer?.let { p ->
                                if (p.isPlaying) {
                                    p.pause()
                                    isPlaying = false
                                } else {
                                    p.play()
                                    isPlaying = true
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (previewPlayer != null) {
                        AndroidView(
                            factory = { ctx ->
                                (LayoutInflater.from(ctx).inflate(R.layout.texture_player_view, null) as PlayerView).apply {
                                    player = previewPlayer
                                    useController = false
                                }
                            },
                            update = { it.player = previewPlayer },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (activeMeta?.thumbnail != null) {
                        Image(
                            bitmap = activeMeta.thumbnail.asImageBitmap(),
                            contentDescription = "Selected video frame preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.semantics {
                                contentDescription = "Video selected, thumbnail preview unavailable"
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = null,
                                tint = GreenIconTint,
                                modifier = Modifier.size(42.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Preview unavailable for this codec",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (!isPlaying) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(ShapeCircle)
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
                            previewPlayer?.let { p ->
                                val newMuted = !isMuted
                                p.volume = if (newMuted) 0f else 1f
                                isMuted = newMuted
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(34.dp)
                            .background(Color.Black.copy(alpha = 0.6f), ShapeCircle)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isMuted) "Unmute preview" else "Mute preview",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if ((activeMeta?.durationSeconds ?: 0L) > 0L) {
                            val mins = activeMeta!!.durationSeconds / 60
                            val secs = activeMeta.durationSeconds % 60
                            MetadataPill("%02d:%02d".format(mins, secs))
                        }
                        if ((activeMeta?.width ?: 0) > 0 && (activeMeta?.height ?: 0) > 0) {
                            MetadataPill("${activeMeta!!.width}×${activeMeta.height}")
                        }
                        if (activeMeta?.fps != null) {
                            MetadataPill("${"%.0f".format(activeMeta.fps)} FPS")
                        }
                    }
                }

                if (previewPlayer != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Slider(
                            value = if (durationMs > 0L) {
                                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            } else 0f,
                            onValueChange = { frac ->
                                if (durationMs > 0L) {
                                    val target = (frac * durationMs).toLong()
                                    previewPlayer.seekTo(target)
                                    positionMs = target
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = GreenPrimary,
                                activeTrackColor = GreenPrimary,
                                inactiveTrackColor = Color(0xFF1A2620)
                            )
                        )
                        Text(
                            text = "${formatTimelineTime(positionMs)} / ${formatTimelineTime(durationMs)}",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(ShapeCorner14)
                        .background(GreenIconBox),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = GreenIconTint,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Select Video",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Choose a video clip or AMV edit from your gallery",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onPickMedia,
                    shape = ShapeCorner20,
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Select Video from Gallery", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun formatTimelineTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSec / 60L, totalSec % 60L)
}

@Composable
private fun MetadataPill(text: String) {
    Box(
        modifier = Modifier
            .clip(ShapeCorner6)
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
