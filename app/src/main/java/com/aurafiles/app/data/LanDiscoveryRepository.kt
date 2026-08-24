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
        val localAddress = localIpv4() ?: return@coroutineScope emptyList()
        val octets = localAddress.hostAddress?.split('.') ?: return@coroutineScope emptyList()
        if (octets.size != 4) return@coroutineScope emptyList()
        val prefix = octets.take(3).joinToString(".")
        val own = localAddress.hostAddress
        val semaphore = Semaphore(56)
        (1..254).map { suffix ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val address = "$prefix.$suffix"
                    if (address == own) return@withPermit null
                    val services = buildSet {
                        if (portOpen(address, 445)) add(LanService.Smb)
                        if (portOpen(address, 21)) add(LanService.Ftp)
                        if (portOpen(address, 80) || portOpen(address, 443) ||
                            portOpen(address, 8080) || portOpen(address, 8008)) add(LanService.Web)
                        if (portOpen(address, 22)) add(LanService.Ssh)
                        if (portOpen(address, 554)) add(LanService.Media)
                    }
                    if (services.isEmpty()) return@withPermit null
                    val resolvedName = withTimeoutOrNull(350) {
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

    private fun localIpv4(): Inet4Address? {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val active = connectivity.activeNetwork?.let(connectivity::getLinkProperties)
            ?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
        if (active != null) return active
        val addresses = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .sortedBy { if (it.name.contains("wlan", true) || it.name.contains("wifi", true)) 0 else 1 }
            .flatMap { Collections.list(it.inetAddresses) }
        return addresses.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 170
        const val SSDP_WINDOW_MS = 2_600L
    }
}
