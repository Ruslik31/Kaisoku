package org.koitharu.kotatsu.core.crash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.main.ui.MainActivity

class CrashDialogActivity : AppCompatActivity() {

	private lateinit var report: CrashReportData

	override fun onCreate(savedInstanceState: Bundle?) {
		setTheme(R.style.Theme_Kotatsu)
		super.onCreate(savedInstanceState)
		report = intent.toCrashReportData() ?: run {
			finishAndRemoveTask()
			return
		}
		if (savedInstanceState == null && !report.isDegraded) {
			copyReport(showToast = false)
		}
		showCrashDialog()
	}

	private fun showCrashDialog() {
		val message = if (report.isDegraded) {
			R.string.crash_dialog_message_degraded
		} else {
			R.string.crash_dialog_message
		}
		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.error_occurred)
			.setIcon(R.drawable.ic_alert_outline)
			.setMessage(message)
			.setNegativeButton(R.string.open_github_issues, null)
			.setNeutralButton(R.string.copy_crash_report, null)
			.setPositiveButton(R.string.restart, null)
			.setOnCancelListener {
				finishAndRemoveTask()
			}
			.create()
		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
				copyReport(showToast = true)
			}
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
				copyReport(showToast = false)
				openIssues()
				finishAndRemoveTask()
			}
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				restartApp()
			}
		}
		dialog.show()
	}

	private fun copyReport(showToast: Boolean) {
		copyToClipboard(getString(R.string.error), report.body)
		if (showToast) {
			Toast.makeText(this, R.string.crash_report_copied, Toast.LENGTH_SHORT).show()
		}
	}

	private fun openIssues() {
		val intent = Intent(Intent.ACTION_VIEW, report.issueUrl.toUri())
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		runCatching {
			startActivity(intent)
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	private fun restartApp() {
		val intent = Intent(this, MainActivity::class.java)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
		runCatching {
			startActivity(intent)
			finishAndRemoveTask()
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	companion object {

		internal fun newIntent(context: Context, report: CrashReportData): Intent {
			return Intent(context, CrashDialogActivity::class.java)
				.putCrashReportData(report)
		}
	}
}
