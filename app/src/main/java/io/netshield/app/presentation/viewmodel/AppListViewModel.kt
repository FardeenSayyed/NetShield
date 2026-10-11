package io.netshield.app.presentation.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.netshield.app.core.database.AppRuleEntity
import io.netshield.app.core.database.CustomDomainEntity
import io.netshield.app.core.database.GlobalSettingEntity
import io.netshield.app.core.database.NetShieldDatabase
import io.netshield.app.vpn.NetShieldBlocklist
import io.netshield.app.vpn.NetShieldVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    var isAdBlockEnabled: Boolean = false,
    var isInternetBlocked: Boolean = false
)

class AppListViewModel(application: Application) : AndroidViewModel(application) {

    private val database = NetShieldDatabase.getDatabase(application)
    private val dao = database.netShieldDao()

    private val _isVpnActive = MutableStateFlow(false)
    val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    private val _isNetworkAdsBlocked = MutableStateFlow(false)
    val isNetworkAdsBlocked: StateFlow<Boolean> = _isNetworkAdsBlocked.asStateFlow()

    private val _isAdultContentBlocked = MutableStateFlow(false)
    val isAdultContentBlocked: StateFlow<Boolean> = _isAdultContentBlocked.asStateFlow()

    private val _blockedCount = MutableStateFlow(0L)
    val blockedCount: StateFlow<Long> = _blockedCount.asStateFlow()

    private val _recent25MinCount = MutableStateFlow(0L)
    val recent25MinCount: StateFlow<Long> = _recent25MinCount.asStateFlow()

    private val _vpnStartTimeMs = MutableStateFlow(0L)
    private val _activeDurationText = MutableStateFlow("")
    val activeDurationText: StateFlow<String> = _activeDurationText.asStateFlow()

    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList: StateFlow<List<AppInfo>> = _appList.asStateFlow()

    private val _alwaysBlockedDomains = MutableStateFlow<Set<String>>(emptySet())
    val alwaysBlockedDomains: StateFlow<Set<String>> = _alwaysBlockedDomains.asStateFlow()

    private var cachedDbRules = mapOf<String, AppRuleEntity>()

    init {
        // Observe saved master states across app lifecycle boundaries
        viewModelScope.launch {
            dao.getGlobalSettingFlow("ads_blocked").collectLatest { _isNetworkAdsBlocked.value = it ?: false }
        }
        viewModelScope.launch {
            dao.getGlobalSettingFlow("adult_blocked").collectLatest { _isAdultContentBlocked.value = it ?: false }
        }
        viewModelScope.launch {
            dao.getGlobalSettingFlow("vpn_active").collectLatest { _isVpnActive.value = it ?: false }
        }
        viewModelScope.launch {
            dao.getBlockedCountFlow().collectLatest { _blockedCount.value = it ?: 0L }
        }
        viewModelScope.launch {
            dao.getRecent25MinCountFlow().collectLatest { _recent25MinCount.value = it ?: 0L }
        }
        viewModelScope.launch {
            dao.getVpnStartTimeFlow().collectLatest { startTime ->
                _vpnStartTimeMs.value = startTime ?: 0L
                updateActiveDurationText()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(10_000L) // Periodic 10-second active timer refresh
                updateActiveDurationText()
            }
        }
        // Observe saved custom domain rules from Room DB
        viewModelScope.launch {
            dao.getAllCustomDomainsFlow().collectLatest { domainList ->
                val domainSet = domainList.toSet()
                _alwaysBlockedDomains.value = domainSet
                NetShieldBlocklist.setCustomDomainRules(domainSet)
            }
        }
        // Observe saved individual application permissions maps
        viewModelScope.launch {
            dao.getAllAppRulesFlow().collectLatest { rulesList ->
                cachedDbRules = rulesList.associateBy { it.packageName }
                // Re-apply values dynamically if the app list is already loaded
                if (_appList.value.isNotEmpty()) {
                    _appList.value = _appList.value.map { app ->
                        val savedRule = cachedDbRules[app.packageName]
                        app.copy(
                            isAdBlockEnabled = savedRule?.isAdBlockEnabled ?: false,
                            isInternetBlocked = savedRule?.isInternetBlocked ?: false
                        )
                    }
                }
            }
        }
    }

