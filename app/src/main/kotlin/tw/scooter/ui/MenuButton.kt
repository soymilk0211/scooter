package tw.scooter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 開啟設定抽屜的三橫槓按鈕。
 *
 * 先前抽屜只能從螢幕左緣滑出 —— 那個手勢在戴手套時幾乎按不到，而且沒有任何
 * 視覺提示它存在。手畫三條線而非用內建圖示，是為了控制粗細與間距，讓它在
 * 陽光下的小尺寸仍然辨識得出來。
 */
@Composable
fun MenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "設定" },
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) {
            Column(
                Modifier
                    .width(18.dp)
                    .size(width = 18.dp, height = 2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.onSurface),
            ) {}
        }
    }
}
