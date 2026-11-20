package com.upscaler.studio.ui.studios

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.upscaler.studio.data.repository.ConnectionState
import com.upscaler.studio.data.repository.UpscalerRepository
import com.upscaler.studio.ui.theme.*

data class VideoGenModeInfo(
    val key: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val subtitle: String
)

private val VIDEOGEN_MODES = listOf(
    VideoGenModeInfo("t2v", "Text to Video", Icons.Default.AutoAwesome, "Wan 2.1 1.3B / Wan 2.2 5B"),
    VideoGenModeInfo("i2v", "Image to Video", Icons.Default.Image, "Animate still images"),
    VideoGenModeInfo("v2v", "Video to Video", Icons.Default.MovieFilter, "Wan VACE + Depth guidance"),
    VideoGenModeInfo("motion_copy", "Motion Copy", Icons.Default.Person, "LivePortrait / Wan Animate"),
    VideoGenModeInfo("character_exchange", "Character Swap", Icons.Default.Face, "Wan VACE / MoCha swap")
)

@Composable
fun VideoGenStudioScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { UpscalerRepository.instance }
    val draft = remember { repository.getStudioDraft("videogen") }

    var selectedMode by remember { mutableStateOf(draft["selectedMode"] as? String ?: "t2v") }
    var prompt by remember { mutableStateOf(draft["prompt"] as? String ?: "") }
    var negativePrompt by remember { mutableStateOf(draft["negativePrompt"] as? String ?: "") }
    var selectedModel by remember { mutableStateOf(draft["selectedModel"] as? String ?: "wan22_ti2v_5b") }
    var selectedAspectRatio by remember { mutableStateOf(draft["selectedAspectRatio"] as? String ?: "16:9") }
    var selectedDuration by remember { mutableIntStateOf((draft["selectedDuration"] as? Number)?.toInt() ?: 5) }
    var selectedResolution by remember { mutableStateOf(draft["selectedResolution"] as? String ?: "480p") }

    var primaryMediaUri by remember {
        mutableStateOf(draft["primaryMediaUri"]?.toString()?.let { Uri.parse(it) })
    }
    var referenceImageUri by remember {
        mutableStateOf(draft["referenceImageUri"]?.toString()?.let { Uri.parse(it) })
    }

    LaunchedEffect(
        selectedMode, prompt, negativePrompt, selectedModel,
        selectedAspectRatio, selectedDuration, selectedResolution,
        primaryMediaUri, referenceImageUri
    ) {
        repository.updateStudioDraft(
            "videogen",
            mapOf(
                "selectedMode" to selectedMode,
                "prompt" to prompt,
                "negativePrompt" to negativePrompt,
                "selectedModel" to selectedModel,
                "selectedAspectRatio" to selectedAspectRatio,
                "selectedDuration" to selectedDuration,
                "selectedResolution" to selectedResolution,
                "primaryMediaUri" to primaryMediaUri?.toString(),
                "referenceImageUri" to referenceImageUri?.toString()
            )
        )
    }

    val videoGenConn by repository.videoGenConnectionState.collectAsState()
    val isConnected = videoGenConn is ConnectionState.Connected

    val primaryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) primaryMediaUri = uri }

    val referencePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) referenceImageUri = uri }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🎬 Video Gen Studio",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isConnected) "Wan 2.1 / 2.2 Video Engine Online" else "Video Gen Backend Offline",
                            color = if (isConnected) GreenIconTint else TextMuted,
                            fontSize = 11.5.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(ShapeCorner10)
                            .background(if (isConnected) Color(0xFF0F261B) else Color(0xFF2E171D))
                            .border(1.dp, if (isConnected) Color(0xFF1B4D36) else Color(0xFF4D2029), ShapeCorner10)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) StatusOnlineGreen else StatusOfflineRed)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isConnected) "Online" else "Offline",
                                color = if (isConnected) StatusOnlineGreen else StatusOfflineRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Modality Selector Bar
            Text("Generation Modality", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeCorner14)
                    .background(DarkSurface)
                    .border(1.dp, DarkCardBorder, ShapeCorner14)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                VIDEOGEN_MODES.forEach { mode ->
                    val isSel = selectedMode == mode.key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(ShapeCorner10)
                            .background(if (isSel) Color(0xFF132A1F) else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSel) GreenPrimary.copy(alpha = 0.8f) else Color.Transparent,
                                ShapeCorner10
                            )
                            .clickable { selectedMode = mode.key }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = mode.icon,
                                contentDescription = mode.title,
                                tint = if (isSel) GreenIconTint else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = when (mode.key) {
                                    "t2v" -> "T2V"
                                    "i2v" -> "I2V"
                                    "v2v" -> "V2V"
                                    "motion_copy" -> "Motion"
                                    "character_exchange" -> "Swap"
                                    else -> mode.key
                                },
                                color = if (isSel) Color.White else TextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Media Pickers when applicable
            if (selectedMode in listOf("i2v", "v2v", "motion_copy", "character_exchange")) {
                Surface(
                    color = DarkSurface,
                    shape = ShapeCorner16,
                    border = BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = when (selectedMode) {
                                "i2v" -> "Primary Image (To Animate)"
                                "v2v" -> "Input Video (Source)"
                                "motion_copy" -> "Driving Video (Motion Source)"
                                "character_exchange" -> "Input Video (Scene)"
                                else -> "Primary Media"
                            },
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                val mimeType = if (selectedMode == "i2v") {
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                } else {
                                    ActivityResultContracts.PickVisualMedia.VideoOnly
                                }
                                primaryPicker.launch(PickVisualMediaRequest(mimeType))
                            },
                            shape = ShapeCorner12,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenPrimary),
                            border = BorderStroke(1.dp, GreenPrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (selectedMode == "i2v") Icons.Default.AddPhotoAlternate else Icons.Default.VideoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = primaryMediaUri?.lastPathSegment ?: "Select Media",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Secondary reference image for motion copy & character exchange
                        if (selectedMode in listOf("motion_copy", "character_exchange")) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = when (selectedMode) {
                                    "motion_copy" -> "Target Portrait (Appearance Source)"
                                    "character_exchange" -> "Target Character Face/Body Image"
                                    else -> "Reference Image"
                                },
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                shape = ShapeCorner12,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenPrimary),
                                border = BorderStroke(1.dp, GreenPrimary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = referenceImageUri?.lastPathSegment ?: "Select Reference Image",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Prompt Input Card
            Surface(
                color = DarkSurface,
                shape = ShapeCorner16,
                border = BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Prompt", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        placeholder = {
                            Text(
                                "Describe cinematic camera movement, subject action, atmosphere, and lighting...",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = GreenPrimary,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))
                    var showNegPrompt by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .clip(ShapeCorner8)
                            .clickable { showNegPrompt = !showNegPrompt }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showNegPrompt) "Hide Negative Prompt" else "+ Add Negative Prompt",
                            color = GreenIconTint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    AnimatedVisibility(visible = showNegPrompt) {
                        Column {
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = negativePrompt,
                                onValueChange = { negativePrompt = it },
                                placeholder = {
                                    Text("blurry, distorted, low quality, artifacts...", color = TextMuted, fontSize = 12.5.sp)
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = GreenPrimary,
                                    unfocusedBorderColor = DarkCardBorder,
                                    focusedContainerColor = DarkSurfaceVariant,
                                    unfocusedContainerColor = DarkSurfaceVariant
                                ),
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Model Selection Card
            Surface(
                color = DarkSurface,
                shape = ShapeCorner16,
                border = BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Model & Pipeline", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    val modelOptions = when (selectedMode) {
                        "t2v" -> listOf(
                            "wan22_ti2v_5b" to "Wan 2.2 TI2V 5B (Recommended)",
                            "wan21_t2v_1_3b" to "Wan 2.1 T2V 1.3B (Ultra Fast · T4)",
                            "wan22_t2v_14b" to "Wan 2.2 T2V A14B (Flagship 24GB+)",
                            "ltx25_av" to "LTX-2.5 (Fast + Native Audio)",
                            "hunyuan_video_1_5" to "HunyuanVideo 1.5 (8.3B Cinematic)"
                        )
                        "i2v" -> listOf(
                            "wan22_ti2v_5b" to "Wan 2.2 TI2V 5B (Recommended)",
                            "wan22_i2v_14b" to "Wan 2.2 I2V A14B (Flagship 24GB+)",
                            "hunyuan_video_1_5" to "HunyuanVideo 1.5 (Cinematic)",
                            "ltx25_av" to "LTX-2.5 (Fast I2V)"
                        )
                        "v2v" -> listOf(
                            "wan21_vace_1_3b" to "Wan 2.1 VACE 1.3B (T4 Ready)",
                            "wan21_vace_14b" to "Wan 2.1 VACE 14B (Flagship 24GB+)"
                        )
                        "motion_copy" -> listOf(
                            "liveportrait_face" to "LivePortrait (Face & Portrait · T4)",
                            "wan22_animate_14b" to "Wan 2.2 Animate-2 14B (Full Body)"
                        )
                        "character_exchange" -> listOf(
                            "wan21_vace_1_3b" to "Wan 2.1 VACE Swap-Anything",
                            "wan22_animate_14b" to "Wan 2.2 Animate Replacement",
                            "mocha_swap" to "MoCha Character Swap (24GB+)"
                        )
                        else -> listOf("wan22_ti2v_5b" to "Wan 2.2 TI2V 5B")
                    }

                    modelOptions.forEach { (key, label) ->
                        val isChosen = selectedModel == key || (selectedModel !in modelOptions.map { it.first } && key == modelOptions.first().first)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ShapeCorner10)
                                .background(if (isChosen) Color(0xFF132A1F) else Color.Transparent)
                            .border(
                                1.dp,
                                if (isChosen) GreenPrimary.copy(alpha = 0.7f) else DarkCardBorder,
                                ShapeCorner10
                            )
                            .clickable { selectedModel = key }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isChosen,
                                onClick = { selectedModel = key },
                                colors = RadioButtonDefaults.colors(selectedColor = GreenPrimary)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = if (isChosen) Color.White else TextSecondary, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // Aspect Ratio & Duration Row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    color = DarkSurface,
                    shape = ShapeCorner16,
                    border = BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Aspect Ratio", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("16:9", "9:16", "1:1").forEach { ratio ->
                                val sel = selectedAspectRatio == ratio
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(ShapeCorner8)
                                        .background(if (sel) GreenPillBg else DarkSurfaceVariant)
                                        .border(1.dp, if (sel) GreenPrimary else Color.Transparent, ShapeCorner8)
                                        .clickable { selectedAspectRatio = ratio }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = ratio,
                                        color = if (sel) GreenIconTint else TextSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    color = DarkSurface,
                    shape = ShapeCorner16,
                    border = BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Duration", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(3, 5, 8).forEach { dur ->
                                val sel = selectedDuration == dur
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(ShapeCorner8)
                                        .background(if (sel) GreenPillBg else DarkSurfaceVariant)
                                        .border(1.dp, if (sel) GreenPrimary else Color.Transparent, ShapeCorner8)
                                        .clickable { selectedDuration = dur }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${dur}s",
                                        color = if (sel) GreenIconTint else TextSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Generate Button
            Button(
                onClick = {
                    if (!isConnected) {
                        Toast.makeText(context, "Colab backend is offline. Connect first.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (selectedMode == "t2v" && prompt.isBlank()) {
                        Toast.makeText(context, "Please enter a prompt.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (selectedMode in listOf("i2v", "v2v") && primaryMediaUri == null) {
                        Toast.makeText(context, "Please select an input image/video.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (selectedMode in listOf("motion_copy", "character_exchange") && (primaryMediaUri == null || referenceImageUri == null)) {
                        Toast.makeText(context, "Please select both source video and target image.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    Toast.makeText(context, "Video Generation Job queued on Wan engine!", Toast.LENGTH_SHORT).show()
                },
                shape = ShapeCorner16,
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Generate with Wan AI",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
