package com.docpocket.smsrdv.firebase

import android.content.Context
import android.os.Build
import android.util.Log
import com.docpocket.smsrdv.data.local.AppointmentEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

object FirebaseSyncManager {

    private const val TAG = "FirebaseSyncManager"
    private const val COLLECTION_DEVICES = "devices"
    private const val COLLECTION_APPOINTMENTS = "rendezvous"

    fun cleanDoctorId(doctorName: String): String {
        val clean = doctorName.trim().lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return if (clean.isNotBlank()) clean else "doctor_default"
    }

    fun getDoctorId(context: Context): String {
        val prefs = context.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("doctor_id", "") ?: ""
        if (id.isBlank()) {
            val name = prefs.getString("doctor_name", "") ?: ""
            id = cleanDoctorId(name)
            prefs.edit().putString("doctor_id", id).apply()
        }
        return id
    }

    /**
     * Enregistre le Token FCM du smartphone dans Firestore sous la clé unique du médecin (devices/{doctorId}).
     * Cela isole hermétiquement chaque médecin : chaque praticien a son propre document de terminal.
     */
    fun saveDeviceToken(context: Context, token: String, isLicensed: Boolean = false, licenseKey: String = "") {
        try {
            val prefs = context.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
            val doctorName = prefs.getString("doctor_name", "Dr.") ?: "Dr."
            val doctorId = getDoctorId(context)
            val actualLicensed = isLicensed || prefs.getBoolean("is_licensed", false)
            val actualKey = if (licenseKey.isNotBlank()) licenseKey else (prefs.getString("license_key", "") ?: "")

            val deviceData = hashMapOf(
                "doctorId" to doctorId,
                "doctorName" to doctorName,
                "fcmToken" to token,
                "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "androidVersion" to Build.VERSION.RELEASE,
                "isLicensed" to actualLicensed,
                "licenseActive" to actualLicensed,
                "licenseKey" to actualKey,
                "licenseActivatedAt" to System.currentTimeMillis(),
                "lastUpdated" to System.currentTimeMillis()
            )

            val db = FirebaseFirestore.getInstance()
            // 1. Sauvegarde dans le document unique du médecin (isolation multi-médecins)
            db.collection(COLLECTION_DEVICES).document(doctorId)
                .set(deviceData, SetOptions.merge())
                .addOnSuccessListener {
                    Log.i(TAG, "Token FCM du Dr $doctorName ($doctorId) synchronisé avec succès sur Firestore !")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Échec de synchronisation du token FCM: ${e.message}")
                }

            // 2. Également document rétro-compatible si nécessaire
            db.collection(COLLECTION_DEVICES).document("doctor_device")
                .set(deviceData, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(TAG, "Erreur saveDeviceToken: ${e.message}", e)
        }
    }

    /**
     * Synchronise un rendez-vous vers Firestore avec étiquetage hermétique par médecin (doctorId).
     */
    suspend fun syncAppointmentToCloud(context: Context, appointment: AppointmentEntity, reminderEpochMs: Long) {
        try {
            val prefs = context.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("fcm_token", "") ?: ""
            val doctorName = prefs.getString("doctor_name", "Dr.") ?: "Dr."
            val doctorId = getDoctorId(context)

            val apptData = hashMapOf(
                "doctorId" to doctorId,
                "doctorName" to doctorName,
                "localId" to appointment.id,
                "nomPatient" to appointment.nomPatient,
                "telephone" to appointment.telephone,
                "dateRdv" to appointment.dateRdv,
                "heureRdv" to appointment.heureRdv,
                "texteSms" to appointment.texteSms,
                "language" to appointment.language,
                "smsEnvoye" to appointment.smsEnvoye,
                "reminderTimestamp" to reminderEpochMs,
                "targetFcmToken" to token,
                "updatedAt" to System.currentTimeMillis(),
                "source" to "android"
            )

            val docKey = appointment.firebaseKey?.takeIf { it.isNotBlank() } ?: "appt_${doctorId}_${appointment.id}"

            val db = FirebaseFirestore.getInstance()
            db.collection(COLLECTION_APPOINTMENTS).document(docKey)
                .set(apptData, SetOptions.merge())
                .await()

            Log.i(TAG, "Rendez-vous #${appointment.id} (Dr: $doctorId) synchronisé vers Firestore ($docKey)")
        } catch (e: Exception) {
            Log.w(TAG, "Erreur syncAppointmentToCloud: ${e.message}")
        }
    }

    /**
     * Met à jour le statut du SMS dans Firestore dès qu'il est envoyé.
     */
    fun markSmsSentInCloud(docKey: String) {
        try {
            if (docKey.isBlank()) return
            val db = FirebaseFirestore.getInstance()
            db.collection(COLLECTION_APPOINTMENTS).document(docKey)
                .update("smsEnvoye", true, "sentAt", System.currentTimeMillis())
                .addOnSuccessListener {
                    Log.i(TAG, "Statut SMS mis à jour dans le Cloud pour $docKey")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur markSmsSentInCloud: ${e.message}")
        }
    }
}
