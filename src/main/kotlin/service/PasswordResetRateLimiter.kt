package com.yourcompany.zeiterfassung.service



import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Простая реализация rate limiter для сброса пароля.
 * Ограничение: не чаще 1 запроса в минуту и не более 5 в сутки на адрес.
 */
object PasswordResetRateLimiter {
    private data class Entry(var lastRequest: Instant, var dailyCount: Int)
    private val requests = ConcurrentHashMap<String, Entry>()

    /**
     * Проверяет, можно ли отправлять код на данный адрес.
     * @param destination email или телефон
     * @return true если запрос разрешён, false если превышен лимит.
     */
    fun allow(destination: String): Boolean {
        val now = Instant.now()
        val entry = requests.compute(destination) { _, old ->
            if (old == null
                || Duration.between(old.lastRequest, now) > Duration.ofDays(1)
            ) {
                Entry(now, 1)
            } else {
                old.apply {
                    // сбрасываем count, если новый день
                    if (Duration.between(lastRequest, now) > Duration.ofDays(1)) {
                        dailyCount = 1
                    } else {
                        dailyCount++
                    }
                    lastRequest = now
                }
            }
        }!!

        // Проверяем: не чаще 1 в минуту и не более 5 в сутки
        val sinceLast = Duration.between(requests[destination]!!.lastRequest, now)
        return sinceLast >= Duration.ofMinutes(1) && entry.dailyCount <= 5
    }
}
