package tw.scooter.ride

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import tw.scooter.rules.TurnRule
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 路口警示的語音播報。
 *
 * 五句話是固定的，所以**首次啟動時就把它們合成成音檔**，之後每次播報都是放本機檔案。
 * 這樣延遲是確定的：即時 TTS 的首次發話延遲取決於引擎有沒有被喚醒、語音資料有沒有
 * 下載，實測差距可以到數秒 —— 而路口只給我們幾秒。
 *
 * 音訊屬性用 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`，與 Google Maps 相同：系統
 * 會據此處理與其他導航 App 的搶麥順序，而不是把我們當成一般媒體播放。
 */
class AlertVoice(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val cacheDir = File(context.cacheDir, "voice").apply { mkdirs() }
    private val cached = ConcurrentHashMap<TurnRule, File>()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    /** 由設定頁控制。關閉時仍會播報，只是不壓低背景音樂。 */
    @Volatile
    var duckOthers: Boolean = true

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun prepare(onReady: (Boolean) -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS 初始化失敗：$status")
                onReady(false)
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val result = engine.setLanguage(Locale.TAIWAN)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                // 部分 ROM（尤其無 GMS 的機器）沒有 zh-TW 語音資料。
                // 這不是崩潰，但騎士會完全聽不到警示 —— 呼叫端必須告知使用者。
                Log.w(TAG, "缺少 zh-TW 語音資料")
                onReady(false)
                return@TextToSpeech
            }
            engine.setAudioAttributes(attributes)
            ready = true
            synthesiseAll(engine)
            onReady(true)
        }
    }

    /** 把五句話合成成音檔。已存在的略過 —— 只有首次啟動或改版才需要合成。 */
    private fun synthesiseAll(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "合成失敗：$utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "已合成 $utteranceId")
            }
        })

        AlertPhrases.all.forEach { rule ->
            val file = File(cacheDir, AlertPhrases.cacheName(rule))
            if (file.exists() && file.length() > 0) {
                cached[rule] = file
                return@forEach
            }
            val code = engine.synthesizeToFile(
                AlertPhrases.of(rule), null, file, "alert_${rule.id}")
            if (code == TextToSpeech.SUCCESS) cached[rule] = file
        }
    }

    /**
     * 播報一則警示。
     *
     * 音檔還沒合成好時退回即時 TTS —— 首次啟動就遇到路口的機率不高，但真的遇到時
     * 慢一點總比沒聲音好。
     */
    fun speak(rule: TurnRule) {
        if (!ready) {
            Log.w(TAG, "語音尚未就緒，略過播報")
            return
        }
        requestFocus()
        val file = cached[rule]?.takeIf { it.exists() && it.length() > 0 }
        if (file != null) play(file) else tts?.speak(
            AlertPhrases.of(rule), TextToSpeech.QUEUE_FLUSH, null, "alert_live_${rule.id}")
    }

    private fun play(file: File) {
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(file.absolutePath)
                setOnCompletionListener { abandonFocus() }
                prepare()
                start()
            }
        }.onFailure {
            Log.w(TAG, "播放音檔失敗，退回即時合成", it)
            abandonFocus()
        }
    }

    private fun requestFocus() {
        // TRANSIENT_MAY_DUCK 讓背景音樂壓低而不是暫停 —— 這正是設定頁那個
        // 「背景音量衰減」開關的實作。關閉時完全不請求焦點，音樂維持原音量。
        if (!duckOthers) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun release() {
        abandonFocus()
        player?.release()
        player = null
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object {
        const val TAG = "AlertVoice"
    }
}
