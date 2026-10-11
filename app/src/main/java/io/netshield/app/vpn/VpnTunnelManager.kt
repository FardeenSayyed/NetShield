package io.netshield.app.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class VpnTunnelManager(private val vpnService: VpnService) {
    private var vpnInterface: ParcelFileDescriptor? = null

    fun startTunnelFileDescriptor(): ParcelFileDescriptor? {
        return try {
            val builder = vpnService.Builder()
            builder.setSession("NetShieldSecureEngine")
                .addAddress("10.0.0.2", 24)
                .setMtu(1500)
                .addDnsServer("10.0.0.1")

            builder.addRoute("10.0.0.1", 32)
            builder.addRoute("10.0.0.0", 24)

            try {
                builder.addDisallowedApplication(vpnService.packageName)
                Log.d("VpnTunnelManager", "Device-wide DNS protection active. Excluded self package (${vpnService.packageName}) from VPN tunnel")
            } catch (e: Exception) {
                Log.w("VpnTunnelManager", "Failed to disallow self package: ${e.message}")
            }

            vpnInterface = builder.establish()
            Log.d("VpnTunnelManager", "Virtual TUN interface established successfully.")
            vpnInterface
        } catch (e: Exception) {
            Log.e("VpnTunnelManager", "CRITICAL: Tunnel initialization failed", e)
            null
        }
    }

    fun stopTunnel() {
        try {
            vpnInterface?.close()
            vpnInterface = null
            Log.d("VpnTunnelManager", "Virtual tunnel closed cleanly.")
        } catch (e: Exception) {
            Log.e("VpnTunnelManager", "Failed to clear loopback interface", e)
        }
    }
}
