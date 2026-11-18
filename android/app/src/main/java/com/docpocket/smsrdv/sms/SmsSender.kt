package com.docpocket.smsrdv.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.docpocket.smsrdv.data.local.AppointmentEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsSender(private val context: Context) {

    companion object {
        private const val TAG = "SmsSender"
    }

    private fun getSafeSmsManager(): SmsManager {
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()

        val targetSubId = if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            defaultSubId
        } else {
            try {
                subscriptionManager?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
                    ?: SubscriptionManager.getDefaultSubscriptionId()
            } catch (_: Exception) {
                SubscriptionManager.getDefaultSubscriptionId()
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (targetSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(targetSubId)
                        ?: context.getSystemService(SmsManager::class.java)
                        ?: SmsManager.getDefault()
                } else {
                    context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erreur createForSubscriptionId: ${e.message}, fallback sur SmsManager.getDefault()")
                SmsManager.getDefault()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (targetSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SmsManager.getSmsManagerForSubscriptionId(targetSubId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    @Suppress("DEPRECATION")
    suspend fun sendReminders(appointments: List<AppointmentEntity>, onSmsSent: suspend (Int) -> Unit) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DocPocket:SmsSenderWakeLock"
        )
        try {
            wakeLock?.acquire(180_000L) // 3 minutes de WakeLock garanti

            val smsManager = getSafeSmsManager()

            for (appointment in appointments) {
                // Protection stricte anti-doublon en mémoire vive
                if (!SmsLock.tryClaim(appointment.id)) {
                    Log.d(TAG, "RDV #${appointment.id} déjà en cours d'envoi par un autre processus. Ignoré.")
                    continue
                }

                try {
                    val prefs = context.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
                val doctorName = prefs.getString("doctor_name", "") ?: ""

                val message = if (appointment.texteSms.isNotBlank()) {
                    appointment.texteSms
                } else {
                    val templateKey = when (appointment.language) {
                        "ar" -> "template_ar"
                        "en" -> "template_en"
                        else -> "template_fr"
                    }

                    val defaultTemplate = when (appointment.language) {
                        "ar" -> "\u200Fمرحباً {Nom du Patient} هذا تذكير بموعدك يوم {Date} الساعة {Heure} في عيادة الدكتور {Nom Du Médecin}. نهارك سعيد."
                        "en" -> "Hello {Nom du Patient} this is a reminder of your appointment on {Date} at {Heure} at Doctor {Nom Du Médecin}'s clinic. Have a good day."
                        else -> "Bonjour {Nom du Patient} ce ci est un rappel de votre rdv le {Date} à {Heure} au cabinet du Docteur {Nom Du Médecin}. bonne journée."
                    }

                    val template = prefs.getString(templateKey, "")?.takeIf { it.isNotBlank() } ?: defaultTemplate

                    template
                        .replace("{Nom du Patient}", appointment.nomPatient, ignoreCase = true)
                        .replace("[Nom]", appointment.nomPatient, ignoreCase = true)
                        .replace("{Date}", appointment.dateRdv, ignoreCase = true)
                        .replace("[Date]", appointment.dateRdv, ignoreCase = true)
                        .replace("{Heure}", appointment.heureRdv, ignoreCase = true)
                        .replace("[Heure]", appointment.heureRdv, ignoreCase = true)
                        .replace("{Nom Du Médecin}", doctorName, ignoreCase = true)
                }

                var emailSuccess = false

                // 1. Envoi Email si présent (avec timeout pour ne jamais bloquer l'envoi SMS)
                if (!appointment.email.isNullOrBlank()) {
                    val emailSender = com.docpocket.smsrdv.email.EmailSender(context)
                    try {
                        withTimeoutOrNull(15000L) {
                            emailSuccess = emailSender.sendEmail(
                                toEmail = appointment.email,
                                subject = "Rappel de Rendez-vous",
                                body = message
                            )
                        } ?: run {
                            Log.w(TAG, "Timeout lors de l'envoi email pour ${appointment.nomPatient}")
                        }

                        if (emailSuccess) {
                            showToast("Email envoyé à ${appointment.nomPatient}")
                            if (appointment.firebaseKey != null) {
                                try {
                                    val api = com.docpocket.smsrdv.network.RetrofitClient.getApi(context)
                                    api?.updateAppointment(appointment.firebaseKey, appointment.copy(emailEnvoye = true))
                                } catch (e: Exception) {
                                    Log.e(TAG, "Erreur maj emailEnvoye API", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erreur envoi email: ${e.message}", e)
                    }
                }

                // 2. Envoi SMS direct via SmsManager avec confirmation réseau
                if (appointment.telephone.isNotBlank()) {
                    val cleanPhone = appointment.telephone.replace(Regex("[^0-9+]"), "")
                    if (cleanPhone.length >= 6) {
                        try {
                            val sentIntent = PendingIntent.getBroadcast(
                                context,
                                appointment.id,
                                Intent(context, SmsSentReceiver::class.java).apply {
                                    action = SmsSentReceiver.ACTION_SMS_SENT
                                    putExtra("appointment_id", appointment.id)
                                },
                                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                            )

                            val parts = smsManager.divideMessage(message)
                            if (parts.size > 1) {
                                val sentIntents = ArrayList<PendingIntent>()
                                for (i in parts.indices) {
                                    sentIntents.add(sentIntent)
                                }
                                smsManager.sendMultipartTextMessage(cleanPhone, null, parts, sentIntents, null)
                                Log.i(TAG, "SMS Multipart transmis (${parts.size} parties) à $cleanPhone pour ${appointment.nomPatient}")
                            } else {
                                smsManager.sendTextMessage(cleanPhone, null, message, sentIntent, null)
                                Log.i(TAG, "SMS Simple transmis à $cleanPhone pour ${appointment.nomPatient}")
                            }

                            onSmsSent(appointment.id)
                            showToast("SMS transmis à l'antenne pour ${appointment.nomPatient}")
                            notifySmsSent(appointment.nomPatient, cleanPhone)

                            // Mise à jour API distante si applicable
                            if (appointment.firebaseKey != null) {
                                try {
                                    val api = com.docpocket.smsrdv.network.RetrofitClient.getApi(context)
                                    api?.updateAppointment(appointment.firebaseKey, appointment.copy(smsEnvoye = true))
                                } catch (e: Exception) {
                                    Log.e(TAG, "Erreur maj Firebase API: ${e.message}")
                                }
                            }

                            delay(2500L) // Délai anti-congestion pour l'opérateur
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception critique lors de l'envoi SMS à $cleanPhone: ${e.message}", e)
                            showToast("Erreur SMS: ${e.localizedMessage}")
                            try {
                                com.docpocket.smsrdv.data.local.AppDatabase.getDatabase(context).appointmentDao().resetSmsStatus(appointment.id)
                            } catch (_: Exception) {}
                        }
                    } else {
                        Log.w(TAG, "Numéro invalide: ${appointment.telephone}")
                    }
                } else if (emailSuccess) {
                    onSmsSent(appointment.id)
                }
            } finally {
                SmsLock.release(appointment.id)
            }
        }
    } finally {
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    private fun notifySmsSent(patientName: String, phone: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channelId = "docpocket_sms_sent_channel"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Confirmations d'envoi SMS",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Affiche l'heure exacte d'envoi du SMS sur l'écran verrouillé"
                    enableVibration(true)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("DocPocket SMS : Message envoyé")
                .setContentText("SMS transmis pour $patientName ($phone) à $time")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build()

            notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
            Log.i(TAG, "Notification visible postée sur l'écran verrouillé à $time")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur notification d'envoi: ${e.message}")
        }
    }
}
