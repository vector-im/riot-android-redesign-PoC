/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.app.features.crypto.keysbackup.setup

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import arrow.core.Try
import com.google.android.material.bottomsheet.BottomSheetDialog
import im.vector.app.R
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.utils.LiveEvent
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.core.utils.selectTxtFileToWrite
import im.vector.app.core.utils.startSharePlainTextIntent
import im.vector.app.databinding.FragmentKeysBackupSetupStep3Binding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class KeysBackupSetupStep3Fragment @Inject constructor() : VectorBaseFragment<FragmentKeysBackupSetupStep3Binding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentKeysBackupSetupStep3Binding {
        return FragmentKeysBackupSetupStep3Binding.inflate(inflater, container, false)
    }

    private lateinit var viewModel: KeysBackupSetupSharedViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = activityViewModelProvider.get(KeysBackupSetupSharedViewModel::class.java)

        viewModel.shouldPromptOnBack = false

        viewModel.passphrase.observe(viewLifecycleOwner) {
            if (it.isNullOrBlank()) {
                // Recovery was generated, so show key and options to save
                views.keysBackupSetupStep3Label2.text = getString(R.string.keys_backup_setup_step3_text_line2_no_passphrase)
                views.keysBackupSetupStep3FinishButton.text = getString(R.string.keys_backup_setup_step3_button_title_no_passphrase)

                views.keysBackupSetupStep3RecoveryKeyText.text = viewModel.recoveryKey.value!!
                        .replace(" ", "")
                        .chunked(16)
                        .joinToString("\n") {
                            it
                                    .chunked(4)
                                    .joinToString(" ")
                        }
                views.keysBackupSetupStep3RecoveryKeyText.isVisible = true
            } else {
                views.keysBackupSetupStep3Label2.text = getString(R.string.keys_backup_setup_step3_text_line2)
                views.keysBackupSetupStep3FinishButton.text = getString(R.string.keys_backup_setup_step3_button_title)
                views.keysBackupSetupStep3RecoveryKeyText.isVisible = false
            }
        }

        setupViews()
    }

    private fun setupViews() {
        views.keysBackupSetupStep3FinishButton.setOnClickListener { onFinishButtonClicked() }
        views.keysBackupSetupStep3CopyButton.setOnClickListener { onCopyButtonClicked() }
        views.keysBackupSetupStep3RecoveryKeyText.setOnClickListener { onRecoveryKeyClicked() }
    }

    private fun onFinishButtonClicked() {
        if (viewModel.megolmBackupCreationInfo == null) {
            // nothing
        } else {
            if (viewModel.passphrase.value.isNullOrBlank() && !viewModel.copyHasBeenMade) {
                Toast.makeText(context, R.string.keys_backup_setup_step3_please_make_copy, Toast.LENGTH_LONG).show()
            } else {
                viewModel.navigateEvent.value = LiveEvent(KeysBackupSetupSharedViewModel.NAVIGATE_FINISH)
            }
        }
    }

    private fun onCopyButtonClicked() {
        val dialog = BottomSheetDialog(requireActivity())
        dialog.setContentView(R.layout.bottom_sheet_save_recovery_key)
        dialog.setCanceledOnTouchOutside(true)
        val recoveryKey = viewModel.recoveryKey.value!!

        if (viewModel.passphrase.value.isNullOrBlank()) {
            dialog.findViewById<TextView>(R.id.keys_backup_recovery_key_text)?.isVisible = false
        } else {
            dialog.findViewById<TextView>(R.id.keys_backup_recovery_key_text)?.let {
                it.isVisible = true
                it.text = recoveryKey.replace(" ", "")
                        .chunked(16)
                        .joinToString("\n") {
                            it
                                    .chunked(4)
                                    .joinToString(" ")
                        }

                it.debouncedClicks {
                    copyToClipboard(requireActivity(), recoveryKey)
                }
            }
        }

        dialog.findViewById<View>(R.id.keys_backup_setup_save)?.setOnClickListener {
            val userId = viewModel.userId
            val timestamp = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            selectTxtFileToWrite(
                    activity = requireActivity(),
                    activityResultLauncher = saveRecoveryActivityResultLauncher,
                    defaultFileName = "recovery-key-$userId-$timestamp.txt",
                    chooserHint = getString(R.string.save_recovery_key_chooser_hint)
            )
            dialog.dismiss()
        }

        dialog.findViewById<View>(R.id.keys_backup_setup_share)?.setOnClickListener {
            startSharePlainTextIntent(
                    fragment = this,
                    activityResultLauncher = null,
                    chooserTitle = context?.getString(R.string.keys_backup_setup_step3_share_intent_chooser_title),
                    text = recoveryKey,
                    subject = context?.getString(R.string.recovery_key))
            viewModel.copyHasBeenMade = true
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun onRecoveryKeyClicked() {
        viewModel.recoveryKey.value?.let {
            viewModel.copyHasBeenMade = true

            copyToClipboard(requireActivity(), it)
        }
    }

    private fun exportRecoveryKeyToFile(uri: Uri, data: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Try {
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)
                            ?.use { os ->
                                os.write(data.toByteArray())
                                os.flush()
                            }
                }
                        ?: throw IOException("Unable to write the file")
            }
                    .fold(
                            { throwable ->
                                activity?.let {
                                    AlertDialog.Builder(it)
                                            .setTitle(R.string.dialog_title_error)
                                            .setMessage(errorFormatter.toHumanReadable(throwable))
                                }
                            },
                            {
                                viewModel.copyHasBeenMade = true
                                activity?.let {
                                    AlertDialog.Builder(it)
                                            .setTitle(R.string.dialog_title_success)
                                            .setMessage(R.string.recovery_key_export_saved)
                                }
                            }
                    )
                    ?.setCancelable(false)
                    ?.setPositiveButton(R.string.ok, null)
                    ?.show()
        }
    }

    private val saveRecoveryActivityResultLauncher = registerStartForActivityResult { activityRessult ->
        val uri = activityRessult.data?.data ?: return@registerStartForActivityResult
        if (activityRessult.resultCode == Activity.RESULT_OK) {
            viewModel.recoveryKey.value?.let {
                exportRecoveryKeyToFile(uri, it)
            }
        }
    }
}
