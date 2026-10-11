package io.netshield.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_domains")
data class CustomDomainEntity(
    @PrimaryKey val domain: String
)
