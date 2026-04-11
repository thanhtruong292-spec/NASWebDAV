package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nas.naswebdav.WebDavViewModel

@Composable
fun LoginScreen(viewModel: WebDavViewModel, onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    // BẢO MẬT: Mở kho lưu trữ EncryptedSharedPreferences (Chuẩn API 1.0.0 Stable)
    val prefs = remember {
        val masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC)
        androidx.security.crypto.EncryptedSharedPreferences.create(
            "NasSecurePrefs",
            masterKeyAlias,
            context,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Đọc dữ liệu đã lưu từ lần mở app trước (nếu có)
    var url by remember { mutableStateOf(prefs.getString("nas_url", "http://192.168.1.1:8080/webdav/") ?: "") }
    var user by remember { mutableStateOf(prefs.getString("nas_user", "admin") ?: "") }
    var pass by remember { mutableStateOf(prefs.getString("nas_pass", "") ?: "") }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Storage, contentDescription = "NAS", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Kết nối máy chủ NAS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Đường dẫn (WebDAV URL)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Tài khoản (Username)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Mật khẩu (Password)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        Spacer(Modifier.height(24.dp))
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Button(
            onClick = {
                if (url.isBlank() || user.isBlank() || pass.isBlank()) {
                    return@Button
                }

                prefs.edit()
                    .putString("nas_url", url)
                    .putString("nas_user", user)
                    .putString("nas_pass", pass)
                    .apply()

                viewModel.connectAndLoad(url, user, pass)
                viewModel.scheduleIdleDuplicateScan(context)
                viewModel.scheduleIdleSpeedTest(context)

                onLoginSuccess()
            },
            interactionSource = interactionSource,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            Text("Kết nối an toàn", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
