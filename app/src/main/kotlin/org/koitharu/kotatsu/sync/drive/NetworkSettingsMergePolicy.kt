package org.koitharu.kotatsu.sync.drive

import org.koitharu.kotatsu.core.prefs.AppSettings

/** Automatic merges must not undo this device's connection choices with an older snapshot. */
internal fun existingNetworkSettingsKeys(local: Map<String, *>): Set<String> = buildSet {
    if (AppSettings.KEY_DOH in local) add(AppSettings.KEY_DOH)
    if (AppSettings.KEY_IMAGES_PROXY in local || "images_proxy" in local) {
        add(AppSettings.KEY_IMAGES_PROXY)
        add("images_proxy")
    }
}
