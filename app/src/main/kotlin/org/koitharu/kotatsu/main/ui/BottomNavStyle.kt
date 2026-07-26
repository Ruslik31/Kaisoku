package org.koitharu.kotatsu.main.ui

/**
 * Presentation of the main bottom navigation bar: either anchored to the bottom edge across the full
 * width, or a detached rounded pill with margins around it.
 */
data class BottomNavStyle(
	val isFloating: Boolean,
	val cornerRadiusDp: Int,
)
