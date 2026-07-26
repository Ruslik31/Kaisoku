package org.koitharu.kotatsu.core.exceptions.resolve

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptchaResolveRouteTest {

	@Test
	fun `an explicit solve with auto-solve disabled shows the solver`() {
		// The regression: callers used to pass tryAutoResolve = true here, the coordinator refused
		// the disabled source, and the refusal was reported as "unresolved" without ever showing
		// anything — the "Solve" button did nothing at all.
		assertEquals(
			CaptchaResolveRoute.MANUAL,
			captchaResolveRoute(isResolveActive = false, tryAutoResolve = true, isAutoResolveDisabled = true),
		)
	}

	@Test
	fun `an explicit solve request is never handled silently`() {
		assertEquals(
			CaptchaResolveRoute.MANUAL,
			captchaResolveRoute(isResolveActive = false, tryAutoResolve = false, isAutoResolveDisabled = false),
		)
	}

	@Test
	fun `a background error may be resolved silently`() {
		assertEquals(
			CaptchaResolveRoute.AUTOMATIC,
			captchaResolveRoute(isResolveActive = false, tryAutoResolve = true, isAutoResolveDisabled = false),
		)
	}

	@Test
	fun `a running solver is awaited instead of starting another`() {
		for (tryAutoResolve in listOf(true, false)) {
			for (isDisabled in listOf(true, false)) {
				assertEquals(
					CaptchaResolveRoute.AWAIT_ACTIVE,
					captchaResolveRoute(
						isResolveActive = true,
						tryAutoResolve = tryAutoResolve,
						isAutoResolveDisabled = isDisabled,
					),
				)
			}
		}
	}
}
