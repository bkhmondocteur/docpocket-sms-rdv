package com.docpocket.smsrdv

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.docpocket.smsrdv.presentation.AppointmentScreen
import com.docpocket.smsrdv.presentation.LicenseScreen
import com.docpocket.smsrdv.presentation.SettingsScreen
import com.docpocket.smsrdv.ui.theme.AppTheme
import com.docpocket.smsrdv.ui.theme.DocPocketTheme
import com.docpocket.smsrdv.worker.SyncService
import com.docpocket.smsrdv.worker.WifiSyncWorker
import com.google.firebase.messaging.FirebaseMessaging
import com.docpocket.smsrdv.firebase.FirebaseSyncManager
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val chooseBackupFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            com.docpocket.smsrdv.backup.BackupManager.setBackupFolderUri(applicationContext, uri)
            Toast.makeText(this, "Dossier de sauvegarde configuré avec succès !", Toast.LENGTH_SHORT).show()
        } else {
            com.docpocket.smsrdv.backup.BackupManager.setFolderConfigured(applicationContext, true)
        }
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[Manifest.permission.SEND_SMS] ?: false
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (smsGranted && notifGranted) {
            Toast.makeText(this, "Permissions SMS & Notifications validées.", Toast.LENGTH_SHORT).show()
        } else if (!smsGranted) {
            Toast.makeText(this, "Attention: La permission SMS est indispensable pour envoyer les rappels.", Toast.LENGTH_LONG).show()
        }

        startForegroundReminderService()
        checkExactAlarmAndBatteryExemptions()
    }

    private fun checkExactAlarmAndBatteryExemptions() {
        // Alarme Exacte (Android 12+ API 31+) : SCHEDULE_EXACT_ALARM
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur ouverture SCHEDULE_EXACT_ALARM: ${e.message}")
                }
                return // Ne pas ouvrir 2 paramètres en même temps
            }
        }

        // Optimisation de Batterie (Android 6.0+ API 23+ / XOS / Doze) : ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur ouverture REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: ${e.message}")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkExactAlarmAndBatteryExemptions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Démarrage immédiat et vérifications du système d'arrière-plan
        startForegroundReminderService()
        checkAndRequestSystemExemptionsAndPermissions()

        val prefs = getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)

        // Initialisation du réveil Cloud via Firebase Cloud Messaging (FCM)
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.i(TAG, "Token FCM initialisé: $token")
                    prefs.edit().putString("fcm_token", token).apply()
                    FirebaseSyncManager.saveDeviceToken(applicationContext, token)
                } else {
                    Log.w(TAG, "Échec récupération FCM token: ${task.exception?.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur initialisation FCM: ${e.message}")
        }

        setContent {
            var isLicensed by remember {
                mutableStateOf(prefs.getBoolean("is_licensed", false))
            }
            var doctorName by remember {
                mutableStateOf(prefs.getString("doctor_name", "") ?: "")
            }
            var currentTheme by remember {
                mutableStateOf(AppTheme.fromKey(prefs.getString("app_theme", "BLEU_MEDICAL")))
            }

            LaunchedEffect(isLicensed) {
                if (isLicensed && doctorName.isNotEmpty()) {
                    Toast.makeText(this@MainActivity, "Bienvenue Dr. $doctorName", Toast.LENGTH_LONG).show()
                }
            }

            var showSettings by remember { mutableStateOf(false) }

            var showInitialBackupSetup by remember {
                mutableStateOf(isLicensed && !com.docpocket.smsrdv.backup.BackupManager.isFolderConfigured(applicationContext))
            }

            if (showInitialBackupSetup) {
                AlertDialog(
                    onDismissRequest = {
                        com.docpocket.smsrdv.backup.BackupManager.setFolderConfigured(applicationContext, true)
                        showInitialBackupSetup = false
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📁", fontSize = 24.sp, modifier = Modifier.padding(end = 8.dp))
                            Text("Dossier de Sauvegarde", fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column {
                            Text(
                                "Pour sécuriser automatiquement la liste de vos patients et leurs coordonnées, veuillez choisir un dossier de destination (ex: Documents).\n\nUne sauvegarde locale tournante (5 max) et Cloud Firestore sera effectuée après chaque modification.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            showInitialBackupSetup = false
                            chooseBackupFolderLauncher.launch(null)
                        }) {
                            Text("Choisir mon dossier")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            com.docpocket.smsrdv.backup.BackupManager.setFolderConfigured(applicationContext, true)
                            showInitialBackupSetup = false
                            Toast.makeText(this@MainActivity, "Dossier par défaut sélectionné (Documents/DocPocket_Sauvegardes)", Toast.LENGTH_LONG).show()
                        }) {
                            Text("Dossier par défaut")
                        }
                    }
                )
            }

            DocPocketTheme(selectedTheme = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isLicensed) {
                        if (showSettings) {
                            SettingsScreen(
                                onBack = { showSettings = false },
                                currentTheme = currentTheme,
                                onThemeChanged = { newTheme -> currentTheme = newTheme }
                            )
                        } else {
                            AppointmentScreen(
                                onNavigateToSettings = { showSettings = true }
                            )
                        }
                    } else {
                        LicenseScreen(
                            onLicenseValid = { name ->
                                val doctorId = FirebaseSyncManager.cleanDoctorId(name)
                                prefs.edit()
                                    .putBoolean("is_licensed", true)
                                    .putString("doctor_name", name)
                                    .putString("doctor_id", doctorId)
                                    .apply()
                                doctorName = name
                                isLicensed = true

                                val token = prefs.getString("fcm_token", "") ?: ""
                                if (token.isNotBlank()) {
                                    FirebaseSyncManager.saveDeviceToken(applicationContext, token)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun checkAndRequestSystemExemptionsAndPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startForegroundReminderService()
            checkExactAlarmAndBatteryExemptions()
        }

        // Planification des Workers de secours (WorkManager)
        val syncWorkRequest = PeriodicWorkRequestBuilder<WifiSyncWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "WifiSyncWork",
            ExistingPeriodicWorkPolicy.KEEP,
            syncWorkRequest
        )

        val reminderWorkRequest = PeriodicWorkRequestBuilder<com.docpocket.smsrdv.worker.ReminderWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PeriodicReminderWork",
            ExistingPeriodicWorkPolicy.KEEP,
            reminderWorkRequest
        )
    }

    private fun startForegroundReminderService() {
        try {
            val serviceIntent = Intent(this, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur au lancement de SyncService: ${e.message}", e)
        }
    }
}
