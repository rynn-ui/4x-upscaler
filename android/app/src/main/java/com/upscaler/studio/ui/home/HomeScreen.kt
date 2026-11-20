package com.upscaler.studio.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.google.android.gms.common.api.ApiException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.upscaler.studio.ui.navigation.Screen
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.upscaler.studio.R
import com.upscaler.studio.data.auth.GoogleAuthManager
import com.upscaler.studio.data.remoteconfig.AppUpdateStatus
import com.upscaler.studio.data.remoteconfig.RemoteConfigManager
import com.upscaler.studio.ui.components.AppUpdateDialog
import com.upscaler.studio.data.model.JobPhase
import com.upscaler.studio.data.model.ManagedJob
import com.upscaler.studio.data.model.SavedMediaItem
import com.upscaler.studio.data.repository.BackendUrlParser
import com.upscaler.studio.data.repository.ColabTarget
import com.upscaler.studio.data.repository.ConnectionState
import com.upscaler.studio.data.repository.UpscalerRepository
import com.upscaler.studio.ui.theme.*
import kotlinx.coroutines.launch

data class StudioItem(
    val key: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val route: String,
    val badge: String
)

private val CORE_STUDIOS = listOf(
    StudioItem(
        key = "upscale",
        title = "2X Super-Resolution",
        description = "AI upscaling for anime, AMVs & movies to crisp 2× HD/4K.",
        icon = Icons.Default.Bolt,
        route = "studio_upscale",
        badge = "Real-ESRGAN"
    ),
    StudioItem(
        key = "rife",
        title = "RIFE 60/120 FPS",
        description = "Butter-smooth frame interpolation & slow-motion.",
        icon = Icons.Default.MovieFilter,
        route = "studio_rife",
        badge = "RIFE v4.x"
    ),
    StudioItem(
        key = "denoise",
        title = "AI Anti-Line Denoiser",
        description = "Removes compression banding, horizontal tears & lines.",
        icon = Icons.Default.CleaningServices,
        route = "studio_denoise",
        badge = "1x-Archiver"
    ),
    StudioItem(
        key = "depth",
        title = "Depth Map Studio",
        description = "Generates monocular depth maps for 3D displacement.",
        icon = Icons.Default.Layers,
        route = "studio_depth",
        badge = "Depth Anything V2"
    ),
    StudioItem(
        key = "videogen",
        title = "Video Gen Studio",
        description = "Text-to-Video, Image Animation, Character Swap & Motion Copy.",
        icon = Icons.Default.AutoAwesomeMotion,
        route = "studio_videogen",
        badge = "Wan 2.1/2.2"
    )
)

private fun routeForTask(taskType: String): String = when (taskType) {
    "upscale" -> "studio_upscale"
    "rife" -> "studio_rife"
    "denoise" -> "studio_denoise"
    "depth" -> "studio_depth"
    "videogen" -> "studio_videogen"
    else -> "home"
}

