package org.koitharu.kotatsu.core.parser.mihon.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MihonExtensionRepoStoreSeedTest {

	private val other = MihonExtensionRepo(
		baseUrl = "https://repo.example",
		name = "Example",
		website = "",
		signingKeyFingerprint = "abc",
	)

	@Test
	fun addsKeiyoushiToAnEmptyList() {
		assertEquals(listOf(MihonExtensionRepoStore.KEIYOUSHI_REPO), MihonExtensionRepoStore.withDefaults(emptyList()))
	}

	@Test
	fun keepsExistingRepositoriesFirst() {
		assertEquals(
			listOf(other, MihonExtensionRepoStore.KEIYOUSHI_REPO),
			MihonExtensionRepoStore.withDefaults(listOf(other)),
		)
	}

	@Test
	fun skipsWhenAlreadyPresentByUrlOrSigner() {
		assertNull(MihonExtensionRepoStore.withDefaults(listOf(MihonExtensionRepoStore.KEIYOUSHI_REPO)))
		val sameSigner = other.copy(
			signingKeyFingerprint = MihonExtensionRepoStore.KEIYOUSHI_REPO.signingKeyFingerprint,
		)
		assertNull(MihonExtensionRepoStore.withDefaults(listOf(sameSigner)))
	}

	@Test
	fun seededRepositoryMatchesTheResolvedStoreIndex() {
		val repo = MihonExtensionRepoStore.KEIYOUSHI_REPO
		assertEquals("https://raw.githubusercontent.com/keiyoushi/extensions/repo", repo.baseUrl)
		assertEquals(MihonExtensionRepoStore.KEIYOUSHI_INDEX_URL, repo.indexUrl)
		assertEquals(true, repo.isStoreFormat)
	}
}
