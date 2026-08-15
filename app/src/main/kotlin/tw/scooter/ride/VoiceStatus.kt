package tw.scooter.ride

import android.speech.tts.TextToSpeech

/**
 * 語音警示的可用狀態。
 *
 * 這個列舉存在的理由是**靜默失效**：語音壞掉時 App 看起來一切正常 —— 地圖在動、
 * 服務在跑、警示也真的判定出來了 —— 只是騎士什麼都沒聽到，而且沒有任何跡象讓他
 * 察覺。他會以為「這個路口沒規則」，然後直接左轉。
 *
 * 分成這幾種而不是一個 Boolean，是因為**補救動作各不相同**：缺語音資料要去下載，
 * 沒有引擎要去裝一個，音量為 0 只要轉大聲。一個 Boolean 說不出這三件事的差別，
 * 畫面就只能給一句無從行動的「語音不可用」，騎士看了也不知道要做什麼。
 */
enum class VoiceStatus {

    /** 尚未檢查完。服務剛啟動的頭幾秒會停在這裡 —— 沒有結論就不要嚇人。 */
    CHECKING,

    /** 五句話都已合成成音檔，播報走本機檔案。 */
    READY,

    /**
     * 引擎與語音資料都在，但預合成沒成功，播報會退回即時 TTS。
     *
     * 聽得到，所以不是致命的；但首句延遲實測 2.8–3.6 秒，路口等不起，
     * 所以仍然要說。
     */
    DEGRADED,

    /** 引擎一切正常，但媒體音量為 0。警示照發，騎士聽不到。 */
    SILENCED,

    /** 引擎在，但沒有 zh-TW 語音資料。無 GMS 的 ROM 常見。 */
    MISSING_DATA,

    /** 裝置上沒有可用的語音引擎。 */
    NO_ENGINE;

    /** 這個狀態下騎士**完全聽不到**警示。 */
    val silent: Boolean
        get() = this == SILENCED || this == MISSING_DATA || this == NO_ENGINE

    /** 要不要在畫面上警告騎士。[CHECKING] 與 [READY] 不用。 */
    val needsWarning: Boolean
        get() = silent || this == DEGRADED

    companion object {

        /**
         * 由 [TextToSpeech] 的初始化結果與語言查詢結果判定引擎能力。
         *
         * 回傳 [READY] 只代表「引擎可用」，不代表音檔已經合成好 —— 合成的結果
         * 是後來才知道的，由 [AlertVoice] 接手判定成 [READY] 或 [DEGRADED]。
         */
        fun ofEngine(initStatus: Int, languageResult: Int): VoiceStatus = when {
            initStatus != TextToSpeech.SUCCESS -> NO_ENGINE
            languageResult == TextToSpeech.LANG_MISSING_DATA -> MISSING_DATA
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED -> MISSING_DATA
            else -> READY
        }

        /**
         * 把引擎狀態與當下的媒體音量合成一個要顯示的狀態。
         *
         * 引擎的問題**優先於**音量：兩者同時壞掉時，叫騎士去轉大聲並不會讓他聽到
         * 任何東西，只是浪費他在路口前的那幾秒。
         *
         * 還在 [CHECKING] 時連音量都不報，寧可晚一秒說話，也不要先閃一則
         * 「音量為 0」再換成「沒有語音資料」—— 警告閃來閃去就沒人當真了。
         */
        fun combine(engine: VoiceStatus, mediaSilent: Boolean): VoiceStatus = when {
            engine == CHECKING -> CHECKING
            engine == MISSING_DATA || engine == NO_ENGINE -> engine
            mediaSilent -> SILENCED
            else -> engine
        }
    }
}
