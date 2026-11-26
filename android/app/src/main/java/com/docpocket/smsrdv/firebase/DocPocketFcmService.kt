package com.docpocket.smsrdv.firebase

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.docpocket.smsrdv.data.local.AppDatabase
import com.docpocket.smsrdv.data.local.AppointmentEntity
import com.docpocket.smsrdv.sms.SmsSender
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocPocketFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "Nouveau Token FCM généré: $token")
        
        val prefs = applicationContext.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()

        // Synchronisation automatique vers Firestore
        FirebaseSyncManager.saveDeviceToken(applicationContext, token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i(TAG, "Push FCM reçu ! De: ${remoteMessage.from}, ID: ${remoteMessage.messageId}")

        val data = remoteMessage.data
        if (data.isEmpty()) {
            Log.w(TAG, "Message FCM reçu sans données utiles (data payload vide)")
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DocPocket:FcmWakeLock"
        )
        wakeLock?.acquire(45_000L) // 45 secondes de WakeLock pour réveil complet

        try {
            val action = data["action"] ?: "SEND_SMS"
            val phone = data["telephone"] ?: data["phone"] ?: ""
            val patientName = data["nomPatient"] ?: data["patientName"] ?: "Patient"
            val message = data["message"] ?: data["texteSms"] ?: ""
            val dateRdv = data["dateRdv"] ?: ""
            val heureRdv = data["heureRdv"] ?: ""
            val language = data["language"] ?: "fr"
            val cloudKey = data["firebaseKey"] ?: remoteMessage.messageId ?: ""
            val apptId = (data["appointmentId"] ?: data["id"])?.toIntOrNull() ?: 0
            val targetDoctorId = data["doctorId"] ?: ""

            // Isolation hermétique multi-médecins :
            val prefs = applicationContext.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
            val myDoctorId = FirebaseSyncManager.getDoctorId(applicationContext)

            if (targetDoctorId.isNotBlank() && myDoctorId.isNotBlank() && targetDoctorId != myDoctorId) {
                Log.w(TAG, "Push ignoré : destiné au Dr '$targetDoctorId' alors que cet appareil est configuré pour le Dr '$myDoctorId'")
                return
            }

            Log.i(TAG, "Ordre FCM reçu: action=$action, patient=$patientName, tel=$phone, id=$apptId, doctorId=$myDoctorId")

            if (phone.isBlank()) {
                Log.w(TAG, "Numéro de téléphone absent dans le payload FCM.")
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val dao = db.appointmentDao()

                    val currentTime = System.currentTimeMillis()
                    val apptDateTime = parseAppointmentDateTime(dateRdv, heureRdv)

                    // 1. PROTECTION STRICTE ANTI-RETARD : Si le RDV est déjà dépassé dans le temps, rejet catégorique
                    if (apptDateTime != null && apptDateTime.time < (currentTime - 2 * 3600 * 1000L)) {
                        Log.w(TAG, "Push FCM rejeté : Le RDV de '$patientName' ($dateRdv à $heureRdv) est dépassé dans le passé. Aucun SMS ne sera renvoyé.")
                        if (cloudKey.isNotBlank()) {
                            FirebaseSyncManager.markSmsSentInCloud(cloudKey)
                        }
                        return@launch
                    }

                    // 2. Recherche anti-doublon multicritère (ID local, clé Cloud, ou Téléphone + Date)
                    var existing: AppointmentEntity? = null
                    if (apptId > 0) {
                        existing = dao.getAppointmentById(apptId)
                    }
                    if (existing == null && cloudKey.isNotBlank()) {
                        existing = dao.getAppointmentByFirebaseKey(cloudKey)
                    }
                    if (existing == null && phone.isNotBlank() && dateRdv.isNotBlank()) {
                        existing = dao.getAppointmentByPhoneAndDate(phone, dateRdv)
                    }
                    if (existing == null && patientName.isNotBlank() && dateRdv.isNotBlank()) {
                        existing = dao.getAppointmentByNameAndDate(patientName, dateRdv)
                    }

                    // 3. Vérification du statut déjà envoyé
                    if (existing != null) {
                        if (existing.smsEnvoye) {
                            Log.i(TAG, "RDV #${existing.id} ($patientName - $dateRdv) déjà marqué envoyé en base locale. Doublon annulé.")
                            if (cloudKey.isNotBlank()) {
                                FirebaseSyncManager.markSmsSentInCloud(cloudKey)
                            }
                            return@launch
                        }

                        // Verrou atomique SQLite
                        val claimed = dao.claimAppointmentForSending(existing.id)
                        if (claimed == 0) {
                            Log.i(TAG, "RDV #${existing.id} déjà verrouillé par un autre processus.")
                            return@launch
                        }
                    } else if (apptDateTime != null && apptDateTime.time < currentTime) {
                        // S'il n'existe pas en base locale et que l'heure du RDV est déjà dépassée, interdire la création
                        Log.w(TAG, "RDV inconnu et déjà passé ($dateRdv $heureRdv). Ignoré sans création.")
                        if (cloudKey.isNotBlank()) {
                            FirebaseSyncManager.markSmsSentInCloud(cloudKey)
                        }
                        return@launch
                    }

                    // 4. Préparer l'entité à expédier
                    val apptToSend = existing ?: run {
                        val newEntity = AppointmentEntity(
                            id = 0,
                            nomPatient = patientName,
                            telephone = phone,
                            dateRdv = dateRdv,
                            heureRdv = heureRdv,
                            texteSms = message,
                            smsEnvoye = true, // Marqué envoyé d'emblée pour verrou
                            language = language,
                            firebaseKey = cloudKey,
                            synced = true
                        )
                        val insertedId = dao.insertAppointment(newEntity).toInt()
                        newEntity.copy(id = insertedId)
                    }

                    // 5. Déclenchement de l'envoi physique par la carte SIM
                    val smsSender = SmsSender(applicationContext)
                    smsSender.sendReminders(listOf(apptToSend.copy(smsEnvoye = false))) { sentId ->
                        dao.markSmsAsSent(sentId)
                        if (cloudKey.isNotBlank()) {
                            FirebaseSyncManager.markSmsSentInCloud(cloudKey)
                        }
                    }

                    Log.i(TAG, "Envoi SMS via carte SIM orchestré avec succès pour $patientName ($phone)")
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur lors du traitement FCM SMS: ${e.message}", e)
                }
            }
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    private fun parseAppointmentDateTime(dateStr: String, timeStr: String): Date? {
        val cleanDate = dateStr.trim()
        val cleanTime = timeStr.trim().ifEmpty { "09:00" }
        val formats = listOf(
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
            SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()),
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        )
        for (f in formats) {
            try {
                val d = f.parse("$cleanDate $cleanTime")
                if (d != null) return d
            } catch (_: Exception) {}
        }
        return null
    }

    companion object {
        private const val TAG = "DocPocketFcmService"
    }
}
