package org.koitharu.kotatsu.scrobbling.common.data

import android.content.SharedPreferences
import org.json.JSONObject

/** Pending progress is keyed by account and local title, and carries the exact confirmed link. */
data class PendingTrackerProgress(
    val accountId: Long,
    val mangaId: Long,
    val targetId: Long,
    val rateId: Int,
    val chapter: Int,
    val revision: Long,
) {
    fun toJson() = JSONObject().put("account", accountId).put("manga", mangaId).put("target", targetId)
        .put("rate", rateId).put("chapter", chapter).put("revision", revision)

    companion object {
        fun fromJson(json: JSONObject) = PendingTrackerProgress(json.getLong("account"), json.getLong("manga"),
            json.getLong("target"), json.getInt("rate"), json.getInt("chapter"), json.getLong("revision"))
    }
}

internal fun coalesceTrackerProgress(previous: PendingTrackerProgress?, next: PendingTrackerProgress): PendingTrackerProgress =
    if (previous != null && previous.accountId == next.accountId && previous.mangaId == next.mangaId &&
        previous.targetId == next.targetId && previous.rateId == next.rateId) {
        next.copy(chapter = maxOf(previous.chapter, next.chapter), revision = previous.revision + 1)
    } else next

class TrackerProgressStore(private val prefs: SharedPreferences) {
    private fun key(account: Long, manga: Long) = "pending_${account}_$manga"

    @Synchronized
    fun read(account: Long, manga: Long): PendingTrackerProgress? =
        prefs.getString(key(account, manga), null)?.let { runCatching { PendingTrackerProgress.fromJson(JSONObject(it)) }.getOrNull() }

    @Synchronized
    fun enqueue(next: PendingTrackerProgress): Boolean {
        val previous = read(next.accountId, next.mangaId)
        if (previous?.targetId == next.targetId && previous.rateId == next.rateId && previous.chapter >= next.chapter) return false
        val receipt = prefs.getString("sent_${next.accountId}_${next.mangaId}", null)?.let {
            runCatching { PendingTrackerProgress.fromJson(JSONObject(it)) }.getOrNull()
        }
        if (receipt?.targetId == next.targetId && receipt.rateId == next.rateId && receipt.chapter >= next.chapter) return false
        val merged = coalesceTrackerProgress(previous, next)
        check(prefs.edit().putString(key(next.accountId, next.mangaId), merged.toJson().toString()).commit())
        return true
    }

    @Synchronized
    fun acknowledge(sent: PendingTrackerProgress) {
        val current = read(sent.accountId, sent.mangaId)
        val editor = prefs.edit().putString("sent_${sent.accountId}_${sent.mangaId}", sent.toJson().toString())
        if (current == sent) editor.remove(key(sent.accountId, sent.mangaId))
        editor.remove("error_${sent.accountId}").apply()
    }

    @Synchronized
    fun fail(sent: PendingTrackerProgress, message: String) {
        if (read(sent.accountId, sent.mangaId) != sent) return
        prefs.edit().remove(key(sent.accountId, sent.mangaId)).putString("error_${sent.accountId}", message.take(500)).apply()
    }

    @Synchronized
    fun discard(sent: PendingTrackerProgress) {
        if (read(sent.accountId, sent.mangaId) == sent) {
            prefs.edit().remove(key(sent.accountId, sent.mangaId)).apply()
        }
    }

    @Synchronized
    fun invalidate(account: Long, manga: Long, manual: Boolean = false) {
        prefs.edit().remove(key(account, manga)).remove("sent_${account}_$manga").apply()
        if (manual) prefs.edit().putBoolean("manual_${account}_$manga", true).apply()
    }

    fun wasManuallyMatched(account: Long, manga: Long) = prefs.getBoolean("manual_${account}_$manga", false)
    fun all(account: Long): List<PendingTrackerProgress> = prefs.all.filterKeys { it.startsWith("pending_${account}_") }
        .values.mapNotNull { value -> (value as? String)?.let { runCatching { PendingTrackerProgress.fromJson(JSONObject(it)) }.getOrNull() } }
        .filter { it.accountId == account }

    fun pendingCount(account: Long) = prefs.all.keys.count { it.startsWith("pending_${account}_") }
    fun error(account: Long) = prefs.getString("error_$account", null)
    fun clearPending() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("pending_") || it.startsWith("error_") }.forEach(editor::remove)
        editor.apply()
    }
}
