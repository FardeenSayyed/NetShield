package io.netshield.app.vpn

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.netshield.app.MainActivity
import io.netshield.app.core.database.BlockedStatEntity
import io.netshield.app.core.database.NetShieldDatabase
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch

class NetShieldVpnService : VpnService() {

    companion object {
        const val ACTION_STOP_VPN = "io.netshield.app.action.STOP_VPN"
        const val ACTION_REFRESH_RULES = "io.netshield.app.action.REFRESH_RULES"

        private const val TAG = "NetShieldVpnService"
        private const val NOTIFICATION_ID = 8472
        private const val NOTIFICATION_CHANNEL_ID = "netshield_vpn_service_channel"
    }

    private var tunnelManager: VpnTunnelManager? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var dnsPacketEngine: DnsPacketEngine? = null

    @Volatile
    private var stopLatch: CountDownLatch? = null

    @Volatile
    private var isRunning = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP_VPN -> shutdownService()
                ACTION_REFRESH_RULES -> refreshVpnRulesAndTunnel()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        tunnelManager = VpnTunnelManager(this)
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager

        val filter = IntentFilter().apply {
            addAction(ACTION_STOP_VPN)
            addAction(ACTION_REFRESH_RULES)
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
        registerNetworkCallback()

        Log.d(TAG, "VPN service created with native network monitoring")
    }

    private fun registerNetworkCallback() {
        try {
            val cm = connectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network handoff available: $network")
                    if (isRunning) {
                        reestablishTunnelWithBackoff()
                    }
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Network connection lost: $network")
                }
            }

            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun reestablishTunnelWithBackoff() {
        Thread {
            var attempt = 0
            var delayMs = 500L
            val maxAttempts = 5

            while (isRunning && attempt < maxAttempts) {
                try {
                    Thread.sleep(delayMs)
                    Log.d(TAG, "Re-establishing VPN tunnel (attempt ${attempt + 1})...")

                    val newPfd = tunnelManager?.startTunnelFileDescriptor()
                    if (newPfd != null) {
                        val oldPfd = vpnInterface
                        vpnInterface = newPfd

                        dnsPacketEngine?.stop()

                        val inStream = FileInputStream(newPfd.fileDescriptor)
                        val outStream = FileOutputStream(newPfd.fileDescriptor)

                        val cm = connectivityManager ?: (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                        val engine = DnsPacketEngine(this, inStream, outStream, cm)
                        loadDatabaseSettingsIntoEngine(engine)
                        engine.start()
                        dnsPacketEngine = engine

                        try { oldPfd?.close() } catch (_: Exception) {}
                        Log.i(TAG, "VPN tunnel cleanly re-established")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Tunnel re-establishment attempt ${attempt + 1} failed", e)
                }
                attempt++
                delayMs *= 2
            }
        }.start()
    }

    private fun refreshVpnRulesAndTunnel() {
        Thread {
            try {
                val engine = dnsPacketEngine
                if (engine != null) {
                    engine.clearCache()
                    loadDatabaseSettingsIntoEngine(engine)
                    Log.d(TAG, "Refreshed DNS & Firewall engine rules in real-time")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh VPN engine rules", e)
            }
        }.start()
    }

    private fun loadDatabaseSettingsIntoEngine(engine: DnsPacketEngine) {
        try {
            val db = NetShieldDatabase.getDatabase(applicationContext)
            val dao = db.netShieldDao()
            engine.isAdsBlocked = dao.getGlobalSettingSync("ads_blocked")?.value ?: false
            engine.isAdultBlocked = dao.getGlobalSettingSync("adult_blocked")?.value ?: false

            val cm = connectivityManager ?: (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
            val firewall = engine.firewallEngine ?: FirewallEngine(cm, packageManager)

            val appRules = dao.getAllAppRulesSync()
            val blockedPackages = appRules.filter { it.isInternetBlocked }.map { it.packageName }.toSet()
            firewall.setBlockedPackages(blockedPackages)

            val customDomains = dao.getAllCustomDomainsSync().toSet()
            NetShieldBlocklist.setCustomDomainRules(customDomains)

            engine.firewallEngine = firewall
            Log.d(TAG, "Loaded database settings (${blockedPackages.size} net-blocked apps, ${customDomains.size} custom blocked domains)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load database settings into engine", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildStatusNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildStatusNotification())
        }

        if (isRunning) {
            Log.d(TAG, "VPN already running")
            return START_STICKY
        }

        isRunning = true

        Thread({
            startVpnEngine()
        }, "NetShieldVpnEngineThread").start()

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App task swiped away. Ensuring Always-On VPN service persists...")
        if (isRunning) {
            val restartIntent = Intent(applicationContext, NetShieldVpnService::class.java)
            applicationContext.startForegroundService(restartIntent)
        }
    }

    private fun recordVpnActiveStartTime(startTimeMs: Long) {
        Thread {
            try {
                val db = NetShieldDatabase.getDatabase(applicationContext)
                val dao = db.netShieldDao()
                val currentStat = dao.getBlockedStatSync()
                runBlocking {
                    dao.saveBlockedStat(
                        BlockedStatEntity(
                            id = 1,
                            totalBlockedCount = currentStat?.totalBlockedCount ?: 0L,
                            recent25MinCount = currentStat?.recent25MinCount ?: 0L,
                            vpnStartTimeMs = startTimeMs
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update VPN start time in DB: ${e.message}")
            }
        }.start()
    }

    private fun startVpnEngine() {
        try {
            Log.i(TAG, "Starting Native NetShield DNS & Firewall Engine")

            val pfd = tunnelManager?.startTunnelFileDescriptor()
            if (pfd == null) {
                Log.e(TAG, "VPN interface could not be established")
                shutdownService()
                return
            }

            vpnInterface = pfd

            val inStream = FileInputStream(pfd.fileDescriptor)
            val outStream = FileOutputStream(pfd.fileDescriptor)
            val cm = connectivityManager ?: (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)

            val engine = DnsPacketEngine(this, inStream, outStream, cm)
            loadDatabaseSettingsIntoEngine(engine)
            engine.start()
            dnsPacketEngine = engine

            recordVpnActiveStartTime(System.currentTimeMillis())

            Log.i(TAG, "NetShield Native DNS & Firewall Engine running")

            stopLatch = CountDownLatch(1)
            stopLatch?.await()

        } catch (e: InterruptedException) {
            Log.d(TAG, "VPN engine interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "VPN engine failed", e)
        } finally {
            if (isRunning) {
                shutdownService()
            }
        }
    }

    private fun shutdownService() {
        if (!isRunning) return
        Log.i(TAG, "Stopping NetShield VPN")

        isRunning = false

        recordVpnActiveStartTime(0L)

        stopLatch?.countDown()
        stopLatch = null

        dnsPacketEngine?.stop()
        dnsPacketEngine = null

        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        try { tunnelManager?.stopTunnel() } catch (_: Exception) {}

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false

        recordVpnActiveStartTime(0L)

        stopLatch?.countDown()
        stopLatch = null

        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
        } catch (_: Exception) {}

        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}

        dnsPacketEngine?.stop()
        dnsPacketEngine = null

        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        super.onDestroy()
    }

    private fun buildStatusNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("NetShield Always-On Active")
            .setContentText("NetShield protection is active and running in the background.")
            .setSmallIcon(R.drawable.ic_secure)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "NetShield Security Service Status",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
