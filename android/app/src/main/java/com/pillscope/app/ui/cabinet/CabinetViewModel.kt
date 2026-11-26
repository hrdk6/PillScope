package com.pillscope.app.ui.cabinet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.remote.dto.DuplicateSaltAlertDto
import com.pillscope.app.data.remote.dto.SaltComponentDto
import com.pillscope.app.data.repository.CabinetMedication
import com.pillscope.app.data.repository.CabinetRepository
import com.pillscope.app.data.repository.CabinetSyncReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CabinetUiState(
    val isSyncing: Boolean = false,
    val lastSyncReport: CabinetSyncReport? = null,
    val lastDuplicateAlerts: List<DuplicateSaltAlertDto> = emptyList(),
    val statusBanner: String? = null,
)

@HiltViewModel
class CabinetViewModel @Inject constructor(
    private val cabinetRepository: CabinetRepository,
) : ViewModel() {
    val medications: StateFlow<List<CabinetMedication>> = cabinetRepository
        .observeAllVisibleMedications()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(CabinetUiState())
    val uiState: StateFlow<CabinetUiState> = _uiState.asStateFlow()

    fun syncCabinet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, statusBanner = null) }
            val report = cabinetRepository.syncWithBackend()
            val msg = if (report.isSuccess) {
                "Synced (${report.pulledCount} medications up to date)"
            } else {
                "Offline mode — local changes queued for next sync"
            }
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    lastSyncReport = report,
                    statusBanner = msg,
                )
            }
        }
    }

    fun addMedication(displayName: String, rawSaltsCsv: String, dosageSchedule: String?) {
        val salts = rawSaltsCsv
            .split(",", "+")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { SaltComponentDto(canonicalSalt = it.lowercase()) }
        if (displayName.isBlank() || salts.isEmpty()) return

        viewModelScope.launch {
            val result = cabinetRepository.addMedicationOfflineFirst(
                displayName = displayName,
                genericSalts = salts,
                dosageSchedule = dosageSchedule,
            )
            val banner = if (result.syncedWithServer) {
                "Added ${result.medication.displayName}"
            } else {
                "Saved ${result.medication.displayName} offline (pending sync)"
            }
            _uiState.update {
                it.copy(
                    lastDuplicateAlerts = result.localDuplicateAlerts,
                    statusBanner = banner,
                )
            }
        }
    }

    fun toggleActive(id: String, active: Boolean) {
        viewModelScope.launch {
            cabinetRepository.toggleMedicationActive(id, active)
        }
    }

    fun deleteMedication(id: String) {
        viewModelScope.launch {
            cabinetRepository.deleteMedication(id)
        }
    }
}
