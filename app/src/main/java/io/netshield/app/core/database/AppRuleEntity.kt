package io.netshield.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_rules")
data class AppRuleEntity(
    @PrimaryKey val packageName: String,
    val isAdBlockEnabled: Boolean,
    val isInternetBlocked: Boolean
)
