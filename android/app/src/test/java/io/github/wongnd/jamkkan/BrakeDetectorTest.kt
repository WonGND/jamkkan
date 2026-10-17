package io.github.wongnd.jamkkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 실제 휴대폰에서 일어나는 상황을 사용 정보 이벤트로 흉내 내서
 * '언제 잠깐만이 뜨고, 언제 안 뜨는지'를 확인한다.
 * 서비스처럼 0.4초마다 확인하고, 최근 15초의 이벤트만 본다.
 */
class BrakeDetectorTest {

    private val own = "io.github.wongnd.jamkkan"
    private val coupang = "com.coupang.mobile"
    private val baemin = "com.sampleapp"
    private val launcher = "com.sec.android.app.launcher"
    private val apps = mapOf(coupang to "쿠팡", baemin to "배달의민족")

    private lateinit var d: BrakeDetector
    private val events = ArrayList<AppEvent>()
    private val launches = ArrayList<BrakeLaunch>()
    private var now = 10_000_000L
    private var userReturnAt = 0L
    private var cooldown = 10L

    @Before
    fun setUp() {
        d = BrakeDetector(own)
        // 감지가 켜진 뒤 홈 화면을 한 번 본 상태에서 시작
        open(launcher, "Launcher")
        advance(400)
    }

    private fun open(pkg: String, cls: String = "MainActivity") {
        events.add(AppEvent(pkg, "$pkg.$cls", BrakeDetector.RESUMED, now))
    }

    private fun tick() {
        val window = events.filter { it.time > now - 15_000 && it.time <= now }
        d.tick(now, window, apps, cooldown, userReturnAt)?.let { launches.add(it) }
    }

    private fun advance(ms: Long) {
        val end = now + ms
        while (now < end) {
            now += 400
            tick()
        }
    }

    private fun jump(ms: Long) {
        now += ms
    }

    /** 잠깐만이 실제로 앞에 뜬 상태 */
    private fun brakeOnScreen() {
        open(own)
        advance(400)
    }

    /** 잠깐만에서 '그냥 둘러보는 중'이나 뒤로 가기로 돌아감 */
    private fun userReturnsTo(pkg: String) {
        userReturnAt = now
        open(pkg)
        advance(400)
    }

    @Test
    fun 감지가_막_켜졌을_때_이미_열려_있던_앱은_무시() {
        val fresh = BrakeDetector(own)
        events.clear()
        open(coupang)
        repeat(10) {
            now += 400
            val window = events.filter { it.time > now - 15_000 && it.time <= now }
            assertEquals(null, fresh.tick(now, window, apps, cooldown, userReturnAt))
        }
    }

    @Test
    fun 앱_화면이_1초_멈추면_띄움() {
        open(coupang)
        advance(1200)
        assertEquals(0, launches.size)
        advance(800)
        assertEquals(listOf(BrakeLaunch(coupang, "쿠팡")), launches)
    }

    @Test
    fun 스플래시_화면에서는_기다렸다가_본_화면이_뜬_뒤_띄움() {
        open(coupang, "SplashActivity")
        advance(3000)
        assertEquals(0, launches.size)
        open(coupang, "MainActivity")
        advance(800)
        assertEquals(0, launches.size)
        advance(1200)
        assertEquals(1, launches.size)
    }

    @Test
    fun 잠금_패턴_화면에서는_풀_때까지_기다림() {
        open(coupang, "PatternLockActivity")
        advance(10_000)
        assertEquals(0, launches.size)
        open(coupang, "MainActivity")
        advance(2000)
        assertEquals(1, launches.size)
    }

    @Test
    fun 잠금_화면에_1분_넘게_있으면_이번엔_묻지_않음() {
        open(coupang, "LoginActivity")
        advance(61_000)
        open(coupang, "MainActivity")
        advance(3000)
        assertEquals(0, launches.size)
    }

    @Test
    fun 화면이_계속_바뀌어도_8초가_지나면_띄움() {
        var t = 0L
        while (t < 7600) {
            open(coupang, if ((t / 400) % 2 == 0L) "HomeActivity" else "FeedActivity")
            advance(400)
            t += 400
        }
        assertEquals(0, launches.size)
        open(coupang, "HomeActivity")
        advance(800)
        assertEquals(1, launches.size)
    }

    @Test
    fun 다_켜지기_전에_홈으로_나가면_안_띄움() {
        open(coupang)
        advance(400)
        open(launcher, "Launcher")
        advance(3000)
        assertEquals(0, launches.size)
    }

