package com.aurafiles.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpRepositoryTest {
    @Test fun defaultsToSshPort22AndReportsNewHostKey() {
        val profile = SftpProfile(name = "NAS", host = "nas.local", username = "oleg", password = "x")
        assertEquals(22, profile.port)
        val error = SftpHostKeyException("nas.local", 22, "SHA256:abc", null)
        assertTrue(error.message.orEmpty().contains("SHA256:abc"))
        assertTrue(error.message.orEmpty().contains("Неизвестный ключ"))
    }

    @Test fun changedHostKeyIsExplicitlyReported() {
        val error = SftpHostKeyException("nas", 22, "SHA256:new", "SHA256:old")
        assertTrue(error.message.orEmpty().contains("изменился"))
        assertTrue(error.message.orEmpty().contains("SHA256:old"))
        assertTrue(error.message.orEmpty().contains("SHA256:new"))
    }
}
