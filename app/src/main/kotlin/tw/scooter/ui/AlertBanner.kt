package tw.scooter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.scooter.rules.AlertCandidate
import tw.scooter.rules.TurnRule
import tw.scooter.ui.theme.ScooterColors

/**
 * 警示的暫時視覺形式。
 *
 * 正式的呈現是語音加懸浮視窗（騎乘中不該低頭看主畫面），兩者都還沒實作。
 * 這個橫幅存在的目的是讓「定位 → 查規則 → 發警示」這條路徑**看得見**，
 * 否則整條鏈是否接通只能靠讀 log 判斷。
 */
@Composable
fun AlertBanner(alert: AlertCandidate, modifier: Modifier = Modifier) {
    val rule = alert.rule
    val accent = when (rule.rule) {
        TurnRule.HOOK -> ScooterColors.Amber
        TurnRule.DIRECT -> ScooterColors.Green
        else -> ScooterColors.Text
    }

    Column(
        modifier = modifier
            .navigationBarsPadding()
            .padding(16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = spokenText(rule.rule),
            style = MaterialTheme.typography.headlineMedium,
            color = accent,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "${rule.entryRoadName.orEmpty()} ➔ ${rule.exitRoadName.orEmpty()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "${alert.distanceMeters.toInt()} 公尺　方位差 ${alert.bearingDelta.toInt()}°",
            style = MaterialTheme.typography.labelSmall,
            color = ScooterColors.TextMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 警示語句。這些字串就是之後要預先合成成音檔的內容 —— 先在畫面上定稿，
 * 避免語音接上後才發現文案要改。
 */
private fun spokenText(rule: TurnRule): String = when (rule) {
    TurnRule.HOOK -> "前方路口，請兩段式左轉"
    TurnRule.DIRECT -> "前方路口，機車可直接左轉"
    TurnRule.INNER_LANE -> "前方路口，請走內側左轉專用道"
    TurnRule.OUTER_LANE -> "前方路口，請走外側左轉專用道"
    TurnRule.UNKNOWN -> "前方路口即將左轉，請依現場標誌指示行駛"
}
