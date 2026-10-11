package io.netshield.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NetShieldDao {
    // Master settings queries
    @Query("SELECT value FROM global_settings WHERE `key` = :settingKey LIMIT 1")
    fun getGlobalSettingFlow(settingKey: String): Flow<Boolean?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGlobalSetting(setting: GlobalSettingEntity)

    // App rules queries
    @Query("SELECT * FROM app_rules")
    fun getAllAppRulesFlow(): Flow<List<AppRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAppRule(rule: AppRuleEntity)

    @Query("SELECT * FROM global_settings WHERE `key` = :settingKey LIMIT 1")
    fun getGlobalSettingSync(settingKey: String): GlobalSettingEntity?

    @Query("SELECT * FROM app_rules WHERE packageName = :packageName LIMIT 1")
    fun getAppRuleSync(packageName: String): AppRuleEntity?

    @Query("SELECT * FROM app_rules")
    fun getAllAppRulesSync(): List<AppRuleEntity>

    // Blocked stats queries
    @Query("SELECT totalBlockedCount FROM blocked_stats WHERE id = 1 LIMIT 1")
    fun getBlockedCountFlow(): Flow<Long?>

    @Query("SELECT recent25MinCount FROM blocked_stats WHERE id = 1 LIMIT 1")
    fun getRecent25MinCountFlow(): Flow<Long?>

    @Query("SELECT vpnStartTimeMs FROM blocked_stats WHERE id = 1 LIMIT 1")
    fun getVpnStartTimeFlow(): Flow<Long?>

    @Query("SELECT * FROM blocked_stats WHERE id = 1 LIMIT 1")
    fun getBlockedStatSync(): BlockedStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBlockedStat(stat: BlockedStatEntity)

    // Custom domain rules queries
    @Query("SELECT domain FROM custom_domains")
    fun getAllCustomDomainsFlow(): Flow<List<String>>

    @Query("SELECT domain FROM custom_domains")
    fun getAllCustomDomainsSync(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCustomDomain(domainEntity: CustomDomainEntity)

    @Query("DELETE FROM custom_domains WHERE domain = :domain")
    suspend fun deleteCustomDomain(domain: String)
}
