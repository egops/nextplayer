package dev.anilbeesetti.nextplayer.settings.screens.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.anilbeesetti.nextplayer.core.common.updater.AppUpdateCheckResult
import dev.anilbeesetti.nextplayer.core.common.updater.AppUpdateInfo
import dev.anilbeesetti.nextplayer.core.common.updater.AppUpdateManager
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AboutPreferencesViewModel @Inject constructor(
    private val appUpdateManager: AppUpdateManager,
) : ViewModel() {

    val uiState: StateFlow<AboutPreferencesUiState> = combine(
        appUpdateManager.checkState,
        appUpdateManager.isChecking,
        appUpdateManager.isDownloading,
        appUpdateManager.downloadProgress,
    ) { checkResult, isChecking, isDownloading, downloadProgress ->
        AboutPreferencesUiState(
            checkResult = checkResult,
            isChecking = isChecking,
            isDownloading = isDownloading,
            downloadProgress = downloadProgress,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AboutPreferencesUiState(),
    )

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private var userInitiatedCheck = false

    fun checkForUpdates() {
        userInitiatedCheck = true
        appUpdateManager.checkForUpdate()
    }

    fun onCheckResultShown() {
        if (!userInitiatedCheck) return
        userInitiatedCheck = false
        when (appUpdateManager.checkState.value) {
            null -> Unit
            else -> _showUpdateDialog.value = true
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    fun downloadAndInstall(update: AppUpdateInfo) {
        viewModelScope.launch {
            appUpdateManager.downloadAndInstall(update)
        }
    }

    fun clearUpdateState() {
        _showUpdateDialog.value = false
        appUpdateManager.clearCheckState()
    }
}

data class AboutPreferencesUiState(
    val checkResult: AppUpdateCheckResult? = null,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
)
