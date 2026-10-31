package me.weishu.kernelsu.ui.screen.detection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
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
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import me.weishu.kernelsu.ui.component.statustag.StatusTag

@Composable
fun DetectionPagerMaterial(
    uiState: DetectionUiState,
    onRescan: () -> Unit,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.detection_title)) },
                colors = expressiveTopAppBarColors(),
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = bottomInnerPadding + 16.dp,
            ),
        ) {
            item {
                SummaryCard(
                    uiState = uiState,
                    onRescan = onRescan,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            val report = uiState.report
            if (report == null) {
                item { EmptyCard() }
            } else {
                items(report.findings, key = { it.id }) { finding ->
                    FindingCard(
                        finding = finding,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (report != null) {
                Text(
                    text = stringResource(
                        R.string.detection_last_scan,
                        formatScanTime(report.scannedAtMillis),
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            TextButton(onClick = onRescan) {
                Text(stringResource(R.string.detection_rescan))
            }
        }
    }
}

@Composable
private fun EmptyCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.detection_never_scanned),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(finding.titleRes),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(finding.summaryRes),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (finding.details.isEmpty()) {
                        Text(
                            text = stringResource(R.string.detection_no_evidence),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        finding.details.forEach { detail ->
                            Text(
                                text = detail,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
