package me.weishu.kernelsu.ui.screen.detection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DetectionPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<DetectionViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var hasActivated by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage && !hasActivated) {
            hasActivated = true
            viewModel.scan()
        }
    }

    when (LocalUiMode.current) {
        UiMode.Miuix -> DetectionPagerMiuix(uiState, viewModel::scan, bottomInnerPadding)
        UiMode.Material -> DetectionPagerMaterial(uiState, viewModel::scan, bottomInnerPadding)
    }
}

internal fun DetectionStatus.labelRes(): Int = when (this) {
    DetectionStatus.CLEAN -> R.string.detection_status_clean
    DetectionStatus.NOTICE -> R.string.detection_status_notice
    DetectionStatus.WARNING -> R.string.detection_status_warning
    DetectionStatus.UNKNOWN -> R.string.detection_status_unknown
}

internal fun DetectionStatus.tagColor(): Color = when (this) {
    DetectionStatus.CLEAN -> Color(0xFF2E7D32)
    DetectionStatus.NOTICE -> Color(0xFFB26A00)
    DetectionStatus.WARNING -> Color(0xFFC62828)
    DetectionStatus.UNKNOWN -> Color(0xFF616161)
}

internal fun formatScanTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
