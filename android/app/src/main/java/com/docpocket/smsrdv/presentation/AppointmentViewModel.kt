package com.docpocket.smsrdv.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docpocket.smsrdv.data.local.AppDatabase
import com.docpocket.smsrdv.data.local.AppointmentEntity
import com.docpocket.smsrdv.sms.SmsSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AppointmentViewModel(application: Application) : AndroidViewModel(application) {

    private val appointmentDao = AppDatabase.getDatabase(application).appointmentDao()
    private val smsSender = SmsSender(application)
    private val prefs = application.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)

    private val _appointments = MutableStateFlow<List<AppointmentEntity>>(emptyList())
    val appointments: StateFlow<List<AppointmentEntity>> = _appointments.asStateFlow()

    private val _globalSmsTemplate = MutableStateFlow(
        prefs.getString("global_sms_template", "Bonjour [Nom], nous vous rappelons votre RDV le [Date] à [Heure]. En cas d'empêchement, veuillez nous contacter.") ?: ""
    )
    val globalSmsTemplate: StateFlow<String> = _globalSmsTemplate.asStateFlow()

    init {
        viewModelScope.launch {
            appointmentDao.getAllAppointments().collect { list ->
                _appointments.value = list
            }
        }
    }

    fun saveGlobalSmsTemplate(template: String) {
        prefs.edit().putString("global_sms_template", template).apply()
        _globalSmsTemplate.value = template
    }

    fun generateSmsText(nomPatient: String, dateRdv: String, heureRdv: String, language: String = "fr"): String {
        val templateKey = when (language) {
            "ar" -> "template_ar"
            "en" -> "template_en"
            else -> "template_fr"
        }
        
        val defaultTemplate = when(language) {
            "ar" -> "مرحباً {Nom du Patient}، تذكير بموعدك يوم {Date} الساعة {Heure} في عيادة الدكتور {Nom Du Médecin}."
            "en" -> "Hello {Nom du Patient}, Reminder of your appointment on {Date} at {Heure} at Doctor {Nom Du Médecin}'s clinic."
            else -> "Bonjour {Nom du Patient}, Rappel de votre RDV le {Date} à {Heure} au cabinet du Docteur {Nom Du Médecin}."
        }
        
        val template = prefs.getString(templateKey, "")?.takeIf { it.isNotBlank() } ?: defaultTemplate
        val doctorName = prefs.getString("doctor_name", "") ?: ""

        return template
            .replace("{Nom du Patient}", nomPatient, ignoreCase = true)
            .replace("[Nom]", nomPatient, ignoreCase = true)
            .replace("{Date}", dateRdv, ignoreCase = true)
            .replace("[Date]", dateRdv, ignoreCase = true)
            .replace("{Heure}", heureRdv, ignoreCase = true)
            .replace("[Heure]", heureRdv, ignoreCase = true)
            .replace("{Nom Du Médecin}", doctorName, ignoreCase = true)
    }

    fun addAppointment(nomPatient: String, telephone: String, email: String, dateRdv: String, heureRdv: String, texteSms: String, language: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var firebaseKey: String? = null
            
            val newAppointmentData = AppointmentEntity(
                nomPatient = nomPatient,
                telephone = telephone,
                email = email.takeIf { it.isNotBlank() },
                language = language,
                dateRdv = dateRdv,
                heureRdv = heureRdv,
                texteSms = texteSms,
                smsEnvoye = false
            )

            try {
                val api = com.docpocket.smsrdv.network.RetrofitClient.getApi(getApplication())
                if (api != null) {
                    val response = api.createAppointment(newAppointmentData)
                    if (response.isSuccessful) {
                        firebaseKey = response.body()?.id
                    }
                }
            } catch (e: Exception) {
                // Keep null if network fails
            }

            val finalAppt = newAppointmentData.copy(firebaseKey = firebaseKey, synced = firebaseKey != null)
            val newId = appointmentDao.insertAppointment(finalAppt).toInt()
            scheduleExactReminders(newId, dateRdv, heureRdv)
            com.docpocket.smsrdv.backup.BackupManager.performAutoBackup(getApplication())
        }
    }
    
    fun updateAppointment(id: Int, nomPatient: String, telephone: String, email: String, dateRdv: String, heureRdv: String, texteSms: String, language: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val existingAppointment = appointmentDao.getAppointmentById(id)
            val firebaseKey = existingAppointment?.firebaseKey

            val updatedAppointment = AppointmentEntity(
                id = id,
                nomPatient = nomPatient,
                telephone = telephone,
                email = email.takeIf { it.isNotBlank() },
                language = language,
                dateRdv = dateRdv,
                heureRdv = heureRdv,
                texteSms = texteSms,
                smsEnvoye = false, // Réinitialiser le statut
                firebaseKey = firebaseKey
            )
            
            var synced = false
            if (firebaseKey != null) {
                try {
                    val api = com.docpocket.smsrdv.network.RetrofitClient.getApi(getApplication())
                    if (api != null) {
                        val response = api.updateAppointment(firebaseKey, updatedAppointment)
                        if (response.isSuccessful) synced = true
                    }
                } catch (e: Exception) {
                    // Ignore network error
                }
            }

            appointmentDao.updateAppointment(updatedAppointment.copy(synced = synced))
            scheduleExactReminders(id, dateRdv, heureRdv)
            com.docpocket.smsrdv.backup.BackupManager.performAutoBackup(getApplication())
        }
    }

    fun deleteAppointment(id: Int) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val existingAppointment = appointmentDao.getAppointmentById(id)
            if (existingAppointment?.firebaseKey != null) {
                try {
                    val api = com.docpocket.smsrdv.network.RetrofitClient.getApi(getApplication())
                    api?.deleteAppointment(existingAppointment.firebaseKey)
                } catch(e: Exception) {}
            }

            appointmentDao.deleteAppointment(id)
            val context = getApplication<Application>()
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            
            val intent = android.content.Intent(context, com.docpocket.smsrdv.worker.SmsAlarmReceiver::class.java).apply {
                putExtra("appointment_id", id)
            }
            
            val pendingIntent48h = android.app.PendingIntent.getBroadcast(
                context, id * 100 + 48, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent48h)

            val oldIntent1 = android.app.PendingIntent.getBroadcast(
                context, id * 10 + 1, intent,
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            oldIntent1?.let { alarmManager.cancel(it) }

            val oldIntent2 = android.app.PendingIntent.getBroadcast(
                context, id * 10 + 2, intent,
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            oldIntent2?.let { alarmManager.cancel(it) }

            com.docpocket.smsrdv.backup.BackupManager.performAutoBackup(getApplication())
        }
    }

    private fun parseAppointmentDateTime(dateStr: String, timeStr: String): java.util.Date? {
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
            } catch (e: Exception) {}
        }
        return null
    }

    private fun scheduleExactReminders(id: Int, dateRdv: String, heureRdv: String) {
        val context = getApplication<Application>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val reminderHours = prefs.getInt("reminder_hours", 48).toLong()
        
        val intent = android.content.Intent(context, com.docpocket.smsrdv.worker.SmsAlarmReceiver::class.java).apply {
            putExtra("appointment_id", id)
        }

        val pendingIntent48h = android.app.PendingIntent.getBroadcast(
            context, id, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        )

        alarmManager.cancel(pendingIntent48h)

        val oldIntent1 = android.app.PendingIntent.getBroadcast(
            context, id * 10 + 1, intent,
            android.app.PendingIntent.FLAG_NO_CREATE or (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        )
        oldIntent1?.let { alarmManager.cancel(it) }

        val oldIntent2 = android.app.PendingIntent.getBroadcast(
            context, id * 10 + 2, intent,
            android.app.PendingIntent.FLAG_NO_CREATE or (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        )
        oldIntent2?.let { alarmManager.cancel(it) }

        try {
            val appointmentDate = parseAppointmentDateTime(dateRdv, heureRdv) ?: return
            
            val currentTime = System.currentTimeMillis()
            val appointmentTime = appointmentDate.time

            if (appointmentTime < (currentTime - 2 * 3600 * 1000L)) {
                // Rendez-vous expiré depuis plus de 2 heures
                return
            }

            // Calcul de l'heure exacte de déclenchement du rappel
            val timeReminder = appointmentTime - (reminderHours * 60 * 60 * 1000L)
            
            // Logique de ciblage infaillible :
            // 1. Si le rappel à H-48 (ou délai configuré) est dans le futur : on programme à cette heure précise.
            // 2. Si le rappel théorique est dépassé mais que le RDV est dans le futur (ex: test dans 2 min ou RDV pris pour demain) :
            //    on programme le réveil matériel exactement à l'heure du RDV (ou dans les 2 minutes prévues pour le test).
            // 3. Si le RDV est déjà dépassé : déclenchement immédiat de rattrapage (+3s).
            val targetAlarmTime = if (timeReminder > currentTime) {
                timeReminder
            } else if (appointmentTime > currentTime) {
                appointmentTime
            } else {
                currentTime + 3000L
            }

            val showIntent = android.app.PendingIntent.getActivity(
                context, 0,
                android.content.Intent(context, com.docpocket.smsrdv.MainActivity::class.java),
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )

            try {
                val alarmClockInfo = android.app.AlarmManager.AlarmClockInfo(targetAlarmTime, showIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent48h)
                android.util.Log.i("AppointmentViewModel", "setAlarmClock armé pour RDV #$id le ${java.util.Date(targetAlarmTime)}")
                val timeFormatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(targetAlarmTime))
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        android.widget.Toast.makeText(context, "⏰ Réveil programmé pour $timeFormatted. Vous pouvez verrouiller l'écran.", android.widget.Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, targetAlarmTime, pendingIntent48h)
                    } else {
                        alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, targetAlarmTime, pendingIntent48h)
                    }
                } catch (secEx: SecurityException) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, targetAlarmTime, pendingIntent48h)
                    }
                }
            }

            // Synchronisation Cloud Firebase pour réveil FCM distant autonome
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val savedAppt = appointmentDao.getAppointmentById(id)
                    if (savedAppt != null) {
                        com.docpocket.smsrdv.firebase.FirebaseSyncManager.syncAppointmentToCloud(context, savedAppt, targetAlarmTime)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AppointmentViewModel", "Erreur sync Cloud: ${e.message}")
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun forceSendRemindersForNext48h() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val reminderHours = prefs.getInt("reminder_hours", 48).toLong()
            val windowEnd = now + (reminderHours * 60 * 60 * 1000L)

            try {
                val unsent = appointmentDao.getUnsentAppointments()
                val appointmentsToSend = unsent.filter { appt ->
                    val dt = parseAppointmentDateTime(appt.dateRdv, appt.heureRdv)
                    dt != null && dt.time in (now - 600_000L)..windowEnd
                }

                val toSendClaimed = mutableListOf<AppointmentEntity>()
                for (appt in appointmentsToSend) {
                    val claimed = appointmentDao.claimAppointmentForSending(appt.id)
                    if (claimed > 0) {
                        toSendClaimed.add(appt)
                    }
                }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (toSendClaimed.isNotEmpty()) {
                        android.widget.Toast.makeText(getApplication(), "Préparation de l'envoi de ${toSendClaimed.size} SMS...", android.widget.Toast.LENGTH_SHORT).show()
                        smsSender.sendReminders(toSendClaimed) { id ->
                            appointmentDao.markSmsAsSent(id)
                        }
                    } else {
                        android.widget.Toast.makeText(getApplication(), "Aucun RDV en attente dans les prochaines ${reminderHours}h.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch(e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "Erreur: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun sendSmsNow(appointment: AppointmentEntity) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "Envoi immédiat du SMS à ${appointment.nomPatient}...", android.widget.Toast.LENGTH_SHORT).show()
                }
                val toSend = appointment.copy(smsEnvoye = false)
                smsSender.sendReminders(listOf(toSend)) { id ->
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        appointmentDao.markSmsAsSent(id)
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "Erreur envoi SMS: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun forceSync() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val api = com.docpocket.smsrdv.network.RetrofitClient.getApi(getApplication())
                if (api != null) {
                    val response = api.getAppointments()
                    if (response.isSuccessful && response.body() != null) {
                        for ((key, remoteAppt) in response.body()!!) {
                            val existing = appointmentDao.getAppointmentByFirebaseKey(key)

                            if (existing != null) {
                                val updatedAppointment = remoteAppt.copy(
                                    id = existing.id,
                                    firebaseKey = key,
                                    synced = true
                                )
                                appointmentDao.updateAppointment(updatedAppointment)
                                scheduleExactReminders(updatedAppointment.id, remoteAppt.dateRdv, remoteAppt.heureRdv)
                            } else {
                                val newAppointment = remoteAppt.copy(
                                    id = 0,
                                    firebaseKey = key,
                                    synced = true
                                )
                                
                                val id = appointmentDao.insertAppointment(newAppointment).toInt()
                                scheduleExactReminders(id, remoteAppt.dateRdv, remoteAppt.heureRdv)
                            }
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(getApplication(), "Synchronisation Wi-Fi terminée", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(getApplication(), "IP non configurée", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun performManualBackup(onResult: (Result<String>) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val res = com.docpocket.smsrdv.backup.BackupManager.performManualBackup(getApplication())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onResult(res)
            }
        }
    }

    fun restoreBackup(item: com.docpocket.smsrdv.backup.BackupItem, mode: com.docpocket.smsrdv.backup.RestoreMode, onResult: (com.docpocket.smsrdv.backup.RestoreResult) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val content = com.docpocket.smsrdv.backup.BackupManager.readBackupContent(getApplication(), item)
            val res = if (!content.isNullOrBlank()) {
                com.docpocket.smsrdv.backup.BackupManager.restoreFromJson(getApplication(), content, mode)
            } else {
                com.docpocket.smsrdv.backup.RestoreResult(false, 0, "Impossible de lire le fichier de sauvegarde.")
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onResult(res)
            }
        }
    }

    fun restoreFromCloud(mode: com.docpocket.smsrdv.backup.RestoreMode, onResult: (com.docpocket.smsrdv.backup.RestoreResult) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val res = com.docpocket.smsrdv.backup.BackupManager.restoreFromCloud(getApplication(), mode)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onResult(res)
            }
        }
    }

    fun restoreFromJsonContent(content: String, mode: com.docpocket.smsrdv.backup.RestoreMode, onResult: (com.docpocket.smsrdv.backup.RestoreResult) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val res = com.docpocket.smsrdv.backup.BackupManager.restoreFromJson(getApplication(), content, mode)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onResult(res)
            }
        }
    }
}
