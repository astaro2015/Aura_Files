package com.aurafiles.app.cloud.google

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local bridge between the interactive AuthorizationClient result and the
 * first Drive request. Tokens are deliberately never persisted to disk by Aura.
 */
object GoogleAccessTokenMemoryCache {
    private val tokens = ConcurrentHashMap<String, String>()

    private fun key(accountEmail: String): String = accountEmail.trim().lowercase()

    fun put(accountEmail: String, accessToken: String) {
        val email = key(accountEmail)
        val token = accessToken.trim()
        if (email.isNotBlank() && token.isNotBlank()) tokens[email] = token
    }

    fun get(accountEmail: String): String? = tokens[key(accountEmail)]?.takeIf(String::isNotBlank)

    fun remove(accountEmail: String, accessToken: String? = null) {
        val email = key(accountEmail)
        if (email.isBlank()) return
        if (accessToken.isNullOrBlank()) tokens.remove(email)
        else tokens.remove(email, accessToken)
    }
}
