package tw.scooter.ride

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * 語音壞掉時，騎士能做的事。
 *
 * 警告畫面本身沒有價值 —— 一個只會說「語音不可用」的畫面，只是把問題轉交給
 * 一個站在路邊、戴著安全帽、正要出發的人。每一種失效都必須連著一個按得下去的動作。
 */
enum class VoiceRemedy {
    /** 叫出系統的語音資料下載流程。 */
    INSTALL_DATA,

    /** 打開系統的文字轉語音設定，讓騎士自己選引擎或補下載。 */
    VOICE_SETTINGS,

    /** 把媒體音量拉到聽得見的程度。 */
    RAISE_VOLUME,

    /** 處理完回來重跑檢查。 */
    RECHECK,

    /** 知道了，這趟不要再擋著地圖。 */
    DISMISS,
}

/**
 * 每種狀態該給哪些按鈕，由左到右就是建議的處理順序。
 *
 * [VoiceStatus.SILENCED] 不給「重新檢查」：轉大聲之後狀態會自己更新，
 * 多一顆沒必要的按鈕只會讓真正該按的那顆變得不明顯。
 */
fun remediesFor(status: VoiceStatus): List<VoiceRemedy> = when (status) {
    VoiceStatus.MISSING_DATA -> listOf(
        VoiceRemedy.INSTALL_DATA, VoiceRemedy.VOICE_SETTINGS, VoiceRemedy.RECHECK,
    )
    VoiceStatus.NO_ENGINE -> listOf(VoiceRemedy.VOICE_SETTINGS, VoiceRemedy.RECHECK)
    VoiceStatus.SILENCED -> listOf(VoiceRemedy.RAISE_VOLUME)
    // 降級是聽得到的，所以可以關掉；致命的三種不行 —— 能被關掉的警告，
    // 和一開始就沒有的警告，對騎士來說沒有差別。
    VoiceStatus.DEGRADED -> listOf(VoiceRemedy.RECHECK, VoiceRemedy.DISMISS)
    VoiceStatus.CHECKING, VoiceStatus.READY -> emptyList()
}

/** 警告能不能被關掉。可關的前提是騎士仍然聽得到警示。 */
fun dismissible(status: VoiceStatus): Boolean = VoiceRemedy.DISMISS in remediesFor(status)

/**
 * 執行一個補救動作。
 *
 * 一律用 try/catch 而不是 `resolveActivity` 判斷：API 30 之後的套件可見性規則會讓
 * `resolveActivity` 對沒宣告 `<queries>` 的隱含 Intent 回傳 null，於是明明開得起來
 * 的設定頁被判定成開不起來。直接開、開不成再退，結果才是真的。
 */
fun performRemedy(context: Context, remedy: VoiceRemedy) {
    when (remedy) {
        VoiceRemedy.INSTALL_DATA ->
            launchFirstWorking(context, Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA), ttsSettings(), allSettings())
        VoiceRemedy.VOICE_SETTINGS ->
            launchFirstWorking(context, ttsSettings(), accessibilitySettings(), allSettings())
        VoiceRemedy.RAISE_VOLUME -> raiseMediaVolume(context)
        VoiceRemedy.RECHECK -> RideService.recheckVoice(context)
        VoiceRemedy.DISMISS -> Unit
    }
}

private fun ttsSettings() = Intent("com.android.settings.TTS_SETTINGS")

private fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

private fun allSettings() = Intent(Settings.ACTION_SETTINGS)

private fun launchFirstWorking(context: Context, vararg intents: Intent) {
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "開不起來：${intent.action}", e)
        }
    }
}

/**
 * 直接設到一半音量，而不是按一格「調高」。
 *
 * 只有音量為 0 時才會出現這顆按鈕，而在時速四十的風聲裡，十五格中的第一格
 * 與零格是同一回事。按了要真的聽得到，否則騎士會以為修好了。
 * 帶 `FLAG_SHOW_UI` 讓系統音量條跳出來，他隨即能自己再調。
 */
private fun raiseMediaVolume(context: Context) {
    val audio = context.getSystemService(AudioManager::class.java) ?: return
    runCatching {
        val target = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            maxOf(target, current, 1),
            AudioManager.FLAG_SHOW_UI,
        )
    }.onFailure {
        // 勿擾模式下改音量需要通知政策權限。改不動就只把音量條叫出來，
        // 讓騎士自己處理 —— 這仍然比什麼都不做好。
        Log.w(TAG, "無法調整音量", it)
        runCatching {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI,
            )
        }
    }
    RideService.refreshVoiceStatus(context)
}

private const val TAG = "VoiceRemedy"