private fun titleForTask(taskType: String): String = when (taskType) {
    "upscale" -> "2X Super-Resolution"
    "rife" -> "RIFE Frame Interpolation"
    "denoise" -> "AI Anti-Line Denoiser"
    "depth" -> "Depth Map Studio"
    "videogen" -> "Video Gen Studio"
    else -> taskType
}

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { UpscalerRepository.instance }
    val connectionState by repository.connectionState.collectAsState()
    val currentUrl by repository.currentBaseUrl.collectAsState()
    val hasToken by repository.hasAuthToken.collectAsState()
    val jobsMap by repository.jobs.collectAsState()

    val selectedColabTarget by repository.selectedColabTarget.collectAsState()
    val useDedicatedVideoGen by repository.useDedicatedVideoGen.collectAsState()
    val videoGenConnectionState by repository.videoGenConnectionState.collectAsState()
    val videoGenBaseUrl by repository.videoGenBaseUrl.collectAsState()
    val videoGenHasToken by repository.videoGenHasAuthToken.collectAsState()

    var showUrlDialog by remember { mutableStateOf(false) }
    var dialogTarget by remember { mutableStateOf(ColabTarget.PRIMARY) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var inputUrl by remember { mutableStateOf("") }
    var inputToken by remember { mutableStateOf("") }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var isConnectingDialog by remember { mutableStateOf(false) }
    var pendingSwitchDialogUrl by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // --- Firebase Remote Config (Force Update) ---
    val remoteConfigManager = remember { RemoteConfigManager.getInstance(context) }
    val updateStatus by remoteConfigManager.updateStatus.collectAsState()
    var showOptionalUpdateDialog by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        remoteConfigManager.fetchAndCheck()
    }

    // --- Google Sign-In & Firebase Auth ---
    val googleAuth = remember { GoogleAuthManager.init(context) }
    var googleIdToken by remember { mutableStateOf<String?>(null) }
    var googleSignedInAs by remember { mutableStateOf<String?>(null) }

    // Enforce mandatory sign-in: if not signed in, route directly to AuthScreen
    LaunchedEffect(googleAuth.isSignedIn) {
        if (!googleAuth.isSignedIn) {
            onNavigate(Screen.Auth.route)
        } else {
            googleIdToken = googleAuth.idToken
            googleSignedInAs = buildString {
                googleAuth.name?.let { append(it) }
                googleAuth.email?.let { append(" <").append(it).append(">") }
            }.takeIf { it.isNotEmpty() } ?: "Signed in"
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        val res = googleAuth.handleSignInResult(result) { firebaseResult ->
            if (firebaseResult.isSuccess) {
                val fUser = firebaseResult.getOrThrow()
                googleSignedInAs = buildString {
                    fUser.displayName?.let { append(it) }
                    fUser.email?.let { append(" <").append(it).append(">") }
                }.takeIf { it.isNotEmpty() } ?: "Signed in"
                Toast.makeText(context, "Firebase Authenticated: ${fUser.email}", Toast.LENGTH_SHORT).show()
            } else {
                val err = firebaseResult.exceptionOrNull()?.message ?: "Firebase Auth failed"
                Log.w("HomeScreen", "Firebase Auth notice: $err")
            }
        }
        if (res.isSuccess) {
            val account = res.getOrThrow()
            googleIdToken = account.idToken ?: null
            inputToken = account.idToken ?: ""
            googleSignedInAs = buildString {
                account.displayName?.let { append(it) }
                account.email?.let { append(" <").append(it).append(">") }
            }.takeIf { it.isNotEmpty() } ?: "Signed in"

            if (account.idToken.isNullOrBlank()) {
                Toast.makeText(
                    context,
                    "Signed in as ${account.email}\nNote: To create Firebase UID, add SHA-1 & Web Client ID in Firebase Console.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(context, "Signed in as ${account.email}", Toast.LENGTH_SHORT).show()
            }
        } else {
            googleSignedInAs = null
            val err = res.exceptionOrNull()
            Log.e("HomeScreen", "Google sign-in error: ${err?.message}", err)
            if (err is ApiException) {
                val detail = when (err.statusCode) {
                    10 -> "Developer Error (10): Add SHA-1 fingerprint or Web Client ID in Firebase Console."
                    12500 -> "Configuration Error (12500): Check SHA-1 fingerprint in Firebase Console."
                    7 -> "Network error during Google Sign-In. Check internet connection."
                    16 -> "Sign-in cancelled."
                    else -> "Google Sign-In failed (${err.statusCode}): ${err.message}"
                }
                if (err.statusCode != 16) {
                    dialogError = detail
                    Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
                }
            } else {
                val msg = err?.message ?: "Google Sign-In failed"
                dialogError = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Capabilities-driven Studio list
    val studios = remember(connectionState) {
        val state = connectionState
        if (state is ConnectionState.Connected && state.capabilities != null) {
            val enabledCaps = state.capabilities.studios
            CORE_STUDIOS.filter { enabledCaps[it.key]?.enabled != false }
        } else {
            CORE_STUDIOS
        }
    }

    val jobsList = remember(jobsMap) {
        jobsMap.values.sortedByDescending { it.createdAtMs }
    }
    val activeQueueCount = remember(jobsList) {
        jobsList.count { it.phase.isActive }
    }

    var savedFiles by remember { mutableStateOf<List<SavedMediaItem>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<SavedMediaItem?>(null) }

    LaunchedEffect(selectedTab, jobsMap) {
        if (selectedTab == 2) {
            isLoadingFiles = true
            savedFiles = repository.loadSavedMedia(context)
            isLoadingFiles = false
        }
    }

    fun openConnectDialog(target: ColabTarget = selectedColabTarget) {
        dialogTarget = target
        inputUrl = if (target == ColabTarget.VIDEOGEN) videoGenBaseUrl else currentUrl
        inputToken = ""
        dialogError = null
        pendingSwitchDialogUrl = null
        showUrlDialog = true
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = GreenPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = when (selectedTab) {
                            1 -> "Render Queue"
                            2 -> "Saved Files"
                            else -> "Upscaler Studio"
                        },
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // User Account + Settings buttons (right side)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { showAccountDialog = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AccountCircle,
                            contentDescription = "User Account",
                            tint = if (googleSignedInAs != null) GreenPrimary else TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = { onNavigate("settings") },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = DarkBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomNavTab(
                        label = "Home",
                        icon = Icons.Default.Home,
                        isSelected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    )
                    BottomNavTab(
                        label = if (activeQueueCount > 0) "Queue ($activeQueueCount)" else "Queue",
                        icon = Icons.AutoMirrored.Outlined.FormatListBulleted,
                        isSelected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    )
                    BottomNavTab(
                        label = "Files",
                        icon = Icons.Outlined.Folder,
                        isSelected = selectedTab == 2,
                        onClick = { selectedTab = 2 }
                    )
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> HomeTabContent(
                selectedColabTarget = selectedColabTarget,
                onSelectColabTarget = { repository.selectColabTarget(it) },
                useDedicatedVideoGen = useDedicatedVideoGen,
                onToggleDedicatedVideoGen = { repository.setUseDedicatedVideoGen(it) },
                primaryConnectionState = connectionState,
                videoGenConnectionState = videoGenConnectionState,
                primaryBaseUrl = currentUrl,
                videoGenBaseUrl = videoGenBaseUrl,
                studios = studios,
                onOpenColab = {
                    val colabUrl = if (selectedColabTarget == ColabTarget.VIDEOGEN) {
                        remoteConfigManager.getVideoGenColabNotebookUrl()
                    } else {
                        remoteConfigManager.getColabNotebookUrl()
                    }
                    val colabIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(colabUrl)
                    )
                    runCatching { context.startActivity(colabIntent) }
                },
                onDisconnectColab = {
                    if (selectedColabTarget == ColabTarget.VIDEOGEN) {
                        repository.disconnectVideoGen()
                    } else {
                        repository.disconnect()
                    }
                },
                onScanQr = { onNavigate("qr_scanner") },
                onOpenPasteDialog = { openConnectDialog(selectedColabTarget) },
                onRetryConnect = {
                    if (selectedColabTarget == ColabTarget.VIDEOGEN) {
                        if (videoGenBaseUrl.isNotBlank()) {
                            scope.launch { repository.refreshVideoGenHealthIfConfigured() }
                        } else {
                            openConnectDialog(ColabTarget.VIDEOGEN)
                        }
                    } else {
                        if (currentUrl.isNotBlank()) {
                            scope.launch { repository.refreshHealthIfConfigured() }
                        } else {
                            openConnectDialog(ColabTarget.PRIMARY)
                        }
                    }
                },
                onNavigateStudio = onNavigate,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )

            1 -> QueueTabContent(
                jobs = jobsList,
                onOpenStudio = { job -> onNavigate(routeForTask(job.taskType)) },
                onPauseToggle = { job -> repository.togglePauseResume(job.localId) },
                onCancel = { job -> repository.cancelManagedJob(job.localId) },
                onRetryDownload = { job -> repository.retryDownload(job.localId) },
                onDismiss = { job -> repository.removeJob(job.localId) },
                onSaveJobToGallery = { job ->
                    val uri = job.savedUriString?.let { Uri.parse(it) }
                    if (uri != null) {
                        scope.launch {
                            val res = repository.saveVideoToGallery(context, uri, job.outputFileName)
                            if (res.isSuccess) {
                                Toast.makeText(context, "Saved to Gallery: ${job.outputFileName}", Toast.LENGTH_SHORT).show()
                                savedFiles = repository.loadSavedMedia(context)
                            } else {
                                Toast.makeText(context, "Failed to save: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )

            2 -> FilesTabContent(
                files = savedFiles,
                isLoading = isLoadingFiles,
                onRefresh = {
                    scope.launch {
                        isLoadingFiles = true
                        savedFiles = repository.loadSavedMedia(context)
                        isLoadingFiles = false
                    }
                },
                onSaveFile = { item ->
                    scope.launch {
                        val res = repository.saveVideoToGallery(context, Uri.parse(item.uriString), item.displayName)
                        if (res.isSuccess) {
                            Toast.makeText(context, "Saved to Gallery: ${item.displayName}", Toast.LENGTH_SHORT).show()
                            savedFiles = repository.loadSavedMedia(context)
                        } else {
                            val err = res.exceptionOrNull()?.message ?: "Failed to save to gallery"
                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onOpenFile = { item ->
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(item.uriString), "video/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(viewIntent, "Open Video")) }
                },
                onShareFile = { item ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/*"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uriString))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(shareIntent, "Share Video")) }
                },
                onDeleteFile = { item ->
                    fileToDelete = item
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isConnectingDialog) showUrlDialog = false
            },
            containerColor = DarkSurface,
            title = {
                Text(
                    text = if (dialogTarget == ColabTarget.VIDEOGEN) "Connect Video Gen Colab" else "Connect Enhancement Colab",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    // Colab Target Selector in dialog
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ShapeCorner10)
                            .background(Color(0xFF141724))
                            .border(1.dp, DarkCardBorder, ShapeCorner10)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val isPrim = dialogTarget == ColabTarget.PRIMARY
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(ShapeCorner8)
                                .background(if (isPrim) GreenPillBg else Color.Transparent)
                                .clickable {
                                    dialogTarget = ColabTarget.PRIMARY
                                    inputUrl = currentUrl
                                    dialogError = null
                                }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "⚡ Enhancement",
                                color = if (isPrim) GreenIconTint else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        val isVid = dialogTarget == ColabTarget.VIDEOGEN
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(ShapeCorner8)
                                .background(if (isVid) GreenPillBg else Color.Transparent)
                                .clickable {
                                    dialogTarget = ColabTarget.VIDEOGEN
                                    inputUrl = videoGenBaseUrl
                                    dialogError = null
                                }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "🎬 Video Gen",
                                color = if (isVid) GreenIconTint else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (dialogTarget == ColabTarget.VIDEOGEN)
                            "Paste the Video Gen Colab Cloudflare/Ngrok URL (Wan 2.1/2.2):"
                        else
                            "Paste your https://...trycloudflare.com URL or upscaler://connect pairing link:",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = {
                            inputUrl = it
                            dialogError = null
                        },
                        placeholder = { Text("https://...trycloudflare.com", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = GreenPrimary,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (pendingSwitchDialogUrl != null) {
                        Surface(
                            color = GreenPillBg,
                            shape = ShapeCorner8,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Text(
                                "Switching backend host will retain your existing jobs running on the previous backend. Tap Connect again to confirm.",
                                color = GreenIconTint,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = inputToken,
                        onValueChange = {
                            inputToken = it
                            dialogError = null
                        },
                        label = {
                            Text(
                                if (hasToken) "Session Token (leave blank to retain saved token)" else "Session Token (auto-filled by link/QR)",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        },
                        placeholder = {
                            if (hasToken) Text("Saved token will be retained", color = TextMuted) else null
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = GreenPrimary,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    // --- Google Sign-In block ---
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = DarkSurfaceVariant,
                        shape = ShapeCorner8,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (googleSignedInAs != null) "Google account" else "Sign in with Google",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (googleSignedInAs != null) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = GreenPrimary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { googleSignInLauncher.launch(googleAuth.signInIntent()) },
                                enabled = googleIdToken == null || googleSignedInAs == null,
                                shape = ShapeCorner10,
                                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 40.dp)
                            ) {
                                if (googleSignedInAs != null) {
                                    Text("Signed in with Google", color = Color.White, fontWeight = FontWeight.Bold)
                                } else {
                                    Text(
                                        text = stringResource(id = R.string.google_sign_in_button),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            val signedInAs = googleSignedInAs
                            if (signedInAs != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = signedInAs,
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap Connect to bind this Google account to the backend session.",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    if (googleSignedInAs != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                googleAuth.signOut()
                                googleIdToken = null
                                googleSignedInAs = null
                                inputToken = ""
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = StatusOfflineRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sign out of Google", color = StatusOfflineRed, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (!dialogError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = dialogError!!, color = StatusOfflineRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isConnectingDialog,
                    onClick = {
                        val parsed = BackendUrlParser.parsePairingInput(inputUrl)
                        if (parsed.isFailure) {
                            dialogError = parsed.exceptionOrNull()?.localizedMessage ?: "Invalid backend URL."
                            return@Button
                        }
                        val candidateBaseUrl = parsed.getOrThrow().baseUrl
                        val targetUrl = if (dialogTarget == ColabTarget.VIDEOGEN) videoGenBaseUrl else currentUrl
                        val isConnected = if (dialogTarget == ColabTarget.VIDEOGEN) videoGenConnectionState is ConnectionState.Connected else connectionState is ConnectionState.Connected
                        if (isConnected && targetUrl.isNotBlank() && !targetUrl.equals(candidateBaseUrl, ignoreCase = true) && pendingSwitchDialogUrl == null) {
                            pendingSwitchDialogUrl = candidateBaseUrl
                            return@Button
                        }
                        pendingSwitchDialogUrl = null
                        isConnectingDialog = true
                        dialogError = null
                        scope.launch {
                            val res = if (dialogTarget == ColabTarget.VIDEOGEN) {
                                repository.connectAndVerifyVideoGen(inputUrl, inputToken.ifBlank { null })
                            } else {
                                repository.connectAndVerify(inputUrl, inputToken.ifBlank { null }, ColabTarget.PRIMARY)
                            }
                            isConnectingDialog = false
                            if (res.isSuccess) {
                                showUrlDialog = false
                            } else {
                                dialogError = res.exceptionOrNull()?.localizedMessage ?: "Connection failed."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    if (isConnectingDialog) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Connect", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isConnectingDialog,
                    onClick = { showUrlDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // --- User Account Dialog (Matching Pixel-Perfect Profile Card) ---
    if (showAccountDialog) {
        Dialog(
            onDismissRequest = { showAccountDialog = false }
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF13151F)),
                border = BorderStroke(1.dp, Color(0xFF282D3B))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp)
                ) {
                    // Header: "Account" + Close "✕"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Account",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { showAccountDialog = false },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // User Profile Section: Circular Avatar + Name + Email + ID chip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val userPhoto = googleAuth.pictureUrl
                        val userInitial = googleAuth.initialLetter
                        val displayName = googleAuth.displayNameOrFallback
                        val userEmail = googleAuth.email ?: "Not signed in"
                        val displayId = googleAuth.formattedDisplayId
                        val rawUid = googleAuth.firebaseUid ?: displayId

                        // Circular Avatar
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF102E22))
                                .border(1.5.dp, Color(0xFF1F4A38), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!userPhoto.isNullOrBlank()) {
                                coil.compose.AsyncImage(
                                    model = userPhoto,
                                    contentDescription = displayName,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Text(
                                    text = userInitial,
                                    color = Color.White,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                color = Color.White,
                                fontSize = 17.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = userEmail,
                                color = TextSecondary,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            // ID Chip with Copy button
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF171B26))
                                    .border(1.dp, Color(0xFF262C3E), RoundedCornerShape(8.dp))
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("User ID", rawUid)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "ID copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ID: $displayId",
                                    color = TextSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy ID",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    HorizontalDivider(color = Color(0xFF232736), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Row 1: Account Provider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Badge,
                            contentDescription = "Account Provider",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Account provider",
                            color = Color.White,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = googleAuth.accountProvider,
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Row 2: Backend URL (clickable with chevron)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showAccountDialog = false
                                showUrlDialog = true
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "Backend URL",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Backend URL",
                            color = Color.White,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val urlDisplay = currentUrl.ifBlank { "Not connected" }
                        val truncatedUrl = if (urlDisplay.length > 28) urlDisplay.take(28) + "..." else urlDisplay
                        Text(
                            text = truncatedUrl,
                            color = TextSecondary,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Open backend settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Row 3: Session token with copy
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = "Session token",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Session token",
                            color = Color.White,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        val activeToken = repository.activeAuthToken ?: ""
                        val maskedToken = if (activeToken.length > 12) {
                            "${activeToken.take(5)}...${activeToken.takeLast(5)}"
                        } else if (activeToken.isNotBlank()) {
                            activeToken
                        } else {
                            "No token"
                        }
                        Text(
                            text = maskedToken,
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (activeToken.isNotBlank()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("upscaler_token", activeToken)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Session token copied", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "No active session token", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy token",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    HorizontalDivider(color = Color(0xFF232736), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(18.dp))

                    // Bottom: Sign out button
                    OutlinedButton(
                        onClick = {
                            googleAuth.signOut()
                            googleIdToken = null
                            googleSignedInAs = null
                            inputToken = ""
                            showAccountDialog = false
                            Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
                            onNavigate(Screen.Auth.route)
                        },
                        border = BorderStroke(1.dp, Color(0xFF282D3B)),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF141724),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Logout,
                                contentDescription = "Sign out",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sign out",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    if (fileToDelete != null) {
        val item = fileToDelete!!
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            containerColor = DarkSurface,
            title = {
                Text("Delete Saved Video?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "Are you sure you want to permanently delete \"${item.displayName}\"? This action cannot be undone.",
                    color = TextSecondary,
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = fileToDelete
                        fileToDelete = null
                        if (toDelete != null) {
                            scope.launch {
                                val success = repository.deleteSavedMedia(context, toDelete).getOrDefault(false)
                                if (success) {
                                    android.widget.Toast.makeText(context, "Deleted ${toDelete.displayName}", android.widget.Toast.LENGTH_SHORT).show()
                                    savedFiles = repository.loadSavedMedia(context)
                                } else {
                                    android.widget.Toast.makeText(context, "Unable to delete file", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusOfflineRed),
                    shape = ShapeCorner10
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { fileToDelete = null },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    shape = ShapeCorner10
                ) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }
}

@Composable
private fun BottomNavTab(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = 76.dp, minHeight = 52.dp)
            .clip(ShapeCorner14)
            .semantics {
                role = Role.Tab
                selected = isSelected
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(58.dp)
                .height(30.dp)
                .clip(ShapeCorner15)
                .background(if (isSelected) GreenPillBg else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) GreenIconTint else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = if (isSelected) GreenLight else TextSecondary,
            fontSize = 11.5.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun HomeTabContent(
    selectedColabTarget: ColabTarget,
    onSelectColabTarget: (ColabTarget) -> Unit,
    useDedicatedVideoGen: Boolean,
    onToggleDedicatedVideoGen: (Boolean) -> Unit,
    primaryConnectionState: ConnectionState,
    videoGenConnectionState: ConnectionState,
    primaryBaseUrl: String,
    videoGenBaseUrl: String,
    studios: List<StudioItem>,
    onOpenColab: () -> Unit,
    onDisconnectColab: () -> Unit,
    onScanQr: () -> Unit,
    onOpenPasteDialog: () -> Unit,
    onRetryConnect: () -> Unit,
    onNavigateStudio: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeState = when (selectedColabTarget) {
        ColabTarget.PRIMARY -> primaryConnectionState
        ColabTarget.VIDEOGEN -> if (useDedicatedVideoGen) videoGenConnectionState else primaryConnectionState
    }
    val activeBaseUrl = when (selectedColabTarget) {
        ColabTarget.PRIMARY -> primaryBaseUrl
        ColabTarget.VIDEOGEN -> if (useDedicatedVideoGen) videoGenBaseUrl else primaryBaseUrl
    }
    val isSharedFallback = selectedColabTarget == ColabTarget.VIDEOGEN && !useDedicatedVideoGen

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ConnectionHeroCard(
                selectedTarget = selectedColabTarget,
                onSelectTarget = onSelectColabTarget,
                useDedicatedVideoGen = useDedicatedVideoGen,
                onToggleDedicatedVideoGen = onToggleDedicatedVideoGen,
                primaryConnected = primaryConnectionState is ConnectionState.Connected,
                videoGenConnected = if (useDedicatedVideoGen) videoGenConnectionState is ConnectionState.Connected else primaryConnectionState is ConnectionState.Connected,
                connectionState = activeState,
                isSharedFallback = isSharedFallback,
                sharedHost = primaryBaseUrl,
                onOpenColab = onOpenColab,
                onDisconnectColab = onDisconnectColab,
                onScanQr = onScanQr,
                onOpenPasteDialog = onOpenPasteDialog,
                onRetryConnect = onRetryConnect
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Editing Studios",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .clip(ShapeCorner10)
                        .background(Color(0xFF0F261B))
                        .border(1.dp, Color(0xFF1B4D36), ShapeCorner10)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${studios.size} Tools",
                        color = GreenIconTint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        items(studios, key = { it.key }) { item ->
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val cardScale by animateFloatAsState(
                targetValue = if (isPressed) 0.985f else 1f,
                label = "studioItemScale"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(cardScale)
                    .clip(ShapeCorner18)
                    .background(DarkSurface)
                    .border(1.dp, DarkCardBorder, ShapeCorner18)
                    .semantics { role = Role.Button }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onNavigateStudio(item.route) }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(ShapeCorner14)
                            .background(GreenIconBox),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = GreenIconTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = item.title,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(ShapeCorner6)
                                    .background(Color(0xFF0F261B))
                                    .border(1.dp, Color(0xFF1B4D36), ShapeCorner6)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = item.badge,
                                    color = GreenIconTint,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.description,
                            color = TextSecondary,
                            fontSize = 12.5.sp,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(ShapeCorner10)
                            .background(Color(0xFF1A1D2A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Open ${item.title}",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun ConnectionHeroCard(
    selectedTarget: ColabTarget,
    onSelectTarget: (ColabTarget) -> Unit,
    useDedicatedVideoGen: Boolean,
    onToggleDedicatedVideoGen: (Boolean) -> Unit,
    primaryConnected: Boolean,
    videoGenConnected: Boolean,
    connectionState: ConnectionState,
    isSharedFallback: Boolean = false,
    sharedHost: String = "",
    onOpenColab: () -> Unit,
    onDisconnectColab: () -> Unit,
    onScanQr: () -> Unit,
    onOpenPasteDialog: () -> Unit,
    onRetryConnect: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val dotColor = when (connectionState) {
        is ConnectionState.Connected -> StatusOnlineGreen
        is ConnectionState.Connecting -> StatusWarningAmber
        is ConnectionState.Failed -> StatusOfflineRed
        is ConnectionState.Disconnected -> StatusOfflineRed
    }
    val statusHeading = when {
        selectedTarget == ColabTarget.VIDEOGEN && isSharedFallback -> {
            when (connectionState) {
                is ConnectionState.Connected -> "Shared Backend: Wan Ready"
                is ConnectionState.Connecting -> "Checking Shared Colab..."
                is ConnectionState.Failed -> "Shared Backend Offline"
                is ConnectionState.Disconnected -> "Shared Backend Offline"
            }
        }
        selectedTarget == ColabTarget.VIDEOGEN -> {
            when (connectionState) {
                is ConnectionState.Connected -> "Video Gen Online"
                is ConnectionState.Connecting -> "Connecting to Video Gen..."
                is ConnectionState.Failed -> "Video Gen Offline"
                is ConnectionState.Disconnected -> "Video Gen Offline"
            }
        }
        else -> {
            when (connectionState) {
                is ConnectionState.Connected -> "Backend Online"
                is ConnectionState.Connecting -> "Connecting to Colab..."
                is ConnectionState.Failed -> "Connection Failed"
                is ConnectionState.Disconnected -> "Backend Offline"
            }
        }
    }
    val statusSub = when {
        selectedTarget == ColabTarget.VIDEOGEN && isSharedFallback -> {
            when (connectionState) {
                is ConnectionState.Connected -> {
                    val g = connectionState.health.gpu
                    "Using Enhancement GPU: ${g.device} (${g.vramFreeMb.toInt()} MB free). Wan video generation supported."
                }
                else -> "Primary Colab is offline. Connect Enhancement Colab or toggle Dedicated Colab."
            }
        }
        else -> when (connectionState) {
            is ConnectionState.Connected -> {
                val g = connectionState.health.gpu
                "GPU: ${g.device} (${g.vramFreeMb.toInt()} MB free)"
            }
            is ConnectionState.Connecting -> "Checking health & capabilities at ${connectionState.baseUrl}"
            is ConnectionState.Failed -> connectionState.message
            is ConnectionState.Disconnected ->
                if (selectedTarget == ColabTarget.VIDEOGEN) "Start the 1-click Video Gen Colab notebook, then paste URL or scan QR."
                else "Start the 1-click Colab notebook, then tap the app link, scan QR, or paste URL."
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeCorner20)
            .background(DarkSurface)
            .border(1.dp, DarkCardBorder, ShapeCorner20)
            .padding(16.dp)
    ) {
        Column {
            // === DIRECT COLAB SWITCHER TOGGLE AT TOP OF HERO CARD ===
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeCorner12)
                    .background(Color(0xFF131625))
                    .border(1.dp, Color(0xFF23273A), ShapeCorner12)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Enhancement Tab
                val isPrimary = selectedTarget == ColabTarget.PRIMARY
                val primaryInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ShapeCorner10)
                        .background(if (isPrimary) GreenPillBg else Color.Transparent)
                        .border(
                            1.dp,
                            if (isPrimary) GreenPrimary.copy(alpha = 0.8f) else Color.Transparent,
                            ShapeCorner10
                        )
                        .clickable(
                            interactionSource = primaryInteraction,
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectTarget(ColabTarget.PRIMARY)
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (primaryConnected) StatusOnlineGreen else StatusOfflineRed)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "⚡ Enhancement",
                            color = if (isPrimary) Color.White else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }

                // Video Gen Tab
                val isVideoGen = selectedTarget == ColabTarget.VIDEOGEN
                val videoGenInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ShapeCorner10)
                        .background(if (isVideoGen) GreenPillBg else Color.Transparent)
                        .border(
                            1.dp,
                            if (isVideoGen) GreenPrimary.copy(alpha = 0.8f) else Color.Transparent,
                            ShapeCorner10
                        )
                        .clickable(
                            interactionSource = videoGenInteraction,
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectTarget(ColabTarget.VIDEOGEN)
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (videoGenConnected) StatusOnlineGreen else StatusOfflineRed)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "🎬 Video Gen",
                            color = if (isVideoGen) Color.White else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (isVideoGen) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            // Dedicated mode option when Video Gen is selected
            if (selectedTarget == ColabTarget.VIDEOGEN) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = Color(0xFF101320),
                    shape = ShapeCorner10,
                    border = BorderStroke(1.dp, Color(0xFF1E2335)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = null,
                                tint = if (useDedicatedVideoGen) GreenIconTint else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (useDedicatedVideoGen) "Dedicated Video Gen GPU" else "Shared Mode (Using Enhancement Colab)",
                                color = if (useDedicatedVideoGen) Color.White else TextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Switch(
                            checked = useDedicatedVideoGen,
                            onCheckedChange = onToggleDedicatedVideoGen,
                            modifier = Modifier.scale(0.8f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = GreenPrimary,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = Color(0xFF1E2235)
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Color(0xFF1E2233), thickness = 1.dp)
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (connectionState is ConnectionState.Connecting) {
                            CircularProgressIndicator(
                                color = StatusWarningAmber,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(12.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(ShapeCircle)
                                    .background(dotColor)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusHeading,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Small Refresh Button near backend offline/online text
                        val isConnecting = connectionState is ConnectionState.Connecting
                        val refreshInteraction = remember { MutableInteractionSource() }
                        val isRefreshPressed by refreshInteraction.collectIsPressedAsState()
                        val refreshScale by animateFloatAsState(
                            targetValue = if (isRefreshPressed) 0.85f else 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "heroRefreshPopOut"
                        )

                        val infiniteTransition = rememberInfiniteTransition(label = "refreshSpin")
                        val spinAngle by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 850, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "spinAngle"
                        )

                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .scale(refreshScale)
                                .clip(CircleShape)
                                .background(Color(0xFF1E2235))
                                .border(
                                    1.dp,
                                    if (isConnecting) GreenPrimary.copy(alpha = 0.7f) else DarkCardBorder,
                                    CircleShape
                                )
                                .semantics { role = Role.Button }
                                .clickable(
                                    enabled = !isConnecting,
                                    interactionSource = refreshInteraction,
                                    indication = null,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onRetryConnect()
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Check backend connection",
                                tint = if (isConnecting) GreenIconTint else Color.White,
                                modifier = Modifier
                                    .size(15.dp)
                                    .rotate(if (isConnecting) spinAngle else 0f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = statusSub,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Clickable Laptop (PC) icon to open Connect to Backend screen
                val laptopInteraction = remember { MutableInteractionSource() }
                val isLaptopPressed by laptopInteraction.collectIsPressedAsState()
                val laptopScale by animateFloatAsState(
                    targetValue = if (isLaptopPressed) 0.88f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "laptopScale"
                )

                Box(
                    modifier = Modifier
                        .scale(laptopScale)
                        .clip(ShapeCorner12)
                        .background(Color(0xFF161A28))
                        .border(1.dp, DarkCardBorder, ShapeCorner12)
                        .semantics { role = Role.Button }
                        .clickable(
                            interactionSource = laptopInteraction,
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenPasteDialog()
                            }
                        )
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (connectionState is ConnectionState.Connected) Icons.Outlined.LaptopMac else Icons.Outlined.Laptop,
                        contentDescription = "Connect to Colab Backend",
                        tint = dotColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Row: Open Colab (Green) + Disconnect Colab (Red)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val openColabInteraction = remember { MutableInteractionSource() }
                val isOpenPressed by openColabInteraction.collectIsPressedAsState()
                val openScale by animateFloatAsState(
                    targetValue = if (isOpenPressed) 0.92f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "openColabPopOut"
                )

                Box(
                    modifier = Modifier
                        .weight(1.15f)
                        .scale(openScale)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(ShapeCorner20)
                        .background(GreenVibrantGradientBrush)
                        .semantics { role = Role.Button }
                        .clickable(
                            interactionSource = openColabInteraction,
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenColab()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (selectedTarget == ColabTarget.VIDEOGEN) "Open Video Colab" else "Open Colab",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                val disconnectInteraction = remember { MutableInteractionSource() }
                val isDisconnectPressed by disconnectInteraction.collectIsPressedAsState()
                val disconnectScale by animateFloatAsState(
                    targetValue = if (isDisconnectPressed) 0.92f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "disconnectPopOut"
                )

                val isConnected = connectionState is ConnectionState.Connected ||
                    connectionState is ConnectionState.Connecting ||
                    connectionState is ConnectionState.Failed

                Box(
                    modifier = Modifier
                        .weight(1.15f)
                        .scale(disconnectScale)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(ShapeCorner20)
                        .background(
                            if (isConnected && !isSharedFallback) {
                                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFFDC2626), Color(0xFFEF4444)))
                            } else if (isSharedFallback) {
                                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF1B3D2B), Color(0xFF1B3D2B)))
                            } else {
                                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF381A22), Color(0xFF381A22)))
                            }
                        )
                        .border(
                            1.dp,
                            if (isConnected && !isSharedFallback) Color(0xFFEF4444) else if (isSharedFallback) Color(0xFF2C6B4A) else Color(0xFF4A2530),
                            ShapeCorner20
                        )
                        .semantics { role = Role.Button }
                        .clickable(
                            enabled = isConnected,
                            interactionSource = disconnectInteraction,
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDisconnectColab()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)
                    ) {
                        Icon(
                            imageVector = if (isSharedFallback) Icons.Default.Link else Icons.Default.LinkOff,
                            contentDescription = null,
                            tint = if (isConnected) Color.White else Color(0xFFB07A85),
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isSharedFallback) "Shared Mode" else "Disconnect",
                            color = if (isConnected) Color.White else Color(0xFFB07A85),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            var isWindowExpanded by remember { mutableStateOf(false) }
            val windowHeaderInteraction = remember { MutableInteractionSource() }
            val isWindowPressed by windowHeaderInteraction.collectIsPressedAsState()
            val windowHeaderScale by animateFloatAsState(
                targetValue = if (isWindowPressed) 0.96f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "windowHeaderPopOut"
            )

            // Collapsible Window ("collap window"): Scan QR & Pairing Link section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(windowHeaderScale)
                    .clip(ShapeCorner14)
                    .background(Color(0xFF141724))
                    .border(1.dp, DarkCardBorder, ShapeCorner14)
                    .clickable(
                        interactionSource = windowHeaderInteraction,
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isWindowExpanded = !isWindowExpanded
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = GreenIconTint,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Scan QR & Pairing Tools",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Icon(
                        imageVector = if (isWindowExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isWindowExpanded) "Collapse window" else "Expand window",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(visible = isWindowExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(ShapeCorner14)
                        .background(Color(0xFF11131E))
                        .border(1.dp, DarkCardBorder, ShapeCorner14)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Scan QR button with pop out animation and haptic feedback
                        val scanQrInteraction = remember { MutableInteractionSource() }
                        val isScanQrPressed by scanQrInteraction.collectIsPressedAsState()
                        val scanQrScale by animateFloatAsState(
                            targetValue = if (isScanQrPressed) 0.92f else 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "scanQrPopOut"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .scale(scanQrScale)
                                .defaultMinSize(minHeight = 44.dp)
                                .clip(ShapeCorner12)
                                .background(Color(0xFF181C2B))
                                .border(1.dp, GreenPrimary, ShapeCorner12)
                                .semantics { role = Role.Button }
                                .clickable(
                                    interactionSource = scanQrInteraction,
                                    indication = null,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onScanQr()
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = GreenIconTint, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Scan QR", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Paste URL button with pop out animation and haptic feedback
                        val pasteUrlInteraction = remember { MutableInteractionSource() }
                        val isPasteUrlPressed by pasteUrlInteraction.collectIsPressedAsState()
                        val pasteUrlScale by animateFloatAsState(
                            targetValue = if (isPasteUrlPressed) 0.92f else 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "pasteUrlPopOut"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .scale(pasteUrlScale)
                                .defaultMinSize(minHeight = 44.dp)
                                .clip(ShapeCorner12)
                                .background(Color(0xFF181C2B))
                                .border(1.dp, DarkCardBorder, ShapeCorner12)
                                .semantics { role = Role.Button }
                                .clickable(
                                    interactionSource = pasteUrlInteraction,
                                    indication = null,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onOpenPasteDialog()
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Paste URL", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    if (connectionState is ConnectionState.Failed || connectionState is ConnectionState.Connected) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val retryInteraction = remember { MutableInteractionSource() }
                        val isRetryPressed by retryInteraction.collectIsPressedAsState()
                        val retryScale by animateFloatAsState(
                            targetValue = if (isRetryPressed) 0.94f else 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "retryPopOut"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .scale(retryScale)
                                .defaultMinSize(minHeight = 42.dp)
                                .clip(ShapeCorner10)
                                .background(Color(0xFF161A28))
                                .border(1.dp, GreenPrimary.copy(alpha = 0.5f), ShapeCorner10)
                                .semantics { role = Role.Button }
                                .clickable(
                                    interactionSource = retryInteraction,
                                    indication = null,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onRetryConnect()
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = GreenIconTint, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (connectionState is ConnectionState.Failed) "Retry Connection" else "Refresh Connection",
                                    color = Color.White,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueTabContent(
    jobs: List<ManagedJob>,
    onOpenStudio: (ManagedJob) -> Unit,
    onPauseToggle: (ManagedJob) -> Unit,
    onCancel: (ManagedJob) -> Unit,
    onRetryDownload: (ManagedJob) -> Unit,
    onDismiss: (ManagedJob) -> Unit,
    onSaveJobToGallery: ((ManagedJob) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (jobs.isEmpty()) {
        Column(
            modifier = modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = null, tint = GreenIconTint, modifier = Modifier.size(52.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("No Jobs Yet", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Jobs submitted in any studio continue running cleanly here even when you navigate back or close the app.",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(jobs, key = { it.localId }) { job ->
            val badgeColor = when (job.phase) {
                JobPhase.SAVED -> StatusOnlineGreen
                JobPhase.FAILED -> StatusOfflineRed
                JobPhase.PAUSED, JobPhase.RECONNECTING, JobPhase.CANCELLED -> StatusWarningAmber
                else -> GreenIconTint
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeCorner16)
                    .background(DarkSurface)
                    .border(1.dp, DarkCardBorder, ShapeCorner16)
                    .clickable { onOpenStudio(job) }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = titleForTask(job.taskType),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.5.sp
                    )
                    Text(
                        text = if (job.phase.isActive) "${job.phase.label} (${job.progress.toInt()}%)" else job.phase.label,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${job.sourceDisplayName} • ${job.paramsSummary}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (job.phase.isActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (job.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(ShapeCorner4),
                        color = GreenPrimary,
                        trackColor = Color(0xFF12281D)
                    )
                    if (job.totalFrames > 0 || job.fps > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (job.totalFrames > 0) {
                                Text(
                                    text = "${job.currentFrame}/${job.totalFrames} frames",
                                    color = TextSecondary,
                                    fontSize = 11.5.sp
                                )
                            }
                            if (job.fps > 0) {
                                Text(
                                    text = "${"%.1f".format(job.fps)} FPS",
                                    color = TextSecondary,
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onPauseToggle(job) },
                            enabled = !job.controlInFlight && job.serverJobId != null,
                            shape = ShapeCorner10,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                        ) {
                            Text(if (job.phase == JobPhase.PAUSED) "Resume" else "Pause", color = Color.White, fontSize = 13.sp)
                        }
                        Button(
                            onClick = { onCancel(job) },
                            enabled = !job.controlInFlight,
                            shape = ShapeCorner10,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                        ) {
                            Text("Stop", color = Color.White, fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { onDismiss(job) },
                            shape = ShapeCorner10,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                        ) {
                            Text("Remove", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                } else {
                    if (!job.errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = job.errorMessage, color = StatusOfflineRed, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (job.canRetryDownload) {
                            Button(
                                onClick = { onRetryDownload(job) },
                                shape = ShapeCorner10,
                                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Text("Retry Download", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                        if (job.phase == JobPhase.SAVED && onSaveJobToGallery != null) {
                            Button(
                                onClick = { onSaveJobToGallery(job) },
                                shape = ShapeCorner10,
                                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Save", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = { onOpenStudio(job) },
                            shape = ShapeCorner10,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                        ) {
                            Text("Studio", color = Color.White, fontSize = 13.sp)
                        }
                        TextButton(
                            onClick = { onDismiss(job) },
                            shape = ShapeCorner10,
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                        ) {
                            Text("Remove", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun FilesTabContent(
    files: List<SavedMediaItem>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSaveFile: (SavedMediaItem) -> Unit,
    onOpenFile: (SavedMediaItem) -> Unit,
    onShareFile: (SavedMediaItem) -> Unit,
    onDeleteFile: (SavedMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Processed Videos & Gallery Exports",
                color = TextSecondary,
                fontSize = 13.sp
            )
            TextButton(
                onClick = onRefresh,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = GreenIconTint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Refresh", color = GreenIconTint)
            }
        }

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GreenPrimary)
                }
            }

            files.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = GreenIconTint, modifier = Modifier.size(52.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No Files Found", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Videos processed by Upscaler Studio appear here automatically. You can save them directly to your device gallery or delete them.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(files, key = { it.uriString }) { item ->
                        val sizeMb = item.sizeBytes / (1024.0 * 1024.0)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ShapeCorner16)
                                .background(DarkSurface)
                                .border(1.dp, DarkCardBorder, ShapeCorner16)
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(ShapeCorner12)
                                        .background(GreenIconBox),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Movie, contentDescription = null, tint = GreenIconTint)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.displayName,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${"%.1f".format(sizeMb)} MB",
                                            color = TextSecondary,
                                            fontSize = 12.sp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        if (item.isSavedToGallery) {
                                            Text(
                                                text = "Saved in Gallery ✓",
                                                color = StatusOnlineGreen,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        } else {
                                            Text(
                                                text = "Ready to Save",
                                                color = GreenPrimary,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = { onOpenFile(item) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Open ${item.displayName}", tint = Color.White)
                                }
                                IconButton(
                                    onClick = { onShareFile(item) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share ${item.displayName}", tint = GreenIconTint)
                                }
                                IconButton(
                                    onClick = { onDeleteFile(item) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete ${item.displayName}", tint = StatusOfflineRed)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            if (!item.isSavedToGallery) {
                                Button(
                                    onClick = { onSaveFile(item) },
                                    shape = ShapeCorner10,
                                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 42.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Save Video to Gallery", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onSaveFile(item) },
                                    shape = ShapeCorner10,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusOnlineGreen),
                                    border = BorderStroke(1.dp, Color(0xFF1B4D3E)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 42.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = StatusOnlineGreen, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Saved in Gallery (Tap to Export Again)", color = StatusOnlineGreen, fontSize = 12.5.sp)
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}
