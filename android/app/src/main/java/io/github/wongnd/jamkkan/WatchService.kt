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
 * 고른 앱이 앞으로 오면 잠깐만을 띄우는 서비스.
 * - 사용 정보(UsageStats)로 지금 앞에 있는 앱을 1초마다 확인
 * - 반복 실행 막기: 잠깐만에서 돌아간 경우는 무시, 같은 앱은 cooldownMin 동안 다시 안 띄움
 */
class WatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastFg: String? = null
    private val lastTrigger = HashMap<String, Long>()

    private val tick = object : Runnable {
        override fun run() {
            try {
                check()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        if (power != null && !power.isInteractive) return // 화면 꺼져 있으면 쉼

        val cfg = WatchConfig.load(this)
        if (cfg.apps.isEmpty() || !Perms.hasUsage(this)) {
            stopSelf()
            return
        }

        val usm = getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - WINDOW_MS, now)
        val e = UsageEvents.Event()
        var fg: String? = null
        var fgTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            // 1 = ACTIVITY_RESUMED (옛 이름 MOVE_TO_FOREGROUND)
            if (e.eventType == 1 && e.timeStamp >= fgTime) {
                fg = e.packageName
                fgTime = e.timeStamp
            }
        }
        if (fg == null || fg == lastFg) return

        val prev = lastFg
        lastFg = fg
        if (prev == null) return // 서비스가 막 켜졌을 때는 지금 앱을 기준으로만 삼음

        val label = cfg.apps[fg] ?: return
        if (prev == packageName) return // 잠깐만에서 돌아간 경우
        val last = lastTrigger[fg] ?: 0L
        if (now - last < cfg.cooldownMin * 60_000L) return
        lastTrigger[fg] = now
        launchBrake(fg, label)
    }

    private fun launchBrake(pkg: String, label: String) {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(MainActivity.EXTRA_ENTRY, "brake")
            putExtra(MainActivity.EXTRA_PKG, pkg)
            putExtra(MainActivity.EXTRA_LABEL, label)
        }
        // 안드로이드 15부터는 '다른 앱 위에 표시' 권한이 있어도 보이는 창이 있어야
        // 뒤에서 화면을 띄울 수 있어서, 1px 투명 창을 잠깐 띄운 채로 연다
        var overlay: View? = null
        val wm = getSystemService(WindowManager::class.java)
        if (wm != null && Settings.canDrawOverlays(this)) {
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
            startActivity(i)
        } catch (_: Exception) {
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

    companion object {
        private const val CHANNEL_ID = "watch"
        private const val NOTIFICATION_ID = 1
        private const val INTERVAL_MS = 1000L
        private const val WINDOW_MS = 15_000L

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
