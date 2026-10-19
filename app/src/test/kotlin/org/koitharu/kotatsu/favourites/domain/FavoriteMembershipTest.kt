package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.favourites.data.FavouriteEntity

class FavoriteMembershipTest {
    @Test fun repeatedAdditionDoesNotRewriteDateRankOrPin() {
        val saved = FavouriteEntity(1, 2, 53, true, 100, 0)
        assertSame(saved, favoriteMembership(saved, 1, 2, 9000))
    }
    @Test fun recoveryPreservesMembershipMetadata() {
        val saved = FavouriteEntity(1, 2, 53, true, 100, 200)
        assertEquals(saved.copy(deletedAt = 0), favoriteMembership(saved, 1, 2, 9000))
    }
    @Test fun newMembershipUsesItsOwnCategoryAndInsertionDate() {
        assertEquals(FavouriteEntity(5, 8, 0, false, 900, 0), favoriteMembership(null, 5, 8, 900))
    }
}
