package com.upscaler.studio.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.upscaler.studio.BuildConfig
import com.upscaler.studio.data.remoteconfig.AppUpdateStatus
import com.upscaler.studio.ui.theme.*

@Composable
fun AppUpdateDialog(
    status: AppUpdateStatus,
    onDismissOptional: () -> Unit = {}
) {
    val context = LocalContext.current

    when (status) {
        is AppUpdateStatus.ForceUpdate -> {
            Dialog(
                onDismissRequest = { /* Non-dismissible: force update */ },
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeCorner20)
                        .background(DarkSurface)
                        .border(1.dp, StatusOfflineRed.copy(alpha = 0.5f), ShapeCorner20)
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Header Icon
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(ShapeCorner16)
                                .background(Color(0xFF32141A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = StatusOfflineRed,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Text(
                            text = status.title,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = status.message,
                            color = TextSecondary,
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Center
                        )

                        // Version Info Chip
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ShapeCorner10)
                                .background(Color(0xFF141724))
                                .border(1.dp, DarkCardBorder, ShapeCorner10)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Current: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    color = TextMuted,
                                    fontSize = 11.5.sp
                                )
                                Text(
                                    text = "Required: v${status.minVersionName} (${status.minVersionCode})",
                                    color = StatusOfflineRed,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(status.updateUrl))
                                runCatching { context.startActivity(intent) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = ShapeCorner12,
                            colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Update Now",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        is AppUpdateStatus.OptionalUpdate -> {
            AlertDialog(
                onDismissRequest = onDismissOptional,
                containerColor = DarkSurface,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(ShapeCorner14)
                            .background(Color(0xFF132238)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                title = {
                    Text(
                        text = status.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = status.message,
                            color = TextSecondary,
                            fontSize = 13.5.sp,
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "New version available: v${status.latestVersionName}",
                            color = GreenPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(status.updateUrl))
                            runCatching { context.startActivity(intent) }
                            onDismissOptional()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                        shape = ShapeCorner10
                    ) {
                        Text("Update Now", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissOptional) {
                        Text("Later", color = TextSecondary)
                    }
                }
            )
        }

        is AppUpdateStatus.Maintenance -> {
            Dialog(
                onDismissRequest = { /* Maintenance is non-dismissible */ },
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeCorner20)
                        .background(DarkSurface)
                        .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), ShapeCorner20)
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(ShapeCorner16)
                                .background(Color(0xFF332511)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Text(
                            text = "Under Maintenance",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = status.message,
                            color = TextSecondary,
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        AppUpdateStatus.UpToDate -> {
            // Nothing to display
        }
    }
}

