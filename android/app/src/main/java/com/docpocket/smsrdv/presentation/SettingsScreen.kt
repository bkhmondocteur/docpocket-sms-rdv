package com.docpocket.smsrdv.presentation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docpocket.smsrdv.BuildConfig
import com.docpocket.smsrdv.ui.theme.AppTheme
import kotlinx.coroutines.launch

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
    var doctorName by remember { mutableStateOf(prefs.getString("doctor_name", "") ?: "") }
    var serverIp by remember { mutableStateOf(prefs.getString("server_ip", "") ?: "") }
    var reminderHours by remember { mutableStateOf(prefs.getInt("reminder_hours", 48).toString()) }

    val defaultFr = "Bonjour {Nom du Patient} ce ci est un rappel de votre rdv le {Date} à {Heure} au cabinet du Docteur {Nom Du Médecin}. bonne journée."
    val defaultAr = "\u200Fمرحباً {Nom du Patient} هذا تذكير بموعدك يوم {Date} الساعة {Heure} في عيادة الدكتور {Nom Du Médecin}. نهارك سعيد."
    val defaultEn = "Hello {Nom du Patient} this is a reminder of your appointment on {Date} at {Heure} at Doctor {Nom Du Médecin}'s clinic. Have a good day."

    var templateFr by remember { mutableStateOf(prefs.getString("template_fr", defaultFr) ?: defaultFr) }
    var templateAr by remember { mutableStateOf(prefs.getString("template_ar", defaultAr) ?: defaultAr) }
    var templateEn by remember { mutableStateOf(prefs.getString("template_en", defaultEn) ?: defaultEn) }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "À propos")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // --- 1. CARTE À PROPOS & VERSION ---
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DocPocket SMS RDV",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Button(
                        onClick = { showAboutDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("À propos", fontSize = 12.sp)
                    }
                }
            }

            // --- 2. SÉLECTION DU THÈME VISUEL (10 THÈMES) ---
            Text(
                "Apparence & Thèmes (10 Thèmes)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Thème actuel : ${selectedTheme.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
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
                                    .width(78.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedTheme = theme
                                        prefs.edit().putString("app_theme", theme.key).apply()
                                        onThemeChanged(theme)
                                    }
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else Color.Transparent
                                    )
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(theme.primaryColor)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = theme.title,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // --- 3. CONFIGURATION DU CABINET ---
            Text("Configuration du Cabinet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = doctorName,
                onValueChange = { doctorName = it },
                label = { Text("Nom Du Médecin") },
                modifier = Modifier.fillMaxWidth()
            )

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // --- 4. RÉVEIL AUTOMATIQUE CLOUD 24/7 (ÉPURÉ SANS BOUTON TEST/TOKEN) ---
            Text("Réveil Automatique Cloud 24/7", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✅", fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "Service Cloud FCM Prêt & Connecté",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Votre téléphone est réveillé automatiquement en veille profonde (écran éteint) pour expédier le SMS à la seconde exacte.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // --- 5. PLANIFICATION DU RAPPEL SMS ---
            Text("Planification du Rappel SMS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = reminderHours,
                onValueChange = { reminderHours = it.filter { char -> char.isDigit() } },
                label = { Text("Délai d'envoi du rappel (heures avant le RDV)") },
                placeholder = { Text("48") },
                supportingText = { Text("Rappel programmé à ${reminderHours.ifBlank { "48" }}h avant l'heure exacte du RDV.") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            )

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // --- 6. RÉSEAU LOCAL (OPTIONNEL) ---
            Text("Réseau Local PC (Optionnel)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = serverIp,
                onValueChange = { serverIp = it },
                label = { Text("Adresse IP du PC (Facultatif avec le Cloud)") },
                placeholder = { Text("ex: 192.168.1.15:3000") },
                modifier = Modifier.fillMaxWidth()
            )

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // --- 7. CONFIGURATION EMAIL ---
            Text("Configuration Email (Expéditeur)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = smtpEmail,
                onValueChange = { smtpEmail = it },
                label = { Text("Adresse Email (Gmail)") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = smtpPassword,
                onValueChange = { smtpPassword = it },
                label = { Text("Mot de passe d'application Gmail") },
                supportingText = { Text("Générez un mot de passe d'application à 16 lettres dans votre compte Google.") },
                modifier = Modifier.fillMaxWidth()
            )

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // --- 8. MODÈLES DE SMS ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Modèles de Rappel SMS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = {
                    templateFr = defaultFr
                    templateAr = defaultAr
                    templateEn = defaultEn
                }) {
                    Text("Réinitialiser")
                }
            }

            OutlinedTextField(
                value = templateFr,
                onValueChange = { templateFr = it },
                label = { Text("Modèle Français") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                OutlinedTextField(
                    value = templateAr,
                    onValueChange = { templateAr = it },
                    label = { Text("Modèle Arabe (نموذج عربي)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }

            OutlinedTextField(
                value = templateEn,
                onValueChange = { templateEn = it },
                label = { Text("Modèle Anglais") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            // --- 9. BOUTON SAUVEGARDER ---
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

                    // Relancer le service pour reprogrammer avec la nouvelle durée
                    val serviceIntent = android.content.Intent(context, com.docpocket.smsrdv.worker.SyncService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }

                    android.widget.Toast.makeText(context, "Paramètres enregistrés avec succès !", android.widget.Toast.LENGTH_SHORT).show()
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Text("Sauvegarder les Paramètres")
            }
        }
    }
}
