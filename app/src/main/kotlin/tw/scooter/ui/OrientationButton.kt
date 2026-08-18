package tw.scooter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.scooter.R
import tw.scooter.ui.theme.ScooterColors

/**
 * 切換「北方朝上」與「車頭朝上」。
 *
 * 做成一顆圖示按鈕而不是設定裡的開關：這是騎乘中會想改的東西
 * （進到不熟的區域想看車頭朝上、要抓方位時想看北方朝上），
 * 而騎乘中沒有人會去翻設定抽屜。
 *
 * 圖示本身就是狀態：北方朝上時箭頭朝上並標 N，車頭朝上時箭頭跟著車頭轉。
 * 不另外寫字，因為陽光下一個轉了向的箭頭比兩個中文字好認。
 */
@Composable
fun OrientationButton(
    headingUp: Boolean,
    bearing: Double?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (headingUp) "▲" else stringResource(R.string.orientation_north),
            style = MaterialTheme.typography.titleMedium,
            color = if (headingUp) ScooterColors.Amber else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            // 車頭朝上時地圖被轉了，箭頭要反向轉回來才會一直指著真正的北方。
            modifier = if (headingUp && bearing != null) {
                Modifier.rotate(-bearing.toFloat())
            } else {
                Modifier
            },
        )
    }
}
