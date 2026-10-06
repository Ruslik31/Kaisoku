package org.koitharu.kotatsu.scrobbling.common.data

import androidx.room.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

@Dao
abstract class ScrobblingDao {

    @Query("SELECT * FROM scrobblings WHERE manga_id = :mangaId")
    abstract suspend fun findForManga(mangaId: Long): List<ScrobblingEntity>

    @Query("SELECT * FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId AND (:accountId IS NULL OR account_id = :accountId)")
    abstract suspend fun find(scrobbler: Int, mangaId: Long, accountId: Long? = null): ScrobblingEntity?

    @Query("SELECT manga_id FROM scrobblings WHERE scrobbler = :scrobbler AND target_id = :targetId AND manga_id != :mangaId AND account_id = :accountId LIMIT 1")
    abstract suspend fun findMangaId(scrobbler: Int, targetId: Long, mangaId: Long, accountId: Long): Long?

    @Query("SELECT manga_id FROM scrobblings WHERE scrobbler = :scrobbler AND target_id = :targetId AND (:accountId IS NULL OR account_id = :accountId) LIMIT 1")
    abstract suspend fun findMangaIdByTarget(scrobbler: Int, targetId: Long, accountId: Long? = null): Long?

    @Query("SELECT * FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId AND (:accountId IS NULL OR account_id = :accountId)")
    abstract fun observe(scrobbler: Int, mangaId: Long, accountId: Long? = null): Flow<ScrobblingEntity?>

    @Query("SELECT * FROM scrobblings WHERE scrobbler = :scrobbler AND (:accountId IS NULL OR account_id = :accountId)")
    abstract fun observeAll(scrobbler: Int, accountId: Long? = null): Flow<List<ScrobblingEntity>>

    @Query("SELECT * FROM scrobblings WHERE scrobbler = :service AND target_id = :target AND account_id = :account")
    abstract suspend fun findAllByTarget(service: Int, target: Long, account: Long): List<ScrobblingEntity>

    @Transaction
    open suspend fun replace(entity: ScrobblingEntity) {
        delete(entity.scrobbler, entity.mangaId, entity.accountId)
        upsert(entity)
    }

    @Transaction
    open suspend fun updateLinked(entity: ScrobblingEntity) {
        val current = find(entity.scrobbler, entity.mangaId, entity.accountId)
        if (current?.targetId == entity.targetId && current.id == entity.id) upsert(entity)
    }

    @Transaction
    open suspend fun refreshLinked(entity: ScrobblingEntity, expectedRateId: Int) {
        val current = find(entity.scrobbler, entity.mangaId, entity.accountId)
        if (current?.targetId == entity.targetId && current.id == expectedRateId) replace(entity)
    }

    @Upsert
    abstract suspend fun upsert(entity: ScrobblingEntity)

    @Query("DELETE FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId AND (:accountId IS NULL OR account_id = :accountId)")
    abstract suspend fun delete(scrobbler: Int, mangaId: Long, accountId: Long? = null)

    @Query("SELECT * FROM scrobblings ORDER BY scrobbler LIMIT :limit OFFSET :offset")
    protected abstract suspend fun findAll(offset: Int, limit: Int): List<ScrobblingEntity>

    fun dumpEnabled(): Flow<ScrobblingEntity> = flow {
        val window = 10
        var offset = 0
        while (currentCoroutineContext().isActive) {
            val list = findAll(offset, window)
            if (list.isEmpty()) {
                break
            }
            offset += window
            list.forEach { emit(it) }
        }
    }
}
