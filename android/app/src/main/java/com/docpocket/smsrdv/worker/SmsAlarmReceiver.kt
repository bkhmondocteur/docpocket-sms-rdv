package com.docpocket.smsrdv.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.docpocket.smsrdv.data.local.AppDatabase
import com.docpocket.smsrdv.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appointmentId = intent.getIntExtra("appointment_id", -1)
        if (appointmentId == -1) {
            Log.e(TAG, "ID de rendez-vous manquant dans l'intent d'alarme")
            return
        }

        Log.i(TAG, "Alarme exacte RTC_WAKEUP déclenchée pour le RDV #$appointmentId")

        // 1. Réveil matériel immédiat du CPU (Crucial pour Android 14/15 et surcouches type XOS/MIUI)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DocPocket:SmsAlarmReceiverWakeLock"
        )
        wakeLock?.acquire(120_000L) // 2 minutes de réveil garanti

        // 2. Prolonger le cycle de vie du BroadcastReceiver
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getDatabase(context.applicationContext)
                val dao = database.appointmentDao()

                // Verrou atomique SQLite pour interdire tout doublon
                val claimed = dao.claimAppointmentForSending(appointmentId)
                if (claimed > 0) {
                    val appointment = dao.getAppointmentById(appointmentId)
                    if (appointment != null) {
                        Log.i(TAG, "Envoi exclusif du rappel pour le patient: ${appointment.nomPatient}")
                        val smsSender = SmsSender(context.applicationContext)
                        smsSender.sendReminders(listOf(appointment)) { id ->
                            dao.markSmsAsSent(id)
                            appointment.firebaseKey?.let { key ->
                                com.docpocket.smsrdv.firebase.FirebaseSyncManager.markSmsSentInCloud(key)
                            }
                            Log.i(TAG, "Rendez-vous #$id validé envoyé dans la base")
                        }
                    } else {
                        Log.w(TAG, "RDV #$appointmentId introuvable dans la base locale.")
                    }
                } else {
                    Log.d(TAG, "RDV #$appointmentId déjà réclamé ou expédié par un autre processus. Doublon évité avec succès !")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception lors du traitement de l'alarme pour RDV #$appointmentId: ${e.message}", e)
                try {
                    val database = AppDatabase.getDatabase(context.applicationContext)
                    database.appointmentDao().resetSmsStatus(appointmentId)
                } catch (_: Exception) {}
            } finally {
                // Toujours libérer le WakeLock et terminer le BroadcastReceiver
                if (wakeLock?.isHeld == true) {
                    try {
                        wakeLock.release()
                    } catch (_: Exception) {}
                }
                try {
                    pendingResult.finish()
                } catch (_: Exception) {}
            }
        }
    }
}
