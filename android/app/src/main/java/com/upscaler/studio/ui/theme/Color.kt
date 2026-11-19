package com.upscaler.studio.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Minimalist Dark Black / Graphite palette
val DarkBackground = Color(0xFF0F1015)       // Deep clean black-graphite
val DarkSurface = Color(0xFF161820)          // Sleek dark card surface
val DarkSurfaceVariant = Color(0xFF1D202A)   // Elevated surface variant
val DarkCardBorder = Color(0xFF282D3B)       // Subtle clean border
val DarkSubtle = Color(0xFF11131A)           // Very deep container

// High-contrast typography colors (WCAG AA compliant against dark surfaces)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFA6ADC4)
val TextMuted = Color(0xFF838B9F)

// Primary Mint & Emerald Green Palette
val GreenPrimary = Color(0xFF10B981)         // Crisp emerald / mint green
val GreenLight = Color(0xFF34D399)           // Vibrant mint highlight
val GreenDark = Color(0xFF059669)            // Deep forest green
val GreenPillBg = Color(0xFF132E24)          // Dark emerald pill/selection background
val GreenIconBox = Color(0xFF0E241C)         // Deep dark green icon box
val GreenIconTint = Color(0xFF6EE7B7)        // Mint tint for icons and active text
val GreenGlow = Color(0x3310B981)            // Glow / subtle highlight

// Pre-cached static gradients for smooth 60/120 FPS rendering (zero allocations)
val GreenGradientBrush = Brush.horizontalGradient(listOf(GreenDark, GreenPrimary))
val GreenVibrantGradientBrush = Brush.horizontalGradient(listOf(GreenPrimary, GreenLight))
val InactiveGradientBrush = Brush.horizontalGradient(listOf(Color(0xFF242733), Color(0xFF242733)))

// Status Colors
val StatusOfflineRed = Color(0xFFFF5E6E)
val StatusOfflineBg = Color(0xFF26151B)
val StatusOnlineGreen = GreenPrimary
val StatusOnlineBg = Color(0xFF0E261D)
val StatusWarningAmber = Color(0xFFF59E0B)

// Backward compat aliases for purple references
val PurplePrimary = GreenPrimary
val PurpleLight = GreenLight
val PurplePillBg = GreenPillBg
val PurpleIconBox = GreenIconBox
val PurpleIconTint = GreenIconTint
val Purple80 = GreenLight
val PurpleGrey80 = TextSecondary
val Pink80 = GreenIconTint
val Purple40 = GreenPrimary
val PurpleGrey40 = DarkCardBorder
val Pink40 = GreenPillBg
