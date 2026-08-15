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
 * 兩項安全規則寫死在這裡，不做成設定：
 * - 車速高於 10 km/h 時四顆按鈕鎖定，避免騎乘中操作。
 * - 進入懸浮視窗模式時整列隱藏（由呼叫端決定是否組合本元件）。
 */
@Composable
fun TopReportBar(
    entryRoad: String?,
    exitRoad: String?,
    unlocked: Boolean,
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
            text = stringResource(
                if (unlocked) R.string.report_prompt else R.string.report_locked,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportButton(R.string.report_hook, R.string.report_hook_sub, unlocked, Modifier.weight(1f)) {
                onReport(TurnRule.HOOK)
            }
            ReportButton(R.string.report_direct, R.string.report_direct_sub, unlocked, Modifier.weight(1f)) {
                onReport(TurnRule.DIRECT)
            }
            ReportButton(R.string.report_inner, R.string.report_inner_sub, unlocked, Modifier.weight(1f)) {
                onReport(TurnRule.INNER_LANE)
            }
            ReportButton(R.string.report_outer, R.string.report_outer_sub, unlocked, Modifier.weight(1f)) {
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
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val face = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val label = if (enabled) MaterialTheme.colorScheme.onSurface else ScooterColors.TextDisabled
    val sub = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else ScooterColors.TextDisabled

    Column(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(face)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleMedium,
            color = label,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(subLabelRes),
            style = MaterialTheme.typography.labelSmall,
            color = sub,
            textAlign = TextAlign.Center,
        )
    }
}
