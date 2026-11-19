package com.upscaler.studio.ui.studios

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.upscaler.studio.data.model.JobPhase
import com.upscaler.studio.data.model.ManagedJob
import com.upscaler.studio.data.repository.ConnectionState
import com.upscaler.studio.data.repository.UpscalerRepository
import com.upscaler.studio.ui.components.LiveProgressCard
import com.upscaler.studio.ui.components.MediaImportPreviewCard
import com.upscaler.studio.ui.components.VideoMeta
import com.upscaler.studio.ui.player.BeforeAfterComparisonPlayer
import com.upscaler.studio.ui.theme.DarkBackground
import com.upscaler.studio.ui.theme.DarkCardBorder
import com.upscaler.studio.ui.theme.DarkSurface
import com.upscaler.studio.ui.theme.GreenIconTint
import com.upscaler.studio.ui.theme.GreenLight
import com.upscaler.studio.ui.theme.GreenPillBg
import com.upscaler.studio.ui.theme.GreenPrimary
import com.upscaler.studio.ui.theme.GreenVibrantGradientBrush
import com.upscaler.studio.ui.theme.InactiveGradientBrush
import com.upscaler.studio.ui.theme.ShapeCorner10
import com.upscaler.studio.ui.theme.ShapeCorner12
import com.upscaler.studio.ui.theme.ShapeCorner14
import com.upscaler.studio.ui.theme.ShapeCorner18
import com.upscaler.studio.ui.theme.ShapeCorner20
import com.upscaler.studio.ui.theme.StatusOfflineBg
import com.upscaler.studio.ui.theme.StatusOfflineRed
import com.upscaler.studio.ui.theme.StatusWarningAmber
import com.upscaler.studio.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScaffold(
    title: String,
    taskType: String,
    ctaLabel: String,
    ctaIcon: ImageVector,
    selectedMediaUri: Uri?,
    onSelectMediaUri: (Uri?) -> Unit,
    onMetaLoaded: (VideoMeta?) -> Unit = {},
    ctaEnabledOverride: Boolean = true,
    disabledReason: String? = null,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    controlsContent: @Composable ColumnScope.(isLocked: Boolean) -> Unit
) {
    val context = LocalContext.current
    val repository = remember { UpscalerRepository.instance }
    val connectionState by repository.connectionState.collectAsState()
    val allJobs by repository.jobs.collectAsState()

    val studioJob: ManagedJob? = remember(allJobs, taskType) {
        allJobs.values
            .filter { it.taskType == taskType }
            .maxByOrNull { it.createdAtMs }
    }

    val effectiveSelectedUri: Uri? = selectedMediaUri

    var isPicking by remember { mutableStateOf(false) }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        isPicking = false
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onSelectMediaUri(uri)
        }
    }

    // Legacy API 26-28 public Movies write permission request before export
    var pendingSubmitAfterPerm by remember { mutableStateOf(false) }
    val legacyWritePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (pendingSubmitAfterPerm) {
            pendingSubmitAfterPerm = false
            onSubmit()
        }
    }

    fun launchWithStorageCheck() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingSubmitAfterPerm = true
                legacyWritePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        onSubmit()
    }

    val isBackendConnected = connectionState is ConnectionState.Connected
    val jobActive = studioJob != null && studioJob.phase.isActive
    val hasCompletedPreview = studioJob != null && studioJob.phase == JobPhase.SAVED && !studioJob.savedUriString.isNullOrBlank()

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (!isBackendConnected) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeCorner14)
                        .background(StatusOfflineBg)
                        .border(1.dp, Color(0xFF4A2530), ShapeCorner14)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = StatusOfflineRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Backend offline. Connect to Colab before starting a job.",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            MediaImportPreviewCard(
                selectedUri = effectiveSelectedUri,
                isImporting = isPicking,
                onPickMedia = {
                    if (!jobActive) {
                        studioJob?.let {
                            if (it.phase.isTerminal) repository.dismissCompletedOrFailedJob(it.localId)
                        }
                        mediaPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    }
                },
                onClearMedia = if (!jobActive && !hasCompletedPreview) {
                    { onSelectMediaUri(null) }
                } else null,
                onMetaLoaded = onMetaLoaded,
                isActiveJobRunning = jobActive || hasCompletedPreview
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Lock controls while actively running or while reviewing a completed preview
            controlsContent(jobActive || hasCompletedPreview)

            Spacer(modifier = Modifier.height(20.dp))

            when {
                jobActive -> {
                    studioJob?.let { activeJob ->
                        LiveProgressCard(
                            job = activeJob,
                            onPauseToggle = { repository.togglePauseResume(activeJob.localId) },
                            onCancel = { repository.cancelManagedJob(activeJob.localId) },
                            onCheckStatus = { repository.checkAndRecoverJob(activeJob.localId) }
                        )
                    }
                }

                hasCompletedPreview -> {
                    studioJob?.let { savedJob ->
                        val outUri = remember(savedJob.savedUriString) { Uri.parse(savedJob.savedUriString) }
                        BeforeAfterComparisonPlayer(
                            outputUri = outUri,
                            savedFileName = savedJob.outputFileName,
                            onProcessAgain = { repository.dismissCompletedOrFailedJob(savedJob.localId) }
                        )
                    }
                }

                else -> {
                    if (studioJob != null && (studioJob.phase == JobPhase.FAILED || studioJob.phase == JobPhase.CANCELLED)) {
                        val isCancelled = studioJob.phase == JobPhase.CANCELLED
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ShapeCorner14)
                                .background(DarkSurface)
                                .border(
                                    1.dp,
                                    if (isCancelled) StatusWarningAmber else StatusOfflineRed,
                                    ShapeCorner14
                                )
                                .padding(14.dp)
                        ) {
                            Column {
                                Text(
                                    text = if (isCancelled) "Job Cancelled" else "Job Failed",
                                    color = if (isCancelled) StatusWarningAmber else StatusOfflineRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = studioJob.errorMessage ?: "Stopped before completion.",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (studioJob.canRetryDownload && !studioJob.serverJobId.isNullOrBlank()) {
                                        Button(
                                            onClick = { repository.retryDownload(studioJob.localId) },
                                            colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                                            shape = ShapeCorner12,
                                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Retry Download", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    } else if (!studioJob.serverJobId.isNullOrBlank() && !isCancelled) {
                                        OutlinedButton(
                                            onClick = { repository.checkAndRecoverJob(studioJob.localId) },
                                            shape = ShapeCorner12,
                                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                        ) {
                                            Text("Check Status", color = Color.White)
                                        }
                                    }
                                    TextButton(
                                        onClick = { repository.dismissCompletedOrFailedJob(studioJob.localId) },
                                        shape = ShapeCorner12,
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                    ) {
                                        Text("Dismiss", color = TextSecondary)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    if (!disabledReason.isNullOrBlank()) {
                        Text(
                            text = disabledReason,
                            color = StatusWarningAmber,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    val canSubmit = effectiveSelectedUri != null &&
                        isBackendConnected &&
                        ctaEnabledOverride

                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val buttonScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.98f else 1f,
                        label = "ctaScale"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(buttonScale)
                            .defaultMinSize(minHeight = 52.dp)
                            .clip(ShapeCorner20)
                            .background(if (canSubmit) GreenVibrantGradientBrush else InactiveGradientBrush)
                            .semantics { role = Role.Button }
                            .clickable(
                                enabled = canSubmit,
                                interactionSource = interactionSource,
                                indication = null
                            ) { launchWithStorageCheck() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Icon(
                                imageVector = ctaIcon,
                                contentDescription = null,
                                tint = if (canSubmit) Color.White else TextSecondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    !isBackendConnected -> "Connect Colab Backend First"
                                    effectiveSelectedUri == null -> "Select a Video Above"
                                    else -> ctaLabel
                                },
                                color = if (canSubmit) Color.White else TextSecondary,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun StudioOptionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeCorner18)
            .background(DarkSurface)
            .border(1.dp, DarkCardBorder, ShapeCorner18)
            .padding(16.dp)
    ) {
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun StudioOptionRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(ShapeCorner12)
            .background(if (selected) GreenPillBg else Color(0xFF141720))
            .border(1.dp, if (selected) GreenPrimary else DarkCardBorder, ShapeCorner12)
            .semantics { role = Role.RadioButton }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = if (selected) Color.White else TextSecondary,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Text("Selected", color = GreenIconTint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StudioChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(ShapeCorner10)
            .background(if (selected) GreenPillBg else Color(0xFF141720))
            .border(1.dp, if (selected) GreenPrimary else DarkCardBorder, ShapeCorner10)
            .semantics { role = Role.RadioButton }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun ExpectedOutputBanner(summary: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeCorner12)
            .background(Color(0xFF0F261B))
            .border(1.dp, Color(0xFF1B4D36), ShapeCorner12)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = summary,
            color = GreenIconTint,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
