package com.upscaler.studio.ui.auth

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.common.api.ApiException
import com.upscaler.studio.R
import com.upscaler.studio.data.auth.GoogleAuthManager
import com.upscaler.studio.ui.theme.*

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val googleAuth = remember { GoogleAuthManager.init(context) }

    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var resetEmail by remember { mutableStateOf("") }
    var remainingSeconds by remember { mutableLongStateOf(googleAuth.getLockoutRemainingSeconds()) }

    LaunchedEffect(remainingSeconds) {
        if (remainingSeconds > 0L) {
            kotlinx.coroutines.delay(1000L)
            remainingSeconds = googleAuth.getLockoutRemainingSeconds()
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        isLoading = true
        googleAuth.handleSignInResult(result) { firebaseResult ->
            isLoading = false
            if (firebaseResult.isSuccess) {
                val fUser = firebaseResult.getOrThrow()
                Toast.makeText(context, "Welcome, ${fUser.displayName ?: fUser.email ?: "User"}!", Toast.LENGTH_SHORT).show()
                onAuthSuccess()
            } else {
                val err = firebaseResult.exceptionOrNull()
                android.util.Log.w("AuthScreen", "Firebase Auth sync failed: ${err?.message}")
                if (googleAuth.isSignedIn) {
                    // Google Sign-In succeeded locally, enter app with Google profile
                    Toast.makeText(context, "Welcome, ${googleAuth.displayNameOrFallback}!", Toast.LENGTH_SHORT).show()
                    onAuthSuccess()
                } else {
                    Toast.makeText(context, err?.localizedMessage ?: "Firebase sign-in failed", Toast.LENGTH_LONG).show()
                }
            }
        }.fold(
            onSuccess = { account ->
                if (account.idToken.isNullOrBlank()) {
                    isLoading = false
                    Toast.makeText(context, "Welcome, ${account.displayName ?: account.email ?: "User"}!", Toast.LENGTH_SHORT).show()
                    onAuthSuccess()
                }
            },
            onFailure = { err ->
                isLoading = false
                val code = if (err is ApiException) err.statusCode else -1
                if (code == 16 || code == 12501) {
                    // Sign-in cancelled by user
                    return@fold
                }
                val msg = when (code) {
                    10 -> "Google Sign-In (Code 10: Developer Error): Please ensure SHA-1 fingerprint is added in Firebase Console for com.ryn.studio."
                    7 -> "Network error (Code 7): Please check your internet connection."
                    12500 -> "Sign-in failed (Code 12500): Check Firebase Google Auth & SHA-1 in console."
                    else -> err.localizedMessage ?: "Google Sign-In failed"
                }
                android.util.Log.e("AuthScreen", "Google Sign-In failed: $msg", err)
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(16.dp))

            // --- Lightning Bolt Logo ---
            Image(
                painter = painterResource(id = R.drawable.ic_lightning_bolt),
                contentDescription = "Upscaler Studio Logo",
                modifier = Modifier.size(52.dp)
            )

            Spacer(Modifier.height(16.dp))

            // --- App Title ---
            Text(
                text = "Upscaler Studio",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            // --- App Subtitle ---
            Text(
                text = "AI video enhancement for anime & AMVs.",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            // --- Segmented Control (Log In / Sign Up) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .background(Color(0xFF141620))
                    .border(1.dp, Color(0xFF232736), RoundedCornerShape(25.dp))
                    .padding(4.dp)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // Log In Tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (!isSignUp) Color(0xFF0D251C) else Color.Transparent)
                            .then(
                                if (!isSignUp) Modifier.border(1.5.dp, GreenPrimary, RoundedCornerShape(22.dp))
                                else Modifier
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { isSignUp = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Log In",
                            color = if (!isSignUp) Color.White else TextSecondary,
                            fontWeight = if (!isSignUp) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 14.5.sp
                        )
                    }

                    // Sign Up Tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (isSignUp) Color(0xFF0D251C) else Color.Transparent)
                            .then(
                                if (isSignUp) Modifier.border(1.5.dp, GreenPrimary, RoundedCornerShape(22.dp))
                                else Modifier
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { isSignUp = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Sign Up",
                            color = if (isSignUp) Color.White else TextSecondary,
                            fontWeight = if (isSignUp) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 14.5.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // --- Continue with Google Button ---
            Button(
                onClick = {
                    googleSignInLauncher.launch(googleAuth.signInIntent())
                },
                enabled = !isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141622)),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_google_logo),
                        contentDescription = "Google Logo",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Continue with Google",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // --- Or Divider ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF232736), thickness = 1.dp)
                Text(
                    text = "or",
                    color = TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF232736), thickness = 1.dp)
            }

            Spacer(Modifier.height(20.dp))

            // --- Email Address Input ---
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = { Text("Email address", color = TextMuted, fontSize = 14.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF141622),
                    unfocusedContainerColor = Color(0xFF141622),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = GreenPrimary,
                    unfocusedBorderColor = Color(0xFF232736)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))

            // --- Password Input ---
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = { Text("Password", color = TextMuted, fontSize = 14.sp) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle password visibility",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF141622),
                    unfocusedContainerColor = Color(0xFF141622),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = GreenPrimary,
                    unfocusedBorderColor = Color(0xFF232736)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // --- Password Requirements Indicator (Sign Up Only) ---
            val hasMinLength = password.length >= 8
            val hasLetters = password.any { it.isLetter() }
            val hasDigits = password.any { it.isDigit() }

            AnimatedVisibility(visible = isSignUp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (hasMinLength) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (hasMinLength) GreenPrimary else TextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "8+ characters",
                            color = if (hasMinLength) Color.White else TextMuted,
                            fontSize = 11.5.sp
                        )
                        Spacer(Modifier.width(16.dp))
                        Icon(
                            imageVector = if (hasLetters && hasDigits) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (hasLetters && hasDigits) GreenPrimary else TextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "Letters & numbers (0-9)",
                            color = if (hasLetters && hasDigits) Color.White else TextMuted,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }

            // --- Forgot Password Link ---
            AnimatedVisibility(visible = !isSignUp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(10.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            text = "Forgot password?",
                            color = GreenPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                resetEmail = email
                                showForgotPasswordDialog = true
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // --- Lockout Countdown Banner ---
            AnimatedVisibility(visible = remainingSeconds > 0 && !isSignUp) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF261215)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7F1D1D))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Lockout",
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Too many failed attempts",
                                color = Color(0xFFFCA5A5),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Please wait ${remainingSeconds}s before trying again.",
                                color = Color(0xFFF87171),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // --- Primary Action Button (Log In / Sign Up) ---
            Button(
                onClick = {
                    focusManager.clearFocus()
                    if (email.isBlank() || password.isBlank()) {
                        Toast.makeText(context, "Please enter email and password", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (isSignUp) {
                        if (password.length < 8) {
                            Toast.makeText(context, "Password must be at least 8 characters long", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!password.any { it.isLetter() } || !password.any { it.isDigit() }) {
                            Toast.makeText(context, "Password must contain both letters and numbers", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isLoading = true
                        googleAuth.signUpWithEmail(email, password) { result ->
                            isLoading = false
                            result.fold(
                                onSuccess = { user ->
                                    Toast.makeText(context, "Account created successfully!", Toast.LENGTH_SHORT).show()
                                    onAuthSuccess()
                                },
                                onFailure = { err ->
                                    Toast.makeText(context, err.message ?: "Sign-up failed", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    } else {
                        if (remainingSeconds > 0) {
                            Toast.makeText(context, "Please wait ${remainingSeconds}s before trying again.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isLoading = true
                        googleAuth.signInWithEmail(email, password) { result ->
                            isLoading = false
                            result.fold(
                                onSuccess = { user ->
                                    googleAuth.resetFailedAttempts()
                                    remainingSeconds = 0L
                                    Toast.makeText(context, "Welcome back!", Toast.LENGTH_SHORT).show()
                                    onAuthSuccess()
                                },
                                onFailure = { err ->
                                    val count = googleAuth.recordFailedAttempt()
                                    val lockSec = googleAuth.getLockoutRemainingSeconds()
                                    if (lockSec > 0) {
                                        remainingSeconds = lockSec
                                        Toast.makeText(context, "Too many failed attempts. Locked for ${GoogleAuthManager.LOCKOUT_DURATION_SECONDS}s.", Toast.LENGTH_LONG).show()
                                    } else {
                                        val attemptsLeft = GoogleAuthManager.MAX_FAILED_ATTEMPTS - count
                                        val errText = err.message ?: "Login failed"
                                        val toastMsg = if (errText.contains("Incorrect password", ignoreCase = true)) {
                                            "Incorrect password. ($attemptsLeft attempts left)"
                                        } else {
                                            errText
                                        }
                                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                },
                enabled = !isLoading && (isSignUp || remainingSeconds <= 0),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isSignUp && remainingSeconds > 0) Color(0xFF261D22) else GreenPrimary,
                    disabledContainerColor = if (!isSignUp && remainingSeconds > 0) Color(0xFF261D22) else GreenPrimary.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color(0xFF0F1015), modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                } else if (!isSignUp && remainingSeconds > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Locked (${remainingSeconds}s)",
                            color = Color(0xFFF87171),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = if (isSignUp) "Sign Up" else "Log In",
                        color = Color(0xFF0A1812),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // --- Forgot Password Dialog ---
    if (showForgotPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showForgotPasswordDialog = false },
            containerColor = DarkSurface,
            shape = ShapeCorner20,
            title = {
                Text("Reset Password", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter your email address to receive a password reset link.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it },
                        placeholder = { Text("name@example.com", color = TextMuted) },
                        singleLine = true,
                        shape = ShapeCorner12,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF141622),
                            unfocusedContainerColor = Color(0xFF141622),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = GreenPrimary,
                            unfocusedBorderColor = DarkCardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (resetEmail.isNotBlank()) {
                            googleAuth.sendPasswordReset(resetEmail) { result ->
                                result.fold(
                                    onSuccess = {
                                        Toast.makeText(context, "Password reset link sent to $resetEmail", Toast.LENGTH_LONG).show()
                                        showForgotPasswordDialog = false
                                    },
                                    onFailure = { err ->
                                        Toast.makeText(context, err.localizedMessage ?: "Failed to send reset email", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    shape = ShapeCorner10
                ) {
                    Text("Send Link", color = Color(0xFF0A1812), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showForgotPasswordDialog = false },
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    shape = ShapeCorner10
                ) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }
}

