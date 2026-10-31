package me.weishu.kernelsu.ui.screen.detection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ksuApp

class DetectionViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DetectionUiState())
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    fun scan() {
        if (_uiState.value.isScanning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true) }
            val report = DetectionEngine.scan(ksuApp)
            _uiState.update { it.copy(isScanning = false, report = report) }
        }
    }
}
