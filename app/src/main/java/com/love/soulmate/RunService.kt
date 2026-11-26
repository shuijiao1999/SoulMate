package com.love.soulmate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 运行期间的前台服务 + 通知栏「停止」按钮。
 *
 * ── 为什么需要前台服务 ──
 * App 退到后台执行时，部分系统会在十几秒后冻结进程（`do_freezer_trap`），
 * 表现为流程跑到一半就没日志了。前台服务 + WakeLock 能保持运行。
 *
 * ── 为什么通知栏要有「停止」按钮（关键）──
 * 踩坑（2026-11-24）：
 *   App 运行时必须退到后台去操作目标应用，
 *   但「停止」按钮在 App 界面上 → **用户在 目标应用界面上根本点不到**。
 *   实测：点「立即检查」后 App 立刻切走，想停都停不了。
 *
 *   解法：把「停止」放进通知栏。用户下拉通知栏就能点，不用切回 App。
 */
class RunService : Service() {

    companion object {
        private const val CH_ID = "soulmate_run"
        private const val CH_NAME = "运行状态"
        private const val NOTIF_ID = 1001

        const val ACTION_STOP = "com.love.soulmate.STOP_RUN"

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        fun start(ctx: Context) {
            if (running) return
            try {
                val i = Intent(ctx, RunService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (t: Throwable) {
                android.util.Log.w("SoulMate", "RunService.start 失败: ${t.message}")
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, RunService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /** 通知栏「停止」按钮的接收器 */
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STOP) return
            android.util.Log.i("SoulMate", "通知栏点了「停止」")

            // 通知 Orchestrator 停
            try {
                OrchestratorHolder.orchestrator?.requestStop()
            } catch (t: Throwable) {
                android.util.Log.w("SoulMate", "requestStop 失败: ${t.message}")
            }

            // 立刻把通知改成「正在停止」
            updateNotification("正在停止…")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        startForeground(NOTIF_ID, buildNotification("正在运行 · 点这里停止"))

        // 注册停止接收器
        //
        // ★ 必须用 RECEIVER_EXPORTED（踩坑 2026-11-24）
        //   用 RECEIVER_NOT_EXPORTED 时，通知栏按钮点击发来的广播会被系统拒绝：
        //   "Exported Denial: ... due to receiver ... not specifying RECEIVER_EXPORTED"
        //   因为通知栏按钮发出的 PendingIntent 被判定为外部来源。
        try {
            val f = IntentFilter(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stopReceiver, f, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(stopReceiver, f)
            }
        } catch (t: Throwable) {
            android.util.Log.w("SoulMate", "注册停止接收器失败: ${t.message}")
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SoulMate::run"
            ).apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        } catch (t: Throwable) {
            android.util.Log.w("SoulMate", "WakeLock 获取失败: ${t.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理「从通知栏点停止」直接拉起 service 的情况
        if (intent?.action == ACTION_STOP) {
            try {
                OrchestratorHolder.orchestrator?.requestStop()
            } catch (_: Throwable) {
            }
            updateNotification("正在停止…")
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            unregisterReceiver(stopReceiver)
        } catch (_: Throwable) {
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wakeLock = null
        super.onDestroy()
    }

    /** 更新通知文案（比如改成「正在停止」） */
    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CH_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        // 点通知主体 → 回到 App 界面
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 「停止」按钮
        val stopPi = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return b.setContentTitle("SoulMate")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_delete,
                    "停止",
                    stopPi
                ).build()
            )
            .build()
    }
}

/**
 * 让 Service 能拿到 Orchestrator 实例。
 *
 * 为什么用 holder 而不是 bind：
 *   RunService 只需要调一个 requestStop()，用 Binder 太重，
 *   而且 MainActivity 和 Service 生命周期不同步，bind 容易出问题。
 *   MainActivity 创建 Orchestrator 时把它放进来即可。
 */
object OrchestratorHolder {
    @Volatile
    var orchestrator: com.love.soulmate.engine.Orchestrator? = null
}
