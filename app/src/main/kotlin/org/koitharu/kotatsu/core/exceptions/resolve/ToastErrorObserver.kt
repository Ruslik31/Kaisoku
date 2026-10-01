package org.koitharu.kotatsu.core.exceptions.resolve

import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails

class ToastErrorObserver(
	host: View,
	fragment: Fragment?,
) : ErrorObserver(host, fragment, null, null) {

	override suspend fun emit(value: Throwable) {
		Snackbar.make(host, value.getDisplayMessage(host.context.resources), Snackbar.LENGTH_INDEFINITE)
			.setAction(R.string.copy) {
				host.context.copyToClipboard(host.context.getString(R.string.error), value.getCopyableErrorDetails())
				Toast.makeText(host.context, R.string.error_copied, Toast.LENGTH_SHORT).show()
			}
			.show()
	}
}
