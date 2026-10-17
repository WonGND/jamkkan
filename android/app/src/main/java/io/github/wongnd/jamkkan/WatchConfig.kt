package io.github.wongnd.jamkkan

import android.content.Context
import org.json.JSONObject

/** 웹 화면이 setWatchConfig 로 넘겨준 '지켜볼 앱'과 '다시 묻지 않는 시간' */
data class WatchConfig(val apps: Map<String, String>, val cooldownMin: Long) {
    companion object {
        private const val PREFS = "watch"
        private const val KEY = "config"

        fun save(c: Context, json: String) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply()
        }

        fun load(c: Context): WatchConfig {
            val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return WatchConfig(emptyMap(), 10)
            return try {
                val o = JSONObject(raw)
                val arr = o.optJSONArray("apps")
                val apps = LinkedHashMap<String, String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i) ?: continue
                        val pkg = a.optString("pkg")
                        if (pkg.isNotEmpty()) apps[pkg] = a.optString("label", pkg)
                    }
                }
                WatchConfig(apps, o.optLong("cooldownMin", 10).coerceIn(1, 120))
            } catch (_: Exception) {
                WatchConfig(emptyMap(), 10)
            }
        }
    }
}
