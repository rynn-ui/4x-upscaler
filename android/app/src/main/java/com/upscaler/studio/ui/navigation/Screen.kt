package com.upscaler.studio.ui.navigation

sealed class Screen(val route: String) {
    object Auth : Screen("auth")
    object Home : Screen("home")
    object UpscalerStudio : Screen("studio_upscale")
    object RifeStudio : Screen("studio_rife")
    object DenoiserStudio : Screen("studio_denoise")
    object DepthStudio : Screen("studio_depth")
    object QrScanner : Screen("qr_scanner")
    object Settings : Screen("settings")
}
