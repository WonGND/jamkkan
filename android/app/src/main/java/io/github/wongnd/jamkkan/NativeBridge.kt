package io.github.wongnd.jamkkan

import android.content.Intent
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * 웹 화면에서 window.JamkkanNative 로 부르는 기능들. 모든 값은 JSON 문자열.
 * (JavascriptInterface 는 백그라운드 스레드에서 불리므로 화면 작업은 runOnUiThread)
 */
class NativeBridge(private val activity: MainActivity) {

    /** 런처에 보이는 설치된 앱 목록: [{"pkg","label"}] */
    @JavascriptInterface
    fun getInstalledApps(): String {
        val pm = activity.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(query, 0)
        val seen = HashSet<String>()
        val apps = ArrayList<Pair<String, String>>()
        for (ri in list) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == activity.packageName || !seen.add(pkg)) continue
            apps.add(pkg to ri.loadLabel(pm).toString())
        }
        apps.sortBy { it.second }
        val arr = JSONArray()
        for ((pkg, label) in apps) arr.put(JSONObject().put("pkg", pkg).put("label", label))
        return arr.toString()
    }

    /** {"apps":[{"pkg","label"}],"cooldownMin":10} 저장 → 감지 서비스 켜기/끄기 */
    @JavascriptInterface
    fun setWatchConfig(json: String) {
        WatchConfig.save(activity, json)
        activity.runOnUiThread { WatchService.sync(activity) }
    }

    /** {"usage":bool,"overlay":bool,"notify":bool,"battery":bool} */
    @JavascriptInterface
    fun getPermissionState(): String = JSONObject()
        .put("usage", Perms.hasUsage(activity))
        .put("overlay", Perms.hasOverlay(activity))
        .put("notify", Perms.hasNotify(activity))
        .put("battery", Perms.hasBattery(activity))
        .toString()

    /** usage / overlay / notify 설정 화면 열기 */
    @JavascriptInterface
    fun openPermissionSettings(kind: String) {
        activity.runOnUiThread { Perms.open(activity, kind) }
    }

    /** 원래 앱(쇼핑·결제 앱)으로 돌아가기 */
    @JavascriptInterface
    fun returnToPreviousApp() {
        WatchService.markUserReturn()
        activity.runOnUiThread { activity.moveTaskToBack(true) }
    }
}
