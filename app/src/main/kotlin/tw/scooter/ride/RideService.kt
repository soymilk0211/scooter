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
import tw.scooter.MainActivity
import tw.scooter.data.ScooterDatabase
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
                engine.evaluate(state)?.let { alert ->
                    RideRepository.onAlert(alert)
                    voice.duckOthers = RideRepository.duckOthers.value
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
        voice.prepare { ok -> RideRepository.onVoiceReady(ok) }
        RideRepository.onServiceStateChanged(running = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
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

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

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

        /** 1 秒。時速 60 時約每 17 公尺一次，足以在 300 公尺內穩定命中路口。 */
        private const val UPDATE_INTERVAL_MS = 1_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RideService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RideService::class.java))
        }
    }
}
