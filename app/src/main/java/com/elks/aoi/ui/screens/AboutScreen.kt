package com.elks.aoi.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.elks.aoi.BuildConfig
import com.elks.aoi.update.UpdateChecker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = BuildConfig.VERSION_NAME

    var checking by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* download still works without notification */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("О приложении") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("AOI Elks", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Версия $currentVersion (code ${BuildConfig.VERSION_CODE})")
            Text(
                "Автоматизированная оптическая инспекция СМД-монтажа.\n" +
                    "Работа оффлайн. OpenCV выравнивание + детекция.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            HorizontalDivider()

            Text("Обновления", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(
                "Проверка через GitHub Releases репозитория DexterHtcone/AOI-Elks.\n" +
                    "Репозиторий должен быть публичным, в Release должен быть APK.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        val p = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        )
                        if (p != PackageManager.PERMISSION_GRANTED) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    checking = true
                    status = null
                    info = null
                    scope.launch {
                        val result = UpdateChecker.check(currentVersion)
                        info = result
                        checking = false
                        status = when {
                            result.error != null && !result.available -> result.error
                            result.available -> "Доступна версия ${result.latestVersion}"
                            else -> "У вас актуальная версия (${result.latestVersion})"
                        }
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Проверка...")
                } else {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Проверить обновления")
                }
            }

            status?.let {
                Text(
                    it,
                    color = if (info?.available == true)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }

            info?.let { u ->
                if (u.releaseNotes.isNotBlank()) {
                    Text("Что нового:", fontWeight = FontWeight.Medium)
                    Text(u.releaseNotes, fontSize = 13.sp)
                }

                if (u.available && u.apkUrl != null) {
                    Button(
                        onClick = {
                            // Android 8+: need "Install unknown apps" permission for auto-install
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                if (!context.packageManager.canRequestPackageInstalls()) {
                                    try {
                                        val settings = android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            android.net.Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(settings)
                                        status = "Разрешите установку из этого источника, затем снова нажмите «Скачать»."
                                        return@Button
                                    } catch (_: Exception) { /* continue download */ }
                                }
                            }
                            UpdateChecker.enqueueDownload(
                                context, u.apkUrl, u.latestVersion
                            )
                            status = "Загрузка начата. После скачивания установка запустится автоматически."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Скачать и установить ${u.latestVersion}")
                    }
                }

                TextButton(
                    onClick = { UpdateChecker.openReleasesPage(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Открыть страницу релизов на GitHub")
                }
            }
        }
    }
}
