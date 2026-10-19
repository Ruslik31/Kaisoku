package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.backups.data.model.FavoriteOrderBackup
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.favourites.data.FavoriteOrderEntity

class FavoriteOrderTest {
    @Test fun editRevisionsHaveDeterministicConflictOrdering() {
        val saved = FavoriteOrderEntity(1, 2, 5, 100, "a")
        assertFalse(saved.copy(rank = 0, updatedAt = 99, revision = "z").isNewerThan(saved))
        assertTrue(saved.copy(rank = 0, updatedAt = 101).isNewerThan(saved))
        assertTrue(saved.copy(revision = "b").isNewerThan(saved))
        assertFalse(saved.isNewerThan(saved))
    }
    @Test fun typedBackupRetainsGlobalScopeAndCanRemapRealCategory() {
        val manga = MangaBackup(1, "Fixture", url = "/1", publicUrl = "https://example.org/1", coverUrl = "", source = "TEST")
        val backup = FavoriteOrderBackup(1, 0, 4, 100, "uuid", manga)
        assertEquals(FavoriteOrderEntity(1, 0, 4, 100, "uuid"), backup.toEntity())
        assertEquals(75L, backup.copy(scopeId = 2).toEntity(75).scopeId)
    }
    @Test fun malformedBackupCannotOrderAnUnrelatedTitle() {
        val manga = MangaBackup(2, "Other", url = "/2", publicUrl = "https://example.org/2", coverUrl = "", source = "TEST")
        try { FavoriteOrderBackup(1, 0, 0, 100, "uuid", manga).toEntity(); fail("Mismatching IDs must fail") }
        catch (_: IllegalArgumentException) { }
    }
}