    @Test
    fun 홈을_누르고_다른_앱으로_가면_그_앱이_감시_대상일_때만_띄움() {
        open(coupang)
        advance(400)
        open(launcher, "Launcher")
        advance(400)
        open("com.kakao.talk")
        advance(3000)
        assertEquals(0, launches.size)
        open(launcher, "Launcher")
        advance(400)
        open(baemin)
        advance(2000)
        assertEquals(listOf(BrakeLaunch(baemin, "배달의민족")), launches)
    }

    @Test
    fun 돌아가기를_고르면_원래_앱으로_가도_다시_안_띄움() {
        open(coupang)
        advance(2000)
        brakeOnScreen()
        userReturnsTo(coupang)
        advance(5000)
        assertEquals(1, launches.size)
    }

    @Test
    fun 잠깐만이_앱에_가려지면_다시_띄움_최대_두_번() {
        open(coupang)
        advance(2000)
        assertEquals(1, launches.size)
        repeat(3) {
            brakeOnScreen()
            open(coupang, "MainActivity") // 사용자가 아무것도 안 눌렀는데 앱이 덮음
            advance(2000)
        }
        assertEquals(3, launches.size)
    }

    @Test
    fun 다시_묻기_간격_안에는_다시_안_띄우고_지나면_띄움() {
        cooldown = 10
        open(coupang)
        advance(2000)
        brakeOnScreen()
        userReturnsTo(coupang)
        open(launcher, "Launcher")
        advance(400)
        jump(5 * 60_000L)
        open(coupang)
        advance(2000)
        assertEquals(1, launches.size)
        open(launcher, "Launcher")
        advance(400)
        jump(6 * 60_000L)
        open(coupang)
        advance(2000)
        assertEquals(2, launches.size)
    }

    @Test
    fun 매번이면_홈에_갔다_다시_열_때마다_띄움() {
        cooldown = 0
        open(coupang)
        advance(2000)
        brakeOnScreen()
        userReturnsTo(coupang)
        advance(2000)
        assertEquals(1, launches.size) // 돌아가기 직후에는 '매번'이어도 안 띄움
        open(launcher, "Launcher")
        advance(400)
        open(coupang)
        advance(2000)
        assertEquals(2, launches.size)
    }

    @Test
    fun 잠깐만에서_최근_앱으로_같은_앱에_돌아가면_같은_흐름으로_봄() {
        open(coupang)
        advance(2000)
        brakeOnScreen()
        jump(20_000) // 가려짐 판단 시간이 지난 뒤
        open(coupang)
        advance(3000)
        assertEquals(1, launches.size)
    }

    @Test
    fun 잠깐만에서_최근_앱으로_다른_감시_앱에_가면_그_앱으로_띄움() {
        open(coupang)
        advance(2000)
        brakeOnScreen()
        open(baemin)
        advance(2000)
        assertEquals(listOf(BrakeLaunch(coupang, "쿠팡"), BrakeLaunch(baemin, "배달의민족")), launches)
    }

    @Test
    fun 화면이_꺼지면_기다리던_것을_버림() {
        open(coupang)
        advance(400)
        d.screenOff()
        advance(3000)
        assertEquals(0, launches.size)
    }

    @Test
    fun 다른_앱의_권한_창을_거쳐_돌아오면_다시_기다렸다_띄움() {
        open(coupang, "SplashActivity")
        advance(800)
        open("com.google.android.permissioncontroller", "GrantPermissionsActivity")
        advance(2000)
        assertEquals(0, launches.size)
        open(coupang, "MainActivity")
        advance(2000)
        assertEquals(1, launches.size)
    }

    @Test
    fun 잠깐만을_직접_연_뒤_쇼핑앱으로_가면_새로_연_것으로_봄() {
        open(own)
        advance(800)
        open(coupang)
        advance(2000)
        assertEquals(1, launches.size)
    }

    @Test
    fun 감시하지_않는_앱은_무시() {
        open("com.kakao.talk")
        advance(5000)
        assertEquals(0, launches.size)
        assertEquals(null, d.pendingPkg)
    }

    @Test
    fun 기다릴_화면_이름_판단() {
        for (c in listOf("SplashActivity", "IntroActivity", "LockScreenActivity", "LoginActivity", "PinCodeActivity", "AuthActivity", "com.x.ui.PatternActivity")) {
            assertTrue(c, BrakeDetector.isWaitingScreen(c))
        }
        for (c in listOf("MainActivity", "HomeActivity", "ProductDetailActivity", "com.coupang.mobile.MainTabActivity", "")) {
            assertFalse(c, BrakeDetector.isWaitingScreen(c))
        }
    }
}
