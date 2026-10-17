package io.github.wongnd.jamkkan

/** 사용 정보(UsageEvents) 한 건. 단위 테스트에서도 쓰려고 안드로이드 타입 대신 씀 */
data class AppEvent(val pkg: String, val cls: String?, val type: Int, val time: Long)

/** 잠깐만을 띄워야 할 때 돌려주는 값 */
data class BrakeLaunch(val pkg: String, val label: String)

/**
 * '고른 앱이 다 켜졌는지, 잠깐만을 띄워도 되는지'를 판단하는 순수 로직.
 * 안드로이드 기능을 쓰지 않아서 JVM 단위 테스트(BrakeDetectorTest)로 상황별 동작을 검증한다.
 *
 * 다 켜졌다고 보는 기준
 *  1) 그 앱의 화면 전환이 SETTLE_MS(1초) 동안 없으면 다 켜진 것
 *  2) 지금 화면 이름이 로딩·인트로·잠금·로그인·본인인증처럼 보이면 끝날 때까지 기다림 (최대 WAIT_SCREEN_MAX_MS, 넘으면 이번엔 안 물음)
 *  3) 화면이 계속 바뀌어도 FORCE_MS(8초)가 지나면 띄움
 *  4) 잠깐만이 뜬 뒤 그 앱이 다시 덮으면(사용자가 돌아가기를 고르지 않았는데) 다시 다 켜졌을 때 띄움 (최대 MAX_RECOVERIES번)
 *
 * 반복 실행 막기
 *  - 잠깐만에서 돌아가기(버튼·뒤로 가기)를 고른 직후 원래 앱이 앞으로 오면 무시
 *  - 잠깐만에서 최근 앱으로 같은 앱에 돌아가면 같은 흐름으로 보고 무시
 *  - 같은 앱은 cooldownMin 동안 다시 안 띄움 (0이면 매번)
 */
class BrakeDetector(private val ownPkg: String) {

    var lastFg: String? = null
        private set
    var pendingPkg: String? = null
        private set

    private val lastTrigger = HashMap<String, Long>()
    private var pendingLabel = ""
    private var pendingSince = 0L
    private var pendingLastChange = 0L
    private var pendingClass = ""

    private var shownPkg: String? = null
    private var shownAt = 0L
    private var recoveries = 0

    /** 화면이 꺼지면 기다리던 것을 버림 */
    fun screenOff() {
        pendingPkg = null
    }

    /**
     * @param events 최근 몇 초의 사용 정보 (시간 순)
     * @param apps 지켜볼 앱 (패키지 → 이름)
     * @param userReturnAt 잠깐만에서 '돌아가기'를 고른 시각
     */
    fun tick(
        now: Long,
        events: List<AppEvent>,
        apps: Map<String, String>,
        cooldownMin: Long,
        userReturnAt: Long
    ): BrakeLaunch? {
        val watching = pendingPkg
        var fg: String? = null
        var fgTime = Long.MIN_VALUE
        var pChange = Long.MIN_VALUE
        var pClass: String? = null
        var pClassTime = Long.MIN_VALUE
        for (e in events) {
            if (e.type == RESUMED && e.time >= fgTime) {
                fg = e.pkg
                fgTime = e.time
            }
            if (watching != null && e.pkg == watching && (e.type == RESUMED || e.type == PAUSED)) {
                if (e.time > pChange) pChange = e.time
                if (e.type == RESUMED && e.time >= pClassTime) {
                    pClass = e.cls
                    pClassTime = e.time
                }
            }
        }

        if (fg != null && fg != lastFg) {
            val prev = lastFg
            lastFg = fg
            onForegroundChanged(prev, fg, apps, cooldownMin, userReturnAt, now)
        }

        val p = pendingPkg ?: return null
        if (lastFg != p) { // 다 켜지기 전에 다른 앱으로 감
            pendingPkg = null
            return null
        }
        if (p == watching) {
            if (pChange > pendingLastChange) pendingLastChange = pChange
            val c = pClass
            if (c != null) pendingClass = c
        }

        val waited = now - pendingSince
        if (isWaitingScreen(pendingClass)) {
            if (waited >= WAIT_SCREEN_MAX_MS) pendingPkg = null
            return null
        }
        val ready = now - pendingLastChange >= SETTLE_MS || waited >= FORCE_MS
        if (!ready) return null

        pendingPkg = null
        lastTrigger[p] = now
        shownPkg = p
        shownAt = now
        return BrakeLaunch(p, pendingLabel)
    }

    private fun onForegroundChanged(
        prev: String?,
        fg: String,
        apps: Map<String, String>,
        cooldownMin: Long,
        userReturnAt: Long,
        now: Long
    ) {
        if (prev == null) return // 감지가 막 켜졌을 때는 지금 앱을 기준으로만 삼음
        if (pendingPkg != null && pendingPkg != fg) pendingPkg = null
        val label = apps[fg] ?: return

        if (prev == ownPkg && fg == shownPkg) {
            // 잠깐만 → 방금 물어본 그 앱
            if (now - userReturnAt < USER_RETURN_GRACE_MS) return // 돌아가기를 고름
            if (now - shownAt < COVERED_WINDOW_MS && recoveries < MAX_RECOVERIES) {
                recoveries++ // 앱이 잠깐만을 덮음 → 다시 다 켜지면 한 번 더
                startPending(fg, label, now)
            }
            return // 최근 앱으로 같은 앱에 돌아감 → 같은 흐름
        }

        val last = lastTrigger[fg]
        if (cooldownMin > 0 && last != null && now - last < cooldownMin * 60_000L) return
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

    companion object {
        const val RESUMED = 1 // UsageEvents.Event.ACTIVITY_RESUMED (옛 MOVE_TO_FOREGROUND)
        const val PAUSED = 2  // UsageEvents.Event.ACTIVITY_PAUSED
        const val SETTLE_MS = 1000L
        const val FORCE_MS = 8000L
        const val WAIT_SCREEN_MAX_MS = 60_000L
        const val USER_RETURN_GRACE_MS = 3000L
        const val COVERED_WINDOW_MS = 8000L
        const val MAX_RECOVERIES = 2

        // 로딩·인트로·잠금·로그인·본인인증처럼 보이는 화면 이름
        private val WAIT_SCREEN = Regex(
            "splash|intro|loading|lock|passcode|pincode|pattern|auth|login|signin|sign_in|certif|verify|password",
            RegexOption.IGNORE_CASE
        )

        fun isWaitingScreen(cls: String): Boolean =
            cls.isNotEmpty() && WAIT_SCREEN.containsMatchIn(cls.substringAfterLast('.'))
    }
}
