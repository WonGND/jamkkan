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
 * 고른 앱이 '다 켜진 뒤'에 잠깐만을 띄우는 서비스.
 *
 * 다 켜졌다고 보는 기준
 *  1) 그 앱의 화면 전환이 SETTLE_MS(1초) 동안 없으면 다 켜진 것
 *  2) 지금 화면 이름이 로딩·인트로·잠금·로그인·본인인증처럼 보이면 그 화면이 끝날 때까지 기다림 (최대 WAIT_SCREEN_MAX_MS)
 *  3) 화면이 계속 바뀌어도 FORCE_MS(8초)가 지나면 띄움
 *  4) 잠깐만이 뜬 뒤 그 앱이 다시 덮으면(사용자가 아무것도 안 눌렀는데) 다시 다 켜졌을 때 한 번 더 띄움
 *
 * 반복 실행 막기
 *  - 잠깐만에서 버튼·뒤로 가기로 돌아간 경우는 무시
 *  - 같은 앱은 cooldownMin 동안 다시 안 띄움 (0이면 매번)
 */
class WatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastFg: String? = null
    private val lastTrigger = HashMap<String, Long>()

    // 다 켜지기를 기다리는 앱
    private var pendingPkg: String? = null
    private var pendingLabel = ""
    private var pendingSince = 0L
    private var pendingLastChange = 0L
    private var pendingClass = ""

    // 마지막으로 띄운 브레이크
    private var shownPkg: String? = null
    private var shownAt = 0L
    private var recoveries = 0

    private val tick = object : Runnable {
        override fun run() {
            try {
                check()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, if (pendingPkg != null) FAST_INTERVAL_MS else INTERVAL_MS)
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
        if (power != null && !power.isInteractive) { // 화면 꺼지면 쉼
            pendingPkg = null
            return
        }

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
        var pChange = 0L
        var pClass: String? = null
        var pClassTime = 0L
        val watching = pendingPkg
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.timeStamp
            // 1 = ACTIVITY_RESUMED, 2 = ACTIVITY_PAUSED
            if (e.eventType == 1 && t >= fgTime) {
                fg = e.packageName
                fgTime = t
            }
            if (watching != null && e.packageName == watching && (e.eventType == 1 || e.eventType == 2)) {
                if (t > pChange) pChange = t
                if (e.eventType == 1 && t >= pClassTime) {
                    pClass = e.className
                    pClassTime = t
                }
            }
        }

        if (fg != null && fg != lastFg) {
            val prev = lastFg
            lastFg = fg
            onForegroundChanged(prev, fg, cfg, now)
        }

        val p = pendingPkg ?: return
        if (lastFg != p) { // 다 켜지기 전에 다른 앱으로 감
            pendingPkg = null
            return
        }
        if (p == watching) {
            if (pChange > pendingLastChange) pendingLastChange = pChange
            if (pClass != null) pendingClass = pClass
        }
        val waitingScreen = WAIT_SCREEN.containsMatchIn(pendingClass.substringAfterLast('.'))
        val waited = now - pendingSince
        val stable = now - pendingLastChange
        val ready = when {
            waitingScreen -> false
            stable >= SETTLE_MS -> true
            waited >= FORCE_MS -> true
            else -> false
        }
        if (waitingScreen && waited >= WAIT_SCREEN_MAX_MS) { // 잠금·로그인 화면에 오래 머물면 이번엔 묻지 않음
            pendingPkg = null
            return
        }
        if (ready) {
            pendingPkg = null
            lastTrigger[p] = now
            shownPkg = p
            shownAt = now
            launchBrake(p, pendingLabel)
        }
    }

    private fun onForegroundChanged(prev: String?, fg: String, cfg: WatchConfig, now: Long) {
        if (prev == null) return // 서비스가 막 켜졌을 때는 지금 앱을 기준으로만 삼음
        if (pendingPkg != null && pendingPkg != fg) pendingPkg = null
        val label = cfg.apps[fg] ?: return

        if (prev == packageName) {
            // 잠깐만 → 이 앱으로 바뀜
            if (now - lastUserReturnAt < USER_RETURN_GRACE_MS) return // 사용자가 돌아가기를 고른 경우
            if (shownPkg == fg && now - shownAt < COVERED_WINDOW_MS && recoveries < MAX_RECOVERIES) {
                recoveries++ // 앱이 잠깐만을 덮음 → 다시 다 켜지면 한 번 더 띄움
                startPending(fg, label, now)
            }
            return
        }

        val last = lastTrigger[fg] ?: 0L
        if (cfg.cooldownMin > 0 && now - last < cfg.cooldownMin * 60_000L) return
        recoveries = 0
        startPending(fg, label, now)
    }

    private fun startPending(pkg: String, label: String, now: Long) {
        pendingPkg = pkg
        pendingLabel = label
        pendingSince = now
        pendingLastChange = now
        pendingClass = ""
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
        private const val FAST_INTERVAL_MS = 400L
        private const val WINDOW_MS = 15_000L
        private const val SETTLE_MS = 1000L
        private const val FORCE_MS = 8000L
        private const val WAIT_SCREEN_MAX_MS = 60_000L
        private const val USER_RETURN_GRACE_MS = 3000L
        private const val COVERED_WINDOW_MS = 8000L
        private const val MAX_RECOVERIES = 2

        // 로딩·인트로·잠금·로그인·본인인증처럼 보이는 화면 이름
        private val WAIT_SCREEN = Regex(
            "splash|intro|loading|lock|passcode|pincode|pattern|auth|login|signin|sign_in|certif|verify|password",
            RegexOption.IGNORE_CASE
        )

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
