package com.yourcompany.zeiterfassung.service

import kotlin.random.Random

object CodeUtil {
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray() // без I,O,L,U

    fun randomCode(len: Int = 8, dashAfter: Int = 4): String {
        val raw = CharArray(len) { alphabet[Random.nextInt(alphabet.size)] }.concatToString()
        return if (dashAfter in 1 until len) raw.substring(0, dashAfter) + "-" + raw.substring(dashAfter) else raw
    }
}
