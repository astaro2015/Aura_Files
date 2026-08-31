package com.aurafiles.app.data

import android.content.Context
import android.net.ConnectivityManager
import com.aurafiles.app.model.LanDevice
import com.aurafiles.app.model.LanService
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class LanDiscoveryRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun scan(): List<LanDevice> = coroutineScope {
        val subnet = async(Dispatchers.IO) { scanSubnet() }
        val upnp = async(Dispatchers.IO) { discoverSsdp() }
        val portMatches = subnet.await()
        val neighborMatches = withContext(Dispatchers.IO) { readArpNeighbors() }
        (portMatches + neighborMatches + upnp.await())
            .groupBy(LanDevice::address)
            .map { (address, matches) ->
                val preferredName = matches.firstNotNullOfOrNull { it.name.takeIf { name -> name != address } } ?: address
                LanDevice(address, preferredName, matches.flatMap { it.services }.toSet())
            }
            .sortedWith(compareBy({ it.name.lowercase() }, LanDevice::address))
    }

    private suspend fun scanSubnet(): List<LanDevice> = coroutineScope {
        val localNetwork = localIpv4Network() ?: return@coroutineScope emptyList()
        val own = localNetwork.address.hostAddress ?: return@coroutineScope emptyList()
        val addresses = generateIpv4ScanAddresses(own, localNetwork.prefixLength)
        val semaphore = Semaphore(64)
        addresses.map { address ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val services = buildSet {
                        if (portOpen(address, 445)) add(LanService.Smb)
                        if (portOpen(address, 21)) add(LanService.Ftp)
                        if (portOpen(address, 80) || portOpen(address, 443) ||
                            portOpen(address, 8080) || portOpen(address, 8008)) add(LanService.Web)
                        if (portOpen(address, 22)) add(LanService.Ssh)
                        if (portOpen(address, 554)) add(LanService.Media)
                    }
                    if (services.isEmpty()) return@withPermit null
                    val resolvedName = withTimeoutOrNull(500) {
                        withContext(Dispatchers.IO) { InetAddress.getByName(address).canonicalHostName }
                    }?.takeIf { it != address }.orEmpty()
                    LanDevice(address, resolvedName.ifBlank { address }, services)
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun discoverSsdp(): List<LanDevice> {
        val request = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: ssdp:all\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val found = linkedMapOf<String, LanDevice>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 350
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
                val deadline = System.currentTimeMillis() + SSDP_WINDOW_MS
                val buffer = ByteArray(16 * 1024)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    if (runCatching { socket.receive(packet) }.isFailure) continue
                    val response = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val headers = response.lineSequence()
                        .mapNotNull { line ->
                            val split = line.indexOf(':')
                            if (split <= 0) null else line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim()
                        }
                        .toMap()
                    val address = headers["location"]?.let { runCatching { URI(it).host }.getOrNull() }
                        ?: packet.address.hostAddress
                        ?: continue
                    val name = headers["server"]?.substringBefore('/')?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: headers["usn"]?.substringBefore("::")?.removePrefix("uuid:")?.take(32)
                        ?: address
                    found[address] = LanDevice(address, name, setOf(LanService.Media))
                }
            }
        }
        return found.values.toList()
    }

    private fun portOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    /**
     * A port scan primes the Wi-Fi neighbour table. Reading it afterwards also
     * reveals phones, TVs and IoT devices that are online but expose none of the
     * services Aura knows how to open. Newer Android versions can deny access to
     * this kernel table, so this deliberately remains a best-effort supplement.
     */
    private fun readArpNeighbors(): List<LanDevice> = runCatching {
        java.io.File("/proc/net/arp").useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                val address = columns.getOrNull(0) ?: return@mapNotNull null
                val flags = columns.getOrNull(2) ?: return@mapNotNull null
                val mac = columns.getOrNull(3) ?: return@mapNotNull null
                if (flags == "0x0" || mac == "00:00:00:00:00:00") return@mapNotNull null
                LanDevice(address = address, name = address, services = emptySet())
            }.toList()
        }
    }.getOrDefault(emptyList())

    private fun localIpv4Network(): LocalIpv4Network? {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val active = connectivity.activeNetwork?.let(connectivity::getLinkProperties)
            ?.linkAddresses
            ?.firstNotNullOfOrNull { linkAddress ->
                val address = linkAddress.address as? Inet4Address ?: return@firstNotNullOfOrNull null
                address.takeIf(Inet4Address::isSiteLocalAddress)
                    ?.let { LocalIpv4Network(it, linkAddress.prefixLength) }
            }
        if (active != null) return active
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .getOrDefault(emptyList())
        return interfaces
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .sortedBy { if (it.name.contains("wlan", true) || it.name.contains("wifi", true)) 0 else 1 }
            .flatMap(NetworkInterface::getInterfaceAddresses)
            .firstNotNullOfOrNull { interfaceAddress ->
                val address = interfaceAddress.address as? Inet4Address ?: return@firstNotNullOfOrNull null
                address.takeIf(Inet4Address::isSiteLocalAddress)
                    ?.let { LocalIpv4Network(it, interfaceAddress.networkPrefixLength.toInt()) }
            }
    }

    private data class LocalIpv4Network(val address: Inet4Address, val prefixLength: Int)

    private companion object {
        // 170 ms caused false negatives on power-saving TVs/NAS devices. 250 ms is
        // still short enough for the bounded scan but tolerates a busy Wi-Fi hop.
        const val CONNECT_TIMEOUT_MS = 250
        const val SSDP_WINDOW_MS = 2_600L
    }
}

