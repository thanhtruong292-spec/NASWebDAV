package com.nas.naswebdav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nas.naswebdav.getStatusColor

// HÀM HIỂN THỊ THÔNG BÁO CHUNG CHO DỰ ÁN
@Composable
fun NotificationDialog(title: String, message: String, icon: ImageVector, iconColor: Color, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đã hiểu", fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold)
            }
        },
        text = { Text(message, fontSize = 14.sp) },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.primary,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
fun DashboardWidget(title: String, value: String, progress: Float, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null, subValue: String? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.aspectRatio(1f)
    ) {
        Column(Modifier.padding(12.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
                }
                if (subValue != null) {
                    Text(
                        text = subValue,
                        color = getStatusColor("Nhiệt độ", subValue),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().weight(1f)) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(110.dp),
                    color = color,
                    strokeWidth = 10.dp,
                    trackColor = color.copy(alpha = 0.2f)
                )
                Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
        }
    }
}

@Composable
fun BiometricLockScreen(activity: androidx.fragment.app.FragmentActivity, onAuthenticated: () -> Unit, onFallbackToLogin: () -> Unit) {
    val executor = remember { androidx.core.content.ContextCompat.getMainExecutor(activity) }
    var authError by remember { mutableStateOf("") }
    var failCount by remember { mutableStateOf(0) }

    val authenticate = {
        val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Xác thực bảo mật NAS")
            .setSubtitle("Vui lòng quét vân tay hoặc khuôn mặt")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        val biometricPrompt = androidx.biometric.BiometricPrompt(activity, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    failCount = 0
                    onAuthenticated()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED && errorCode != androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        failCount++
                        authError = "Lỗi: $errString"
                        if (failCount >= 3) onFallbackToLogin()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    failCount++
                    authError = "Vân tay không khớp!"
                    if (failCount >= 3) onFallbackToLogin()
                }
            })
        biometricPrompt.authenticate(promptInfo)
    }

    // Tự động gọi popup quét vân tay ngay khi vừa mở ứng dụng
    LaunchedEffect(Unit) {
        authenticate()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .background(Color(0xFF00897B).copy(alpha = 0.15f), CircleShape)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Fingerprint,
                    contentDescription = "Fingerprint",
                    modifier = Modifier.fillMaxSize(),
                    tint = Color(0xFF4DB6AC)
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = "NAS CHAINEDBOX",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Dữ liệu của bạn đang được bảo vệ an toàn",
                color = Color.LightGray,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(48.dp))

            if (authError.isNotEmpty()) {
                Text(
                    text = "$authError (Lần ${failCount}/3)",
                    color = Color(0xFFFF5252),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0xFFFF5252).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Spacer(Modifier.height(24.dp))
            }

            Button(
                onClick = authenticate,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(0.8f).height(50.dp)
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Chạm để xác thực lại", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(32.dp))
            Text(
                text = "Màn hình đăng nhập thủ công sẽ tự động xuất hiện\nsau khi xác thực sai 3 lần.",
                color = Color.Gray.copy(alpha = 0.6f),
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 16.sp
            )
        }
    }
}

fun getFlagEmoji(countryCode: String): String {
    if (countryCode.isEmpty() || countryCode == "UN" || countryCode == "LOCAL") return "🌐"
    return try {
        val firstLetter = Character.codePointAt(countryCode.uppercase(), 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(countryCode.uppercase(), 1) - 0x41 + 0x1F1E6
        String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
    } catch (e: Exception) {
        "🌐"
    }
}
