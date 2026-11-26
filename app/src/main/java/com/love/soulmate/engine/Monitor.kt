package com.love.soulmate.engine

import android.content.Context
import android.util.Log
import com.love.soulmate.core.Pusher

/**
 * 常驻监控循环。
 *
 * ── 用户需求（2026-11-24）──
 * 「回复功能应该是一直时刻监控后台是否有新消息的」
 *
 * ── 之前的问题 ──
 * 点一次「立即检查」只跑一轮就结束。新消息来了不知道，
 * 要用户自己再点一次。这不符合「自动聊天助手」的定位。
 *
 * ── 设计思路 ──
 * 三层节流，尽量省电省流量：
 *
 * ```
 * 每 30 秒：查 目标应用 数据库 mtime（1 次 shell，约 50ms）
 *           ↓ 变了
 * 每有变化：扫聊天列表（约 5-10 秒）
 *           ↓ 有新会话
 * 逐条回复（45s 间隔 / 20s 全局冷却）
 * ```
 *
 * 第一层几乎不耗电（一次 stat），只有真收到消息才进入重流程。
 *
 * ── 为什么不用通知监听 ──
 * 通知监听（NotificationListenerService）也能感知新消息，但：
 *   · 需要额外权限
 *   · 分组通知读不到具体内容
 *   · 目标应用有时不发明细通知
 * 数据库 mtime 更可靠 —— 只要 目标应用 收到消息就一定写库。
 *
 * ── 和「立即检查」的关系 ──
 * 监控模式跑的是同一套 processSpace()，只是外面套了个循环：
 *   · 监控中：用户不需要再点按钮
 *   · 用户点「停止」：循环退出
 *   · 用户点「立即检查」：手动触发一轮（不进入监控循环）
 */
object Monitor {

    private const val TAG = "SoulMate"

    /** 轮询间隔：查数据库 mtime */
    private const val POLL_MS = 30_000L

    /** 两轮之间的最小间隔（防止连续触发把手机跑热） */
    private const val MIN_ROUND_GAP_MS = 60_000L

    /** 没变化时最多这么多次连续空转就降频（省电） */
    private const val IDLE_BACKOFF_AFTER = 20  // 20 * 30s = 10 分钟后开始降频
    private const val IDLE_POLL_MS = 120_000L  // 降频后 2 分钟一次

    @Volatile
    private var stopRequested = false

    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun isRunning(): Boolean = running

    fun requestStop() {
        stopRequested = true
    }

    /**
     * 启动监控循环。
     *
     * @param ctx        应用 context
     * @param features   功能开关（只跑勾上的）
     * @param dryRun     演练模式（不发真消息）
     * @param onRound    每一轮的执行体 —— 由 MainActivity 注入，
     *                   内部调用 Orchestrator.runOnce()
     * @param onLog      日志回调（更新界面）
     */
    fun start(
        ctx: Context,
        features: Features,
        dryRun: Boolean,
        onRound: (force: Boolean) -> Int,
        onLog: (String, String) -> Unit,
        onState: (running: Boolean, text: String) -> Unit,
    ) {
        if (running) {
            onLog("监控已在运行", "warn")
            return
        }

        stopRequested = false
        running = true

        thread = Thread {
            var idleCount = 0
            var lastRoundAt = 0L
            var roundNo = 0

            try {
                onLog(
                    "🔭 开始常驻监控（每 ${POLL_MS / 1000} 秒查一次新消息）",
                    "info"
                )
                onState(true, "监控中")

                while (!stopRequested) {
                    // ① 查数据库有没有变化（最便宜的一步）
                    val changed = try {
                        com.love.soulmate.soul.DirtyCheck.hasNewData(ctx)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Monitor: 查 mtime 失败 ${t.message}")
                        false
                    }

                    if (!changed) {
                        idleCount++
                        val wait = if (idleCount > IDLE_BACKOFF_AFTER) {
                            IDLE_POLL_MS
                        } else {
                            POLL_MS
                        }
                        // 分段 sleep，保证能及时响应停止
                        var slept = 0L
                        while (slept < wait && !stopRequested) {
                            Thread.sleep(1_000)
                            slept += 1_000
                        }
                        if (idleCount == IDLE_BACKOFF_AFTER + 1) {
                            onLog("  长时间无新消息，降频到每 ${IDLE_POLL_MS / 1000} 秒检查", "info")
                        }
                        continue
                    }

                    // ② 有变化 → 跑一轮
                    idleCount = 0

                    // 两轮之间至少隔 MIN_ROUND_GAP_MS（除非第一次）
                    val sinceLast = System.currentTimeMillis() - lastRoundAt
                    if (lastRoundAt > 0 && sinceLast < MIN_ROUND_GAP_MS) {
                        val wait = MIN_ROUND_GAP_MS - sinceLast
                        onLog("  距上轮仅 ${sinceLast / 1000}s，等 ${wait / 1000}s", "info")
                        Thread.sleep(wait)
                        if (stopRequested) break
                    }

                    roundNo++
                    onLog("── 第 $roundNo 轮（检测到数据变化）──", "info")
                    onState(true, "第 $roundNo 轮处理中")

                    val sent = try {
                        onRound(false)
                    } catch (t: Throwable) {
                        if (t.javaClass.simpleName.contains("StopSignal")) {
                            onLog("已按你的要求停止", "warn")
                            break
                        }
                        Log.e(TAG, "Monitor: 第 $roundNo 轮异常 ${t.message}")
                        onLog("  第 $roundNo 轮异常：${t.message}", "error")
                        0
                    }

                    lastRoundAt = System.currentTimeMillis()
                    onLog("  第 $roundNo 轮结束，发出 $sent 条", "info")

                    // 在跑了：更新通知文案
                    onState(true, "第 $roundNo 轮完成 · 继续监控")

                    // 轮次上限保护（避免通宵跑出问题）
                    if (roundNo >= 100000) {
                        onLog("已跑 100000 轮，自动停止", "warn")
                        break
                    }

                    // 跑完立刻再查一次（可能有连续新消息）
                    Thread.sleep(3_000)
                }

                onLog("🔭 监控已停止", "info")
            } catch (t: InterruptedException) {
                onLog("监控被中断", "warn")
            } catch (t: Throwable) {
                Log.e(TAG, "Monitor 异常退出: ${t.message}")
                onLog("监控异常退出：${t.message}", "error")
                try {
                    Pusher.alertTargetBroken(ctx, "监控异常退出", t.message ?: "")
                } catch (_: Throwable) {
                }
            } finally {
                running = false
                onState(false, "待命")
            }
        }.apply {
            name = "SoulMate-Monitor"
            isDaemon = true
            start()
        }
    }

    fun stop(ctx: Context) {
        stopRequested = true
        try {
            thread?.interrupt()
        } catch (_: Throwable) {
        }
        running = false
    }
}
