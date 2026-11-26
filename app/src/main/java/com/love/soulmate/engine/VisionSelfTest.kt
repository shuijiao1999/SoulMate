package com.love.soulmate.engine

import com.love.soulmate.core.Screen
import com.love.soulmate.core.Shell
import com.love.soulmate.core.Ui
import com.love.soulmate.core.Vision
import com.love.soulmate.llm.Llm

/**
 * 视觉能力自测。不依赖 目标应用，可以在任意界面跑。
 *
 * 目的：验证「截图 → 标注编号 → 模型选编号 → 精确点击」这条链路
 * 在当前设备 + 当前模型配置下是否真的可用。
 */
object VisionSelfTest {

    data class Case(val hint: String, val expectRid: String? = null)

    /**
     * 跑一轮自测。
     *
     * @param onLog 日志回调
     * @return 通过数 / 总数
     */
    fun run(onLog: (String, String) -> Unit): Pair<Int, Int> {
        val cfg = Llm.visionConfig
        onLog("视觉自测开始", "info")
        onLog("  模型: ${cfg.model}", "info")
        onLog("  端点: ${cfg.baseUrl}", "info")
        onLog("  启用: ${cfg.enabled}", "info")

        if (!cfg.enabled) {
            onLog("视觉未启用，跳过", "warn")
            return 0 to 0
        }

        // 1. 连通性 + 看图能力
        onLog("── 1. 看图连通性 ──", "info")
        val desc = Vision.describe("用一句话描述这张截图是什么界面，20字以内")
        if (desc.isBlank()) {
            onLog("✗ 视觉模型无响应（检查 Key / 模型名 / 网络）", "error")
            return 0 to 1
        }
        onLog("✓ 模型返回: ${desc.take(60)}", "info")

        // 2. 元素标注 + 选编号
        onLog("── 2. 元素标注与选号 ──", "info")
        val nodes = Ui.snap()
        val clickable = nodes.filter { it.clickable && it.w > 0 && it.h > 0 }
        onLog("  当前界面可点击元素: ${clickable.size} 个", "info")
        if (clickable.isEmpty()) {
            onLog("✗ 没有可点击元素，无法测试", "warn")
            return 1 to 2
        }

        // 3. 实测：让模型找「底部聊天 tab」——这是真实业务里最常用的目标
        //    优先用有 rid 的节点做校验基准，确保能算出准确偏差
        var expect: com.love.soulmate.core.UiNode? = null
        var hint = ""

        val chatTab = clickable.firstOrNull { it.rid == "main_tab_msg" }
        val meTab = clickable.firstOrNull { it.rid == "meTextView" }

        when {
            chatTab != null -> {
                expect = chatTab
                hint = "底部导航栏里的「聊天」标签"
            }
            meTab != null -> {
                expect = meTab
                hint = "底部导航栏里的「自己」标签"
            }
            else -> {
                // 没有已知 rid，就用最大的可点击元素当基准
                val big = clickable.maxByOrNull { it.w.toLong() * it.h }!!
                expect = big
                hint = big.text.ifBlank { big.desc }.ifBlank {
                    "页面上面积最大的可点击区域"
                }
            }
        }

        onLog("  目标: 「${hint.take(24)}」 (真实位置 ${expect.cx},${expect.cy})", "info")

        var pass = 1
        val t0 = System.currentTimeMillis()
        val picked = Vision.tapByVision(hint, nodes)
        val cost = System.currentTimeMillis() - t0

        if (picked == null) {
            onLog("✗ 视觉没找到目标（${cost}ms）", "error")
            return pass to 3
        }

        val dx = kotlin.math.abs(picked.cx - expect.cx)
        val dy = kotlin.math.abs(picked.cy - expect.cy)
        // 阈值 60px：验证阶段允许一定误差，真实使用场景下主要靠 rid 精确匹配
        val good = dx <= 60 && dy <= 60
        onLog(
            "  ${if (good) "✓" else "✗"} 选中 (${picked.cx},${picked.cy})" +
                    " 偏差 (${dx},${dy})  耗时 ${cost}ms",
            if (good) "info" else "warn"
        )
        if (good) pass++

        // 4. 屏幕信息
        onLog("── 3. 环境 ──", "info")
        onLog("  ${Screen.desc()}", "info")

        onLog("自测完成: $pass/3 通过", if (pass == 3) "info" else "warn")
        return pass to 3
    }

    /** 快速验证：只测视觉模型能不能看图（不点击） */
    fun quickCheck(onLog: (String, String) -> Unit): Boolean {
        val cfg = Llm.visionConfig
        if (!cfg.enabled) {
            onLog("视觉未启用", "warn")
            return false
        }
        val d = Vision.describe("这是一张手机截图。用一句话说明当前是什么界面。")
        return if (d.isBlank()) {
            onLog("视觉模型无响应", "error")
            false
        } else {
            onLog("视觉可用: ${d.take(50)}", "info")
            true
        }
    }
}
