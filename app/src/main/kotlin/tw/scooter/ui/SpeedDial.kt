package tw.scooter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.scooter.ui.theme.ScooterColors
import kotlin.math.roundToInt

/**
 * 圓圈在畫面上的位置，以左上角為原點的像素座標。
 *
 * 存像素而不是 Alignment，是因為騎士拖到哪就該留在哪 —— 手把上的手機角度人人不同，
 * 「右下角」對某些人來說正好被油門線擋住。
 */
data class DialPosition(val x: Float, val y: Float) {
    companion object {
        /** 還沒被拖過。由呼叫端決定初始落點，元件自己不假設畫面有多大。 */
        val UNSET = DialPosition(Float.NaN, Float.NaN)
    }

    val isSet: Boolean get() = !x.isNaN() && !y.isNaN()
}

/**
 * 時速圓圈。
 *
 * **刻意不知道自己在哪個畫面上。** 它只收「速度、速限、位置」，回報「位置被拖到
 * 哪裡」——懸浮視窗（`SYSTEM_ALERT_WINDOW`）做出來時，那邊需要的是同一個東西，
 * 而一個綁死在 Activity 版面上的元件到時候只能重寫一份。兩份會分岔，而分岔的那天
 * 騎士會在兩個地方看到不同的速度。
 *
 * 顏色只表達一件事：**現在有沒有超速**。這是刻意不佔語音通道的決定 ——
 * 「您已超速」只在接近測速照相且確實超速時才說出口，其餘時間由這個顏色承擔。
 *
 * 沒有速限資料時不上色。台灣沒有可靠的全路網速限（OSM 的 `maxspeed` 只有三成五
 * 覆蓋），猜一個速限出來上色，比不上色危險得多。
 */
@Composable
fun SpeedDial(
    speedKmh: Double,
    speedLimitKmh: Int?,
    position: DialPosition,
    onPositionChanged: (DialPosition) -> Unit,
    /** 拖曳結束。落地寫檔在這裡做，不在每一幀 —— DataStore 每次寫入都是整檔改寫。 */
    onPositionSettled: (DialPosition) -> Unit,
    modifier: Modifier = Modifier,
    bounds: androidx.compose.ui.unit.IntSize? = null,
) {
    val overSpeed = speedLimitKmh != null && speedKmh > speedLimitKmh
    val ring = when {
        speedLimitKmh == null -> MaterialTheme.colorScheme.outline
        overSpeed -> ScooterColors.Alarm
        else -> ScooterColors.Green
    }
    // 面與字走主題色，警示紅則是寫死的 —— 那個顏色的職責只有一個，
    // 深淺兩種模式下都必須是同一個紅（見 Theme.kt）。
    val face = if (overSpeed) ScooterColors.AlarmFace else MaterialTheme.colorScheme.surface
    val ink = if (overSpeed) ScooterColors.OnAlarm else MaterialTheme.colorScheme.onSurface
    val subInk = if (overSpeed) ScooterColors.OnAlarm else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
            .size(DIAL_SIZE)
            .background(face, CircleShape)
            .border(RING_WIDTH, ring, CircleShape)
            .pointerInput(bounds) {
                // 夾在版面內。少了這個，一次甩得比較用力的拖曳就能把圓圈丟到
                // 畫面外，而它一旦出界就再也拖不回來了。
                val maxX = ((bounds?.width ?: 0) - size.width).coerceAtLeast(0).toFloat()
                val maxY = ((bounds?.height ?: 0) - size.height).coerceAtLeast(0).toFloat()
                var live = position
                detectDragGestures(
                    onDragEnd = { onPositionSettled(live) },
                    onDragCancel = { onPositionSettled(live) },
                ) { change, drag ->
                    change.consume()
                    live = DialPosition(
                        x = (live.x + drag.x).coerceIn(0f, maxX),
                        y = (live.y + drag.y).coerceIn(0f, maxY),
                    )
                    onPositionChanged(live)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = speedKmh.roundToInt().coerceAtLeast(0).toString(),
                color = ink,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Text(
                // 沒有速限資料時寫單位，不寫一個猜來的數字。
                text = speedLimitKmh?.let { "限 $it" } ?: "km/h",
                color = subInk,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val DIAL_SIZE = 84.dp
private val RING_WIDTH = 3.dp