internal const val DEFAULT_LAN_SCAN_ADDRESS_LIMIT = 512

/**
 * Returns addresses inside the actual IPv4 subnet, excluding this device and
 * (where applicable) the network and broadcast addresses.
 *
 * Small networks are returned completely. Huge corporate/VPN networks are
 * deliberately bounded: Aura checks the conventional first host (usually the
 * router) and then the addresses nearest to the phone. This keeps discovery
 * useful without accidentally starting a many-minute /16 or /8 port scan.
 */
internal fun generateIpv4ScanAddresses(
    localAddress: String,
    prefixLength: Int,
    maxAddresses: Int = DEFAULT_LAN_SCAN_ADDRESS_LIMIT,
): List<String> {
    require(prefixLength in 0..32) { "IPv4 prefix length must be between 0 and 32" }
    require(maxAddresses >= 0) { "Address limit must not be negative" }
    if (maxAddresses == 0) return emptyList()

    val local = ipv4ToLong(localAddress)
        ?: throw IllegalArgumentException("Invalid IPv4 address: $localAddress")
    val mask = if (prefixLength == 0) 0L else (0xffff_ffffL shl (32 - prefixLength)) and 0xffff_ffffL
    val network = local and mask
    val broadcast = network or (mask.inv() and 0xffff_ffffL)
    val first = if (prefixLength <= 30) network + 1 else network
    val last = if (prefixLength <= 30) broadcast - 1 else broadcast
    if (first > last) return emptyList()

    val available = (last - first + 1) - if (local in first..last) 1 else 0
    if (available <= 0) return emptyList()
    if (available <= maxAddresses.toLong()) {
        return (first..last).asSequence()
            .filter { it != local }
            .map(::longToIpv4)
            .toList()
    }

    val result = LinkedHashSet<Long>(maxAddresses)
    // The first usable address is commonly the router/default gateway.
    if (first != local) result += first
    var distance = 1L
    while (result.size < maxAddresses && (local - distance >= first || local + distance <= last)) {
        val before = local - distance
        if (before >= first && before != local) result += before
        if (result.size >= maxAddresses) break
        val after = local + distance
        if (after <= last && after != local) result += after
        distance += 1
    }
    return result.map(::longToIpv4)
}

private fun ipv4ToLong(address: String): Long? {
    val octets = address.split('.')
    if (octets.size != 4) return null
    var result = 0L
    for (octetText in octets) {
        val octet = octetText.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        result = (result shl 8) or octet.toLong()
    }
    return result
}

private fun longToIpv4(address: Long): String = buildString {
    append(address shr 24 and 0xff)
    append('.')
    append(address shr 16 and 0xff)
    append('.')
    append(address shr 8 and 0xff)
    append('.')
    append(address and 0xff)
}
