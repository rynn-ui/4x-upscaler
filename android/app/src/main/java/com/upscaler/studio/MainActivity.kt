package com.upscaler.studio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.upscaler.studio.data.auth.GoogleAuthManager
import com.upscaler.studio.data.remoteconfig.AppUpdateStatus
import com.upscaler.studio.data.remoteconfig.RemoteConfigManager
import com.upscaler.studio.data.repository.BackendUrlParser
import com.upscaler.studio.data.repository.ConnectionState
import com.upscaler.studio.data.repository.UpscalerRepository
import com.upscaler.studio.ui.auth.AuthScreen
import com.upscaler.studio.ui.components.AppUpdateDialog
import com.upscaler.studio.ui.home.HomeScreen
import com.upscaler.studio.ui.navigation.Screen
import com.upscaler.studio.ui.scanner.QrScannerScreen
import com.upscaler.studio.ui.settings.LocalButtonSize
import com.upscaler.studio.ui.settings.SettingsManager
import com.upscaler.studio.ui.settings.SettingsScreen
import com.upscaler.studio.ui.studios.DenoiserStudioScreen
import com.upscaler.studio.ui.studios.DepthStudioScreen
import com.upscaler.studio.ui.studios.RifeStudioScreen
import com.upscaler.studio.ui.studios.UpscalerStudioScreen
import com.upscaler.studio.ui.theme.DarkSurface
import com.upscaler.studio.ui.theme.GreenPrimary
import com.upscaler.studio.ui.theme.TextSecondary
import com.upscaler.studio.ui.theme.UpscalerStudioTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val repository = UpscalerRepository.instance
    private var lastHandledDeepLink: String? = null
    private var pendingSwitchDeepLink by mutableStateOf<String?>(null)
    private var isConnectingViaDeepLink = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository.init(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        handleDeepLink(intent)

        setContent {
            val settingsManager = remember { SettingsManager.getInstance(applicationContext) }
            val buttonSize by settingsManager.buttonSize.collectAsState()
            val baseDensity = LocalDensity.current
            val scaledDensity = remember(baseDensity, buttonSize) {
                Density(
                    density = baseDensity.density * buttonSize.scale,
                    fontScale = baseDensity.fontScale * buttonSize.scale
                )
            }

            CompositionLocalProvider(
                LocalDensity provides scaledDensity,
                LocalButtonSize provides buttonSize
            ) {
                UpscalerStudioTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val currentHost by repository.currentBaseUrl.collectAsState()
                    val googleAuth = remember { GoogleAuthManager.init(applicationContext) }
                    val currentUser by googleAuth.currentUserState.collectAsState()
                    val isAlreadySignedIn = googleAuth.isSignedIn || currentUser != null

                    val remoteConfigManager = remember { RemoteConfigManager.getInstance(applicationContext) }
                    val updateStatus by remoteConfigManager.updateStatus.collectAsState()
                    var showOptionalUpdateDialog by remember { mutableStateOf(true) }

                    LaunchedEffect(Unit) {
                        remoteConfigManager.fetchAndCheck()
                    }

                    NavHost(
                        navController = navController,
                        startDestination = if (isAlreadySignedIn) Screen.Home.route else Screen.Auth.route
                    ) {
                        composable(Screen.Auth.route) {
                            AuthScreen(
                                onAuthSuccess = {
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(Screen.Auth.route) { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable(Screen.Home.route) {
                            HomeScreen(onNavigate = { route ->
                                if (route == Screen.Auth.route) {
                                    navController.navigate(Screen.Auth.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                } else if (navController.currentDestination?.route == Screen.Home.route) {
                                    navController.navigate(route) {
                                        launchSingleTop = true
                                    }
                                }
                            })
                        }
                        composable(Screen.UpscalerStudio.route) {
                            UpscalerStudioScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Screen.RifeStudio.route) {
                            RifeStudioScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Screen.DenoiserStudio.route) {
                            DenoiserStudioScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Screen.DepthStudio.route) {
                            DepthStudioScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Screen.QrScanner.route) {
                            QrScannerScreen(
                                onConnected = { navController.popBackStack() },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.Settings.route) {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                    }

                    if (pendingSwitchDeepLink != null) {
                        val parsed = BackendUrlParser.parsePairingInput(pendingSwitchDeepLink!!).getOrNull()
                        val newHost = parsed?.baseUrl ?: pendingSwitchDeepLink!!
                        AlertDialog(
                            onDismissRequest = { pendingSwitchDeepLink = null },
                            title = { Text("Switch Backend Host?", color = Color.White) },
                            text = {
                                Text(
                                    "You are currently connected to:\n$currentHost\n\nSwitching to:\n$newHost\n\nExisting jobs will continue running on their original backend.",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        val raw = pendingSwitchDeepLink!!
                                        pendingSwitchDeepLink = null
                                        lastHandledDeepLink = raw
                                        lifecycleScope.launch {
                                            repository.connectAndVerify(raw)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                                ) {
                                    Text("Switch & Connect", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { pendingSwitchDeepLink = null }) {
                                    Text("Keep Current", color = Color.White)
                                }
                            },
                            containerColor = DarkSurface
                        )
                    }

                    if (updateStatus !is AppUpdateStatus.UpToDate) {
                        if (updateStatus is AppUpdateStatus.ForceUpdate ||
                            updateStatus is AppUpdateStatus.Maintenance ||
                            (updateStatus is AppUpdateStatus.OptionalUpdate && showOptionalUpdateDialog)
                        ) {
                            AppUpdateDialog(
                                status = updateStatus,
                                onDismissOptional = { showOptionalUpdateDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (isConnectingViaDeepLink) {
            return
        }
        val isConnecting = repository.connectionState.value is ConnectionState.Connecting
        if (repository.currentBaseUrl.value.isNotBlank() && !isConnecting) {
            lifecycleScope.launch {
                repository.refreshHealthIfConfigured()
            }
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val rawData = intent?.dataString ?: return
        val data = intent.data ?: return
        if ((data.scheme == "upscaler" || data.scheme == "ryn") && data.host == "connect") {
            val parsed = BackendUrlParser.parsePairingInput(rawData)
            if (parsed.isFailure) return
            val candidateUrl = parsed.getOrThrow().baseUrl
            val currentUrl = repository.currentBaseUrl.value
            val isConnected = repository.connectionState.value is ConnectionState.Connected

            if (isConnected && currentUrl.equals(candidateUrl, ignoreCase = true)) {
                return
            }

            if (isConnected && currentUrl.isNotBlank() && !currentUrl.equals(candidateUrl, ignoreCase = true)) {
                pendingSwitchDeepLink = rawData
                return
            }

            if (isConnectingViaDeepLink && rawData == lastHandledDeepLink) {
                return
            }

            lastHandledDeepLink = rawData
            isConnectingViaDeepLink = true
            lifecycleScope.launch {
                try {
                    repository.connectAndVerify(rawData)
                } finally {
                    isConnectingViaDeepLink = false
                }
            }
        }
    }
}
