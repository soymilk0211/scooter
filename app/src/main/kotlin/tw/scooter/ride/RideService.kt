package tw.scooter.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tw.scooter.MainActivity
import tw.scooter.data.ScooterDatabase
import tw.scooter.settings.SettingsStore
import tw.scooter.R

/**
 * 騎乘期間的前景服務。
 *
 * 必須是前景服務而非背景工作 —— 騎士會切到 Google Maps，App 進入背景後
 * 一般背景工作會被系統節流甚至清除，而漏掉的警示就是漏掉的路口。
 *
 * 即便如此，部分廠牌 ROM 仍會在螢幕關閉後清掉前景服務。偵測與告知的機制
 * 尚未實作（見「騎乘前檢查」待辦）—— 靜默失效比不能用更危險。
 */
class RideService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val engine by lazy { AlertEngine(ScooterDatabase.open(this)) }
    private val voice by lazy { AlertVoice(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            RideRepository.onLocation(location)
            // 判定緊接在定位之後，同一條執行緒 —— 路口只有幾秒的判定窗，
            // 排到別的排程器上等待是拿安全換架構整潔。
            RideRepository.state.value?.let { state ->
                val alerts = engine.evaluate(state)
                RideRepository.onSpeedLimit(alerts.speedLimitKmh)
                voice.duckOthers = RideRepository.duckOthers.value
                // 測速先，路口後。這不是優先度，是時窗位置：測速在 500–320 公尺
                // 之間講，路口在 300 公尺以內講，同一輪同時命中本來就少見。
                alerts.enforcement?.let { seen ->
                    RideRepository.onEnforcement(seen)
                    voice.speakEnforcement(seen.point.speedLimitKmh, seen.overSpeed)
                }
                alerts.turn?.let { alert ->
                    RideRepository.onAlert(alert)
                    voice.speak(alert.rule.rule)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        requestUpdates()
        checkVoice()
        followSettings()
        RideRepository.onServiceStateChanged(running = true)
    }

    /**
     * 設定由服務自己訂閱，不等畫面來推。
     *
     * 服務被 START_STICKY 拉回來時 Activity 可能根本不存在（騎士切去 Google Maps
     * 之後系統回收了它），那時若靠畫面來設定，衰減開關就會悄悄回到預設值。
     */
    private fun followSettings() {
        scope.launch {
            SettingsStore.flow(this@RideService)
                .map { it.duckOthers }
                .distinctUntilChanged()
                .collect { RideRepository.setDuckOthers(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // 騎士照著警告畫面去裝了語音資料，回來按「重新檢查」。重跑整套檢查，
            // 不必重啟 App —— 要他重啟就等於要他重新授權、重新等 GPS 定位。
            ACTION_RECHECK_VOICE -> checkVoice()
            ACTION_REFRESH_VOICE -> voice.refreshStatus()
        }
        return START_STICKY
    }

    private fun checkVoice() {
        voice.prepare { status ->
            RideRepository.onVoiceStatus(status)
            // 騎士切到 Google Maps 之後，這則常駐通知是唯一還看得到的地方。
            updateNotification(status)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        voice.release()
        client.removeLocationUpdates(callback)
        RideRepository.onServiceStateChanged(running = false)
        super.onDestroy()
    }

    private fun requestUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            client.requestLocationUpdates(request, callback, mainLooper)
        } catch (e: SecurityException) {
            // 權限在服務啟動後被撤銷。停止自己，讓 UI 重新引導授權。
            stopSelf()
        }
    }

    /**
     * 常駐通知會反映語音狀態。
     *
     * 這不是理想的通道 —— 頻道是 IMPORTANCE_LOW，不會彈出來 —— 但騎士切到
     * Google Maps 之後，在懸浮視窗做出來之前，它是唯一還能改變的畫面。
     */
    private fun buildNotification(status: VoiceStatus): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (status.silent) R.string.service_title_silent else R.string.service_title
        val text = when {
            status.silent -> R.string.service_text_silent
            status == VoiceStatus.DEGRADED -> R.string.service_text_degraded
            else -> R.string.service_text
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(title))
            .setContentText(getString(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(WARNING_COLOR.takeIf { status.needsWarning } ?: 0)
            .setColorized(status.silent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: VoiceStatus) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun startInForeground() {
        val notification = buildNotification(VoiceStatus.CHECKING)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "ride"
        private const val NOTIFICATION_ID = 1
        private const val WARNING_COLOR = 0xFFFF453A.toInt()

        /** 1 秒。時速 60 時約每 17 公尺一次，足以在 300 公尺內穩定命中路口。 */
        private const val UPDATE_INTERVAL_MS = 1_000L

        private const val ACTION_RECHECK_VOICE = "tw.scooter.RECHECK_VOICE"
        private const val ACTION_REFRESH_VOICE = "tw.scooter.REFRESH_VOICE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RideService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RideService::class.java))
        }

        /** 重跑整套語音檢查，包含重新初始化引擎與補合成音檔。 */
        fun recheckVoice(context: Context) = sendIfRunning(context, ACTION_RECHECK_VOICE)

        /**
         * 只重問一次音量。畫面回到前景時呼叫 —— 騎士可能是切出去調音量才回來的，
         * 為了這件事重跑引擎初始化太重了。
         */
        fun refreshVoiceStatus(context: Context) = sendIfRunning(context, ACTION_REFRESH_VOICE)

        /**
         * 服務沒在跑就不送。否則一個純粹的「查一下狀態」會把整個定位服務叫起來，
         * 而騎士根本沒有要開始騎。
         */
        private fun sendIfRunning(context: Context, action: String) {
            if (!RideRepository.serviceRunning.value) return
            context.startForegroundService(
                Intent(context, RideService::class.java).setAction(action),
            )
        }
    }
}
