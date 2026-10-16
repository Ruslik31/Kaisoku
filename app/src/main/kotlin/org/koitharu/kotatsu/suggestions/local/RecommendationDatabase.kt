package org.koitharu.kotatsu.suggestions.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "candidates", indices = [Index("touched")])
data class RecommendationCandidateEntity(@PrimaryKey val key: String, val payload: String, val touched: Long, val sourceVersion: String)

@Entity(tableName = "results")
data class RecommendationResultEntity(@PrimaryKey val context: String, val payload: String, val refreshed: Long, val scope: String, val error: String?)

@Entity(tableName = "dismissals")
data class RecommendationDismissalEntity(@PrimaryKey val key: String, val dismissedAt: Long)

@Entity(tableName = "baselines")
data class RecommendationBaselineEntity(@PrimaryKey val key: String, val payload: String)

@Entity(tableName = "provider_links", primaryKeys = ["key", "provider"])
data class RecommendationProviderLink(val key: String, val provider: String, val identifier: String, val kind: String)

@Entity(tableName = "cooldowns")
data class RecommendationCooldown(@PrimaryKey val operation: String, val until: Long)

@Dao
interface RecommendationDao {
    @Query("SELECT * FROM candidates ORDER BY touched DESC LIMIT 2000")
    suspend fun candidates(): List<RecommendationCandidateEntity>
    @Query("SELECT * FROM candidates WHERE key IN (:keys)")
    suspend fun candidates(keys: List<String>): List<RecommendationCandidateEntity>
    @Upsert suspend fun saveCandidates(rows: List<RecommendationCandidateEntity>)
    @Query("DELETE FROM candidates WHERE key NOT IN (SELECT key FROM candidates ORDER BY touched DESC, key LIMIT 2000)")
    suspend fun trim()
    @Query("SELECT * FROM results WHERE context = :context") fun observe(context: String): Flow<RecommendationResultEntity?>
    @Query("SELECT * FROM results WHERE context = :context") suspend fun result(context: String): RecommendationResultEntity?
    @Upsert suspend fun result(row: RecommendationResultEntity)
    @Query("SELECT key FROM dismissals") fun dismissals(): Flow<List<String>>
    @Query("SELECT key FROM dismissals") suspend fun dismissed(): List<String>
    @Upsert suspend fun dismiss(row: RecommendationDismissalEntity)
    @Query("DELETE FROM dismissals WHERE key = :key") suspend fun undo(key: String)
    @Query("DELETE FROM dismissals") suspend fun clearDismissals()
    @Query("SELECT * FROM baselines WHERE key = :key") suspend fun baseline(key: String): RecommendationBaselineEntity?
    @Query("SELECT * FROM baselines") suspend fun baselines(): List<RecommendationBaselineEntity>
    @Upsert suspend fun baseline(row: RecommendationBaselineEntity)
    @Query("SELECT * FROM provider_links WHERE key = :key") suspend fun links(key: String): List<RecommendationProviderLink>
    @Query("SELECT * FROM provider_links") suspend fun links(): List<RecommendationProviderLink>
    @Upsert suspend fun link(row: RecommendationProviderLink)
    @Query("SELECT * FROM cooldowns WHERE operation = :operation") suspend fun cooldown(operation: String): RecommendationCooldown?
    @Upsert suspend fun cooldown(row: RecommendationCooldown)
    @Query("DELETE FROM cooldowns WHERE until <= :now") suspend fun trimCooldowns(now: Long)
    @Query("DELETE FROM results WHERE context NOT IN (SELECT context FROM results ORDER BY refreshed DESC, context LIMIT 40)")
    suspend fun trimResults()
}

@Database(entities = [RecommendationCandidateEntity::class, RecommendationResultEntity::class,
    RecommendationDismissalEntity::class, RecommendationBaselineEntity::class, RecommendationProviderLink::class,
    RecommendationCooldown::class], version = 1, exportSchema = true)
abstract class RecommendationDatabase : RoomDatabase() {
    abstract fun dao(): RecommendationDao
}
