package org.koitharu.kotatsu.core.exceptions.resolve

/** How a Cloudflare challenge should be handled. */
enum class CaptchaResolveRoute {

	/** A solver is already running for this source; wait for its result instead of starting another. */
	AWAIT_ACTIVE,

	/** Hand it to the coordinator, which may solve it silently. */
	AUTOMATIC,

	/** Show the solver to the user. */
	MANUAL,
}

/**
 * Picks the route for a challenge.
 *
 * [AUTOMATIC][CaptchaResolveRoute.AUTOMATIC] requires *both* that the caller allows a silent attempt
 * and that the source permits one. Ignoring the preference here strands the challenge: the
 * coordinator refuses a disabled source, and the refusal reads as "not resolved" rather than falling
 * through, so the user's "Solve" press does nothing at all.
 */
fun captchaResolveRoute(
	isResolveActive: Boolean,
	tryAutoResolve: Boolean,
	isAutoResolveDisabled: Boolean,
): CaptchaResolveRoute = when {
	isResolveActive -> CaptchaResolveRoute.AWAIT_ACTIVE
	tryAutoResolve && !isAutoResolveDisabled -> CaptchaResolveRoute.AUTOMATIC
	else -> CaptchaResolveRoute.MANUAL
}
