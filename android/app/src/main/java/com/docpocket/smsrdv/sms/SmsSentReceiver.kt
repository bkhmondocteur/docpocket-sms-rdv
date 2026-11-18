package com.docpocket.smsrdv.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import com.docpocket.smsrdv.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsSentReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SMS_SENT = "com.docpocket.smsrdv.SMS_SENT"
        private const val TAG = "SmsSentReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appointmentId = intent.getIntExtra("appointment_id", -1)
        if (appointmentId == -1) return

        val resultCode = resultCode
        Log.i(TAG, "Confirmation modem GSM reçue pour RDV #$appointmentId | Code résultat: $resultCode")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context.applicationContext).appointmentDao()
                if (resultCode == Activity.RESULT_OK) {
                    Log.i(TAG, "SUCCÈS RÉSEAU : SMS transmis à l'antenne opérateur pour RDV #$appointmentId")
                    dao.markSmsAsSent(appointmentId)
                } else {
                    val errorDetail = when (resultCode) {
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Échec générique (Erreur opérateur ou SIM)"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio éteinte (Mode avion ou réseau coupé)"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "PDU nul"
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "Aucun service / Hors couverture"
                        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Limite d'envoi SMS atteinte"
                        else -> "Code d'erreur réseau $resultCode"
                    }
                    Log.e(TAG, "ÉCHEC MODEM GSM pour RDV #$appointmentId : $errorDetail")
                    // Réinitialiser le statut dans la base pour permettre une nouvelle tentative
                    dao.resetSmsStatus(appointmentId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de la mise à jour après callback SMS: ${e.message}", e)
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Exception) {}
            }
        }
    }
}
