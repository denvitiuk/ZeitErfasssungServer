package com.yourcompany.zeiterfassung.service

import java.time.LocalDate

/**
 * EmailTemplates содержит методы для генерации HTML- и plain-text шаблонов
 * для различных типов системных писем.
 */
object EmailTemplates {
    /**
     * Генерирует современный HTML-шаблон письма для сброса пароля.
     * Включает адаптивный дизайн, брендовые цвета и крупный, жирный код.
     * @param code шестизначный код сброса пароля
     * @return HTML-контент письма
     */
    fun buildResetPasswordHtml(code: String): String = """
<!DOCTYPE html>
<html lang="de">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>Passwort zurücksetzen</title>
  <style>
    body { margin:0; padding:0; background:#f0f2f5; font-family:"Helvetica Neue",Helvetica,Arial,sans-serif; }
    .container { width:100%; max-width:600px; margin:0 auto; background:#ffffff; border-radius:8px; overflow:hidden; }
    .header { background:#4a90e2; color:#ffffff; text-align:center; padding:40px; }
    .header img { height:60px; }
    .content { padding:30px; color:#333333; line-height:1.5; }
    .code { display:block; font-size:36px; font-weight:700; color:#4a90e2; text-align:center; margin:30px auto; }
    .button { display:block; width:200px; margin:30px auto; padding:15px; background:#4a90e2; color:#ffffff; text-align:center; text-decoration:none; border-radius:4px; font-weight:600; }
    .footer { padding:20px; text-align:center; font-size:12px; color:#999999; }
  </style>
</head>
<body>
  <div class="container">
    <div class="header">
      <img src="cid:logo12.png" alt="Logo" style="height:60px;"/>
      <h1>Passwort zurücksetzen</h1>
    </div>
    <div class="content">
      <p>Hallo,</p>
      <p>Du hast angefordert, dein Passwort zurückzusetzen. Verwende bitte den folgenden Code:</p>
      <span class="code"><strong>$code</strong></span>
      <p>Wenn du diese Anfrage nicht gestellt hast, kannst du diese Nachricht ignorieren.</p>
      <a href="#" class="button">Code in App eingeben</a>
    </div>
    <div class="footer">
      © ${LocalDate.now().year} ZeitErfassung. Alle Rechte vorbehalten.
    </div>
  </div>
</body>
</html>
""".trimIndent()

    /**
     * Генерирует простое текстовое тело письма для тех, кто предпочитает plain-text.
     */
    fun buildResetPasswordText(code: String): String = """
Passwort zurücksetzen

Dein Code: $code

Dieser Code ist 10 Minuten gültig.
Wenn du diese Anfrage nicht angefordert hast, ignoriere diese E-Mail.

© ${LocalDate.now().year} ZeitErfassung
""".trimIndent()
}
