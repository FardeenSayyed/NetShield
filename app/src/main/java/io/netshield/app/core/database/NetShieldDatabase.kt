package io.netshield.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        GlobalSettingEntity::class,
        AppRuleEntity::class,
        BlockedStatEntity::class,
        CustomDomainEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class NetShieldDatabase : RoomDatabase() {
    abstract fun netShieldDao(): NetShieldDao

    companion object {
        @Volatile
        private var INSTANCE: NetShieldDatabase? = null

        fun getDatabase(context: Context): NetShieldDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NetShieldDatabase::class.java,
                    "netshield_secure_vault.db"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
