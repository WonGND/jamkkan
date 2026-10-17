package io.github.wongnd.jamkkan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 고른 앱이 다 켜진 뒤 잠깐만을 띄우는 서비스.
 * 판단은 BrakeDetector(단위 테스트로 검증)가 하고, 여기서는 사용 정보를 모아 넘기고 화면을 띄우기만 한다.
 */
class WatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var detector: BrakeDetector

    private val tick = object : Runnable {
        override fun run() {
            try {
                check()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, if (detector.pendingPkg != null) FAST_INTERVAL_MS else INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        detector = BrakeDetector(packageName)
        startAsForeground()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "쇼핑앱 지켜보기", NotificationManager.IMPORTANCE_MIN)
        channel.setShowBadge(false)
        nm?.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle("잠깐만이 지켜보는 중")
            .setContentText("고른 앱이 켜지면 먼저 한 번 물어봐요")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun check() {
        val power = getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) { // 화면 꺼지면 쉼
            detector.screenOff()
            return
        }

        val cfg = WatchConfig.load(this)
        if (cfg.apps.isEmpty() || !Perms.hasUsage(this)) {
            stopSelf()
            return
        }

        val usm = getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val list = ArrayList<AppEvent>()
        val usage = usm.queryEvents(now - WINDOW_MS, now)
        val e = UsageEvents.Event()
        while (usage.hasNextEvent()) {
            usage.getNextEvent(e)
            if (e.eventType == BrakeDetector.RESUMED || e.eventType == BrakeDetector.PAUSED) {
                list.add(AppEvent(e.packageName, e.className, e.eventType, e.timeStamp))
            }
        }

        val launch = detector.tick(now, list, cfg.apps, cfg.cooldownMin, lastUserReturnAt) ?: return
        if (Settings.canDrawOverlays(this)) showBrake(launch) else notifyBrake(launch)
    }

    private fun brakeIntent(launch: BrakeLaunch): Intent =
        Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(MainActivity.EXTRA_ENTRY, "brake")
            putExtra(MainActivity.EXTRA_PKG, launch.pkg)
            putExtra(MainActivity.EXTRA_LABEL, launch.label)
            putExtra(MainActivity.EXTRA_AT, System.currentTimeMillis())
        }

    /** 잠깐만 화면을 앞으로 띄움 */
    private fun showBrake(launch: BrakeLaunch) {
        // 안드로이드 15부터는 '다른 앱 위에 표시' 권한이 있어도 보이는 창이 있어야
        // 뒤에서 화면을 띄울 수 있어서, 1px 투명 창을 잠깐 띄운 채로 연다
        var overlay: View? = null
        val wm = getSystemService(WindowManager::class.java)
        if (wm != null) {
            try {
                val v = View(this)
                val lp = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                wm.addView(v, lp)
                overlay = v
            } catch (_: Exception) {
            }
        }
        try {
            startActivity(brakeIntent(launch))
        } catch (_: Exception) {
            notifyBrake(launch)
        }
        val v = overlay
        if (v != null && wm != null) {
            handler.postDelayed({
                try {
                    wm.removeView(v)
                } catch (_: Exception) {
                }
            }, 1500)
        }
    }

    /** '다른 앱 위에 표시' 권한이 없으면 화면 대신 알림으로 알려 줌 (누르면 잠깐만) */
    private fun notifyBrake(launch: BrakeLaunch) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(ALERT_CHANNEL_ID, "잠깐만 알림", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(channel)
        val pi = PendingIntent.getActivity(
            this, 1, brakeIntent(launch),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle("잠깐만, 정말 필요한 거예요?")
            .setContentText("${launch.label}이(가) 켜졌어요. 눌러서 한 번 생각해 봐요")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        try {
            nm.notify(ALERT_NOTIFICATION_ID, n)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "watch"
        private const val ALERT_CHANNEL_ID = "brake"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        private const val INTERVAL_MS = 1000L
        private const val FAST_INTERVAL_MS = 400L
        private const val WINDOW_MS = 15_000L

        /** 잠깐만에서 '돌아가기'를 고른 시각 (그 직후 원래 앱이 앞으로 와도 다시 띄우지 않기 위해) */
        @Volatile
        var lastUserReturnAt = 0L

        fun markUserReturn() {
            lastUserReturnAt = System.currentTimeMillis()
        }

        /** 지켜볼 앱이 있고 사용 정보 권한이 있으면 켜고, 아니면 끔 */
        fun sync(c: Context) {
            val cfg = WatchConfig.load(c)
            val i = Intent(c, WatchService::class.java)
            if (cfg.apps.isNotEmpty() && Perms.hasUsage(c)) {
                try {
                    c.startForegroundService(i)
                } catch (_: Exception) {
                }
            } else {
                c.stopService(i)
            }
        }
    }
}
