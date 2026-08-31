package com.aurafiles.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoveryAddressTest {
    @Test
    fun slash24ReturnsEveryOtherUsableHost() {
        val addresses = generateIpv4ScanAddresses("192.168.7.42", 24)

        assertEquals(253, addresses.size)
        assertEquals("192.168.7.1", addresses.first())
        assertEquals("192.168.7.254", addresses.last())
        assertFalse("192.168.7.42" in addresses)
        assertFalse("192.168.7.0" in addresses)
        assertFalse("192.168.7.255" in addresses)
    }

    @Test
    fun slash23CrossesTheOldSlash24Boundary() {
        val addresses = generateIpv4ScanAddresses("192.168.8.200", 23)

        assertEquals(509, addresses.size)
        assertTrue("192.168.9.1" in addresses)
        assertTrue("192.168.9.254" in addresses)
        assertFalse("192.168.8.200" in addresses)
    }

    @Test
    fun largeSubnetIsBoundedAndStaysInsideNetwork() {
        val addresses = generateIpv4ScanAddresses("10.24.80.100", 16, maxAddresses = 128)

        assertEquals(128, addresses.size)
        assertEquals("10.24.0.1", addresses.first())
        assertTrue(addresses.all { it.startsWith("10.24.") })
        assertFalse("10.24.80.100" in addresses)
    }

    @Test
    fun pointToPointAndSingleHostPrefixesAreHandled() {
        assertEquals(listOf("10.0.0.5"), generateIpv4ScanAddresses("10.0.0.4", 31))
        assertTrue(generateIpv4ScanAddresses("10.0.0.4", 32).isEmpty())
    }
}
