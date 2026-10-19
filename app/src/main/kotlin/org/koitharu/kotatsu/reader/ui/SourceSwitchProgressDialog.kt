package org.koitharu.kotatsu.reader.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R

/** Keep the outgoing reader suspended during an explicit history transfer, including recreation. */
class SourceSwitchProgressDialog : DialogFragment() {
    private val viewModel by activityViewModels<ReaderViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.source_switch_progress, requireArguments().getString(KEY_TITLE)))
            .setView(R.layout.dialog_source_switch_progress)
            .setNegativeButton(R.string.cancel, null)
            .create()

    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener { button ->
            button.isEnabled = false
            viewModel.cancelSourceSwitch()
        }
    }

    companion object {
        const val TAG = "source_switch_progress"
        private const val KEY_TITLE = "title"

        fun newInstance(title: String) = SourceSwitchProgressDialog().apply {
            arguments = Bundle().apply { putString(KEY_TITLE, title) }
        }
    }
}
