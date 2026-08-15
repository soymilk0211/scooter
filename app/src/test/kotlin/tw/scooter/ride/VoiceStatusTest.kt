package tw.scooter.ride

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 這些測試守的是一件事：**任何一條路徑都不能讓騎士在不知情的狀況下騎在沒有聲音的
 * App 上**。分類錯了只是按鈕給錯；漏判成 READY 才是真正的失敗。
 */
class VoiceStatusTest {

    @Test
    fun `initialisation failure means there is no engine at all`() {
        assertEquals(
            VoiceStatus.NO_ENGINE,
            VoiceStatus.ofEngine(TextToSpeech.ERROR, TextToSpeech.LANG_AVAILABLE),
        )
    }

    @Test
    fun `a missing voice pack is not the same failure as a missing engine`() {
        // 兩者的補救動作不同：一個去下載語音，一個去裝引擎。混為一談，
        // 畫面就只能給一句無從行動的「語音不可用」。
        assertEquals(
            VoiceStatus.MISSING_DATA,
            VoiceStatus.ofEngine(TextToSpeech.SUCCESS, TextToSpeech.LANG_MISSING_DATA),
        )
        assertEquals(
            VoiceStatus.MISSING_DATA,
            VoiceStatus.ofEngine(TextToSpeech.SUCCESS, TextToSpeech.LANG_NOT_SUPPORTED),
        )
    }

    @Test
    fun `an engine with zh-TW is usable`() {
        assertEquals(
            VoiceStatus.READY,
            VoiceStatus.ofEngine(TextToSpeech.SUCCESS, TextToSpeech.LANG_COUNTRY_AVAILABLE),
        )
    }

    @Test
    fun `a working engine on a muted device is reported as silenced`() {
        assertEquals(VoiceStatus.SILENCED, VoiceStatus.combine(VoiceStatus.READY, mediaSilent = true))
        assertEquals(VoiceStatus.SILENCED, VoiceStatus.combine(VoiceStatus.DEGRADED, mediaSilent = true))
    }

    @Test
    fun `engine faults outrank a muted device`() {
        // 兩者同時壞掉時叫騎士去轉大聲，他轉完仍然聽不到 —— 而他會以為修好了。
        assertEquals(
            VoiceStatus.MISSING_DATA,
            VoiceStatus.combine(VoiceStatus.MISSING_DATA, mediaSilent = true),
        )
        assertEquals(
            VoiceStatus.NO_ENGINE,
            VoiceStatus.combine(VoiceStatus.NO_ENGINE, mediaSilent = true),
        )
    }

    @Test
    fun `nothing is claimed before the check finishes`() {
        assertEquals(VoiceStatus.CHECKING, VoiceStatus.combine(VoiceStatus.CHECKING, mediaSilent = true))
        assertFalse(VoiceStatus.CHECKING.needsWarning)
    }

    @Test
    fun `an audible device is left alone`() {
        assertEquals(VoiceStatus.READY, VoiceStatus.combine(VoiceStatus.READY, mediaSilent = false))
        assertFalse(VoiceStatus.READY.needsWarning)
        assertFalse(VoiceStatus.READY.silent)
    }

    @Test
    fun `every way of hearing nothing counts as silent`() {
        listOf(VoiceStatus.SILENCED, VoiceStatus.MISSING_DATA, VoiceStatus.NO_ENGINE).forEach {
            assertTrue("$it 必須算作聽不到", it.silent)
            assertTrue("$it 必須警告騎士", it.needsWarning)
        }
    }

    @Test
    fun `a slow voice still warns but is not counted as silence`() {
        // 慢三秒的播報聽得到，所以不是致命的；但路口可能已經過了，仍然要說。
        assertFalse(VoiceStatus.DEGRADED.silent)
        assertTrue(VoiceStatus.DEGRADED.needsWarning)
    }

    @Test
    fun `every warning comes with something the rider can do`() {
        VoiceStatus.entries.filter { it.needsWarning }.forEach {
            assertTrue("$it 的警告沒有給任何動作", remediesFor(it).isNotEmpty())
        }
    }

    @Test
    fun `states that need no warning offer no buttons`() {
        assertTrue(remediesFor(VoiceStatus.READY).isEmpty())
        assertTrue(remediesFor(VoiceStatus.CHECKING).isEmpty())
    }

    @Test
    fun `only the failure the rider can still hear may be dismissed`() {
        // 能被關掉的警告，和一開始就沒有的警告，對騎士來說沒有差別。
        assertTrue(dismissible(VoiceStatus.DEGRADED))
        VoiceStatus.entries.filter { it.silent }.forEach {
            assertFalse("$it 不該可以被關掉", dismissible(it))
        }
    }

    @Test
    fun `a missing voice pack sends the rider to the download flow first`() {
        assertEquals(VoiceRemedy.INSTALL_DATA, remediesFor(VoiceStatus.MISSING_DATA).first())
    }
}
