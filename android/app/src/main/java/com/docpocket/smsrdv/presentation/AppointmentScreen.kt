package com.docpocket.smsrdv.presentation

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docpocket.smsrdv.data.local.AppointmentEntity
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentScreen(viewModel: AppointmentViewModel = viewModel(), onNavigateToSettings: () -> Unit) {
    val appointments by viewModel.appointments.collectAsState()
    val globalTemplate by viewModel.globalSmsTemplate.collectAsState()
    val context = LocalContext.current

    var editingId by remember { mutableStateOf<Int?>(null) }
    var nomPatient by remember { mutableStateOf("") }
    var telephone by remember { mutableStateOf("") }
    var dateRdv by remember { mutableStateOf("") }
    var heureRdv by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("fr") }
    var texteSms by remember { mutableStateOf("") }
    
    var showSettings by remember { mutableStateOf(false) }

    // Auto-update SMS text if it's new
    LaunchedEffect(nomPatient, dateRdv, heureRdv, language, editingId) {
        if (editingId == null) {
            texteSms = viewModel.generateSmsText(nomPatient.ifBlank { "{Nom du Patient}" }, dateRdv.ifBlank { "{Date}" }, heureRdv.ifBlank { "{Heure}" }, language)
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Paramètres du SMS Global") },
            text = {
                var tempTemplate by remember { mutableStateOf(globalTemplate) }
                Column {
                    Text("Utilisez les balises [Nom], [Date], [Heure] pour personnaliser.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempTemplate,
                        onValueChange = { tempTemplate = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Save is not implemented in dialog directly, we need a local state, but let's do it simple
                    // Wait, we need to pass tempTemplate back. See below:
                }) { }
            }
        )
    }

    // Settings Dialog implementation
    var showSettingsDialog by remember { mutableStateOf(false) }
    if (showSettingsDialog) {
        var tempTemplate by remember { mutableStateOf(globalTemplate) }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Texte SMS par défaut") },
            text = {
                Column {
                    Text("Ce modèle sera utilisé pour pré-remplir les nouveaux rendez-vous.\nBalises : [Nom], [Date], [Heure]", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempTemplate,
                        onValueChange = { tempTemplate = it },
                        modifier = Modifier.fillMaxWidth().height(150.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveGlobalSmsTemplate(tempTemplate)
                    showSettingsDialog = false
                    if (editingId == null) {
                         texteSms = viewModel.generateSmsText(nomPatient.ifBlank { "[Nom]" }, dateRdv.ifBlank { "[Date]" }, heureRdv.ifBlank { "[Heure]" })
                    }
                }) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("Annuler") }
            }
        )
    }

    var showAboutDialog by remember { mutableStateOf(false) }
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = com.docpocket.smsrdv.R.drawable.app_logo),
                            contentDescription = "Logo",
                            modifier = Modifier.size(36.dp).padding(end = 8.dp)
                        )
                        Text("DocPocket SMS RDV")
                    }
                },
                actions = {
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "À propos")
                    }
                    IconButton(onClick = { viewModel.forceSync() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Forcer Synchro")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))

                // Vérification dynamique de l'autorisation 'Alarmes et rappels'
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                val canExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager?.canScheduleExactAlarms() ?: true
                } else true

                if (!canExactAlarm) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "⚠️ Action Requise : Alarmes et Rappels",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Pour déclencher l'envoi de SMS en arrière-plan lorsque le téléphone est verrouillé, vous devez autoriser DocPocket SMS RDV dans 'Alarmes et rappels'.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Activer 'Alarmes et rappels'")
                            }
                        }
                    }
                }

                // Formulaire
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(if (editingId == null) "Ajouter un rendez-vous" else "Modifier le rendez-vous", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        
                        OutlinedTextField(
                            value = nomPatient,
                            onValueChange = { nomPatient = it },
                            label = { Text("Nom du patient") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = telephone,
                            onValueChange = { telephone = it },
                            label = { Text("Téléphone (+33...)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email du patient (Optionnel)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = dateRdv,
                                onValueChange = { input ->
                                    // Format manuel strict jj/mm/aaaa avec insertion automatique des /
                                    val digits = input.filter { it.isDigit() }.take(8)
                                    val formatted = buildString {
                                        for (i in digits.indices) {
                                            append(digits[i])
                                            if ((i == 1 || i == 3) && i != digits.lastIndex) {
                                                append('/')
                                            }
                                        }
                                    }
                                    dateRdv = formatted
                                },
                                label = { Text("Date (jj/mm/aaaa)") },
                                placeholder = { Text("JJ/MM/AAAA") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        val c = Calendar.getInstance()
                                        DatePickerDialog(context, { _, y, m, d ->
                                            dateRdv = String.format("%02d/%02d/%04d", d, m + 1, y)
                                        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                                    }) {
                                        Icon(Icons.Default.DateRange, contentDescription = "Choisir date")
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = heureRdv,
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }.take(4)
                                    val formatted = if (digits.length <= 2) digits else "${digits.substring(0, 2)}:${digits.substring(2)}"
                                    heureRdv = formatted
                                },
                                label = { Text("Heure (hh:mm)") },
                                placeholder = { Text("HH:MM") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        val c = Calendar.getInstance()
                                        TimePickerDialog(context, { _, h, m ->
                                            heureRdv = String.format("%02d:%02d", h, m)
                                        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                                    }) {
                                        Icon(Icons.Default.Notifications, contentDescription = "Choisir heure")
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Language Selector
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = when (language) { "fr" -> "Français"; "ar" -> "Arabe"; "en" -> "Anglais"; else -> "Français" },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Langue du message") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                DropdownMenuItem(text = { Text("Français") }, onClick = { 
                                    language = "fr"
                                    texteSms = viewModel.generateSmsText(nomPatient.ifBlank { "{Nom du Patient}" }, dateRdv.ifBlank { "{Date}" }, heureRdv.ifBlank { "{Heure}" }, "fr")
                                    expanded = false 
                                })
                                DropdownMenuItem(text = { Text("Arabe") }, onClick = { 
                                    language = "ar"
                                    texteSms = viewModel.generateSmsText(nomPatient.ifBlank { "{Nom du Patient}" }, dateRdv.ifBlank { "{Date}" }, heureRdv.ifBlank { "{Heure}" }, "ar")
                                    expanded = false 
                                })
                                DropdownMenuItem(text = { Text("Anglais") }, onClick = { 
                                    language = "en"
                                    texteSms = viewModel.generateSmsText(nomPatient.ifBlank { "{Nom du Patient}" }, dateRdv.ifBlank { "{Date}" }, heureRdv.ifBlank { "{Heure}" }, "en")
                                    expanded = false 
                                })
                            }
                        }
                        
                        OutlinedTextField(
                            value = texteSms,
                            onValueChange = { texteSms = it },
                            label = { Text("Texte du message personnalisé") },
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            maxLines = 4
                        )

                        val prefs = context.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
                        val configuredHours = prefs.getInt("reminder_hours", 48)

                        Card(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                ) {
                                    Text("⏰", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
                                    Column {
                                        Text(
                                            "Délai d'envoi du rappel",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "${configuredHours}h avant le rendez-vous",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = onNavigateToSettings,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(
                                        "Modifier",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (editingId != null) {
                                TextButton(onClick = {
                                    editingId = null
                                    nomPatient = ""
                                    telephone = ""
                                    email = ""
                                    dateRdv = ""
                                    heureRdv = ""
                                    texteSms = viewModel.generateSmsText("{Nom du Patient}", "{Date}", "{Heure}", language)
                                }) {
                                    Text("Annuler")
                                }
                            }
                            Button(
                                onClick = {
                                    if (nomPatient.isNotBlank() && (telephone.isNotBlank() || email.isNotBlank()) && dateRdv.isNotBlank() && heureRdv.isNotBlank()) {
                                        if (editingId == null) {
                                            viewModel.addAppointment(nomPatient, telephone, email, dateRdv, heureRdv, texteSms, language)
                                        } else {
                                            viewModel.updateAppointment(editingId!!, nomPatient, telephone, email, dateRdv, heureRdv, texteSms, language)
                                            editingId = null
                                        }
                                        nomPatient = ""
                                        telephone = ""
                                        email = ""
                                        dateRdv = ""
                                        heureRdv = ""
                                        texteSms = viewModel.generateSmsText("{Nom du Patient}", "{Date}", "{Heure}", language)
                                    }
                                }
                            ) {
                                Text(if (editingId == null) "Ajouter" else "Enregistrer")
                            }
                        }
                    }
                }
            }

            item {
                Text("Liste des rendez-vous", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }

            items(appointments) { rdv ->
                AppointmentItem(
                    appointment = rdv,
                    onSendSmsClick = { viewModel.sendSmsNow(rdv) },
                    onEditClick = {
                        editingId = rdv.id
                        nomPatient = rdv.nomPatient
                        telephone = rdv.telephone
                        email = rdv.email ?: ""
                        language = rdv.language
                        dateRdv = rdv.dateRdv
                        heureRdv = rdv.heureRdv
                        texteSms = rdv.texteSms
                    },
                    onDeleteClick = { viewModel.deleteAppointment(rdv.id) }
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun AppointmentItem(
    appointment: AppointmentEntity,
    onSendSmsClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onSendSmsClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Envoyer SMS maintenant",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = appointment.nomPatient, fontWeight = FontWeight.Bold)
                Text(text = "${appointment.dateRdv} à ${appointment.heureRdv} | ${appointment.telephone}", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SMS : ", style = MaterialTheme.typography.bodySmall)
                    if (appointment.smsEnvoye) {
                        Icon(Icons.Default.CheckCircle, "Envoyé", tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                        Text(" Envoyé", color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(" En attente", color = Color(0xFFF44336), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            IconButton(onClick = onEditClick) {
                Icon(Icons.Default.Edit, contentDescription = "Modifier", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDeleteClick) {
                Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
