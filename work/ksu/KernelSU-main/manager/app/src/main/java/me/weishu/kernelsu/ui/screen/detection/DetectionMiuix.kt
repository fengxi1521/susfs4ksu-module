package me.weishu.kernelsu.ui.screen.detection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.statustag.StatusTag
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun DetectionPagerMiuix(
    uiState: DetectionUiState,
    onRescan: () -> Unit,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.detection_title),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    SummaryCard(
                        uiState = uiState,
                        onRescan = onRescan,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                val report = uiState.report
                if (report == null) {
                    item {
                        EmptyCard(modifier = Modifier.padding(top = 12.dp))
                    }
                } else {
                    items(report.findings, key = { it.id }) { finding ->
                        FindingCard(
                            finding = finding,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(bottomInnerPadding + 16.dp))
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    uiState: DetectionUiState,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = uiState.report
    val summary = when {
        uiState.isScanning -> stringResource(R.string.detection_scanning)
        report == null -> stringResource(R.string.detection_never_scanned)
        report.warningCount > 0 || report.noticeCount > 0 -> stringResource(
            R.string.detection_summary_issues,
            report.warningCount,
            report.noticeCount,
        )

        else -> stringResource(R.string.detection_summary_clean)
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = summary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
            )
            if (report != null) {
                Text(
                    text = stringResource(
                        R.string.detection_last_scan,
                        formatScanTime(report.scannedAtMillis),
                    ),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!report.rootAvailable) {
                    Text(
                        text = stringResource(R.string.detection_root_shell_unavailable),
                        fontSize = 13.sp,
                        color = Color(0xFFC62828),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.detection_rescan),
                onClick = onRescan,
            )
        }
    }
}

@Composable
private fun EmptyCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.detection_never_scanned),
                fontSize = 15.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun FindingCard(
    finding: DetectionFinding,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(finding.id) { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Sink
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(finding.titleRes),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(finding.summaryRes),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                StatusTag(
                    label = stringResource(finding.status.labelRes()),
                    backgroundColor = finding.status.tagColor(),
                    contentColor = Color.White,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        text = stringResource(R.string.detection_evidence),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface,
                    )
                    if (finding.details.isEmpty()) {
                        Text(
                            text = stringResource(R.string.detection_no_evidence),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        finding.details.forEach { detail ->
                            Text(
                                text = detail,
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
