package io.netshield.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "global_settings")
data class GlobalSettingEntity(
    @PrimaryKey val key: String,
    val value: Boolean
)
