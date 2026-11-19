package com.upscaler.studio.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.upscaler.studio.BuildConfig
import com.upscaler.studio.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    val currentButtonSize by settingsManager.buttonSize.collectAsState()

    var showAboutDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showContactDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // --- Section: UI Button Size (Accessibility) ---
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "UI Button Size ( Accessibility )",
                    color = TextSecondary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                // Segmented 3-button toggle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeCorner14)
                        .background(Color(0xFF141724))
                        .border(1.dp, DarkCardBorder, ShapeCorner14)
                        .padding(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        UiButtonSize.entries.forEach { size ->
                            val isSelected = currentButtonSize == size
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(ShapeCorner10)
                                    .background(
                                        if (isSelected) Color(0xFF262C44) else Color.Transparent
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFF3E486E) else Color.Transparent,
                                        ShapeCorner10
                                    )
                                    .semantics { role = Role.RadioButton }
                                    .clickable { settingsManager.setButtonSize(size) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = size.label,
                                    color = if (isSelected) Color.White else TextSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // --- Section: App Developer Card ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeCorner20)
                    .background(DarkSurface)
                    .border(1.dp, DarkCardBorder, ShapeCorner20)
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(ShapeCorner12)
                            .background(Color(0xFF281E3C)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color(0xFFA78BFA),
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App Developer",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Developed by ryn",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Get in touch or review portfolio",
                            color = TextMuted,
                            fontSize = 11.5.sp
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = { showContactDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                        shape = ShapeCorner10,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Contact",
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // --- Section: App Info & Privacy Card ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeCorner20)
                    .background(DarkSurface)
                    .border(1.dp, DarkCardBorder, ShapeCorner20)
                    .padding(vertical = 6.dp)
            ) {
                Column {
                    // Row 1: About App
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAboutDialog = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(ShapeCorner12)
                                .background(Color(0xFF281E3C)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Color(0xFFA78BFA),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "About Upscaler Studio",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Learn more about this app",
                                color = TextSecondary,
                                fontSize = 12.5.sp
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    HorizontalDivider(
                        color = DarkCardBorder,
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    // Row 2: Privacy Policy
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPrivacyDialog = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(ShapeCorner12)
                                .background(Color(0xFF152238)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Security,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Privacy Policy",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "How we handle your data",
                                color = TextSecondary,
                                fontSize = 12.5.sp
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    HorizontalDivider(
                        color = DarkCardBorder,
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    // Row 3: App Version
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(ShapeCorner12)
                                .background(Color(0xFF16281D)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = GreenIconTint,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "App Version",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                                color = TextSecondary,
                                fontSize = 12.5.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About Upscaler Studio", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Upscaler Studio is an advanced AI-powered video enhancement suite providing 4X Super-Resolution, RIFE 60/120 FPS frame interpolation, AI Anti-Line Artifact Denoising, and 3D Monocular Depth Map generation.\n\nAll heavy AI rendering is processed on private, dedicated GPU runtimes via Google Colab with end-to-end user isolation.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showAboutDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                ) {
                    Text("OK", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkSurface
        )
    }

    if (showContactDialog) {
        val contacts = remember {
            listOf(
                ContactChannel(
                    title = "GitHub",
                    handle = "github.com/rynn-ui",
                    url = "https://github.com/rynn-ui",
                    icon = Icons.Default.Code,
                    iconColor = Color(0xFFF1F5F9),
                    iconBg = Color(0xFF1E2430)
                ),
                ContactChannel(
                    title = "Discord",
                    handle = "@rynn0976 (Tap to copy)",
                    discordId = "@rynn0976",
                    icon = Icons.AutoMirrored.Filled.Chat,
                    iconColor = Color(0xFFA78BFA),
                    iconBg = Color(0xFF261D3A)
                )
            )
        }

        AlertDialog(
            onDismissRequest = { showContactDialog = false },
            containerColor = DarkSurface,
            shape = ShapeCorner20,
            tonalElevation = 8.dp,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(ShapeCorner10)
                            .background(Color(0xFF281E3C)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color(0xFFA78BFA),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Developer Contacts",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ryn (Upscaler Studio)",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    contacts.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ShapeCorner12)
                                .background(Color(0xFF131722))
                                .border(1.dp, Color(0xFF232838), ShapeCorner12)
                                .clickable {
                                    if (item.url != null) {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
                                        runCatching { context.startActivity(intent) }
                                    } else if (item.discordId != null) {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Discord ID", item.discordId)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Discord ID copied: ${item.discordId}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(ShapeCorner10)
                                    .background(item.iconBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.title,
                                    tint = item.iconColor,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    color = Color.White,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    text = item.handle,
                                    color = TextSecondary,
                                    fontSize = 11.5.sp
                                )
                            }

                            if (item.discordId != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(ShapeCorner8)
                                        .background(Color(0xFF231E38))
                                        .border(1.dp, Color(0xFF4C3B6E), ShapeCorner8)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy Discord ID",
                                        tint = Color(0xFFA78BFA),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "Copy",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open ${item.title}",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = { showContactDialog = false },
                    border = BorderStroke(1.dp, DarkCardBorder),
                    shape = ShapeCorner10
                ) {
                    Text("Close", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("Privacy Policy", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "1. Zero Data Collection: Upscaler Studio does not collect, sell, or analyze your media files.\n\n2. Private Processing: Uploads are processed strictly inside your own isolated Colab GPU session through authenticated encrypted tunnels.\n\n3. Local Media Control: Processed videos are kept inside private app storage until you explicitly choose to tap 'Save' to export them into your public gallery.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showPrivacyDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                ) {
                    Text("Understood", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkSurface
        )
    }
}

private data class ContactChannel(
    val title: String,
    val handle: String,
    val url: String? = null,
    val discordId: String? = null,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconColor: Color,
    val iconBg: Color
)

