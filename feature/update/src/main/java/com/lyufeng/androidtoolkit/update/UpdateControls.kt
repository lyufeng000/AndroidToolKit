package com.lyufeng.androidtoolkit.update

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Composable
fun UpdateControls() {
    val model: UpdateViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.checkAutomatically() }

    TextButton(
        onClick = { model.checkManually() },
        enabled = state != UpdateState.Checking && state != UpdateState.Downloading,
    ) {
        Text(stringResource(R.string.update_check))
    }

    when (val current = state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> Text(stringResource(R.string.update_checking))
        UpdateState.Downloading -> Text(stringResource(R.string.update_downloading))
        is UpdateState.Message -> Text(stringResource(current.text))
        is UpdateState.Available -> UpdateDialog(
            title = stringResource(R.string.update_available, current.release.version),
            message = stringResource(R.string.update_confirm_download),
            action = stringResource(R.string.update_download),
            onConfirm = { model.download(current.release) },
            onDismiss = model::dismiss,
        )
        is UpdateState.Ready -> UpdateDialog(
            title = stringResource(R.string.update_ready),
            message = stringResource(
                if (current.needsPermission) R.string.update_allow_install else R.string.update_confirm_install,
            ),
            action = stringResource(R.string.update_install),
            onConfirm = { model.install(current.file) },
            onDismiss = model::dismiss,
        )
    }
}

@Composable
private fun UpdateDialog(
    title: String,
    message: String,
    action: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } },
    )
}

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Downloading : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Ready(val file: File, val needsPermission: Boolean = false) : UpdateState
    data class Message(@StringRes val text: Int) : UpdateState
}

internal class UpdateViewModel(private val application: Application) : AndroidViewModel(application) {
    private val repository = UpdateRepository()
    private val installer = UpdateInstaller(application)
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutableState.asStateFlow()
    private var checked = false

    fun checkAutomatically() {
        if (!checked) checkManually(showResult = false)
    }

    @Suppress("DEPRECATION")
    fun checkManually(showResult: Boolean = true) {
        if (mutableState.value == UpdateState.Checking || mutableState.value == UpdateState.Downloading) return
        checked = true
        mutableState.value = UpdateState.Checking
        viewModelScope.launch {
            mutableState.value = try {
                val currentVersion = application.packageManager
                    .getPackageInfo(application.packageName, 0).versionName.orEmpty()
                val release = repository.check(currentVersion)
                when {
                    release != null -> UpdateState.Available(release)
                    showResult -> UpdateState.Message(R.string.update_current)
                    else -> UpdateState.Idle
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (showResult) UpdateState.Message(R.string.update_check_failed) else UpdateState.Idle
            }
        }
    }

    fun download(release: UpdateRelease) {
        if (mutableState.value != UpdateState.Available(release)) return
        mutableState.value = UpdateState.Downloading
        viewModelScope.launch {
            mutableState.value = try {
                UpdateState.Ready(installer.download(release))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                UpdateState.Message(R.string.update_download_failed)
            }
        }
    }

    fun install(file: File) {
        if ((mutableState.value as? UpdateState.Ready)?.file != file) return
        mutableState.value = try {
            if (installer.install(file)) UpdateState.Idle else UpdateState.Ready(file, needsPermission = true)
        } catch (_: Exception) {
            UpdateState.Message(R.string.update_install_failed)
        }
    }

    fun dismiss() {
        mutableState.value = UpdateState.Idle
    }
}
