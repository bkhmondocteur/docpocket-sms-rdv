package com.docpocket.smsrdv.firebase

import android.content.Context
import android.os.Build
import android.util.Log
import com.docpocket.smsrdv.data.local.AppointmentEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

object FirebaseSyncManager {

    private const val TAG = "FirebaseSyncManager"
    private const val COLLECTION_DEVICES = "devices"
    private const val COLLECTION_APPOINTMENTS = "rendezvous"

    fun cleanDoctorId(doctorName: String): String {
        val withoutPrefix = doctorName.trim()
            .replace(Regex("^(?i)(docteur|dr\\.?)\\s+"), "")
            .trim()
        val clean = withoutPrefix.lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return if (clean.isNotBlank()) clean else "doctor_default"
    }

    fun getDoctorId(context: Context): String {
        val prefs = context.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("doctor_name", "") ?: ""
        val computed = if (name.isNotBlank()) cleanDoctorId(name) else ""
        val saved = prefs.getString("doctor_id", "") ?: ""
        if (computed.isNotBlank() && computed != saved) {
            prefs.edit().putString("doctor_id", computed).apply()
            return computed
        }
        if (saved.isNotBlank()) return saved
        return computed.ifBlank { "doctor_default" }
    }

    /**
     * Enregistre le Token FCM du smartphone dans Firestore sous la clé unique du médecin (devices/{doctorId}).
     * Ne crée jamais de profil par défaut si le médecin n'est pas activé, et n'écrase jamais une révocation Cloud.
     */
    fun saveDeviceToken(
        context: Context,
        token: String,
        isLicensed: Boolean = false,
        licenseKey: String = "",
        isNewActivation: Boolean = false
    ) {
        try {
            val prefs = context.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
            val rawDoctorName = (prefs.getString("doctor_name", "") ?: "").trim()
            val actualLicensed = isLicensed || prefs.getBoolean("is_licensed", false)

            // Ne jamais polluer Firestore avec un profil vide "doctor_default" avant activation
            if (!isNewActivation && (!actualLicensed || rawDoctorName.isBlank())) {
                return
            }

            val cleanDisplayName = rawDoctorName
                .replace(Regex("^(?i)(docteur|dr\\.?)\\s+"), "")
                .trim()
                .ifBlank { rawDoctorName }
            val doctorId = getDoctorId(context)
            val actualKey = if (licenseKey.isNotBlank()) licenseKey else (prefs.getString("license_key", "") ?: "")

            val deviceData = hashMapOf<String, Any>(
                "doctorId" to doctorId,
                "doctorName" to cleanDisplayName,
                "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "androidVersion" to Build.VERSION.RELEASE,
                "lastUpdated" to System.currentTimeMillis()
            )
            if (token.isNotBlank()) {
                deviceData["fcmToken"] = token
            }
            if (actualKey.isNotBlank()) {
                deviceData["licenseKey"] = actualKey
            }
            // Seule une nouvelle activation explicite fixe le statut de licence ; un simple refresh token préserve une révocation distante.
            if (isNewActivation) {
                deviceData["isLicensed"] = true
                deviceData["licenseActive"] = true
                deviceData["revokedAt"] = 0L
                deviceData["licenseActivatedAt"] = System.currentTimeMillis()
            }

            val db = FirebaseFirestore.getInstance()
            db.collection(COLLECTION_DEVICES).document(doctorId)
                .set(deviceData, SetOptions.merge())
                .addOnSuccessListener {
                    Log.i(TAG, "Profil FCM Dr $cleanDisplayName ($doctorId) synchronisé avec Firestore !")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Échec synchronisation profil FCM: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur saveDeviceToken: ${e.message}", e)
        }
    }

    /**
     * Écoute en temps réel le statut de licence dans Firestore pour bloquer immédiatement une licence révoquée.
     */
    fun listenToLicenseStatus(context: Context, onRevoked: () -> Unit): ListenerRegistration? {
        return try {
            val prefs = context.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("is_licensed", false)) return null
            val doctorId = getDoctorId(context)
            if (doctorId.isBlank() || doctorId == "doctor_default") return null

            FirebaseFirestore.getInstance()
                .collection(COLLECTION_DEVICES)
                .document(doctorId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    val isLicensedField = snapshot.getBoolean("isLicensed")
                    val licenseActiveField = snapshot.getBoolean("licenseActive")
                    if (isLicensedField == false || licenseActiveField == false) {
                        Log.w(TAG, "Licence révoquée détectée sur Firestore pour $doctorId !")
                        prefs.edit().putBoolean("is_licensed", false).apply()
                        onRevoked()
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur listenToLicenseStatus: ${e.message}")
            null
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
