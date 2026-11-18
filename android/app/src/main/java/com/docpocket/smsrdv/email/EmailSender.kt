package com.docpocket.smsrdv.email

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class EmailSender(private val context: Context) {

    suspend fun sendEmail(toEmail: String, subject: String, body: String, customSmtpEmail: String? = null, customSmtpPassword: String? = null): Boolean {
        return withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("docpocket_prefs", Context.MODE_PRIVATE)
            val smtpEmail = customSmtpEmail ?: prefs.getString("smtp_email", "") ?: ""
            val smtpPassword = customSmtpPassword ?: prefs.getString("smtp_password", "") ?: ""

            if (smtpEmail.isBlank() || smtpPassword.isBlank()) {
                return@withContext false
            }

            val props = Properties()
            props["mail.smtp.auth"] = "true"
            props["mail.smtp.host"] = "smtp.gmail.com"
            props["mail.smtp.port"] = "465"
            props["mail.smtp.ssl.enable"] = "true"
            props["mail.smtp.socketFactory.port"] = "465"
            props["mail.smtp.socketFactory.class"] = "javax.net.ssl.SSLSocketFactory"

            val session = Session.getInstance(props,
                object : javax.mail.Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(smtpEmail, smtpPassword)
                    }
                })

            val message = MimeMessage(session)
            message.setFrom(InternetAddress(smtpEmail))
            message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(toEmail)
            )
            message.subject = subject
            message.setText(body)

            Transport.send(message)
            true
        }
    }
}
