package com.docpocket.smsrdv.presentation

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docpocket.smsrdv.BuildConfig
import com.docpocket.smsrdv.backup.BackupManager
import com.docpocket.smsrdv.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    currentTheme: AppTheme = AppTheme.BLEU_MEDICAL,
    onThemeChanged: (AppTheme) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)

    var selectedTheme by remember { mutableStateOf(currentTheme) }
    var showAboutDialog by remember { mutableStateOf(false) }

    var smtpEmail by remember { mutableStateOf(prefs.getString("smtp_email", "") ?: "") }
    var smtpPassword by remember { mutableStateOf(prefs.getString("smtp_password", "") ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var doctorName by remember { mutableStateOf(prefs.getString("doctor_name", "") ?: "") }
    var serverIp by remember { mutableStateOf(prefs.getString("server_ip", "") ?: "") }
    var reminderHours by remember { mutableStateOf(prefs.getInt("reminder_hours", 48).toString()) }

    val defaultFr = "Bonjour {Nom du Patient} ce ci est un rappel de votre rdv le {Date} à {Heure} au cabinet du Docteur {Nom Du Médecin}. bonne journée."
    val defaultAr = "\u200Fمرحباً {Nom du Patient} هذا تذكير بموعدك يوم {Date} الساعة {Heure} في عيادة الدكتور {Nom Du Médecin}. نهارك سعيد."
    val defaultEn = "Hello {Nom du Patient} this is a reminder of your appointment on {Date} at {Heure} at Doctor {Nom Du Médecin}'s clinic. Have a good day."

    var templateFr by remember { mutableStateOf(prefs.getString("template_fr", defaultFr) ?: defaultFr) }
    var templateAr by remember { mutableStateOf(prefs.getString("template_ar", defaultAr) ?: defaultAr) }
    var templateEn by remember { mutableStateOf(prefs.getString("template_en", defaultEn) ?: defaultEn) }

    val coroutineScope = rememberCoroutineScope()
    var showRestoreDialog by remember { mutableStateOf(false) }
    var isBackingUp by remember { mutableStateOf(false) }
    var backupFolderDisplayName by remember { mutableStateOf(BackupManager.getBackupFolderDisplayName(context)) }
    var lastBackupTime by remember { mutableStateOf(prefs.getLong(BackupManager.KEY_LAST_BACKUP_TIME, 0L)) }
    var lastBackupCount by remember { mutableStateOf(prefs.getInt(BackupManager.KEY_LAST_BACKUP_COUNT, 0)) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            BackupManager.setBackupFolderUri(context, uri)
            backupFolderDisplayName = BackupManager.getBackupFolderDisplayName(context)
            Toast.makeText(context, "Dossier de sauvegarde mis à jour !", Toast.LENGTH_SHORT).show()
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showRestoreDialog) {
        RestoreDialog(
            onDismiss = { showRestoreDialog = false },
            onRestored = {
                lastBackupTime = prefs.getLong(BackupManager.KEY_LAST_BACKUP_TIME, 0L)
                lastBackupCount = prefs.getInt(BackupManager.KEY_LAST_BACKUP_COUNT, 0)
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "Paramètres",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = "Configuration & Personnalisation",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }

                    FilledTonalIconButton(
                        onClick = { showAboutDialog = true },
                        modifier = Modifier.padding(end = 6.dp).size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "À propos",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = {
                            val parsedHours = reminderHours.toIntOrNull() ?: 48
                            prefs.edit()
                                .putString("doctor_name", doctorName)
                                .putString("smtp_email", smtpEmail)
                                .putString("smtp_password", smtpPassword)
                                .putString("template_fr", templateFr)
                                .putString("template_ar", templateAr)
                                .putString("template_en", templateEn)
                                .putString("server_ip", serverIp)
                                .putInt("reminder_hours", parsedHours)
                                .apply()

                            val serviceIntent = android.content.Intent(context, com.docpocket.smsrdv.worker.SyncService::class.java)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }

                            Toast.makeText(context, "Paramètres enregistrés avec succès !", Toast.LENGTH_SHORT).show()
                            onBack()
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Enregistrer les Paramètres",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // --- 1. CARTE VERSION & À PROPOS ---
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "DocPocket SMS RDV",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME} • Build ${BuildConfig.VERSION_CODE}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                    Button(
                        onClick = { showAboutDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("À propos", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }

            // --- 2. THÈMES VISUELS (10 THÈMES) ---
            SettingsSectionCard(
                emoji = "🎨",
                title = "Apparence & Thèmes (10 Thèmes)"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Thème sélectionné :",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = selectedTheme.title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(AppTheme.values()) { theme ->
                        val isSelected = theme == selectedTheme
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(80.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedTheme = theme
                                    prefs.edit().putString("app_theme", theme.key).apply()
                                    onThemeChanged(theme)
                                }
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(theme.primaryColor)
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color.LightGray,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = theme.title,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // --- 3. CABINET & RÉVEIL CLOUD 24/7 ---
            val fcmToken = prefs.getString("fcm_token", "") ?: ""
            val isCloudActive = fcmToken.isNotBlank()

            SettingsSectionCard(
                emoji = "🩺",
                title = "Cabinet & Service Cloud 24/7"
            ) {
                OutlinedTextField(
                    value = doctorName,
                    onValueChange = { doctorName = it },
                    label = { Text("Nom du Médecin") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Réveil Automatique Cloud 24/7",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isCloudActive) "Téléphone synchronisé avec le serveur Cloud" else "En attente de connexion Firebase",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isCloudActive) Color(0xFFECFDF5) else Color(0xFFFEF2F2),
                            border = BorderStroke(1.dp, if (isCloudActive) Color(0xFF6EE7B7) else Color(0xFFFCA5A5))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            if (isCloudActive) Color(0xFF10B981) else Color(0xFFEF4444),
                                            shape = CircleShape
                                        )
                                )
                                Text(
                                    text = if (isCloudActive) "Actif" else "Inactif",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = if (isCloudActive) Color(0xFF047857) else Color(0xFFB91C1C)
                                )
                            }
                        }
                    }
                }
            }

            // --- 4. SAUVEGARDE & RESTAURATION DES PATIENTS ---
            SettingsSectionCard(
                emoji = "🛡️",
                title = "Sauvegarde & Restauration"
            ) {
                Text(
                    "Double Protection : 5 sauvegardes locales tournantes + Cloud Firestore après chaque modification.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Dossier de sauvegarde :", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "📁 $backupFolderDisplayName",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        OutlinedButton(
                            onClick = { folderPickerLauncher.launch(null) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Changer", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    }
                }

                if (lastBackupTime > 0L) {
                    val sdf = SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.getDefault())
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Dernière : ${sdf.format(Date(lastBackupTime))}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "$lastBackupCount patient(s)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isBackingUp = true
                                val res = BackupManager.performManualBackup(context)
                                isBackingUp = false
                                res.onSuccess { msg ->
                                    lastBackupTime = prefs.getLong(BackupManager.KEY_LAST_BACKUP_TIME, 0L)
                                    lastBackupCount = prefs.getInt(BackupManager.KEY_LAST_BACKUP_COUNT, 0)
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }.onFailure { err ->
                                    Toast.makeText(context, "Erreur sauvegarde: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isBackingUp,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(42.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (isBackingUp) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("💾 Sauvegarder", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }

                    OutlinedButton(
                        onClick = { showRestoreDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(42.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("🔄 Restaurer", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }

            // --- 5. PLANIFICATION & RÉSEAU LOCAL ---
            SettingsSectionCard(
                emoji = "⏱️",
                title = "Planification & Réseau"
            ) {
                OutlinedTextField(
                    value = reminderHours,
                    onValueChange = { reminderHours = it.filter { char -> char.isDigit() } },
                    label = { Text("Délai du rappel (heures avant le RDV)") },
                    placeholder = { Text("48") },
                    supportingText = { Text("Rappel programmé à ${reminderHours.ifBlank { "48" }}h avant le RDV.") },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = serverIp,
                    onValueChange = { serverIp = it },
                    label = { Text("IP Serveur Local PC (Facultatif avec Cloud)") },
                    placeholder = { Text("ex: 192.168.1.15:3000") },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // --- 5B. FIABILITÉ ARRIÈRE-PLAN (BATTERIE & DÉMARRAGE AUTO) ---
            val powerMgr = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val batteryUnrestricted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                powerMgr?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } else true

            SettingsSectionCard(
                emoji = "🔋",
                title = "Fiabilité en Veille (Écran éteint)"
            ) {
                Text(
                    "Sur Xiaomi (HyperOS/MIUI), Infinix (XOS), Oppo ou Samsung, activez « Démarrage automatique » et « Batterie : Aucune restriction » pour envoyer les SMS sans ouvrir l'application.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!batteryUnrestricted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        Button(
                            onClick = {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Text("Batterie libre", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val autostartIntents = listOf(
                                android.content.Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                                android.content.Intent().setComponent(android.content.ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")),
                                android.content.Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                                android.content.Intent().setComponent(android.content.ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")),
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                            )
                            for (intent in autostartIntents) {
                                try {
                                    context.startActivity(intent)
                                    return@OutlinedButton
                                } catch (_: Exception) {}
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text("Démarrage auto / App Info", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }

            // --- 6. EMAIL & MODÈLES DE SMS ---
            SettingsSectionCard(
                emoji = "✉️",
                title = "Email & Modèles de Rappel SMS"
            ) {
                OutlinedTextField(
                    value = smtpEmail,
                    onValueChange = { smtpEmail = it },
                    label = { Text("Adresse Email Gmail (Expéditeur)") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = smtpPassword,
                    onValueChange = { smtpPassword = it },
                    label = { Text("Mot de passe d'application Gmail") },
                    supportingText = { Text("Mot de passe à 16 caractères généré dans votre compte Google.") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Masquer" else "Voir", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Modèles de messages :", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    TextButton(onClick = {
                        templateFr = defaultFr
                        templateAr = defaultAr
                        templateEn = defaultEn
                    }) {
                        Text("Réinitialiser", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }

                OutlinedTextField(
                    value = templateFr,
                    onValueChange = { templateFr = it },
                    label = { Text("Modèle Français") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )

                Spacer(modifier = Modifier.height(10.dp))

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    OutlinedTextField(
                        value = templateAr,
                        onValueChange = { templateAr = it },
                        label = { Text("Modèle Arabe (نموذج عربي)") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = templateEn,
                    onValueChange = { templateEn = it },
                    label = { Text("Modèle Anglais") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    emoji: String,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            content()
        }
    }
}
