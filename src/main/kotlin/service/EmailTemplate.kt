package com.yourcompany.zeiterfassung.service


/**
 * EmailTemplates содержит методы для генерации HTML- и plain-text шаблонов
 * для различных типов системных писем.
 */
object EmailTemplates {
    /**
     * Генерирует современный HTML-шаблон письма для сброса пароля.
     * Включает брендовые цвета, кнопку, адаптивный дизайн.
     * @param code шестизначный код сброса пароля
     * @return HTML-контент письма
     */
    fun buildResetPasswordHtml(code: String): String = """
        <!DOCTYPE html>
        <html lang=\"de\">
        <head>
            <meta charset=\"UTF-8\" />
            <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />
            <title>Passwort zurücksetzen</title>
            <style>
              body { margin:0; padding:0; background-color:#f4f4f4; font-family:Arial, sans-serif; }
              .wrapper { width:100%; table-layout:fixed; background-color:#f4f4f4; padding:20px 0; }
              .main { background-color:#ffffff; margin:0 auto; width:90%; max-width:600px; border-radius:8px; overflow:hidden; }
              .header { background-color:#FF9800; color:#ffffff; text-align:center; padding:40px; }
              .header img { max-width:100px; }
              .content { padding:24px; color:#333333; }
              .btn { display:inline-block; margin:20px auto; padding:12px 24px; background-color:#FF9800; color:#ffffff; text-decoration:none; border-radius:4px; font-weight:bold; }
              .code { font-size:32px; font-weight:bold; letter-spacing:4px; text-align:center; margin:24px 0; }
              .footer { background-color:#fafafa; text-align:center; font-size:12px; color:#777777; padding:16px; }
            </style>
        </head>
        <body>
          <table class=\"wrapper\" cellpadding=\"0\" cellspacing=\"0\">
            <tr>
              <td>
                <table class=\"main\" cellpadding=\"0\" cellspacing=\"0\">
                  <tr>
                    <td class=\"header\">
                      <img src=\"https://yourcdn.com/logo.png\" alt=\"ZeitErfassung Logo\" />
                      <h1>Passwort zurücksetzen</h1>
                    </td>
                  </tr>
                  <tr>
                    <td class=\"content\">
                      <p>Hallo,</p>
                      <p>Du hast angefordert, dein Passwort zurückzusetzen. Verwende bitte den folgenden Code:</p>
                      <div class=\"code\">$code</div>
                      <p>Falls du dein Passwort nicht zurücksetzen wolltest, ignoriere diese Nachricht.</p>
                      <p style=\"text-align:center;\">
                        <a href=\"https://yourapp.com/reset-password?code=$code\" class=\"btn\">Passwort jetzt ändern</a>
                      </p>
                    </td>
                  </tr>
                  <tr>
                    <td class=\"footer\">
                      © ${'$'}{java.time.Year.now()} ZeitErfassung. Alle Rechte vorbehalten.<br/>
                      Folge uns auf
                      <a href=\"https://twitter.com/yourapp\">Twitter</a>,
                      <a href=\"https://facebook.com/yourapp\">Facebook</a>
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
          </table>
        </body>
        </html>
    """.trimIndent()

    /**
     * Генерирует текстовое тело письма для тех, кто предпочитает plain-text.
     */
    fun buildResetPasswordText(code: String): String = buildString {
        appendLine("Passwort zurücksetzen")
        appendLine()
        appendLine("Dein Code: $code")
        appendLine()
        appendLine("Dieser Code ist 10 Minuten gültig.")
        appendLine("Wenn du diese Anfrage nicht gestellt hast, ignoriere diese E-Mail.")
        appendLine()
        appendLine("Besuche https://yourapp.com/reset-password?code=$code, um dein Passwort zu ändern.")
        appendLine()
        appendLine("© ${java.time.Year.now()} ZeitErfassung")
    }
}
