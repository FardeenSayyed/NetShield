package io.netshield.app.vpn

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

object UidLookup {

    private const val TAG = "UidLookup"

    fun getSocketUid(
        protocol: Int,
        srcAddress: InetAddress,
        srcPort: Int,
        dstAddress: InetAddress,
        dstPort: Int,
        connectivityManager: ConnectivityManager
    ): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val localSocket = InetSocketAddress(srcAddress, srcPort)
                val remoteSocket = InetSocketAddress(dstAddress, dstPort)
                val uid = connectivityManager.getConnectionOwnerUid(protocol, localSocket, remoteSocket)
                if (uid >= 0) {
                    return uid
                }
            } catch (e: Exception) {
                Log.w(TAG, "getConnectionOwnerUid failed: ${e.message}")
            }
        }

        return getUidFromProcNet(protocol, srcPort)
    }

    private fun getUidFromProcNet(protocol: Int, localPort: Int): Int {
        val procFilePath = if (protocol == 6) "/proc/net/tcp" else "/proc/net/udp"
        val procFile = File(procFilePath)

        if (!procFile.exists() || !procFile.canRead()) {
            return -1
        }

        try {
            val portHex = String.format("%04X", localPort)
            procFile.useLines { lines ->
                for (line in lines) {
                    val tokens = line.trim().split("\\s+".toRegex())
                    if (tokens.size >= 8) {
                        val localAddress = tokens[1]
                        if (localAddress.endsWith(":$portHex", ignoreCase = true)) {
                            val uidStr = tokens[7]
                            val uid = uidStr.toIntOrNull()
                            if (uid != null) {
                                return uid
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing $procFilePath: ${e.message}")
        }

        return -1
    }

    fun getPackageNameForUid(uid: Int, packageManager: PackageManager): String? {
        if (uid < 0) return null
        return try {
            packageManager.getNameForUid(uid)
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving package name for UID $uid: ${e.message}")
            null
        }
    }
}
