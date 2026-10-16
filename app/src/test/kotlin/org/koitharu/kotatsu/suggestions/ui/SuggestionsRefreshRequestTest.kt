package org.koitharu.kotatsu.suggestions.ui

import android.annotation.SuppressLint
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.*
import org.junit.Test

@SuppressLint("RestrictedApi")
class SuggestionsRefreshRequestTest {
    @Test fun expeditedBatteryConstraintReproducesTheReportedCrash() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            OneTimeWorkRequestBuilder<SuggestionsWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        }
        assertEquals("Expedited jobs only support network and storage constraints", error.message)
    }

    @Test fun refreshRequestsKeepNetworkAndBatteryConstraintsWithoutExpediting() {
        for (network in listOf(NetworkType.CONNECTED, NetworkType.UNMETERED)) for (force in listOf(false, true)) {
            val constraints = Constraints.Builder().setRequiredNetworkType(network).setRequiresBatteryNotLow(true).build()
            val request = SuggestionsWorker.Scheduler.createRefreshRequest(constraints, force)
            assertFalse(request.workSpec.expedited)
            assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
            assertEquals(network, request.workSpec.constraints.requiredNetworkType)
            assertEquals(force, request.workSpec.input.getBoolean("force_refresh", !force))
        }
    }
}
