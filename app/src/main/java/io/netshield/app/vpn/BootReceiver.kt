package io.netshield.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.netshield.app.core.database.NetShieldDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "Device booted or app updated. Checking Always-On VPN status...")

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = NetShieldDatabase.getDatabase(context.applicationContext)
                    val dao = db.netShieldDao()

                    val isVpnActive = dao.getGlobalSettingSync("vpn_active")?.value ?: false
                    val isAdsBlocked = dao.getGlobalSettingSync("ads_blocked")?.value ?: false
                    val isAdultBlocked = dao.getGlobalSettingSync("adult_blocked")?.value ?: false

                    if (isVpnActive || isAdsBlocked || isAdultBlocked) {
                        Log.i(TAG, "Restoring Always-On VPN service on boot...")
                        val serviceIntent = Intent(context, NetShieldVpnService::class.java)
                        context.startForegroundService(serviceIntent)
                    } else {
                        Log.d(TAG, "Always-On VPN was not enabled prior to reboot")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore VPN service on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
