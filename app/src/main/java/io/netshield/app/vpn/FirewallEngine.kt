package io.netshield.app.vpn

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.util.Log
import java.net.InetAddress

class FirewallEngine(
    private val connectivityManager: ConnectivityManager,
    private val packageManager: PackageManager
) {

    companion object {
        private const val TAG = "FirewallEngine"
        private const val PROTOCOL_TCP = 6
        private const val PROTOCOL_UDP = 17
        private const val IPV4_HEADER_MIN_LENGTH = 20
    }

    @Volatile
    private var blockedPackages: Set<String> = emptySet()

    fun setBlockedPackages(packages: Set<String>) {
        blockedPackages = packages
        Log.d(TAG, "Updated internet-blocked packages count: ${packages.size}")
    }

    fun isPacketBlocked(packet: ByteArray, length: Int): Boolean {
        if (blockedPackages.isEmpty() || length < IPV4_HEADER_MIN_LENGTH) {
            return false
        }

        val ipVersion = (packet[0].toInt() ushr 4) and 0x0F
        if (ipVersion != 4) return false

        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        val protocol = packet[9].toInt() and 0xFF

        if (protocol != PROTOCOL_TCP && protocol != PROTOCOL_UDP) {
            return false
        }

        if (length < ipHeaderLength + 4) {
            return false
        }

        return try {
            val srcIpBytes = ByteArray(4)
            val dstIpBytes = ByteArray(4)
            System.arraycopy(packet, 12, srcIpBytes, 0, 4)
            System.arraycopy(packet, 16, dstIpBytes, 0, 4)

            val srcAddress = InetAddress.getByAddress(srcIpBytes)
            val dstAddress = InetAddress.getByAddress(dstIpBytes)

            val srcPort = ((packet[ipHeaderLength].toInt() and 0xFF) shl 8) or
                    (packet[ipHeaderLength + 1].toInt() and 0xFF)
            val dstPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                    (packet[ipHeaderLength + 3].toInt() and 0xFF)

            val uid = UidLookup.getSocketUid(
                protocol = protocol,
                srcAddress = srcAddress,
                srcPort = srcPort,
                dstAddress = dstAddress,
                dstPort = dstPort,
                connectivityManager = connectivityManager
            )

            if (uid < 0) return false

            val packageName = UidLookup.getPackageNameForUid(uid, packageManager)
            val blocked = packageName != null && packageName in blockedPackages

            if (blocked) {
                Log.i(TAG, "FIREWALL BLACKHOLED packet for app: $packageName (UID=$uid, protocol=$protocol)")
            }

            blocked
        } catch (e: Exception) {
            Log.w(TAG, "Error evaluating firewall rule for packet", e)
            false
        }
    }
}
