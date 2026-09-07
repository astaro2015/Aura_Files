package com.aurafiles.app.network

import com.aurafiles.app.model.SmbProfile
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbSupportTest {
    @Test
    fun parsesPlainUncUrlAndIpv6Targets() {
        assertEquals(SmbTarget("nas.local", ""), parseSmbTarget("nas.local"))
        assertEquals(SmbTarget("10.0.0.2", "Media"), parseSmbTarget("\\\\10.0.0.2\\Media"))
        assertEquals(SmbTarget("server", "Movies"), parseSmbTarget("smb://server:445/Movies"))
        assertEquals(SmbTarget("fe80::1234", "Share"), parseSmbTarget("[fe80::1234]:445/Share"))
        assertEquals("[fe80::1234]", smbUrlHost("fe80::1234"))
        assertEquals("[fe80::1%25wlan0]", smbUrlHost("fe80::1%wlan0"))
    }

    @Test
    fun rejectsAmbiguousOrUnsupportedTargets() {
        assertThrows(IllegalArgumentException::class.java) { parseSmbTarget("server:1445") }
        assertThrows(IllegalArgumentException::class.java) { parseSmbTarget("smb://server/share/folder") }
        assertThrows(IllegalArgumentException::class.java) { parseSmbTarget("[fe80::1]garbage") }
        assertThrows(IllegalArgumentException::class.java) { parseSmbTarget("server/shareA", "shareB") }
        assertThrows(IllegalArgumentException::class.java) { parseSmbTarget("user@server") }
    }

    @Test
    fun parsesDomainUserAndRejectsConflictingDomain() {
        assertEquals(SmbIdentity("oleg", "OFFICE"), parseSmbIdentity("OFFICE\\oleg"))
        assertEquals(SmbIdentity("oleg", "OFFICE"), parseSmbIdentity("oleg", "OFFICE"))
        assertEquals(SmbIdentity("oleg", "example.com"), parseSmbIdentity("oleg@example.com"))
        assertThrows(IllegalArgumentException::class.java) {
            parseSmbIdentity("OFFICE\\oleg", "OTHER")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSmbIdentity("oleg@example.com", "OTHER")
        }
    }

    @Test
    fun guestNormalizationDropsStaleDomainAndPassword() {
        val normalized = SmbProfile(
            name = " NAS ",
            host = " smb://10.0.0.2/Media ",
            share = "",
            username = "  ",
            password = "old-secret",
            domain = "WORKGROUP",
            id = "profile-id",
        ).normalizedSmbProfile()

        assertEquals("NAS", normalized.name)
        assertEquals("10.0.0.2", normalized.host)
        assertEquals("Media", normalized.share)
        assertEquals("", normalized.username)
        assertEquals("", normalized.password)
        assertEquals("", normalized.domain)
        assertEquals("profile-id", normalized.id)
    }

    @Test
    fun relativeSmbPathPreservesVisibleSpacesButBlocksTraversal() {
        assertEquals("Folder\\ name with spaces \\file.txt", normalizeSmbRelativePath("/Folder/ name with spaces /file.txt"))
        assertEquals("Folder\\file.txt", normalizeSmbRelativePath("Folder\\.\\file.txt"))
        assertThrows(IllegalArgumentException::class.java) { normalizeSmbRelativePath("Folder/../secret") }
    }

    @Test
    fun userMessagesHideJcifsInternalAddressAndExplainGuestFailure() {
        val failed = IOException("Failed to connect: 0.0.0.0<00>/10.0.0.2")
        val message = smbUserMessage(failed, host = "10.0.0.2")
        assertTrue(message.contains("10.0.0.2"))
        assertFalse(message.contains("0.0.0.0<00>"))

        val guest = smbUserMessage(IOException("STATUS_LOGON_FAILURE"), host = "nas", guestMode = true)
        assertTrue(guest.contains("гостевой", ignoreCase = true))
        assertTrue(guest.contains("пользователя", ignoreCase = true))
    }

    @Test
    fun transportDetectionUsesExceptionTypesAndDoesNotRetryAccessDenied() {
        assertTrue(isLikelySmbTransportError(SocketTimeoutException("read timed out")))
        assertTrue(isLikelySmbTransportError(ConnectException("refused")))
        assertTrue(isLikelySmbTransportError(UnknownHostException("nas.invalid")))
        assertFalse(isLikelySmbTransportError(IOException("STATUS_ACCESS_DENIED")))
        assertFalse(isLikelySmbTransportError(IOException("STATUS_OBJECT_NAME_NOT_FOUND")))
        assertTrue(isLikelySmbTransportError(IOException("STATUS_NETWORK_UNREACHABLE")))
        assertTrue(isLikelySmbTransportError(IOException("STATUS_IO_TIMEOUT")))
    }
}
