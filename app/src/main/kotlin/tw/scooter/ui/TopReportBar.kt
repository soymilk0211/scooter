package tw.scooter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tw.scooter.R
import tw.scooter.rules.TurnRule
import tw.scooter.ui.theme.ScooterColors

/**
 * 頂部回報列。
 *
 * **本元件只在騎士真的停下來時才會被組合進畫面**（由呼叫端判斷），所以裡面
 * 沒有鎖定狀態。這是刻意的：一顆變灰的按鈕仍然邀請人去按，而騎乘中按下去
 * 拿到的方位角可能正記在轉彎的半途 —— 那會把規則掛到一個不存在的來向上。
 *
 * 另一項規則同樣留在呼叫端：進入懸浮視窗模式時整列隱藏。
 */
@Composable
fun TopReportBar(
    entryRoad: String?,
    exitRoad: String?,
    onReport: (TurnRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        ManeuverLine(entryRoad, exitRoad)

        Text(
            text = stringResource(R.string.report_prompt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportButton(R.string.report_hook, R.string.report_hook_sub, Modifier.weight(1f)) {
                onReport(TurnRule.HOOK)
            }
            ReportButton(R.string.report_direct, R.string.report_direct_sub, Modifier.weight(1f)) {
                onReport(TurnRule.DIRECT)
            }
            ReportButton(R.string.report_inner, R.string.report_inner_sub, Modifier.weight(1f)) {
                onReport(TurnRule.INNER_LANE)
            }
            ReportButton(R.string.report_outer, R.string.report_outer_sub, Modifier.weight(1f)) {
                onReport(TurnRule.OUTER_LANE)
            }
        }
    }
}

@Composable
private fun ManeuverLine(entryRoad: String?, exitRoad: String?) {
    if (entryRoad == null) {
        Text(
            text = stringResource(R.string.no_maneuver),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = entryRoad,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = "  ➔  ",
            style = MaterialTheme.typography.headlineMedium,
            color = ScooterColors.Amber,
        )
        Text(
            text = exitRoad.orEmpty(),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun ReportButton(
    labelRes: Int,
    subLabelRes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(subLabelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
