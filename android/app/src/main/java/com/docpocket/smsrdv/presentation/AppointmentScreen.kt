package com.docpocket.smsrdv.presentation

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docpocket.smsrdv.R
import com.docpocket.smsrdv.data.local.AppointmentEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private enum class AppointmentFilter(val label: String) {
    ALL("Tous"),
    TODAY("Aujourd'hui"),
    PENDING("En attente"),
    SENT("Envoyés")
}

private fun isAppointmentExpired(dateStr: String, timeStr: String): Boolean {
    return try {
        val cleanDate = dateStr.trim()
        val cleanTime = timeStr.trim().ifEmpty { "09:00" }
        val formats = listOf(
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
            SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
        )
        for (f in formats) {
            try {
                val parsed = f.parse("$cleanDate $cleanTime")
                if (parsed != null) {
                    return parsed.time < (System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
                }
            } catch (_: Exception) {}
        }
        false
    } catch (_: Exception) {
        false
    }
}

private fun formatDoctorDisplayName(rawName: String): String {
    val clean = rawName.trim().replace(Regex("^(?i)(docteur|dr\\.?)\\s+"), "").trim()
    return if (clean.isNotEmpty()) "Dr $clean" else "Cabinet Médical"
}

private fun getPatientInitials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentScreen(viewModel: AppointmentViewModel = viewModel(), onNavigateToSettings: () -> Unit) {
    val appointments by viewModel.appointments.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE) }

    val doctorName = prefs.getString("doctor_name", "") ?: ""
    val configuredHours = prefs.getInt("reminder_hours", 48)
    val isCloudActive = !(prefs.getString("fcm_token", "") ?: "").isBlank()

    // État du formulaire
    var isFormExpanded by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Int?>(null) }
    var nomPatient by remember { mutableStateOf("") }
    var telephone by remember { mutableStateOf("") }
    var dateRdv by remember { mutableStateOf("") }
    var heureRdv by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("fr") }
    var texteSms by remember { mutableStateOf("") }

    // État recherche et filtres
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(AppointmentFilter.ALL) }

    // Scroll & Coroutine
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    var canExactAlarm by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager?.canScheduleExactAlarms() ?: true else true
        )
    }
    var isIgnoringBattery by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true else true
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                canExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager?.canScheduleExactAlarms() ?: true
                } else true
                isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
                } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val needsBackgroundPermissions = !canExactAlarm || !isIgnoringBattery
    val formItemIndex = if (needsBackgroundPermissions) 2 else 1

    // Dialogues
    var showAboutDialog by remember { mutableStateOf(false) }
    var pendingDeleteAppt by remember { mutableStateOf<AppointmentEntity?>(null) }

    LaunchedEffect(nomPatient, dateRdv, heureRdv, language, editingId) {
        if (editingId == null) {
            texteSms = viewModel.generateSmsText(
                nomPatient.ifBlank { "{Nom du Patient}" },
                dateRdv.ifBlank { "{Date}" },
                heureRdv.ifBlank { "{Heure}" },
                language
            )
        }
    }

    val todayFormatted = remember {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Calendar.getInstance().time)
    }

    // Statistiques rapides
    val todayCount = remember(appointments, todayFormatted) {
        appointments.count { it.dateRdv.trim() == todayFormatted }
    }
    val sentCount = remember(appointments) {
        appointments.count { it.smsEnvoye }
    }
    val pendingCount = remember(appointments) {
        appointments.count { !it.smsEnvoye && !isAppointmentExpired(it.dateRdv, it.heureRdv) }
    }

    // Liste filtrée par recherche + filtre actif
    val filteredAppointments = remember(appointments, searchQuery, activeFilter, todayFormatted) {
        val q = searchQuery.trim()
        appointments.filter { rdv ->
            val matchesFilter = when (activeFilter) {
                AppointmentFilter.ALL -> true
                AppointmentFilter.TODAY -> rdv.dateRdv.trim() == todayFormatted
                AppointmentFilter.PENDING -> !rdv.smsEnvoye && !isAppointmentExpired(rdv.dateRdv, rdv.heureRdv)
                AppointmentFilter.SENT -> rdv.smsEnvoye
            }
            val matchesQuery = if (q.isEmpty()) true else {
                rdv.nomPatient.contains(q, ignoreCase = true) ||
                    rdv.telephone.contains(q, ignoreCase = true) ||
                    rdv.dateRdv.contains(q, ignoreCase = true) ||
                    (rdv.email?.contains(q, ignoreCase = true) == true)
            }
            matchesFilter && matchesQuery
        }
    }

    fun resetForm() {
        editingId = null
        nomPatient = ""
        telephone = ""
        email = ""
        dateRdv = ""
        heureRdv = ""
        texteSms = viewModel.generateSmsText("{Nom du Patient}", "{Date}", "{Heure}", language)
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    pendingDeleteAppt?.let { rdvToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAppt = null },
            icon = {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text("Supprimer ce rendez-vous ?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Voulez-vous vraiment supprimer le rendez-vous de ${rdvToDelete.nomPatient} prévu le ${rdvToDelete.dateRdv} à ${rdvToDelete.heureRdv} ?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAppointment(rdvToDelete.id)
                        pendingDeleteAppt = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Supprimer", maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAppt = null }) {
                    Text("Annuler", maxLines = 1, softWrap = false)
                }
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.app_logo),
                                    contentDescription = "Logo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "DocPocket SMS RDV",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                letterSpacing = (-0.3).sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalIconButton(
                                onClick = { showAboutDialog = true },
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Icon(Icons.Default.Info, contentDescription = "À propos", modifier = Modifier.size(16.dp))
                            }
                            FilledTonalIconButton(
                                onClick = { viewModel.forceSync() },
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Synchroniser", modifier = Modifier.size(16.dp))
                            }
                            FilledTonalIconButton(
                                onClick = onNavigateToSettings,
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Paramètres",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Bandeau identité du médecin & statut Cloud sur toute la largeur
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = formatDoctorDisplayName(doctorName),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(
                                            if (isCloudActive) Color(0xFF10B981) else Color(0xFFEF4444),
                                            CircleShape
                                        )
                                )
                                Text(
                                    text = if (isCloudActive) "Cloud 24/7" else "Hors ligne",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isCloudActive) Color(0xFF047857) else Color(0xFFB91C1C),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            // 1. Alerte Permissions Arrière-Plan (Alarmes Exactes / Batterie)
            if (needsBackgroundPermissions) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Envoi SMS en veille bloqué par Android",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF991B1B)
                                )
                            }
                            Text(
                                "Sans ces autorisations, le téléphone suspend l'application écran éteint et n'envoie le SMS qu'à l'ouverture :",
                                fontSize = 12.sp,
                                color = Color(0xFF7F1D1D)
                            )
                            if (!canExactAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("1. Autoriser Alarmes & Rappels", fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            }
                            if (!isIgnoringBattery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(fallback)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Désactiver l'économie de batterie (Aucune restriction)", fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }

            // 2. Tableau de bord KPI (4 indicateurs interactifs)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DashboardStatPill(
                        label = "Aujourd'hui",
                        count = todayCount,
                        accentColor = MaterialTheme.colorScheme.primary,
                        isSelected = activeFilter == AppointmentFilter.TODAY,
                        onClick = {
                            activeFilter = if (activeFilter == AppointmentFilter.TODAY) AppointmentFilter.ALL else AppointmentFilter.TODAY
                        },
                        modifier = Modifier.weight(1f)
                    )
                    DashboardStatPill(
                        label = "En attente",
                        count = pendingCount,
                        accentColor = Color(0xFFD97706),
                        isSelected = activeFilter == AppointmentFilter.PENDING,
                        onClick = {
                            activeFilter = if (activeFilter == AppointmentFilter.PENDING) AppointmentFilter.ALL else AppointmentFilter.PENDING
                        },
                        modifier = Modifier.weight(1f)
                    )
                    DashboardStatPill(
                        label = "Envoyés",
                        count = sentCount,
                        accentColor = Color(0xFF059669),
                        isSelected = activeFilter == AppointmentFilter.SENT,
                        onClick = {
                            activeFilter = if (activeFilter == AppointmentFilter.SENT) AppointmentFilter.ALL else AppointmentFilter.SENT
                        },
                        modifier = Modifier.weight(1f)
                    )
                    DashboardStatPill(
                        label = "Total",
                        count = appointments.size,
                        accentColor = Color(0xFF6366F1),
                        isSelected = activeFilter == AppointmentFilter.ALL,
                        onClick = { activeFilter = AppointmentFilter.ALL },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. Carte Formulaire Moderne & Rétractable (Nouveau RDV / Modification)
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        width = if (isFormExpanded) 1.5.dp else 1.dp,
                        color = if (isFormExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isFormExpanded) 3.dp else 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // En-tête cliquable pour ouvrir/fermer
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isFormExpanded = !isFormExpanded }
                                .background(
                                    if (isFormExpanded)
                                        Brush.horizontalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                                MaterialTheme.colorScheme.surface
                                            )
                                        )
                                    else
                                        Brush.horizontalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f)
                                            )
                                        )
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (editingId != null) Icons.Default.Edit else Icons.Default.Add,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (editingId == null) "Nouveau Rendez-Vous" else "Modifier le Rendez-Vous",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isFormExpanded) "Remplissez les coordonnées ci-dessous" else "Appuyez ici pour planifier un rappel SMS",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isFormExpanded) "Réduire" else "Ouvrir",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Icon(
                                        imageVector = if (isFormExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Contenu animé du formulaire
                        AnimatedVisibility(
                            visible = isFormExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = nomPatient,
                                    onValueChange = { nomPatient = it },
                                    label = { Text("Nom du patient", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    placeholder = { Text("Ex: Ahmed Ben Ali") },
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = telephone,
                                    onValueChange = { telephone = it },
                                    label = { Text("Téléphone portable", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    placeholder = { Text("Ex: 53573117") },
                                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )

                                OutlinedTextField(
                                    value = email,
                                    onValueChange = { email = it },
                                    label = { Text("Email (optionnel)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    placeholder = { Text("patient@email.com") },
                                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                // Date & Heure côte à côte avec libellés courts (jamais de saut de ligne !)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = dateRdv,
                                        onValueChange = { input ->
                                            val digits = input.filter { it.isDigit() }.take(8)
                                            val formatted = buildString {
                                                for (i in digits.indices) {
                                                    append(digits[i])
                                                    if ((i == 1 || i == 3) && i != digits.lastIndex) append('/')
                                                }
                                            }
                                            dateRdv = formatted
                                        },
                                        label = { Text("Date", maxLines = 1, softWrap = false) },
                                        placeholder = { Text("jj/mm/aaaa") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true,
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                val c = Calendar.getInstance()
                                                DatePickerDialog(context, { _, y, m, d ->
                                                    dateRdv = String.format("%02d/%02d/%04d", d, m + 1, y)
                                                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                                            }) {
                                                Icon(Icons.Default.DateRange, contentDescription = "Choisir date", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        modifier = Modifier.weight(1.25f)
                                    )
                                    OutlinedTextField(
                                        value = heureRdv,
                                        onValueChange = { input ->
                                            val digits = input.filter { it.isDigit() }.take(4)
                                            heureRdv = if (digits.length <= 2) digits else "${digits.substring(0, 2)}:${digits.substring(2)}"
                                        },
                                        label = { Text("Heure", maxLines = 1, softWrap = false) },
                                        placeholder = { Text("hh:mm") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true,
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                val c = Calendar.getInstance()
                                                TimePickerDialog(context, { _, h, m ->
                                                    heureRdv = String.format("%02d:%02d", h, m)
                                                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                                            }) {
                                                Icon(Icons.Default.Notifications, contentDescription = "Choisir heure", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                // Boutons de sélection rapide de date
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("Aujourd'hui" to 0, "Demain" to 1, "+48h" to 2, "+7 jours" to 7).forEach { (chipLabel, daysToAdd) ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    val c = Calendar.getInstance()
                                                    c.add(Calendar.DAY_OF_YEAR, daysToAdd)
                                                    dateRdv = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(c.time)
                                                }
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = chipLabel,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }
                                }

                                // Sélecteur de langue
                                var expandedLang by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = expandedLang,
                                    onExpandedChange = { expandedLang = !expandedLang }
                                ) {
                                    OutlinedTextField(
                                        value = when (language) {
                                            "ar" -> "Arabe (العربية)"
                                            "en" -> "Anglais (English)"
                                            else -> "Français"
                                        },
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Langue du SMS") },
                                        shape = RoundedCornerShape(12.dp),
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedLang) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = expandedLang,
                                        onDismissRequest = { expandedLang = false }
                                    ) {
                                        DropdownMenuItem(text = { Text("Français") }, onClick = {
                                            language = "fr"
                                            texteSms = viewModel.generateSmsText(nomPatient.ifBlank { "{Nom du Patient}" }, dateRdv.ifBlank { "{Date}" }, heureRdv.ifBlank { "{Heure}" }, "fr")
                                            expandedLang = false
                                        })
                                        DropdownMenuItem(text = { Text("Arabe (العربية)") }, onClick = {
                                            language = "ar"
                                            texteSms = viewModel.generateSmsText(nomPatient.ifBlank { "{Nom du Patient}" }, dateRdv.ifBlank { "{Date}" }, heureRdv.ifBlank { "{Heure}" }, "ar")
                                            expandedLang = false
                                        })
                                        DropdownMenuItem(text = { Text("Anglais (English)") }, onClick = {
                                            language = "en"
                                            texteSms = viewModel.generateSmsText(nomPatient.ifBlank { "{Nom du Patient}" }, dateRdv.ifBlank { "{Date}" }, heureRdv.ifBlank { "{Heure}" }, "en")
                                            expandedLang = false
                                        })
                                    }
                                }

                                // Message personnalisé + compteur
                                OutlinedTextField(
                                    value = texteSms,
                                    onValueChange = { texteSms = it },
                                    label = { Text("Aperçu du SMS (${texteSms.length} car.)") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 120.dp),
                                    maxLines = 4
                                )

                                // Bandeau Délai de rappel + Bouton Modifier protégé contre le wrapping
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
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
                                            Text("⏰", fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
                                            Column {
                                                Text(
                                                    "Délai d'envoi du rappel",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    "${configuredHours}h avant le rendez-vous",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = onNavigateToSettings,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text(
                                                "Modifier",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }

                                // Boutons Valider / Annuler
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            resetForm()
                                            isFormExpanded = false
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(0.4f).height(46.dp)
                                    ) {
                                        Text("Annuler", maxLines = 1, softWrap = false)
                                    }
                                    Button(
                                        onClick = {
                                            if (nomPatient.isNotBlank() && (telephone.isNotBlank() || email.isNotBlank()) && dateRdv.isNotBlank() && heureRdv.isNotBlank()) {
                                                if (editingId == null) {
                                                    viewModel.addAppointment(nomPatient, telephone, email, dateRdv, heureRdv, texteSms, language)
                                                } else {
                                                    viewModel.updateAppointment(editingId!!, nomPatient, telephone, email, dateRdv, heureRdv, texteSms, language)
                                                }
                                                resetForm()
                                                isFormExpanded = false
                                            }
                                        },
                                        enabled = nomPatient.isNotBlank() && (telephone.isNotBlank() || email.isNotBlank()) && dateRdv.isNotBlank() && heureRdv.isNotBlank(),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(0.6f).height(46.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            if (editingId == null) "Enregistrer" else "Mettre à jour",
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Zone de Recherche de Patient + Filtres
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                "Rechercher un patient (nom, téléphone, date)...",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Rechercher", tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Effacer la recherche", modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Onglets de filtre rapides
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(AppointmentFilter.values()) { filter ->
                            val isSelected = activeFilter == filter
                            val countForFilter = when (filter) {
                                AppointmentFilter.ALL -> appointments.size
                                AppointmentFilter.TODAY -> todayCount
                                AppointmentFilter.PENDING -> pendingCount
                                AppointmentFilter.SENT -> sentCount
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = { activeFilter = filter },
                                label = {
                                    Text(
                                        "${filter.label} ($countForFilter)",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                shape = RoundedCornerShape(20.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Liste des rendez-vous (${filteredAppointments.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (searchQuery.isNotEmpty() || activeFilter != AppointmentFilter.ALL) {
                            Text(
                                text = "Tout afficher",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    searchQuery = ""
                                    activeFilter = AppointmentFilter.ALL
                                }
                            )
                        }
                    }
                }
            }

            // 5. État vide si aucun résultat
            if (filteredAppointments.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = if (searchQuery.isNotEmpty()) Icons.Default.Search else Icons.Default.DateRange,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty())
                                    "Aucun patient trouvé pour « $searchQuery »"
                                else
                                    "Aucun rendez-vous dans cette catégorie",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty())
                                    "Essayez avec un autre nom, numéro ou date."
                                else
                                    "Appuyez sur « Nouveau Rendez-Vous » ci-dessus pour commencer.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // 6. Liste des cartes patients
                items(filteredAppointments, key = { it.id }) { rdv ->
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
                            isFormExpanded = true
                            coroutineScope.launch {
                                listState.animateScrollToItem(formItemIndex)
                            }
                        },
                        onDeleteClick = { pendingDeleteAppt = rdv }
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardStatPill(
    label: String,
    count: Int,
    accentColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) accentColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) accentColor else MaterialTheme.colorScheme.outlineVariant
        ),
        shadowElevation = if (isSelected) 0.dp else 1.dp,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accentColor,
                maxLines = 1
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
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
    val isExpired = remember(appointment.dateRdv, appointment.heureRdv, appointment.smsEnvoye) {
        !appointment.smsEnvoye && isAppointmentExpired(appointment.dateRdv, appointment.heureRdv)
    }

    val statusColor = when {
        appointment.smsEnvoye -> Color(0xFF10B981)
        isExpired -> Color(0xFF94A3B8)
        else -> Color(0xFFF59E0B)
    }

    val statusBgColor = when {
        appointment.smsEnvoye -> Color(0xFFECFDF5)
        isExpired -> Color(0xFFF1F5F9)
        else -> Color(0xFFFFFBEB)
    }

    val statusTextColor = when {
        appointment.smsEnvoye -> Color(0xFF047857)
        isExpired -> Color(0xFF475569)
        else -> Color(0xFFB45309)
    }

    val statusLabel = when {
        appointment.smsEnvoye -> "SMS Envoyé"
        isExpired -> "Expiré (Passé)"
        else -> "En attente"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Barre verticale de statut à gauche
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(statusColor)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar initiales patient
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = getPatientInitials(appointment.nomPatient),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.width(11.dp))

                // Informations patient + Date/Tél + Badge statut
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = appointment.nomPatient,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = "📅 ${appointment.dateRdv} à ${appointment.heureRdv}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = "📞 ${appointment.telephone}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = statusBgColor,
                        border = BorderStroke(0.5.dp, statusColor.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(statusColor, CircleShape)
                            )
                            Text(
                                text = statusLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusTextColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // Boutons d'action groupés proprement
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = onSendSmsClick,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Envoyer SMS maintenant",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    IconButton(
                        onClick = onEditClick,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Modifier",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Supprimer",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
