package io.netshield.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_stats")
data class BlockedStatEntity(
    @PrimaryKey val id: Int = 1,
    val totalBlockedCount: Long = 0L,
    val recent25MinCount: Long = 0L,
    val vpnStartTimeMs: Long = 0L
)
