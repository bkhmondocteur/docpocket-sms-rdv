package com.docpocket.smsrdv.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.docpocket.smsrdv.data.local.AppDatabase
import com.docpocket.smsrdv.network.RetrofitClient

class WifiSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.d("WifiSyncWorker", "Début de la synchronisation Wi-Fi en arrière-plan")

            val dao = AppDatabase.getDatabase(applicationContext).appointmentDao()
            val api = RetrofitClient.getApi(applicationContext) ?: return Result.success() // Pas d'IP configurée

            val response = api.getAppointments()
            if (response.isSuccessful && response.body() != null) {
                val appointmentsMap = response.body()!!
                
                for ((key, remoteAppt) in appointmentsMap) {
                    val existing = dao.getAppointmentByFirebaseKey(key)
                    if (existing == null) {
                        // Insert new
                        val newAppt = remoteAppt.copy(
                            id = 0,
                            firebaseKey = key,
                            synced = true
                        )
                        dao.insertAppointment(newAppt)
                        Log.d("WifiSyncWorker", "Nouveau RDV récupéré: $key")
                    } else {
                        // Update existing if needed, e.g., if we need to sync updates from PC
                        // For simplicity, we just keep the PC as source of truth for base data
                        // and Android updates smsEnvoye/emailEnvoye.
                    }
                }
            } else {
                Log.e("WifiSyncWorker", "Erreur API: ${response.code()}")
            }

            Log.d("WifiSyncWorker", "Fin de la synchronisation Wi-Fi en arrière-plan")
            Result.success()
        } catch (e: Exception) {
            Log.e("WifiSyncWorker", "Erreur de synchronisation Wi-Fi en arrière-plan", e)
            Result.retry()
        }
    }
}
