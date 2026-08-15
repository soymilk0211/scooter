package tw.scooter.ride

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import tw.scooter.rules.TurnRule
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 路口警示的語音播報。
 *
 * 五句話是固定的，所以**首次啟動時就把它們合成成音檔**，之後每次播報都是放本機檔案。
 * 這樣延遲是確定的：即時 TTS 的首次發話延遲取決於引擎有沒有被喚醒、語音資料有沒有
 * 下載，實測差距可以到數秒 —— 而路口只給我們幾秒。
 *
 * 音訊屬性用 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`，與 Google Maps 相同：系統
 * 會據此處理與其他導航 App 的搶麥順序，而不是把我們當成一般媒體播放。
 *
 * 這個類別對外只回報一件事 —— [VoiceStatus]。它有義務**永遠回報**：任何一條
 * 路徑上的沉默都會變成騎士的沉默，而騎士不會知道。
 */
class AlertVoice(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val cacheDir = File(context.cacheDir, "voice").apply { mkdirs() }
    private val cached = ConcurrentHashMap<TurnRule, File>()

    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private val watchdog = Handler(Looper.getMainLooper())

    /**
     * 存成欄位而不是每次寫 `::onSynthesisTimeout`。
     *
     * 方法參考每取一次就是一個新的 [Runnable] 實例，而 `removeCallbacks` 是比對
     * 實例相等 —— 寫成方法參考的話取消永遠不會生效，逾時會在重新檢查時打斷
     * 一次正常的合成，把好的狀態誤判成降級。
     */
    private val synthesisTimeout = Runnable { onSynthesisTimeout() }

    /** 引擎與音檔的狀態，不含音量 —— 音量是每次回報時現場問的。 */
    @Volatile
    private var engineStatus: VoiceStatus = VoiceStatus.CHECKING

    /** 語言檢查過關即為 true。音檔還沒合成好時仍可用即時 TTS 發聲。 */
    @Volatile
    private var engineUsable = false

    @Volatile
    private var onStatus: (VoiceStatus) -> Unit = {}

    private val pendingSynthesis = AtomicInteger(0)
    private val synthesisFailed = AtomicBoolean(false)
    private val resolvedUtterances = ConcurrentHashMap.newKeySet<String>()
    private val queuedRules = ConcurrentHashMap<String, TurnRule>()

    /**
     * 每次 [prepare] 遞增。所有回呼都帶著自己那一輪的號碼，對不上就丟掉。
     *
     * 沒有這個號碼的話：騎士連按兩次「重新檢查」，第一顆引擎（正常）的回呼可能
     * 晚於第二顆（壞掉）抵達，於是把狀態蓋回 READY —— 語音是壞的，畫面卻乾淨，
     * 正好是這整套機制要防的那件事。
     */
    private val generation = AtomicInteger(0)

    /** 由設定頁控制。關閉時仍會播報，只是不壓低背景音樂。 */
    @Volatile
    var duckOthers: Boolean = true

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * 檢查語音能力並在需要時預先合成音檔。可重複呼叫 —— 騎士照著警告畫面去裝了
     * 語音資料之後，要能回來按「重新檢查」而不必重啟 App。
     */
    fun prepare(onStatus: (VoiceStatus) -> Unit = {}) {
        this.onStatus = onStatus
        shutdownEngine()
        engineStatus = VoiceStatus.CHECKING
        engineUsable = false
        resolvedUtterances.clear()
        queuedRules.clear()
        synthesisFailed.set(false)
        pendingSynthesis.set(0)
        val round = generation.incrementAndGet()
        publish()

        tts = TextToSpeech(context) { initStatus ->
            val engine = tts
            if (engine == null) {
                // release() 與初始化回呼賽跑。沒有引擎可用就照實說。
                settle(VoiceStatus.NO_ENGINE, round)
                return@TextToSpeech
            }
            val languageResult = if (initStatus == TextToSpeech.SUCCESS) {
                engine.setLanguage(Locale.TAIWAN)
            } else {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            when (val capability = VoiceStatus.ofEngine(initStatus, languageResult)) {
                VoiceStatus.NO_ENGINE -> {
                    Log.w(TAG, "TTS 初始化失敗：$initStatus")
                    settle(capability, round)
                }
                VoiceStatus.MISSING_DATA -> {
                    // 部分 ROM（尤其無 GMS 的機器）沒有 zh-TW 語音資料。
                    // 這不是崩潰，但騎士會完全聽不到警示 —— 畫面必須告訴他。
                    Log.w(TAG, "缺少 zh-TW 語音資料：$languageResult")
                    settle(capability, round)
                }
                else -> {
                    engine.setAudioAttributes(attributes)
                    engineUsable = true
                    synthesiseAll(engine, round)
                }
            }
        }
    }

    /**
     * 把五句話合成成音檔。已存在的略過 —— 只有首次啟動或改版才需要合成。
     *
     * 合成結果決定 [VoiceStatus.READY] 或 [VoiceStatus.DEGRADED]，所以要等它做完
     * 才算有結論；在那之前狀態停在 [VoiceStatus.CHECKING]，畫面不出現任何警告。
     */
    private fun synthesiseAll(engine: TextToSpeech, round: Int) {
        val missing = AlertPhrases.all.filter { rule ->
            val file = File(cacheDir, AlertPhrases.cacheName(rule))
            val usable = file.exists() && file.length() > 0
            if (usable) cached[rule] = file
            !usable
        }

        if (missing.isEmpty()) {
            settle(VoiceStatus.READY, round)
            return
        }

        pendingSynthesis.set(missing.size)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = resolveUtterance(utteranceId, true, round)
            override fun onError(utteranceId: String?) = resolveUtterance(utteranceId, false, round)
        })

        // 有些引擎在合成失敗時兩種訊號都不給。等不到就當作降級 —— 停在 CHECKING
        // 等於這整套警告機制自己也靜默失效了。
        watchdog.postDelayed(synthesisTimeout, SYNTHESIS_TIMEOUT_MS)

        missing.forEach { rule ->
            val id = utteranceId(rule)
            queuedRules[id] = rule
            val file = File(cacheDir, AlertPhrases.cacheName(rule))
            val code = engine.synthesizeToFile(AlertPhrases.of(rule), null, file, id)
            if (code != TextToSpeech.SUCCESS) resolveUtterance(id, ok = false, round = round)
        }
    }

    /** 合成回呼來自 binder 執行緒，而且同一個 id 可能來兩次，所以先去重再計數。 */
    private fun resolveUtterance(utteranceId: String?, ok: Boolean, round: Int) {
        if (round != generation.get()) return
        val id = utteranceId ?: return
        if (!resolvedUtterances.add(id)) return

        val rule = queuedRules[id]
        val file = rule?.let { File(cacheDir, AlertPhrases.cacheName(it)) }
        if (ok && rule != null && file != null && file.exists() && file.length() > 0) {
            cached[rule] = file
        } else {
            Log.w(TAG, "合成失敗：$id")
            synthesisFailed.set(true)
        }

        if (pendingSynthesis.decrementAndGet() <= 0) {
            watchdog.removeCallbacks(synthesisTimeout)
            settle(if (synthesisFailed.get()) VoiceStatus.DEGRADED else VoiceStatus.READY, round)
        }
    }

    private fun onSynthesisTimeout() {
        if (engineStatus != VoiceStatus.CHECKING) return
        Log.w(TAG, "合成逾時，退回即時 TTS")
        settle(VoiceStatus.DEGRADED, generation.get())
    }

    /** 只有當前這一輪的回呼能改變狀態。遲到的那一輪講的是一顆已經關掉的引擎。 */
    private fun settle(status: VoiceStatus, round: Int) {
        if (round != generation.get()) {
            Log.i(TAG, "丟棄過期的語音檢查結果：$status")
            return
        }
        engineStatus = status
        publish()
    }

    /**
     * 把當下的狀態送出去。音量是**每次現場問**的，因為它隨時會變 ——
     * 騎士可能在啟動後才把音量轉到 0，那時引擎狀態完全沒有改變。
     */
    private fun publish() {
        onStatus(VoiceStatus.combine(engineStatus, mediaSilent()))
    }

    /**
     * 媒體音量是否為 0。
     *
     * `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` 走的是 `STREAM_MUSIC`，所以媒體音量
     * 為 0 時警示照發、焦點照搶，就是一點聲音也沒有 —— 症狀與缺語音資料一模一樣。
     */
    private fun mediaSilent(): Boolean =
        runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }
            .getOrDefault(false)

    /** 外部（畫面回到前景時）觸發的音量複查，不重跑引擎初始化。 */
    fun refreshStatus() {
        publish()
    }

    /**
     * 播報一則警示。
     *
     * 音檔還沒合成好時退回即時 TTS —— 首次啟動就遇到路口的機率不高，但真的遇到時
     * 慢一點總比沒聲音好。
     */
    fun speak(rule: TurnRule) {
        // 播報這一刻正是最該確認音量的時候：這則警示如果沒被聽到，就是現在沒被聽到。
        publish()
        if (!engineUsable) {
            Log.w(TAG, "語音不可用，略過播報：$engineStatus")
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

    private fun shutdownEngine() {
        // 先作廢這一輪，之後任何遲到的回呼都動不了狀態。
        generation.incrementAndGet()
        watchdog.removeCallbacks(synthesisTimeout)
        tts?.shutdown()
        tts = null
        engineUsable = false
    }

    fun release() {
        abandonFocus()
        player?.release()
        player = null
        shutdownEngine()
        engineStatus = VoiceStatus.CHECKING
        onStatus = {}
    }

    private fun utteranceId(rule: TurnRule) = "alert_v${AlertPhrases.VERSION}_${rule.id}"

    private companion object {
        const val TAG = "AlertVoice"

        /** 合成五句短語遠用不到這麼久；這個值只是為了確保「永遠會有結論」。 */
        const val SYNTHESIS_TIMEOUT_MS = 15_000L
    }
}
