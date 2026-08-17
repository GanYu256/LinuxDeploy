package io.github.ganyu256.linuxdeploypro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.ganyu256.linuxdeploypro.data.CliManager
import io.github.ganyu256.linuxdeploypro.data.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 开机自启：系统 BOOT_COMPLETED 后拉起 config/.autostart 中标记的容器 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CoroutineScope(Dispatchers.IO).launch {
            ConfigStore.autostartList(context).forEach { name ->
                try {
                    CliManager.start(context, name) { }
                } catch (_: Exception) {
                }
            }
        }
    }
}
