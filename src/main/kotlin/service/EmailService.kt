package com.yourcompany.zeiterfassung.service

import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object EmailService {
    private val mailSession: Session by lazy {
        val smtpHost = System.getenv("SMTP_HOST") ?: error("SMTP_HOST not set")
        val smtpPort = System.getenv("SMTP_PORT") ?: error("SMTP_PORT not set")
        val smtpUser = System.getenv("SMTP_USER") ?: error("SMTP_USER not set")
        val smtpPass = System.getenv("SMTP_PASS") ?: error("SMTP_PASS not set")

        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", smtpPort)
        }

        Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(smtpUser, smtpPass)
            }
        })
    }

    /**
     * Sends an email with the specified subject and HTML/plain text body.
     * @param to recipient email address
     * @param subject email subject
     * @param body email body (HTML is supported)
     */
    fun send(to: String, subject: String, body: String) {
        val message = MimeMessage(mailSession).apply {
            setFrom(InternetAddress(System.getenv("SMTP_FROM")
                ?: mailSession.getProperty("mail.smtp.user")))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject)
            setContent(body, "text/html; charset=utf-8")
        }

        Transport.send(message)
    }
}
