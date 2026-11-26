package com.love.soulmate.core

import android.util.Log
import com.love.soulmate.llm.Llm
import org.json.JSONObject

/**
 * 视觉状态识别：看懂「现在是什么页面」。
 *
 * ── 为什么需要它 ──
 * 之前踩的坑全是同一类：流程卡住，但不知道为什么卡。
 *   · 匹配点进去弹「今日免费匹配机会已用完 (20/20)」—— 浮层挡住，代码还在等对话页
 *   · 「当前网络不可用，请检查网络设置」+ 重新加载 —— 网络断了，代码以为还在加载
 *   · 各种活动浮窗、登录失效页 —— 结构变了，读不到列表
 *
 * 靠 rid / 关键字去穷举这些状态永远补不全。
 * 用视觉看一眼，模型能直接说「这是额度用完页」。
 *
 * ── 成本 ──
 * 一次调用约 5-9 秒、0.06 credit。只在关键节点调用（进匹配前、发现列表为空时），
 * 不是每步都调。
 */
object StateReader {

    enum class PageState {
        MAIN,           // 主界面（有底部 tab）
        CHAT_LIST,      // 聊天列表
        CONVERSATION,   // 对话页
        PLANET,         // 星球页
        MATCH_PAGE,     // 灵魂匹配页（含等待动画）
        QUOTA_EMPTY,    // 额度用完
        NETWORK_ERROR,  // 网络不可用
        LOGIN_EXPIRED,  // 未登录/登录失效
        POPUP,          // 有弹窗/浮层遮挡
        LOADING,        // 加载中
        OTHER,          // 其他/未知
    }

    data class Result(
        val state: PageState,
        val reason: String = "",
        val canRecover: Boolean = false,
    )

    /** 状态的中文名，用于日志 */
    fun label(s: PageState): String = when (s) {
        PageState.MAIN -> "主界面"
        PageState.CHAT_LIST -> "聊天列表"
        PageState.CONVERSATION -> "对话页"
        PageState.PLANET -> "星球页"
        PageState.MATCH_PAGE -> "灵魂匹配页"
        PageState.QUOTA_EMPTY -> "额度已用完"
        PageState.NETWORK_ERROR -> "网络错误"
        PageState.LOGIN_EXPIRED -> "登录失效"
        PageState.POPUP -> "弹窗遮挡"
        PageState.LOADING -> "加载中"
        PageState.OTHER -> "未知页面"
    }

    private const val PROMPT = """这是一张手机 App 的截图。请判断它属于下面哪一类页面。

只输出一个 JSON，不要任何解释：
{"state": "类别", "reason": "一句话说明依据"}

类别只能是以下之一（照抄英文）：
- MAIN          正常主界面，底部有「星球/广场/聊天/自己」等导航栏
- CHAT_LIST     聊天会话列表，一列一列的联系人和最后一条消息
- CONVERSATION  和某个人的聊天对话页，有消息气泡
- PLANET        星球页，有「灵魂匹配」「语音匹配」等卡片
- MATCH_PAGE    正在匹配/匹配结果页，有飞碟或「TA正在赶来」
- QUOTA_EMPTY   提示额度/次数已用完，要求明天再来或购买次数卡
- NETWORK_ERROR 提示网络不可用、加载失败、请检查网络
- LOGIN_EXPIRED 要求登录、手机号验证、登录已过期
- POPUP         有明显弹窗/浮层挡住主内容（但页面本身正常）
- LOADING       白屏、只有 logo、转圈等加载中状态
- OTHER         以上都不是

判断要点：
· 如果看到「已用完」「明天继续」「购买次数」字样 → QUOTA_EMPTY
· 如果看到「网络不可用」「加载失败」「重试」→ NETWORK_ERROR
· 如果半透明浮层盖住内容，但能看清下面是什么 → POPUP"""

    /**
     * 识别当前页面状态。
     *
     * @param hintActivity 已知的 Activity 名（可选，作为辅助线索）
     */
    fun read(hintActivity: String = ""): Result {
        if (!Llm.visionConfig.enabled) {
            return Result(PageState.OTHER, "视觉未启用")
        }

        val prompt = buildString {
            appendLine(PROMPT)
            if (hintActivity.isNotBlank()) {
                appendLine()
                appendLine("辅助线索：当前 Activity 名是 $hintActivity")
            }
        }

        val t0 = System.currentTimeMillis()
        val text = Vision.describe(prompt)
        val dt = System.currentTimeMillis() - t0

        if (text.isBlank()) {
            return Result(PageState.OTHER, "视觉无返回")
        }

        val (state, reason) = parse(text)
        Log.i(
            "SoulMate",
            "StateReader: ${label(state)} (${dt}ms) 依据=$reason"
        )
        return Result(
            state = state,
            reason = reason,
            canRecover = state in RECOVERABLE
        )
    }

    /** 能自动恢复的状态 */
    private val RECOVERABLE = setOf(
        PageState.QUOTA_EMPTY,
        PageState.NETWORK_ERROR,
        PageState.POPUP,
        PageState.LOADING,
    )

    fun parse(text: String): Pair<PageState, String> {
        // 优先 JSON
        Regex("\"state\"\\s*:\\s*\"([A-Z_]+)\"").find(text)?.let { m ->
            val s = m.groupValues[1]
            val reason = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"")
                .find(text)?.groupValues?.get(1) ?: ""
            val st = runCatching { PageState.valueOf(s) }.getOrNull()
            if (st != null) return st to reason
        }
        // 退而求其次：直接在文本里找类别名
        for (st in PageState.entries) {
            if (text.contains(st.name, ignoreCase = true)) {
                return st to text.take(60)
            }
        }
        // 中文关键词兜底
        return when {
            text.contains("用完") || text.contains("次数") -> PageState.QUOTA_EMPTY to text.take(60)
            text.contains("网络") -> PageState.NETWORK_ERROR to text.take(60)
            text.contains("登录") -> PageState.LOGIN_EXPIRED to text.take(60)
            text.contains("弹窗") || text.contains("浮层") -> PageState.POPUP to text.take(60)
            text.contains("加载") -> PageState.LOADING to text.take(60)
            text.contains("对话") || text.contains("聊天记录") -> PageState.CONVERSATION to text.take(60)
            text.contains("列表") -> PageState.CHAT_LIST to text.take(60)
            text.contains("主界面") || text.contains("导航") -> PageState.MAIN to text.take(60)
            else -> PageState.OTHER to text.take(60)
        }
    }

    /**
     * 尝试自动恢复异常状态。
     *
     * @return 恢复后是否已脱离异常
     */
    fun tryRecover(state: PageState): Boolean {
        return when (state) {
            PageState.QUOTA_EMPTY -> {
                // 关掉额度弹窗：点 X 或者按返回
                pressBackTwice()
                true
            }
            PageState.NETWORK_ERROR -> {
                // 点「重新加载」（视觉找），找不到就返回重进
                val picked = Vision.tapByVision("页面上的「重新加载」或「重试」按钮")
                if (picked == null) pressBackTwice()
                Thread.sleep(3000)
                true
            }
            PageState.POPUP -> {
                // 找关闭按钮
                val picked = Vision.tapByVision("弹窗右上角的关闭按钮（X 或 ×）")
                if (picked == null) Ui.back()
                Thread.sleep(2000)
                true
            }
            PageState.LOADING -> {
                // 等着就行
                Thread.sleep(5000)
                true
            }
            else -> false
        }
    }

    private fun pressBackTwice() {
        Ui.back()
        Thread.sleep(1200)
        Ui.back()
        Thread.sleep(1200)
    }
}
