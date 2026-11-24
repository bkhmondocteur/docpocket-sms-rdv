package com.docpocket.smsrdv.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.docpocket.smsrdv.data.local.AppDatabase
import com.docpocket.smsrdv.data.local.AppointmentEntity
import com.docpocket.smsrdv.firebase.FirebaseSyncManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupItem(
    val fileName: String,
    val timestamp: Long,
    val dateFormatted: String,
    val totalPatients: Int,
    val isAuto: Boolean,
    val uri: Uri? = null,
    val file: File? = null
)

enum class RestoreMode {
    REPLACE,
    MERGE
}

data class RestoreResult(
    val success: Boolean,
    val count: Int,
    val message: String
)

object BackupManager {

    private const val TAG = "BackupManager"
    const val PREFS_NAME = "docpocket_prefs"
    const val KEY_BACKUP_URI = "backup_folder_uri"
    const val KEY_BACKUP_FOLDER_NAME = "backup_folder_name"
    const val KEY_BACKUP_CONFIGURED = "backup_folder_configured"
    const val KEY_LAST_BACKUP_TIME = "last_backup_time"
    const val KEY_LAST_BACKUP_COUNT = "last_backup_count"
    private const val MAX_AUTO_BACKUPS = 5

    fun isFolderConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BACKUP_CONFIGURED, false)
    }

    fun setFolderConfigured(context: Context, configured: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BACKUP_CONFIGURED, configured).apply()
    }

    fun getBackupFolderUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_BACKUP_URI, null) ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            null
        }
    }

    fun setBackupFolderUri(context: Context, uri: Uri, displayName: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Log.w(TAG, "takePersistableUriPermission: ${e.message}")
        }
        val name = displayName ?: uri.lastPathSegment ?: "Dossier sélectionné"
        prefs.edit()
            .putString(KEY_BACKUP_URI, uri.toString())
            .putString(KEY_BACKUP_FOLDER_NAME, name)
            .putBoolean(KEY_BACKUP_CONFIGURED, true)
            .apply()
    }

    fun getBackupFolderDisplayName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_BACKUP_FOLDER_NAME, null)
        if (!name.isNullOrBlank()) return formatFolderDisplayName(name)
        val uriStr = prefs.getString(KEY_BACKUP_URI, null)
        if (!uriStr.isNullOrBlank()) {
            return try {
                val seg = Uri.parse(uriStr).lastPathSegment ?: "Dossier externe"
                formatFolderDisplayName(seg)
            } catch (e: Exception) {
                "Dossier externe"
            }
        }
        return "Documents/DocPocket_Sauvegardes"
    }

    private fun formatFolderDisplayName(raw: String): String {
        val decoded = try { Uri.decode(raw) } catch (e: Exception) { raw }
        val clean = decoded.substringAfterLast(":").substringAfterLast("/").trim()
        return if (clean.isNotBlank()) clean else decoded
    }

    fun getDefaultBackupDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, "documents")
        val dir = File(baseDir, "DocPocket_Sauvegardes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Sauvegarde automatique exécutée après tout ajout, suppression ou modification de patient.
     * Effectue une sauvegarde locale (en gardant au maximum 5 fichiers) ET une sauvegarde sur Cloud Firestore.
     */
    suspend fun performAutoBackup(context: Context) = withContext(Dispatchers.IO) {
        try {
            val dao = AppDatabase.getDatabase(context).appointmentDao()
            val appointments = dao.getAllAppointmentsList()
            val jsonString = buildBackupJson(context, appointments, isAuto = true)

            // 1. Sauvegarde locale (gestion des 5 fichiers max)
            val successLocal = saveLocalBackupFile(context, jsonString, isAuto = true)

            // 2. Sauvegarde Cloud Firestore
            val successCloud = saveCloudBackup(context, jsonString, isAuto = true, count = appointments.size)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
                .putInt(KEY_LAST_BACKUP_COUNT, appointments.size)
                .apply()

            Log.i(TAG, "Auto-backup complété. Local: $successLocal, Cloud: $successCloud, Patients: ${appointments.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur performAutoBackup: ${e.message}", e)
        }
    }

    /**
     * Sauvegarde manuelle déclenchée depuis les Réglages.
     */
    suspend fun performManualBackup(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dao = AppDatabase.getDatabase(context).appointmentDao()
            val appointments = dao.getAllAppointmentsList()
            val jsonString = buildBackupJson(context, appointments, isAuto = false)

            val fileName = saveLocalBackupFile(context, jsonString, isAuto = false)
            saveCloudBackup(context, jsonString, isAuto = false, count = appointments.size)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
                .putInt(KEY_LAST_BACKUP_COUNT, appointments.size)
                .apply()

            Result.success("Sauvegarde réussie ($fileName - ${appointments.size} patients)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildBackupJson(context: Context, appointments: List<AppointmentEntity>, isAuto: Boolean): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val doctorId = FirebaseSyncManager.getDoctorId(context)
        val doctorName = prefs.getString("doctor_name", "Dr.") ?: "Dr."
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        val root = JSONObject()
        root.put("app", "DocPocket SMS RDV")
        root.put("version", 2)
        root.put("type", if (isAuto) "AUTO" else "MANUAL")
        root.put("timestamp", now)
        root.put("dateFormatted", sdf.format(Date(now)))
        root.put("doctorId", doctorId)
        root.put("doctorName", doctorName)
        root.put("totalPatients", appointments.size)

        val array = JSONArray()
        for (appt in appointments) {
            val obj = JSONObject()
            obj.put("id", appt.id)
            obj.put("nomPatient", appt.nomPatient)
            obj.put("telephone", appt.telephone)
            obj.put("dateRdv", appt.dateRdv)
            obj.put("heureRdv", appt.heureRdv)
            obj.put("texteSms", appt.texteSms)
            obj.put("smsEnvoye", appt.smsEnvoye)
            obj.put("email", appt.email ?: "")
            obj.put("language", appt.language)
            obj.put("firebaseKey", appt.firebaseKey ?: "")
            array.put(obj)
        }
        root.put("patients", array)
        return root.toString(2)
    }

    private fun saveLocalBackupFile(context: Context, jsonContent: String, isAuto: Boolean): String {
        val timestamp = System.currentTimeMillis()
        val fileName = if (isAuto) {
            "docpocket_auto_backup_${timestamp}.json"
        } else {
            val dateTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))
            "docpocket_backup_manuel_${dateTag}.json"
        }

        val treeUri = getBackupFolderUri(context)
        var written = false

        // 1. Essayer d'écrire dans le dossier SAF configuré par l'utilisateur
        if (treeUri != null) {
            try {
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (treeDoc != null && treeDoc.exists() && treeDoc.isDirectory) {
                    if (isAuto) {
                        // Rotation : garder au maximum MAX_AUTO_BACKUPS (5 fichiers auto)
                        rotateSafAutoBackups(context, treeDoc)
                    }

                    val newDoc = treeDoc.createFile("application/json", fileName)
                    if (newDoc != null) {
                        context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                            out.write(jsonContent.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        written = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erreur écriture dossier SAF, fallback local: ${e.message}")
            }
        }

        // 2. Toujours enregistrer également ou en fallback dans le répertoire local garanti
        val defaultDir = getDefaultBackupDir(context)
        if (isAuto) {
            rotateLocalFileAutoBackups(defaultDir)
        }
        val localFile = File(defaultDir, fileName)
        FileOutputStream(localFile).use { fos ->
            fos.write(jsonContent.toByteArray(Charsets.UTF_8))
            fos.flush()
        }

        return fileName
    }

    private fun rotateSafAutoBackups(context: Context, treeDoc: DocumentFile) {
        try {
            val autoFiles = treeDoc.listFiles()
                .filter { it.name?.startsWith("docpocket_auto_") == true && it.name?.endsWith(".json") == true }
                .sortedBy { it.lastModified() }

            if (autoFiles.size >= MAX_AUTO_BACKUPS) {
                val toDeleteCount = autoFiles.size - MAX_AUTO_BACKUPS + 1
                for (i in 0 until toDeleteCount) {
                    autoFiles[i].delete()
                    Log.i(TAG, "Ancien auto-backup supprimé (SAF): ${autoFiles[i].name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur rotation SAF: ${e.message}")
        }
    }

    private fun rotateLocalFileAutoBackups(dir: File) {
        try {
            val autoFiles = dir.listFiles { _, name ->
                name.startsWith("docpocket_auto_") && name.endsWith(".json")
            }?.sortedBy { it.lastModified() } ?: return

            if (autoFiles.size >= MAX_AUTO_BACKUPS) {
                val toDeleteCount = autoFiles.size - MAX_AUTO_BACKUPS + 1
                for (i in 0 until toDeleteCount) {
                    autoFiles[i].delete()
                    Log.i(TAG, "Ancien auto-backup supprimé (Local): ${autoFiles[i].name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur rotation Local: ${e.message}")
        }
    }

    private suspend fun saveCloudBackup(context: Context, jsonContent: String, isAuto: Boolean, count: Int): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val doctorId = FirebaseSyncManager.getDoctorId(context)
            val doctorName = prefs.getString("doctor_name", "Dr.") ?: "Dr."
            val now = System.currentTimeMillis()

            val cloudData = hashMapOf(
                "doctorId" to doctorId,
                "doctorName" to doctorName,
                "backupType" to (if (isAuto) "AUTO" else "MANUAL"),
                "lastBackupTimestamp" to now,
                "dateFormatted" to SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(now)),
                "totalPatients" to count,
                "patientsJson" to jsonContent
            )

            val db = FirebaseFirestore.getInstance()
            db.collection("backups").document(doctorId)
                .set(cloudData, SetOptions.merge())
                .await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Erreur saveCloudBackup: ${e.message}")
            false
        }
    }

    /**
     * Liste l'ensemble des sauvegardes locales disponibles (SAF + local fallback).
     */
    suspend fun getAvailableBackups(context: Context): List<BackupItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<BackupItem>()
        val seenNames = mutableSetOf<String>()

        // 1. Depuis SAF
        val treeUri = getBackupFolderUri(context)
        if (treeUri != null) {
            try {
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (treeDoc != null && treeDoc.exists()) {
                    treeDoc.listFiles().forEach { doc ->
                        val name = doc.name ?: ""
                        if (name.startsWith("docpocket_") && name.endsWith(".json")) {
                            seenNames.add(name)
                            val item = parseBackupHeader(context, doc.uri, name, doc.lastModified())
                            if (item != null) result.add(item)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erreur lecture SAF: ${e.message}")
            }
        }

        // 2. Depuis le répertoire local par défaut
        val defaultDir = getDefaultBackupDir(context)
        defaultDir.listFiles { _, name ->
            name.startsWith("docpocket_") && name.endsWith(".json")
        }?.forEach { file ->
            if (!seenNames.contains(file.name)) {
                seenNames.add(file.name)
                val item = parseBackupHeaderFromFile(file)
                if (item != null) result.add(item)
            }
        }

        result.sortedByDescending { it.timestamp }
    }

    private fun parseBackupHeader(context: Context, uri: Uri, fileName: String, lastModified: Long): BackupItem? {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return null
            val json = JSONObject(content)
            val timestamp = json.optLong("timestamp", lastModified)
            val dateStr = json.optString("dateFormatted", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp)))
            val total = json.optInt("totalPatients", 0)
            val isAuto = json.optString("type", "").equals("AUTO", ignoreCase = true) || fileName.contains("auto")
            BackupItem(fileName, timestamp, dateStr, total, isAuto, uri = uri)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseBackupHeaderFromFile(file: File): BackupItem? {
        return try {
            val content = file.readText()
            val json = JSONObject(content)
            val timestamp = json.optLong("timestamp", file.lastModified())
            val dateStr = json.optString("dateFormatted", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp)))
            val total = json.optInt("totalPatients", 0)
            val isAuto = json.optString("type", "").equals("AUTO", ignoreCase = true) || file.name.contains("auto")
            BackupItem(file.name, timestamp, dateStr, total, isAuto, file = file)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Lit le contenu JSON d'un BackupItem
     */
    suspend fun readBackupContent(context: Context, item: BackupItem): String? = withContext(Dispatchers.IO) {
        try {
            if (item.uri != null) {
                context.contentResolver.openInputStream(item.uri)?.bufferedReader()?.use { it.readText() }
            } else if (item.file != null) {
                item.file.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur readBackupContent: ${e.message}")
            null
        }
    }

    /**
     * Restaure les patients depuis une chaîne JSON
     */
    suspend fun restoreFromJson(context: Context, jsonString: String, mode: RestoreMode): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(jsonString)
            val patientsArray = json.optJSONArray("patients")
                ?: return@withContext RestoreResult(false, 0, "Format de sauvegarde invalide (clé 'patients' absente)")

            val dao = AppDatabase.getDatabase(context).appointmentDao()
            val restoredList = mutableListOf<AppointmentEntity>()

            for (i in 0 until patientsArray.length()) {
                val obj = patientsArray.getJSONObject(i)
                val appt = AppointmentEntity(
                    id = if (mode == RestoreMode.REPLACE) obj.optInt("id", 0) else 0,
                    nomPatient = obj.optString("nomPatient", ""),
                    telephone = obj.optString("telephone", ""),
                    email = obj.optString("email", "").takeIf { it.isNotBlank() },
                    language = obj.optString("language", "fr"),
                    dateRdv = obj.optString("dateRdv", ""),
                    heureRdv = obj.optString("heureRdv", ""),
                    texteSms = obj.optString("texteSms", ""),
                    smsEnvoye = obj.optBoolean("smsEnvoye", false),
                    firebaseKey = obj.optString("firebaseKey", "").takeIf { it.isNotBlank() }
                )
                if (appt.nomPatient.isNotBlank() && appt.telephone.isNotBlank()) {
                    restoredList.add(appt)
                }
            }

            if (mode == RestoreMode.REPLACE) {
                dao.deleteAllAppointments()
                dao.insertAppointments(restoredList)
            } else {
                // Mode FUSION : insérer sans écraser les existants
                val existing = dao.getAllAppointmentsList()
                val toInsert = restoredList.filter { rest ->
                    existing.none { ex ->
                        ex.nomPatient.equals(rest.nomPatient, ignoreCase = true) &&
                                ex.dateRdv == rest.dateRdv &&
                                ex.heureRdv == rest.heureRdv
                    }
                }
                dao.insertAppointments(toInsert)
            }

            // Déclencher une sauvegarde automatique pour synchroniser le nouvel état
            performAutoBackup(context)

            RestoreResult(true, restoredList.size, "${restoredList.size} patient(s) restauré(s) avec succès !")
        } catch (e: Exception) {
            RestoreResult(false, 0, "Erreur de restauration : ${e.message}")
        }
    }

    /**
     * Restaure la dernière sauvegarde depuis le Cloud Firestore
     */
    suspend fun restoreFromCloud(context: Context, mode: RestoreMode): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val doctorId = FirebaseSyncManager.getDoctorId(context)
            val db = FirebaseFirestore.getInstance()
            val doc = db.collection("backups").document(doctorId).get().await()

            if (!doc.exists()) {
                return@withContext RestoreResult(false, 0, "Aucune sauvegarde Cloud trouvée pour ce médecin.")
            }

            val jsonContent = doc.getString("patientsJson")
            if (jsonContent.isNullOrBlank()) {
                return@withContext RestoreResult(false, 0, "Sauvegarde Cloud vide ou corrompue.")
            }

            restoreFromJson(context, jsonContent, mode)
        } catch (e: Exception) {
            RestoreResult(false, 0, "Erreur Cloud : ${e.message}")
        }
    }
}
