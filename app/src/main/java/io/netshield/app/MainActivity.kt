package io.netshield.app

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.netshield.app.presentation.dashboard.MainAppScreen
import io.netshield.app.presentation.viewmodel.AppListViewModel
import io.netshield.app.ui.theme.NetShieldTheme
import io.netshield.app.vpn.NetShieldVpnService
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAndPingCountApiOnFirstLaunch()
        setContent {
            NetShieldTheme {
                val appViewModel: AppListViewModel = viewModel()
                val isNetworkAdsBlocked by appViewModel.isNetworkAdsBlocked.collectAsState()
                val isAdultContentBlocked by appViewModel.isAdultContentBlocked.collectAsState()

                val vpnPrepareLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == RESULT_OK) {
                        startNetShieldVpnService()
                        appViewModel.toggleVpn(true)
                    } else {
                        appViewModel.toggleAdBlocking(false)
                        appViewModel.toggleAdultContentBlocking(false)
                        appViewModel.toggleVpn(false)
                    }
                }
                LaunchedEffect(isNetworkAdsBlocked, isAdultContentBlocked) {
                    if (isNetworkAdsBlocked || isAdultContentBlocked) {
                        val intent = VpnService.prepare(this@MainActivity)
                        if (intent != null) {
                            vpnPrepareLauncher.launch(intent)
                        } else {
                            startNetShieldVpnService()
                            appViewModel.toggleVpn(true)
                        }
                    } else {
                        stopNetShieldVpnService()
                        appViewModel.toggleVpn(false)
                    }
                }
                MainAppScreen(viewModel = appViewModel)
            }
        }
    }

    private fun checkAndPingCountApiOnFirstLaunch() {
        val prefs = getSharedPreferences("netshield_install_prefs", MODE_PRIVATE)
        val isFirstLaunchCounted = prefs.getBoolean("is_first_launch_counted", false)
        if (!isFirstLaunchCounted) {
            Thread {
                try {
                    val url = URL("https://api.countapi.xyz/hit/io_netshield_app/installs")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    if (conn.responseCode == 200) {
                        prefs.edit().putBoolean("is_first_launch_counted", true).apply()
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
            }.start()
        }
    }

    private fun startNetShieldVpnService() {
        val intent = Intent(this, NetShieldVpnService::class.java)
        startService(intent)
    }

    private fun stopNetShieldVpnService() {
        val intent = Intent(NetShieldVpnService.ACTION_STOP_VPN)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
