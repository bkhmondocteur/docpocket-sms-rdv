package com.docpocket.smsrdv.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.docpocket.smsrdv.data.local.AppDatabase
import com.docpocket.smsrdv.sms.SmsSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ReminderWorker"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Exécution du ReminderWorker de secours d'arrière-plan")

        return try {
            val database = AppDatabase.getDatabase(applicationContext)
            val dao = database.appointmentDao()
            val specificId = inputData.getInt("appointment_id", -1)

            val smsSender = SmsSender(applicationContext)

            if (specificId != -1) {
                // Envoi d'un RDV spécifique avec verrou atomique
                val claimed = dao.claimAppointmentForSending(specificId)
                if (claimed > 0) {
                    val appointment = dao.getAppointmentById(specificId)
                    if (appointment != null) {
                        smsSender.sendReminders(listOf(appointment)) { id ->
                            dao.markSmsAsSent(id)
                            appointment.firebaseKey?.let { key ->
                                com.docpocket.smsrdv.firebase.FirebaseSyncManager.markSmsSentInCloud(key)
                            }
                        }
                        Log.i(TAG, "SMS spécifique envoyé pour RDV #$specificId")
                    }
                } else {
                    Log.d(TAG, "RDV #$specificId déjà envoyé ou en cours. Ignoré par ReminderWorker.")
                }
            } else {
                // Scan global de secours pour tous les RDV arrivés à échéance (ex: 48h)
                val prefs = applicationContext.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
                val reminderHours = prefs.getInt("reminder_hours", 48).toLong()
                val now = System.currentTimeMillis()
                val unsent = dao.getUnsentAppointments()

                val dueAppointments = unsent.filter { appt ->
                    val dt = parseAppointmentDateTime(appt.dateRdv, appt.heureRdv)
                    if (dt == null) false
                    else {
                        val reminderTime = dt.time - (reminderHours * 3600 * 1000L)
                        now >= reminderTime && dt.time >= (now - 2 * 3600 * 1000L)
                    }
                }

                if (dueAppointments.isNotEmpty()) {
                    Log.i(TAG, "ReminderWorker: ${dueAppointments.size} RDV trouvés à vérifier")
                    for (appt in dueAppointments) {
                        val claimed = dao.claimAppointmentForSending(appt.id)
                        if (claimed > 0) {
                            smsSender.sendReminders(listOf(appt)) { id ->
                                dao.markSmsAsSent(id)
                                appt.firebaseKey?.let { key ->
                                    com.docpocket.smsrdv.firebase.FirebaseSyncManager.markSmsSentInCloud(key)
                                }
                                Log.i(TAG, "RDV #$id validé envoyé par ReminderWorker")
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "ReminderWorker: Aucun RDV en attente à cette heure.")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de l'exécution de ReminderWorker: ${e.message}", e)
            Result.retry()
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
}