    private fun updateActiveDurationText() {
        val start = _vpnStartTimeMs.value
        if (start <= 0L) {
            _activeDurationText.value = ""
            return
        }
        val elapsedMs = System.currentTimeMillis() - start
        if (elapsedMs < 0L) {
            _activeDurationText.value = "< 1 min"
            return
        }
        val totalMins = elapsedMs / (1000 * 60)
        val hrs = totalMins / 60
        val mins = totalMins % 60

        _activeDurationText.value = when {
            totalMins < 1 -> "< 1 min"
            hrs == 0L -> "$mins mins"
            mins == 0L -> "$hrs hr"
            else -> "$hrs hr $mins mins"
        }
    }

    fun toggleVpn(active: Boolean) {
        _isVpnActive.value = active
        viewModelScope.launch(Dispatchers.IO) { dao.saveGlobalSetting(GlobalSettingEntity("vpn_active", active)) }
    }

    fun toggleAdBlocking(enabled: Boolean) {
        _isNetworkAdsBlocked.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            dao.saveGlobalSetting(GlobalSettingEntity("ads_blocked", enabled))
            notifyVpnRulesChanged()
        }
    }

    fun toggleAdultContentBlocking(enabled: Boolean) {
        _isAdultContentBlocked.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            dao.saveGlobalSetting(GlobalSettingEntity("adult_blocked", enabled))
            notifyVpnRulesChanged()
        }
    }

    fun setAdBlock(packageName: String, enabled: Boolean) {
        _appList.value = _appList.value.map {
            if (it.packageName == packageName) it.copy(isAdBlockEnabled = enabled) else it
        }
        saveAppRuleToDisk(packageName)
    }

    fun setInternetBlocked(packageName: String, blocked: Boolean) {
        _appList.value = _appList.value.map {
            if (it.packageName == packageName) {
                it.copy(isInternetBlocked = blocked, isAdBlockEnabled = if (blocked) false else it.isAdBlockEnabled)
            } else it
        }
        saveAppRuleToDisk(packageName)
    }

    private fun saveAppRuleToDisk(packageName: String) {
        val app = _appList.value.firstOrNull { it.packageName == packageName } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            dao.saveAppRule(AppRuleEntity(app.packageName, app.isAdBlockEnabled, app.isInternetBlocked))
            notifyVpnRulesChanged()
        }
    }

    private fun notifyVpnRulesChanged() {
        LocalBroadcastManager.getInstance(getApplication())
            .sendBroadcast(Intent(NetShieldVpnService.ACTION_REFRESH_RULES))
    }

    fun restartVpn() {
        notifyVpnRulesChanged()
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveInfoList = pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))

            val apps = resolveInfoList
                .mapNotNull { resolveInfo ->
                    val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                    val packageName = activityInfo.packageName
                    if (packageName == getApplication<Application>().packageName) return@mapNotNull null

                    try {
                        // Merge loaded package structures with database persistence rules from cachedDbRules map
                        val savedRule = cachedDbRules[packageName]
                        AppInfo(
                            name = activityInfo.loadLabel(pm).toString(),
                            packageName = packageName,
                            icon = activityInfo.loadIcon(pm),
                            isAdBlockEnabled = savedRule?.isAdBlockEnabled ?: false,
                            isInternetBlocked = savedRule?.isInternetBlocked ?: false
                        )
                    } catch (_: Exception) { null }
                }
                .distinctBy { it.packageName }
                .sortedBy { it.name.lowercase() }

            _appList.value = apps
        }
    }

    fun addAlwaysBlockedDomain(domain: String) {
        val trimmed = domain.trim().lowercase()
        if (trimmed.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                dao.saveCustomDomain(CustomDomainEntity(trimmed))
                notifyVpnRulesChanged()
            }
        }
    }

    fun removeAlwaysBlockedDomain(domain: String) {
        val trimmed = domain.trim().lowercase()
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteCustomDomain(trimmed)
            notifyVpnRulesChanged()
        }
    }

    fun getAdBlockPackagesRaw(): String = _appList.value.filter { it.isAdBlockEnabled }.joinToString("\n") { it.packageName }.ifBlank { "None" }
    fun getInternetBlockedPackagesRaw(): String = _appList.value.filter { it.isInternetBlocked }.joinToString("\n") { it.packageName }.ifBlank { "None" }
}
