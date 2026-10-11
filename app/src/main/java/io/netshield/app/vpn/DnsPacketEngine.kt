package io.netshield.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import io.netshield.app.core.database.BlockedStatEntity
import io.netshield.app.core.database.NetShieldDatabase
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class DnsPacketEngine(
    private val vpnService: VpnService,
    private val inputStream: FileInputStream,
    private val outputStream: FileOutputStream,
    private val connectivityManager: ConnectivityManager,
    var firewallEngine: FirewallEngine? = null
) {

    companion object {
        private const val TAG = "DnsPacketEngine"
        private const val DNS_PORT = 53
        private const val DOT_PORT = 853
        private const val PROTOCOL_UDP = 17
        private const val IPV4_HEADER_MIN_LENGTH = 20
        private const val UDP_HEADER_LENGTH = 8
        private const val BUFFER_SIZE = 32767

        private val GOOGLE_SAFESEARCH_VIP = byteArrayOf(
            216.toByte(), 239.toByte(), 38.toByte(), 120.toByte()
        )
        private val BING_SAFESEARCH_VIP = byteArrayOf(
            204.toByte(), 79.toByte(), 197.toByte(), 220.toByte()
        )

        private val FALLBACK_DNS_SERVERS = arrayOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

        private val blockTimestamps = ConcurrentLinkedQueue<Long>()
        private val totalBlockedCounter = AtomicLong(0L)

        private fun recordBlockExecuted(context: Context) {
            val now = System.currentTimeMillis()
            blockTimestamps.add(now)

            val cutoff = now - 1_500_000L
            while ((blockTimestamps.peek() ?: Long.MAX_VALUE) < cutoff) {
                blockTimestamps.poll()
            }

            val total = totalBlockedCounter.incrementAndGet()
            val recent25m = blockTimestamps.size.toLong()

            Thread {
                try {
                    val db = NetShieldDatabase.getDatabase(context)
                    val dao = db.netShieldDao()
                    val currentStat = dao.getBlockedStatSync()
                    val newTotal = if (currentStat != null) currentStat.totalBlockedCount + 1L else total
                    runBlocking {
                        dao.saveBlockedStat(
                            BlockedStatEntity(
                                id = 1,
                                totalBlockedCount = newTotal,
                                recent25MinCount = recent25m
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist blocked stat to DB: ${e.message}")
                }
            }.start()
        }
    }

    data class DnsQuestionResult(
        val domain: String,
        val qtype: Int,
        val questionSectionEndOffset: Int
    )

    private class DnsCache(private val maxCapacity: Int = 1000) {
        private class CacheEntry(val responsePacket: ByteArray, val expiresAt: Long)

        private val map = object : LinkedHashMap<String, CacheEntry>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
                return size > maxCapacity
            }
        }

        @Synchronized
        fun get(key: String): ByteArray? {
            val entry = map[key] ?: return null
            if (System.currentTimeMillis() > entry.expiresAt) {
                map.remove(key)
                return null
            }
            return entry.responsePacket.copyOf()
        }

        @Synchronized
        fun put(key: String, responsePacket: ByteArray, ttlSeconds: Long = 120L) {
            val expiresAt = System.currentTimeMillis() + (ttlSeconds.coerceIn(30L, 600L) * 1000L)
            map[key] = CacheEntry(responsePacket.copyOf(), expiresAt)
        }

        @Synchronized
        fun clear() {
            map.clear()
        }
    }

    private val dnsCache = DnsCache()

    fun clearCache() {
        dnsCache.clear()
        Log.d(TAG, "Cleared DNS cache due to rules update")
    }

    @Volatile
    private var isRunning = false
    private var workerThread: Thread? = null

    @Volatile
    var isAdsBlocked: Boolean = true

    @Volatile
    var isAdultBlocked: Boolean = false

    fun start() {
        if (isRunning) return
        isRunning = true
        workerThread = Thread({
            runWorkerLoop()
        }, "DnsPacketEngineWorkerThread").apply { start() }
        Log.i(TAG, "DnsPacketEngine background worker thread started")
    }

    fun stop() {
        isRunning = false
        workerThread?.interrupt()
        workerThread = null
        Log.i(TAG, "DnsPacketEngine stopped")
    }

    private fun runWorkerLoop() {
        val buffer = ByteArray(BUFFER_SIZE)
        while (isRunning) {
            try {
                val length = inputStream.read(buffer)
                if (length <= 0) continue
                processPacket(buffer, length)
            } catch (e: Exception) {
                if (!isRunning) break
                Log.e(TAG, "Error reading TUN packet stream", e)
            }
        }
    }

    fun processPacket(packet: ByteArray, length: Int) {
        if (length < IPV4_HEADER_MIN_LENGTH) return

        val ipVersion = (packet[0].toInt() ushr 4) and 0x0F
        if (ipVersion != 4) return

        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        val protocol = packet[9].toInt() and 0xFF

        if (length < ipHeaderLength + 4) return

        val dstPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 3].toInt() and 0xFF)

        val isAppBlocked = firewallEngine?.isPacketBlocked(packet, length) == true

        if (isAppBlocked) {
            if (protocol == PROTOCOL_UDP && (dstPort == DNS_PORT || dstPort == DOT_PORT)) {
                val dnsOffset = ipHeaderLength + UDP_HEADER_LENGTH
                val dnsLength = length - dnsOffset
                if (dnsLength >= 12) {
                    Log.i(TAG, "FIREWALL REJECTED DNS query for internet-blocked app with NXDOMAIN")
                    recordBlockExecuted(vpnService.applicationContext)
                    val nxDomainPacket = buildNxDomainResponse(packet, ipHeaderLength, dnsOffset, dnsLength)
                    writeToTun(nxDomainPacket, nxDomainPacket.size)
                }
            }
            return
        }

        if (protocol == PROTOCOL_UDP) {
            if (dstPort == DNS_PORT) {
                handleDnsQuery(packet, length, ipHeaderLength)
            } else if (dstPort == DOT_PORT) {
                val dnsOffset = ipHeaderLength + UDP_HEADER_LENGTH
                val dnsLength = length - dnsOffset
                if (dnsLength >= 12) {
                    recordBlockExecuted(vpnService.applicationContext)
                    val nxDomainPacket = buildNxDomainResponse(packet, ipHeaderLength, dnsOffset, dnsLength)
                    writeToTun(nxDomainPacket, nxDomainPacket.size)
                }
            }
        }
    }

    private fun handleDnsQuery(packet: ByteArray, length: Int, ipHeaderLength: Int) {
        val dnsOffset = ipHeaderLength + UDP_HEADER_LENGTH
        val dnsLength = length - dnsOffset

        if (dnsLength < 12) return

        val question = parseDnsQuestion(packet, dnsOffset, dnsLength)
        if (question == null) {
            writeToTun(packet, length)
            return
        }

        val domain = question.domain
        val qtype = question.qtype
        val cacheKey = "$domain:$qtype"

        if (NetShieldBlocklist.isCustomBlocked(domain)) {
            Log.i(TAG, "CUSTOM DOMAIN BLOCKED (NXDOMAIN): $domain")
            recordBlockExecuted(vpnService.applicationContext)
            val nxDomainPacket = buildNxDomainResponse(packet, ipHeaderLength, dnsOffset, dnsLength)
            writeToTun(nxDomainPacket, nxDomainPacket.size)
            return
        }

        if (isPushNotificationDomain(domain) || isVoipOrWhatsAppDomain(domain)) {
            Log.d(TAG, "PUSH/VOIP/WHATSAPP DOMAIN ALLOWED: $domain")
            forwardOrServeCachedDnsQuery(packet, ipHeaderLength, dnsOffset, dnsLength, domain, cacheKey)
            return
        }

        if (isAdultBlocked) {
            val safesearchVip = getSafeSearchVip(domain, qtype)
            if (safesearchVip != null) {
                Log.i(TAG, "SAFESEARCH ENFORCED: domain=$domain VIP=${formatIp(safesearchVip)}")
                recordBlockExecuted(vpnService.applicationContext)
                val answerPacket = buildSyntheticDnsResponse(
                    originalPacket = packet,
                    ipHeaderLength = ipHeaderLength,
                    dnsOffset = dnsOffset,
                    dnsLength = dnsLength,
                    qtype = qtype,
                    rdata = safesearchVip
                )
                writeToTun(answerPacket, answerPacket.size)
                return
            } else if (qtype == 28 && (domain == "google.com" || domain.endsWith(".google.com") ||
                        domain == "youtube.com" || domain.endsWith(".youtube.com") ||
                        domain == "bing.com" || domain.endsWith(".bing.com"))) {
                recordBlockExecuted(vpnService.applicationContext)
                val nxDomainPacket = buildNxDomainResponse(packet, ipHeaderLength, dnsOffset, dnsLength)
                writeToTun(nxDomainPacket, nxDomainPacket.size)
                return
            }
        }

        val blocked = NetShieldBlocklist.shouldBlock(
            domain = domain,
            checkAds = isAdsBlocked,
            checkAdult = isAdultBlocked
        )

        if (blocked) {
            Log.i(TAG, "SINKHOLED domain (NXDOMAIN): $domain (ads=$isAdsBlocked, adult=$isAdultBlocked, qtype=$qtype)")
            recordBlockExecuted(vpnService.applicationContext)
            val nxDomainPacket = buildNxDomainResponse(packet, ipHeaderLength, dnsOffset, dnsLength)
            writeToTun(nxDomainPacket, nxDomainPacket.size)
        } else {
            forwardOrServeCachedDnsQuery(packet, ipHeaderLength, dnsOffset, dnsLength, domain, cacheKey)
        }
    }

    private fun forwardOrServeCachedDnsQuery(
        packet: ByteArray,
        ipHeaderLength: Int,
        dnsOffset: Int,
        dnsLength: Int,
        domain: String,
        cacheKey: String
    ) {
        val cachedResponse = dnsCache.get(cacheKey)
        if (cachedResponse != null && cachedResponse.size >= ipHeaderLength + UDP_HEADER_LENGTH + 12) {
            val cachedDnsOffset = ipHeaderLength + UDP_HEADER_LENGTH
            cachedResponse[cachedDnsOffset] = packet[dnsOffset]
            cachedResponse[cachedDnsOffset + 1] = packet[dnsOffset + 1]

            swapIpAndUdpHeadersForIncomingQuery(
                respPacket = cachedResponse,
                queryPacket = packet,
                totalLength = cachedResponse.size,
                ipHeaderLength = ipHeaderLength
            )

            writeToTun(cachedResponse, cachedResponse.size)
            Log.d(TAG, "DNS CACHE HIT (<1ms, 0 radio power): $domain")
            return
        }

        forwardDnsQuery(packet, ipHeaderLength, dnsOffset, dnsLength, domain, cacheKey)
    }

    private fun isPushNotificationDomain(domain: String): Boolean {
        val norm = domain.lowercase()
        return norm == "mtalk.google.com" || norm.endsWith(".mtalk.google.com") ||
                norm == "android.googleapis.com" || norm == "fcm.googleapis.com" ||
                norm == "fcmregistrations.googleapis.com" || norm == "firebaseinstallations.googleapis.com" ||
                norm == "push.apple.com" || norm.endsWith(".push.apple.com") ||
                norm == "g.whatsapp.net" || norm.endsWith(".whatsapp.net")
    }

    private fun isVoipOrWhatsAppDomain(domain: String): Boolean {
        val norm = domain.lowercase()
        return norm == "whatsapp.com" || norm.endsWith(".whatsapp.com") ||
                norm == "whatsapp.net" || norm.endsWith(".whatsapp.net") ||
                norm == "whatsapp.biz" || norm.endsWith(".whatsapp.biz") ||
                norm == "whatsapp.cc" || norm.endsWith(".whatsapp.cc") ||
                norm.startsWith("stun.") || norm.startsWith("turn.") ||
                norm == "stun.l.google.com" || norm.contains("stun") || norm.contains("turn")
    }

    private fun getSafeSearchVip(domain: String, qtype: Int): ByteArray? {
        if (qtype != 1) return null

        val norm = domain.lowercase()
        return when {
            norm == "google.com" || norm.endsWith(".google.com") ||
                    norm == "youtube.com" || norm.endsWith(".youtube.com") -> GOOGLE_SAFESEARCH_VIP

            norm == "bing.com" || norm.endsWith(".bing.com") -> BING_SAFESEARCH_VIP

            else -> null
        }
    }

    private fun forwardDnsQuery(
        packet: ByteArray,
        ipHeaderLength: Int,
        dnsOffset: Int,
        dnsLength: Int,
        domain: String,
        cacheKey: String
    ) {
        val upstreamAddresses = getUpstreamDnsServers()

        for (upstreamDns in upstreamAddresses) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                if (!vpnService.protect(socket)) {
                    Log.e(TAG, "Could not protect DNS DatagramSocket")
                    continue
                }
                socket.soTimeout = 2000

                val outgoing = DatagramPacket(packet, dnsOffset, dnsLength, upstreamDns, DNS_PORT)
                socket.send(outgoing)

                val recvBuf = ByteArray(4096)
                val incoming = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(incoming)

                if (incoming.length > 0) {
                    val responseDnsLen = incoming.length
                    val totalRespLen = ipHeaderLength + UDP_HEADER_LENGTH + responseDnsLen
                    val responsePacket = ByteArray(totalRespLen)

                    System.arraycopy(packet, 0, responsePacket, 0, ipHeaderLength + UDP_HEADER_LENGTH)
                    System.arraycopy(recvBuf, 0, responsePacket, ipHeaderLength + UDP_HEADER_LENGTH, responseDnsLen)

                    dnsCache.put(cacheKey, responsePacket, ttlSeconds = 120L)

                    swapIpAndUdpHeaders(responsePacket, totalRespLen, ipHeaderLength)
                    writeToTun(responsePacket, totalRespLen)

                    Log.d(TAG, "Forwarded DNS response for $domain via $upstreamDns")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Upstream DNS query failed via $upstreamDns for $domain: ${e.message}")
            } finally {
                socket?.close()
            }
        }

        Log.e(TAG, "All upstream DNS resolution attempts failed for $domain")
    }

    @Suppress("DEPRECATION")
    private fun getUpstreamDnsServers(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            val networks = connectivityManager.allNetworks
            for (network in networks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) {
                    val linkProps = connectivityManager.getLinkProperties(network)
                    val dnsList = linkProps?.dnsServers
                    if (!dnsList.isNullOrEmpty()) {
                        val ipv4Dns = dnsList.filterIsInstance<Inet4Address>()
                        result.addAll(ipv4Dns)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve underlying physical network DNS: ${e.message}")
        }

        for (fallback in FALLBACK_DNS_SERVERS) {
            try {
                result.add(InetAddress.getByName(fallback))
            } catch (_: Exception) {}
        }

        return result.distinct()
    }

    private fun buildNxDomainResponse(
        originalPacket: ByteArray,
        ipHeaderLength: Int,
        dnsOffset: Int,
        dnsLength: Int
    ): ByteArray {
        val totalLen = ipHeaderLength + UDP_HEADER_LENGTH + dnsLength
        val respPacket = ByteArray(totalLen)
        System.arraycopy(originalPacket, 0, respPacket, 0, totalLen)

        respPacket[dnsOffset + 2] = 0x81.toByte()
        respPacket[dnsOffset + 3] = 0x83.toByte()

        respPacket[dnsOffset + 6] = 0x00.toByte()
        respPacket[dnsOffset + 7] = 0x00.toByte()
        respPacket[dnsOffset + 8] = 0x00.toByte()
        respPacket[dnsOffset + 9] = 0x00.toByte()
        respPacket[dnsOffset + 10] = 0x00.toByte()
        respPacket[dnsOffset + 11] = 0x00.toByte()

        swapIpAndUdpHeaders(respPacket, totalLen, ipHeaderLength)
        return respPacket
    }

    private fun buildSyntheticDnsResponse(
        originalPacket: ByteArray,
        ipHeaderLength: Int,
        dnsOffset: Int,
        dnsLength: Int,
        qtype: Int,
        rdata: ByteArray
    ): ByteArray {
        val answerLen = 2 + 2 + 2 + 4 + 2 + rdata.size
        val newDnsPayloadLen = dnsLength + answerLen
        val totalPacketLen = ipHeaderLength + UDP_HEADER_LENGTH + newDnsPayloadLen

        val respPacket = ByteArray(totalPacketLen)
        System.arraycopy(originalPacket, 0, respPacket, 0, ipHeaderLength + UDP_HEADER_LENGTH)

        val newDnsOffset = ipHeaderLength + UDP_HEADER_LENGTH
        System.arraycopy(originalPacket, dnsOffset, respPacket, newDnsOffset, dnsLength)

        respPacket[newDnsOffset + 2] = 0x81.toByte()
        respPacket[newDnsOffset + 3] = 0x80.toByte()

        respPacket[newDnsOffset + 6] = 0x00.toByte()
        respPacket[newDnsOffset + 7] = 0x01.toByte()

        var ptr = newDnsOffset + dnsLength

        respPacket[ptr++] = 0xC0.toByte()
        respPacket[ptr++] = 0x0C.toByte()

        val typeVal = if (qtype == 28) 28 else 1
        respPacket[ptr++] = ((typeVal shr 8) and 0xFF).toByte()
        respPacket[ptr++] = (typeVal and 0xFF).toByte()

        respPacket[ptr++] = 0x00.toByte()
        respPacket[ptr++] = 0x01.toByte()

        respPacket[ptr++] = 0x00.toByte()
        respPacket[ptr++] = 0x00.toByte()
        respPacket[ptr++] = 0x00.toByte()
        respPacket[ptr++] = 0x3C.toByte()

        respPacket[ptr++] = ((rdata.size shr 8) and 0xFF).toByte()
        respPacket[ptr++] = (rdata.size and 0xFF).toByte()

        System.arraycopy(rdata, 0, respPacket, ptr, rdata.size)

        swapIpAndUdpHeaders(respPacket, totalPacketLen, ipHeaderLength)
        return respPacket
    }

    private fun swapIpAndUdpHeadersForIncomingQuery(
        respPacket: ByteArray,
        queryPacket: ByteArray,
        totalLength: Int,
        ipHeaderLength: Int
    ) {
        System.arraycopy(queryPacket, 12, respPacket, 16, 4)
        System.arraycopy(queryPacket, 16, respPacket, 12, 4)

        val srcPortIdx = ipHeaderLength
        val dstPortIdx = ipHeaderLength + 2
        respPacket[srcPortIdx] = queryPacket[dstPortIdx]
        respPacket[srcPortIdx + 1] = queryPacket[dstPortIdx + 1]
        respPacket[dstPortIdx] = queryPacket[srcPortIdx]
        respPacket[dstPortIdx + 1] = queryPacket[srcPortIdx + 1]

        respPacket[2] = ((totalLength shr 8) and 0xFF).toByte()
        respPacket[3] = (totalLength and 0xFF).toByte()

        val udpLength = totalLength - ipHeaderLength
        respPacket[ipHeaderLength + 4] = ((udpLength shr 8) and 0xFF).toByte()
        respPacket[ipHeaderLength + 5] = ((udpLength and 0xFF).toByte())

        respPacket[10] = 0x00.toByte()
        respPacket[11] = 0x00.toByte()
        val ipChecksum = calculateIpChecksum(respPacket, ipHeaderLength)
        respPacket[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        respPacket[11] = (ipChecksum and 0xFF).toByte()

        respPacket[ipHeaderLength + 6] = 0x00.toByte()
        respPacket[ipHeaderLength + 7] = 0x00.toByte()
    }

    private fun swapIpAndUdpHeaders(packet: ByteArray, totalLength: Int, ipHeaderLength: Int) {
        for (i in 0..3) {
            val temp = packet[12 + i]
            packet[12 + i] = packet[16 + i]
            packet[16 + i] = temp
        }

        val srcPortIdx = ipHeaderLength
        val dstPortIdx = ipHeaderLength + 2
        val p1 = packet[srcPortIdx]
        val p2 = packet[srcPortIdx + 1]
        packet[srcPortIdx] = packet[dstPortIdx]
        packet[srcPortIdx + 1] = packet[dstPortIdx + 1]
        packet[dstPortIdx] = p1
        packet[dstPortIdx + 1] = p2

        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()

        val udpLength = totalLength - ipHeaderLength
        packet[ipHeaderLength + 4] = ((udpLength shr 8) and 0xFF).toByte()
        packet[ipHeaderLength + 5] = ((udpLength and 0xFF).toByte())

        packet[10] = 0x00.toByte()
        packet[11] = 0x00.toByte()
        val ipChecksum = calculateIpChecksum(packet, ipHeaderLength)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        packet[ipHeaderLength + 6] = 0x00.toByte()
        packet[ipHeaderLength + 7] = 0x00.toByte()
    }

    private fun calculateIpChecksum(header: ByteArray, length: Int): Int {
        var sum = 0
        var i = 0
        while (i < length) {
            val word = ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun parseDnsQuestion(packet: ByteArray, dnsOffset: Int, dnsLength: Int): DnsQuestionResult? {
        val limit = dnsOffset + dnsLength
        var offset = dnsOffset + 12
        val domainBuilder = StringBuilder(128)

        while (offset < limit) {
            val labelLen = packet[offset].toInt() and 0xFF
            if (labelLen == 0) {
                offset += 1
                break
            }
            if ((labelLen and 0xC0) == 0xC0) {
                offset += 2
                break
            }
            if (labelLen > 63 || offset + 1 + labelLen > limit) return null

            if (domainBuilder.isNotEmpty()) domainBuilder.append('.')
            for (i in 0 until labelLen) {
                domainBuilder.append((packet[offset + 1 + i].toInt() and 0xFF).toChar())
            }
            offset += 1 + labelLen
        }

        if (domainBuilder.isEmpty() || offset + 4 > limit) return null

        val qtype = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
        val questionEndOffset = offset + 4

        return DnsQuestionResult(
            domain = domainBuilder.toString().lowercase(),
            qtype = qtype,
            questionSectionEndOffset = questionEndOffset
        )
    }

    private fun writeToTun(buffer: ByteArray, length: Int) {
        try {
            outputStream.write(buffer, 0, length)
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write response to TUN interface: ${e.message}")
        }
    }

    private fun formatIp(ip: ByteArray): String {
        return ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }
}
