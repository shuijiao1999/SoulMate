package com.love.soulmate.engine

import android.os.SystemClock

/**
 * 卡住自救：长时间没进展时，用视觉模型看一眼该点哪里。
 *
 * ── 用户需求（2026-11-25）──
 * 「能不能加个异常模块，就是卡在某个地方半天，就使用视觉模型临时接入，
 *   看下截下来要点哪里去继续下去」
 *
 * ── 为什么需要 ──
 * 常规流程是「按 rid / 坐标找元素」，但 目标应用会不时冒出没见过的页面：
 *   · 新版活动页、会员推广、青少年模式提示
 *   · 布局改版后 rid 全变
 *   · 风控弹窗（「操作过于频繁」「请稍后再试」）
 * 这些情况代码里没有对应分支，就只能干等固定秒数直到超时，
 * 一整轮就这么浪费了。
 *
 * ── 它做什么 ──
 * 记录「最后一次有意义的进展」的时间戳。当某段流程超过阈值没有更新进展时：
 *   ① 截一张当前屏幕
 *   ② 把可点击元素编号标注在图上
 *   ③ 问视觉模型「为了实现 <目标>，该点哪个编号？」
 *   ④ 点它，然后重新判断是否脱困
 *
 * ── 它不做什么 ──
 *   · 不替代正常流程（正常能走通就不该走这里）
 *   · 不猜文案（不打字，只点击）
 *   · 不会无限重试（每段流程最多 MAX_RESCUE 次）
 *
 * ── 设计取舍 ──
 * 为什么用「时间戳」而不是「重试次数」做触发条件：
 *   不同步骤耗时差很多（读一个会话 5 秒，匹配一次 40 秒），
 *   用次数会在慢步骤上误触发、在快步骤上太迟钝。
 *   用「多久没进展」这个统一信号最准。
 *
 * @param tag 这段流程的名字，只用于日志
 * @param goal 用自然语言描述「现在想干什么」，喂给视觉模型
 * @param idleMs 多久算「卡住」，默认 90 秒
 */
class StuckRescue(
    private val tag: String,
    private val goal: String,
    private val idleMs: Long = 90_000L,
    private val onLog: (String, String) -> Unit,
) {

    /** 最后一次有进展的时间 */
    @Volatile
    private var lastProgressAt: Long = SystemClock.elapsedRealtime()

    /** 已经用过几次自救（防止死循环点同一个错地方） */
    @Volatile
    private var rescueCount: Int = 0

    /** 上一次点过的坐标（如果这次还指向同一个点，说明没效果，别再点） */
    private var lastTap: Pair<Int, Int>? = null

    companion object {
        /** 每段流程最多自救几次 */
        private const val MAX_RESCUE = 3
    }

    /**
     * 标记「有进展」。
     *
     * 调用点应该选在**确实推进了一步**的地方：
     *   · 成功进入对话页
     *   · 读到消息列表
     *   · 发送成功
     * 不要每行代码都调，否则永远触发不了。
     */
    fun progress() {
        lastProgressAt = SystemClock.elapsedRealtime()
        rescueCount = 0
        lastTap = null
    }

    /** 已经卡了多久（毫秒） */
    fun idleMs(): Long = SystemClock.elapsedRealtime() - lastProgressAt

    /** 是否已经卡住、且自救次数还没用完 */
    fun shouldRescue(): Boolean =
        idleMs() >= idleMs && rescueCount < MAX_RESCUE

    /**
     * 执行一次自救。
     *
     * @return true = 点了一个候选元素（可能有用，也可能没用，调用方自己再判断）
     *         false = 自救失败（视觉不可用 / 模型找不到可点的 / 已经点过同一个地方）
     */
    fun rescue(): Boolean {
        if (rescueCount >= MAX_RESCUE) {
            onLog("  [$tag] 自救次数已用完（$MAX_RESCUE 次），放弃", "warn")
            return false
        }
        rescueCount++

        onLog(
            "  [$tag] 已卡 ${idleMs() / 1000} 秒，启动视觉自救" +
                    "（第 $rescueCount/$MAX_RESCUE 次）",
            "warn"
        )

        // ① 先看当前是什么页面，避免在陌生页面上乱点
        val state = try {
            com.love.soulmate.core.StateReader.read(
                com.love.soulmate.core.Ui.currentActivity()
            )
        } catch (_: Throwable) {
            null
        }
        if (state != null) {
            onLog("  [$tag] 当前页面：${com.love.soulmate.core.StateReader.label(state.state)}", "info")
        }

        // ② 让视觉模型找可点的东西
        //
        // Vision.tapByVision 会：截屏 → 给可点击元素编号 → 问模型 → 点击
        // 这里把「目标」传进去，让它挑最可能脱困的那个。
        //
        // 注意：tapByVision 内部已经点了，所以这里不用再点。
        val picked = try {
            com.love.soulmate.core.Vision.tapByVision(goal)
        } catch (t: Throwable) {
            onLog("  [$tag] 视觉自救异常：${t.message}", "error")
            null
        }

        if (picked == null) {
            onLog("  [$tag] 视觉模型没找到该点的地方", "warn")
            return false
        }

        val xy = picked.cx to picked.cy

        // ③ 防止反复点同一个没用的地方
        if (lastTap == xy) {
            onLog(
                "  [$tag] 上次点的还是这里 (${xy.first},${xy.second})，说明没用，换个策略",
                "warn"
            )
            // 退一步：按一次返回，往往能脱离卡死的子页面
            try {
                com.love.soulmate.core.Ui.back()
            } catch (_: Throwable) {
            }
            Thread.sleep(1200)
            return false
        }
        lastTap = xy

        onLog(
            "  [$tag] 自救点了 rid='${picked.rid}' " +
                    "text='${picked.text.take(16)}' (${xy.first},${xy.second})",
            "warn"
        )

        Thread.sleep(2000)
        return true
    }

    /**
     * 卡住时调用：如果该自救就自救一次。
     *
     * 调用方在「等一个动作完成」的循环里每次迭代调一下即可：
     *
     * ```kotlin
     * val rescue = StuckRescue("打开会话", "进入和她的聊天对话页", onLog = ::log)
     * while (waited < 60_000) {
     *     if (进了对话页) { rescue.progress(); return true }
     *     if (rescue.check()) continue   // 自救过了，重新判断
     *     Thread.sleep(500)
     * }
     * ```
     *
     * @return true = 刚做过一次自救（调用方应该重新判断状态）
     */
    fun check(): Boolean {
        if (!shouldRescue()) return false
        return rescue()
    }
}
