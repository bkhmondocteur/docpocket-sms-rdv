package com.docpocket.smsrdv.presentation

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docpocket.smsrdv.backup.BackupItem
import com.docpocket.smsrdv.backup.BackupManager
import com.docpocket.smsrdv.backup.RestoreMode
import com.docpocket.smsrdv.backup.RestoreResult
import kotlinx.coroutines.launch

@Composable
fun RestoreDialog(
    onDismiss: () -> Unit,
    onRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var backups by remember { mutableStateOf<List<BackupItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedBackup by remember { mutableStateOf<BackupItem?>(null) }
    var showCloudConfirm by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }

    fun loadBackups() {
        coroutineScope.launch {
            isLoading = true
            backups = BackupManager.getAvailableBackups(context)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadBackups()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (!content.isNullOrBlank()) {
                        isRestoring = true
                        val res = BackupManager.restoreFromJson(context, content, RestoreMode.MERGE)
                        isRestoring = false
                        Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                        if (res.success) {
                            onRestored()
                            onDismiss()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Erreur fichier: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🔄 Restaurer les Patients",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Bouton Restauration Cloud Firestore
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                "☁️ Cloud Firestore",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Restaurer depuis votre espace Cloud sécurisé",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Button(
                            onClick = { showCloudConfirm = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Restaurer", fontSize = 11.sp)
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 2.dp))

                // 2. Liste des sauvegardes locales (les 5 tournantes + manuelles)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📁 Sauvegardes locales récentes :",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = { loadBackups() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualiser", modifier = Modifier.size(16.dp))
                    }
                }

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                } else if (backups.isEmpty()) {
                    Text(
                        "Aucun fichier de sauvegarde locale trouvé pour le moment. Une sauvegarde se crée automatiquement à chaque modification de patient.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(backups) { item ->
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedBackup = item }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                if (item.isAuto) "🔄 Auto" else "💾 Manuel",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = if (item.isAuto) MaterialTheme.colorScheme.primary else Color(0xFF2E7D32)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                item.dateFormatted,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Text(
                                            "${item.totalPatients} patient(s) enregistré(s)",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { selectedBackup = item },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("Choisir", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 2.dp))

                // 3. Parcourir un fichier externe
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("application/json") },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    Text("📂 Parcourir un autre fichier .json", fontSize = 12.sp)
                }

                if (isRestoring) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restauration en cours...", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {}
    )

    // Dialogue de confirmation pour une sauvegarde locale sélectionnée
    selectedBackup?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedBackup = null },
            title = {
                Text("Restaurer cette sauvegarde ?")
            },
            text = {
                Column {
                    Text(
                        "Fichier : ${item.fileName}\nDate : ${item.dateFormatted}\nNombre de patients : ${item.totalPatients}\n\nChoisissez la méthode de restauration :",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val backupToRestore = item
                        selectedBackup = null
                        coroutineScope.launch {
                            isRestoring = true
                            val content = BackupManager.readBackupContent(context, backupToRestore)
                            val res = if (!content.isNullOrBlank()) {
                                BackupManager.restoreFromJson(context, content, RestoreMode.MERGE)
                            } else {
                                RestoreResult(false, 0, "Impossible de lire la sauvegarde.")
                            }
                            isRestoring = false
                            Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                            if (res.success) {
                                onRestored()
                                onDismiss()
                            }
                        }
                    }
                ) {
                    Text("Fusionner (Recommandé)")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        val backupToRestore = item
                        selectedBackup = null
                        coroutineScope.launch {
                            isRestoring = true
                            val content = BackupManager.readBackupContent(context, backupToRestore)
                            val res = if (!content.isNullOrBlank()) {
                                BackupManager.restoreFromJson(context, content, RestoreMode.REPLACE)
                            } else {
                                RestoreResult(false, 0, "Impossible de lire la sauvegarde.")
                            }
                            isRestoring = false
                            Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                            if (res.success) {
                                onRestored()
                                onDismiss()
                            }
                        }
                    }
                ) {
                    Text("Remplacer tout")
                }
            }
        )
    }

    // Dialogue de confirmation pour la restauration Cloud Firestore
    if (showCloudConfirm) {
        AlertDialog(
            onDismissRequest = { showCloudConfirm = false },
            title = {
                Text("Restaurer depuis le Cloud Firestore ?")
            },
            text = {
                Text(
                    "Cette action va récupérer la dernière sauvegarde Cloud synchronisée pour votre compte praticien.\n\nChoisissez la méthode :",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCloudConfirm = false
                        coroutineScope.launch {
                            isRestoring = true
                            val res = BackupManager.restoreFromCloud(context, RestoreMode.MERGE)
                            isRestoring = false
                            Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                            if (res.success) {
                                onRestored()
                                onDismiss()
                            }
                        }
                    }
                ) {
                    Text("Fusionner (Recommandé)")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showCloudConfirm = false
                        coroutineScope.launch {
                            isRestoring = true
                            val res = BackupManager.restoreFromCloud(context, RestoreMode.REPLACE)
                            isRestoring = false
                            Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                            if (res.success) {
                                onRestored()
                                onDismiss()
                            }
                        }
                    }
                ) {
                    Text("Remplacer tout")
                }
            }
        )
    }
}
