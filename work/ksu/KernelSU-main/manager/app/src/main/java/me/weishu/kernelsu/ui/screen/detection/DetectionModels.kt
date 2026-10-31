package me.weishu.kernelsu.ui.screen.detection

import androidx.annotation.StringRes

enum class DetectionStatus {
    /** No evidence of the observed feature. */
    CLEAN,

    /** The feature is present but expected on this device (e.g. KernelSU itself). */
    NOTICE,

    /** The feature indicates an unusual or modified environment. */
    WARNING,

    /** The probe could not be evaluated. */
    UNKNOWN,
}

data class DetectionFinding(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val status: DetectionStatus,
    val details: List<String>,
)

data class DetectionReport(
    val findings: List<DetectionFinding>,
    val scannedAtMillis: Long,
    val rootAvailable: Boolean,
) {
    val warningCount: Int get() = findings.count { it.status == DetectionStatus.WARNING }
    val noticeCount: Int get() = findings.count { it.status == DetectionStatus.NOTICE }
}

data class DetectionUiState(
    val isScanning: Boolean = false,
    val report: DetectionReport? = null,
)
