package tw.scooter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.scooter.R
import tw.scooter.ride.VoiceRemedy
import tw.scooter.ride.VoiceStatus
import tw.scooter.ride.remediesFor
import tw.scooter.ui.theme.ScooterColors

/**
 * 語音失效的警告。
 *
 * 這是全 App 唯一用滿版紅底的元件，而且它蓋在回報列上方 —— 因為它要說的是
 * 「接下來這趟，這台裝置不會在路口出聲」。其他失效（沒定位、服務沒跑）騎士遲早
 * 會自己發現；聽不到永遠不會被自己發現，只會在路口被誤以為是「這裡沒規則」。
 *
 * 三種致命狀態沒有關閉鍵。能被關掉的警告，和一開始就沒有的警告，是同一件事。
 */
@Composable
fun VoiceWarning(
    status: VoiceStatus,
    onRemedy: (VoiceRemedy) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!status.needsWarning) return

    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ScooterColors.Alarm)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(titleOf(status)),
            style = MaterialTheme.typography.headlineMedium,
            color = ScooterColors.OnAlarm,
        )
        Text(
            text = stringResource(bodyOf(status)),
            style = MaterialTheme.typography.bodyMedium,
            color = ScooterColors.OnAlarm,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )

        val remedies = remediesFor(status)
        if (remedies.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                remedies.forEach { remedy ->
                    RemedyButton(remedy, Modifier.weight(1f)) { onRemedy(remedy) }
                }
            }
        }
    }
}

@Composable
private fun RemedyButton(remedy: VoiceRemedy, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = stringResource(labelOf(remedy)),
        style = MaterialTheme.typography.titleMedium,
        color = ScooterColors.OnAlarm,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ScooterColors.AlarmFace)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

/**
 * 標題只分兩種：聽不到，或聽得到但會慢。這是騎士當下唯一需要先分清楚的事，
 * 細節留給內文。
 */
private fun titleOf(status: VoiceStatus): Int =
    if (status.silent) R.string.voice_alarm_silent_title else R.string.voice_alarm_degraded_title

private fun bodyOf(status: VoiceStatus): Int = when (status) {
    VoiceStatus.MISSING_DATA -> R.string.voice_alarm_missing_data
    VoiceStatus.NO_ENGINE -> R.string.voice_alarm_no_engine
    VoiceStatus.SILENCED -> R.string.voice_alarm_silenced
    else -> R.string.voice_alarm_degraded
}

private fun labelOf(remedy: VoiceRemedy): Int = when (remedy) {
    VoiceRemedy.INSTALL_DATA -> R.string.voice_remedy_install
    VoiceRemedy.VOICE_SETTINGS -> R.string.voice_remedy_settings
    VoiceRemedy.RAISE_VOLUME -> R.string.voice_remedy_volume
    VoiceRemedy.RECHECK -> R.string.voice_remedy_recheck
    VoiceRemedy.DISMISS -> R.string.voice_remedy_dismiss
}
