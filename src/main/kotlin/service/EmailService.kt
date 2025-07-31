package com.yourcompany.zeiterfassung.service

import io.github.cdimascio.dotenv.Dotenv
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMultipart

object EmailService {
    fun send(to: String, subject: String, body: String, env: Dotenv) {
        val smtpHost = env["SMTP_HOST"] ?: error("SMTP_HOST not set")
        val smtpPort = env["SMTP_PORT"] ?: error("SMTP_PORT not set")
        val smtpUser = env["SMTP_USER"] ?: error("SMTP_USER not set")
        val smtpPass = env["SMTP_PASS"] ?: error("SMTP_PASS not set")
        val smtpFrom = env["SMTP_FROM"] ?: smtpUser

        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", smtpPort)
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(smtpUser, smtpPass)
            }
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(smtpFrom))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject)
        }

        // Build multipart/related: HTML body + inline logo
        val htmlPart = MimeBodyPart().apply {
            setContent(body, "text/html; charset=utf-8")
        }
        val imagePart = MimeBodyPart().apply {
            val fds = FileDataSource("/Users/yuliyanatasheva/IdeaProjects/zeiterfassung-server/src/main/kotlin/service/img.png")
            dataHandler = DataHandler(fds)
            fileName = "img.png"
            setHeader("Content-ID", "<img.png>")
            disposition = MimeBodyPart.INLINE
        }
        val multipart = MimeMultipart("related").apply {
            addBodyPart(htmlPart)
            addBodyPart(imagePart)
        }
        message.setContent(multipart)

        Transport.send(message)
    }
}
