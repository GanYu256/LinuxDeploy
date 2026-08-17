package io.github.ganyu256.linuxdeploypro.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 长任务保活前台服务。
 *
 * 部署 / 导出等 CLI 长任务需要前台服务保活。
 * 本服务在任务期间：
 * 1. startForeground 提升进程优先级（Android 前台服务进程一般不被回收）；
 * 2. PARTIAL_WAKE_LOCK 保持 CPU 唤醒（锁屏时 CLI 子进程不被挂起）。
 * 服务本身不执行 CLI，只做保活壳；任务结束由 MainScreen 调用 stopService。
 *
 * 调用约定：API 26+ 必须用 startForegroundService()，本服务 onCreate 立即
 * startForeground，满足 5 秒内必须前台化的约束。
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linuxdeploy:keepalive").apply {
            setReferenceCounted(false)
            // 上限 30 分钟：防止异常路径未 stopService 时长时间占用 CPU 唤醒
            acquire(30 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        // 任务期间保持；意外被杀不自动重启（CLI 子进程也会随之结束，重启无意义）
        START_NOT_STICKY

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "长任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "部署 / 导出等长任务进行中" },
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LinuxDeploy")
            .setContentText("长任务运行中，请保持应用打开")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()

    companion object {
        private const val CHANNEL_ID = "long_task"
        private const val NOTIFICATION_ID = 4001
    }
}
