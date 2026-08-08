package com.dongholab.pagetuner.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongholab.pagetuner.common.DiagnosticLogger
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPaper
import com.dongholab.pagetuner.ui.theme.EinkSoft

private const val PAGE_SIZE = 7

/**
 * 앱 내 실시간 진단 로그 뷰어 패널.
 *
 * - 스크롤 없음: E-Ink 규칙 준수, ◄ Prev / Next ► 버튼으로 이동
 * - 뒤로 갔다 돌아와도 현재 페이지 위치 유지: rememberSaveable 사용
 * - 새 로그가 추가돼도 현재 보던 페이지 유지 (기존 EinkPagingContainer의
 *   remember(items) 리셋 문제 해결 — 페이지 상태를 items 변화와 독립 관리)
 */
@Composable
fun DiagnosticLogPanel(
    modifier: Modifier = Modifier,
) {
    val logs by DiagnosticLogger.logsState.collectAsState()

    // rememberSaveable: 탭 전환 후 복귀해도 페이지 유지
    // items 변화와 독립적이므로 새 로그 추가 시에도 리셋되지 않음
    var currentPageIndex by rememberSaveable { mutableIntStateOf(0) }

    val reversedLogs = logs.reversed()
    val totalPages = if (reversedLogs.isEmpty()) 1
    else (reversedLogs.size + PAGE_SIZE - 1) / PAGE_SIZE
    val safePageIndex = currentPageIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    val pageItems = reversedLogs.drop(safePageIndex * PAGE_SIZE).take(PAGE_SIZE)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = EinkPaper,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 헤더 행: 제목 + 지우기 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🔍 실시간 진단 로그 (${logs.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = EinkInk,
                )
                Button(
                    onClick = {
                        DiagnosticLogger.clear()
                        currentPageIndex = 0
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EinkInk,
                        contentColor = EinkPaper,
                    ),
                ) {
                    Text("지우기", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (logs.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = EinkSoft,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, EinkLine),
                ) {
                    Text(
                        text = "아직 로그가 없습니다.\n웹소설 다운로드 또는 번역 버튼을 눌러보세요.",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = EinkMuted,
                    )
                }
            } else {
                // 페이지 네비게이션 (2페이지 이상일 때만 표시)
                if (totalPages > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val rangeStart = safePageIndex * PAGE_SIZE + 1
                        val rangeEnd = minOf((safePageIndex + 1) * PAGE_SIZE, reversedLogs.size)
                        Text(
                            text = "$rangeStart-$rangeEnd / ${reversedLogs.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = EinkMuted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { currentPageIndex = (safePageIndex - 1).coerceAtLeast(0) },
                                enabled = safePageIndex > 0,
                            ) {
                                Text("◄ Prev")
                            }
                            TextButton(
                                onClick = { currentPageIndex = (safePageIndex + 1).coerceAtMost(totalPages - 1) },
                                enabled = safePageIndex < totalPages - 1,
                            ) {
                                Text("Next ►")
                            }
                        }
                    }
                }

                // 현재 페이지 로그 항목들
                pageItems.forEach { logLine ->
                    DiagnosticLogRow(logLine = logLine)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLogRow(logLine: String) {
    val isError = logLine.contains("FAILURE") || logLine.contains("WARNING")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) EinkSoft else EinkPaper,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isError) EinkInk else EinkLine,
        ),
    ) {
        Text(
            text = logLine,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = if (isError) EinkInk else EinkMuted,
        )
    }
}
