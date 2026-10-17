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
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pageLoaded = false
    private var pendingJs: String? = null
    private var brakeIntentPending = false

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
                // 바깥 링크는 브라우저로
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageLoaded = true
                pendingJs?.let { view.evaluateJavascript(it, null) }
                pendingJs = null
            }
        }

        brakeIntentPending = isBrake(intent)
        webView.loadUrl(startUrl(intent))
    }

    /** 감시 중인 앱 때문에 다시 앞으로 올 때 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isBrake(intent)) {
            brakeIntentPending = true
            val info = JSONObject()
                .put("entry", "brake")
                .put("pkg", intent.getStringExtra(EXTRA_PKG) ?: "")
                .put("label", intent.getStringExtra(EXTRA_LABEL) ?: "")
            runJs("window.jamkkanLaunch && jamkkanLaunch($info)")
        }
    }

    override fun onResume() {
        super.onResume()
        if (brakeIntentPending) {
            brakeIntentPending = false
        } else if (pageLoaded) {
            // 런처나 최근 앱에서 직접 연 경우: 브레이크 화면이 아니라 홈으로
            runJs("window.jamkkanLaunch && jamkkanLaunch({entry:'app'})")
        }
        WatchService.sync(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 웹 화면 안의 뒤로 가기(판단 단계, 시트)를 먼저 처리하고, 더 없으면 원래 앱으로
        if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshGuide()
    }

    fun refreshGuide() {
        runJs("typeof renderGuide === 'function' && layers.includes('guide') && renderGuide()")
    }

    fun runJs(js: String) {
        if (pageLoaded) webView.evaluateJavascript(js, null) else pendingJs = js
    }

    private fun isBrake(i: Intent?): Boolean = i?.getStringExtra(EXTRA_ENTRY) == "brake"

    private fun startUrl(i: Intent?): String {
        val base = "https://$ASSET_HOST/assets/web/app/index.html"
        if (i == null || !isBrake(i)) return base
        val pkg = i.getStringExtra(EXTRA_PKG) ?: ""
        val label = i.getStringExtra(EXTRA_LABEL) ?: ""
        return "$base?entry=brake&pkg=${Uri.encode(pkg)}&label=${Uri.encode(label)}"
    }

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        const val REQ_NOTIFY = 1
    }
}
