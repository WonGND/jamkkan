package io.github.wongnd.jamkkan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅하거나 앱을 업데이트한 뒤에도 감지를 다시 켬 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WatchService.sync(context)
        }
    }
}
