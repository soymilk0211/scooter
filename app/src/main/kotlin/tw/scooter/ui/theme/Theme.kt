package tw.scooter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 極簡深色為預設。騎乘中的畫面只有三種顏色職責：底、字、以及一個提醒色。
 * 不使用漸層、陰影或裝飾邊框 —— 陽光下看得清楚比好看重要。
 */
object ScooterColors {
    val Ink = Color(0xFF000000)          // 純黑，OLED 省電且對比最高
    val Surface = Color(0xFF121214)      // 浮起的面
    val SurfaceMuted = Color(0xFF1C1C1F) // 鎖定狀態的面
    val Line = Color(0xFF2A2A2E)

    val Text = Color(0xFFF5F5F7)
    val TextMuted = Color(0xFF8A8A8E)
    val TextDisabled = Color(0xFF4A4A4F)

    val Amber = Color(0xFFFFB020)        // 待轉／需要注意
    val Green = Color(0xFF32D74B)        // 可直接左轉

    // 第四種顏色職責，僅供一件事使用：**這台裝置現在保護不了你**。
    // 用滿版填色而非邊框或圖示，因為它必須在陽光下用眼角餘光就看得到。
    val Alarm = Color(0xFFFF453A)
    val AlarmFace = Color(0xFF7A1710)    // 警告面上的按鈕，仍在紅色系內
    val OnAlarm = Color(0xFFFFFFFF)

    // 淺色模式的面必須比底圖**暗**。極簡淺色底圖的地表接近純白，先前 #F2F2F5 的卡片
    // 疊上去等於消失；而卡片與按鈕原本同一個色，回報按鈕在淺色下也分不出鎖定與否。
    // 這裡沿用深色的邏輯 —— 浮起的面比底下的面亮一階。
    val LightInk = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFE8E8EC)       // 卡片面，也是按鈕鎖定時的面
    val LightSurfaceRaised = Color(0xFFFFFFFF) // 可按的按鈕面
    val LightLine = Color(0xFFD0D0D6)
    val LightText = Color(0xFF101012)
    val LightTextMuted = Color(0xFF6C6C70)
}

private val Dark = darkColorScheme(
    background = ScooterColors.Ink,
    surface = ScooterColors.Surface,
    surfaceVariant = ScooterColors.SurfaceMuted,
    onBackground = ScooterColors.Text,
    onSurface = ScooterColors.Text,
    onSurfaceVariant = ScooterColors.TextMuted,
    primary = ScooterColors.Amber,
    onPrimary = ScooterColors.Ink,
    outline = ScooterColors.Line,
)

private val Light = lightColorScheme(
    background = ScooterColors.LightInk,
    surface = ScooterColors.LightSurface,
    surfaceVariant = ScooterColors.LightSurfaceRaised,
    onBackground = ScooterColors.LightText,
    onSurface = ScooterColors.LightText,
    onSurfaceVariant = ScooterColors.LightTextMuted,
    primary = ScooterColors.Amber,
    onPrimary = ScooterColors.LightInk,
    outline = ScooterColors.LightLine,
)

private val ScooterTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.4.sp),
)

enum class AppearanceMode { SYSTEM, DARK, LIGHT }

/**
 * 這個外觀模式最終是深色還是淺色。
 *
 * 獨立出來是因為底圖也要問同一個問題 —— 地圖的皮如果自己算一次「現在是不是深色」，
 * 遲早會跟介面的答案不一致，而半深半淺的畫面比兩者都錯還難看。
 */
@Composable
fun AppearanceMode.resolvesToDark(): Boolean = when (this) {
    AppearanceMode.SYSTEM -> isSystemInDarkTheme()
    AppearanceMode.DARK -> true
    AppearanceMode.LIGHT -> false
}

@Composable
fun ScooterTheme(
    mode: AppearanceMode = AppearanceMode.DARK,
    content: @Composable () -> Unit,
) {
    val dark = mode.resolvesToDark()
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = ScooterTypography,
        content = content,
    )
}
