package io.github.wongnd.jamkkan

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * 잠깐만 화면 = 기존 웹 화면(app/index.html)을 WebView로 띄운 것.
 * 웹 쪽은 window.JamkkanNative 가 있으면 네이티브 모드로 동작한다 (docs/NATIVE.md).
 *
 * 브레이크/홈 구분은 '어떻게 열렸는지'(인텐트)로만 한다.
 *  - 감지 서비스가 연 경우: entry=brake
 *  - 앱 아이콘으로 연 경우: 홈
 *  - 최근 앱에서 돌아온 경우: 보던 화면 그대로 (오래된 브레이크는 웹이 알아서 홈으로)
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pageLoaded = false
    private val pendingJs = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true // 기록 저장(localStorage)
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.addJavascriptInterface(NativeBridge(this), "JamkkanNative")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == ASSET_HOST) return false
                // 바깥 링크는 다른 앱으로
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageLoaded = true
                for (js in pendingJs) view.evaluateJavascript(js, null)
                pendingJs.clear()
            }
        }

        webView.loadUrl(startUrl(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val info = if (isBrake(intent)) {
            JSONObject()
                .put("entry", "brake")
                .put("pkg", intent.getStringExtra(EXTRA_PKG) ?: "")
                .put("label", intent.getStringExtra(EXTRA_LABEL) ?: "")
                .put("at", intent.getLongExtra(EXTRA_AT, System.currentTimeMillis()))
        } else {
            JSONObject().put("entry", "app") // 앱 아이콘으로 다시 엶
        }
        runJs("window.jamkkanLaunch && jamkkanLaunch($info)")
    }

    override fun onResume() {
        super.onResume()
        WatchService.sync(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!pageLoaded) {
            leaveToPreviousApp()
            return
        }
        // 웹 화면에 열린 창(판단 단계, 시트, 설정 등)이 있으면 그걸 먼저 닫고, 없으면 원래 앱으로
        webView.evaluateJavascript("window.jamkkanBack ? jamkkanBack() : false") { handled ->
            if (handled != "true") leaveToPreviousApp()
        }
    }

    private fun leaveToPreviousApp() {
        WatchService.markUserReturn()
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshGuide()
    }

    fun refreshGuide() {
        runJs("typeof renderGuide === 'function' && layers.includes('guide') && renderGuide()")
    }

    fun runJs(js: String) {
        if (pageLoaded) webView.evaluateJavascript(js, null) else pendingJs.add(js)
    }

    private fun isBrake(i: Intent?): Boolean = i?.getStringExtra(EXTRA_ENTRY) == "brake"

    private fun startUrl(i: Intent?): String {
        val base = "https://$ASSET_HOST/assets/web/app/index.html"
        if (i == null || !isBrake(i)) return base
        val pkg = i.getStringExtra(EXTRA_PKG) ?: ""
        val label = i.getStringExtra(EXTRA_LABEL) ?: ""
        val at = i.getLongExtra(EXTRA_AT, System.currentTimeMillis())
        return "$base?entry=brake&pkg=${Uri.encode(pkg)}&label=${Uri.encode(label)}&at=$at"
    }

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        const val EXTRA_AT = "at"
        const val REQ_NOTIFY = 1
    }
}
