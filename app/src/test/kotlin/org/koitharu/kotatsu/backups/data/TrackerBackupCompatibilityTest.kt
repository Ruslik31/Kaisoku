package org.koitharu.kotatsu.backups.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.backups.data.model.ScrobblingBackup
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity

class TrackerBackupCompatibilityTest {
    @Test fun oldBackupsKeepTheirIdentityAndRequireOwnershipConfirmation() {
        val legacy = Json.decodeFromString<ScrobblingBackup>("""{"scrobbler":2,"id":12,"manga_id":-88,"target_id":15,
            "status":"CURRENT","chapter":7,"comment":null,"rating":0.8}""")
        assertEquals(0L, legacy.accountId); assertEquals(-88L, legacy.toEntity().mangaId)
    }
    @Test fun newBackupsRoundTripSeparateAccountsWithoutChangingLocalTitleIds() {
        for (account in listOf(1L, 2L)) {
            val entity = ScrobblingEntity(2, 12, -88, 15, "CURRENT", 7, "Notes", 0.8f, account)
            val encoded = Json.encodeToString(ScrobblingBackup.serializer(), ScrobblingBackup(entity))
            val restored = Json.decodeFromString<ScrobblingBackup>(encoded).toEntity()
            assertEquals(entity.accountId, restored.accountId); assertEquals(entity.mangaId, restored.mangaId)
            assertEquals(entity.targetId, restored.targetId); assertEquals(entity.chapter, restored.chapter)
            assertEquals(entity.comment, restored.comment)
        }
    }
}
