package com.docpocket.smsrdv.worker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.docpocket.smsrdv.MainActivity
import com.docpocket.smsrdv.data.local.AppDatabase
import com.docpocket.smsrdv.sms.SmsSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SyncService : Service() {

    companion object {
        private const val TAG = "SyncService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "docpocket_sms_reminders_channel"
    }

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DocPocket:SyncServiceWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock acquis avec succès pour maintenir le service actif écran éteint")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur acquisition WakeLock: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors du démarrage du Foreground Service: ${e.message}", e)
        }

        rescheduleAllPendingReminders()
        startLocalReminderLoop()
        startNetworkSyncLoop()

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Application fermée du multitâche. Protection et redémarrage automatique du service...")
        try {
            val restartIntent = Intent(applicationContext, SyncService::class.java)
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    applicationContext,
                    999,
                    restartIntent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )
            } else {
                PendingIntent.getService(
                    applicationContext,
                    999,
                    restartIntent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )
            }
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000L,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erreur onTaskRemoved: ${e.message}")
        }
    }

    private fun rescheduleAllPendingReminders() {
        scope.launch {
            try {
                val dao = AppDatabase.getDatabase(applicationContext).appointmentDao()
                val unsent = dao.getUnsentAppointments()
                Log.d(TAG, "Reprogrammation des alarmes pour ${unsent.size} RDV non envoyés")
                for (appt in unsent) {
                    scheduleExactReminders(appt.id, appt.dateRdv, appt.heureRdv)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de la reprogrammation des alarmes: ${e.message}", e)
            }
        }
    }

    /**
     * Boucle locale ultra-réactive pour les rappels SMS :
     * Totalement découplée du réseau pour éviter tout blocage ou retard de transmission.
     */
    private fun startLocalReminderLoop() {
        scope.launch {
            while (true) {
                try {
                    checkAndSendLocalDueReminders()
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur boucle locale SMS: ${e.message}")
                }
                delay(10000L) // Scan local toutes les 10 secondes
            }
        }
    }

    /**
     * Boucle réseau indépendante pour la synchronisation PC Wi-Fi
     */
    private fun startNetworkSyncLoop() {
        scope.launch {
            while (true) {
                delay(30000L)
                try {
                    val api = com.docpocket.smsrdv.network.RetrofitClient.getApi(applicationContext)
                    val dao = AppDatabase.getDatabase(applicationContext).appointmentDao()
                    if (api != null) {
                        val response = api.getAppointments()
                        if (response.isSuccessful && response.body() != null) {
                            val appointmentsMap = response.body()!!
                            for ((key, remoteAppt) in appointmentsMap) {
                                val existing = dao.getAppointmentByFirebaseKey(key)
                                if (existing == null) {
                                    val newAppt = remoteAppt.copy(id = 0, firebaseKey = key, synced = true)
                                    val id = dao.insertAppointment(newAppt).toInt()
                                    scheduleExactReminders(id, remoteAppt.dateRdv, remoteAppt.heureRdv)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur sync PC Wi-Fi: ${e.message}")
                }
            }
        }
    }

    private suspend fun checkAndSendLocalDueReminders() {
        try {
            val dao = AppDatabase.getDatabase(applicationContext).appointmentDao()
            val unsent = dao.getUnsentAppointments()
            if (unsent.isEmpty()) return

            val now = System.currentTimeMillis()
            val prefs = getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
            val reminderHours = prefs.getInt("reminder_hours", 48).toLong()

            val due = unsent.filter { appt ->
                val dt = parseAppointmentDateTime(appt.dateRdv, appt.heureRdv)
                if (dt == null) false
                else {
                    val appointmentTime = dt.time
                    val reminderTime = appointmentTime - (reminderHours * 3600 * 1000L)
                    val targetReminderTime = if (reminderTime > (now - 60_000L)) reminderTime else appointmentTime
                    // Si l'heure cible est atteinte ET que le RDV n'est pas périmé depuis plus de 2 heures
                    now >= targetReminderTime && appointmentTime >= (now - 2 * 3600 * 1000L)
                }
            }

            for (appt in due) {
                // VERROU ATOMIQUE SQLITE : seul ce thread peut l'envoyer s'il obtient le claim
                val claimed = dao.claimAppointmentForSending(appt.id)
                if (claimed > 0) {
                    Log.i(TAG, "SyncService: RDV #${appt.id} réclamé avec succès pour envoi exclusif")
                    val smsSender = SmsSender(applicationContext)
                    smsSender.sendReminders(listOf(appt)) { id ->
                        dao.markSmsAsSent(id)
                        Log.i(TAG, "SyncService: RDV #$id marqué comme envoyé en base locale !")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur vérification SMS locaux: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Service de Rappels SMS DocPocket",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintient l'envoi fiable des rappels SMS de rendez-vous en arrière-plan"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DocPocket SMS RDV")
            .setContentText("DocPocket SMS RDV : Service de rappels actif")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
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

    private fun scheduleExactReminders(id: Int, dateRdv: String, heureRdv: String) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
        val reminderHours = prefs.getInt("reminder_hours", 48).toLong()

        val intent = Intent(this, SmsAlarmReceiver::class.java).apply {
            putExtra("appointment_id", id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        alarmManager.cancel(pendingIntent)

        try {
            val appointmentDate = parseAppointmentDateTime(dateRdv, heureRdv) ?: return
            val currentTime = System.currentTimeMillis()
            val appointmentTime = appointmentDate.time

            if (appointmentTime < (currentTime - 2 * 3600 * 1000L)) {
                return
            }

            val timeReminder = appointmentTime - (reminderHours * 60 * 60 * 1000L)
            val targetAlarmTime = if (timeReminder > currentTime) {
                timeReminder
            } else if (appointmentTime > currentTime) {
                appointmentTime
            } else {
                currentTime + 3000L
            }

            val showIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            try {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(targetAlarmTime, showIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                Log.d(TAG, "setAlarmClock programmé pour RDV #$id le ${Date(targetAlarmTime)}")
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAlarmTime, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, targetAlarmTime, pendingIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur programmation alarme: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        job.cancel()
    }
}
