package com.love.soulmate.soul

import com.love.soulmate.core.Screen
import com.love.soulmate.core.Ui
import com.love.soulmate.core.UiNode

/**
 * 灵魂匹配：把每日匹配额度用光。
 *
 * 流程（实测验证过的路径）：
 *   星球页 → 点「开始匹配」→ 进对话页 → 读卡片/资料 → 筛人 → 生成开场 → 发送
 *
 * 额度读取的坑（踩过）：
 *   星球页有两个卡片，左边是灵魂匹配、右边是语音匹配。
 *   语音匹配也写「今日剩余3次」，如果全屏扫会读错。
 *   所以只认 x1 < 屏宽 25% 的那个「今日剩余N次」。
 */
object Matcher {

    data class Quota(val left: Int, val raw: String)

    /**
     * 读灵魂匹配剩余次数。
     *
     * 返回值约定：
     *   >=0  读到了具体次数
     *   -1   读不到（页面没加载好），调用方应重试而不是当作 0
     *
     * 坑（实测）：
     *   星球页左侧是灵魂匹配、右侧是语音匹配，两边都写「今日剩余N次」。
     *   只认 x1 < 屏宽 25% 的那个，否则会把语音匹配的 3 次读成灵魂匹配额度。
     */
    fun readQuota(): Quota {
        SoulBot.gotoMain()

        // ★ 先检查是不是停在「额度用完」的 RN 付费页（2026-11-25 真机）
        //
        // 额度耗尽后点「开始匹配」，目标应用 不会停在星球页，
        // 而是跳 RnContainerActivity 显示：
        //   「今日免费匹配机会已用完 (20/20)」
        //   「完成活跃任务获取次数」「购次数卡x1」…
        // 这时如果还去星球页找「今日剩余N次」，会一直读不到 ——
        // 日志表现为「灵魂匹配剩余 读不到（读不到次数）」。
        if (Ui.currentActivity().contains("RnContainer")) {
            val t = Ui.snap().joinToString(" ") { it.text }
            if (t.contains("机会已用完") || t.contains("次数已用完") ||
                t.contains("免费匹配已用完") || t.contains("20/20")
            ) {
                android.util.Log.w("SoulMate", "Matcher: 读到额度用完页 → 0 次")
                Ui.back()
                Thread.sleep(1200)
                return Quota(0, "今日已用完")
            }
            Ui.back()
            Thread.sleep(1200)
        }

        if (Ui.snap().none { it.rid == "tv_button_left_bottom" }) {
            tapPlanetTab()
            Thread.sleep(2200)
        }

        // 页面可能还在加载，最多重试 4 次
        repeat(4) { attempt ->
            val nodes = Ui.snap()
            val allText = nodes.joinToString(" ") { it.text }

            // 明确的「已用完」
            if (allText.contains("用完")) return Quota(0, "已用完")

            // 目标应用Match 页的「今天灵魂匹配次数已用完」
            if (allText.contains("次数已用完") || allText.contains("明天继续")) {
                return Quota(0, "今日已用完")
            }

            // 停在匹配结果页（不在线/失败）→ 说明点进去过，额度已消耗
            if (allText.contains("不在线") || allText.contains("一会儿再来")) {
                return Quota(-1, "匹配对象不在线")
            }

            val leftLimit = Screen.width() * 0.25
            for (n in nodes) {
                if (n.x1 > leftLimit) continue
                val m = Regex("今日剩余(\\d+)次").find(n.text)
                if (m != null) {
                    val left = m.groupValues[1].toIntOrNull()
                    if (left != null) return Quota(left, n.text)
                }
            }

            // ★ 额度用完的另一种表现（2026-11-25 真机】
            //
            // 免费额度耗尽后，灵魂匹配卡片**不再显示「今日剩余N次」**，
            // 改成显示「立即寻找懂你的人」；
            // 而右边的语音匹配卡片仍然显示「今日剩余3次」——
            // 所以只要「左侧卡片没有次数文字 + 有开始匹配按钮」就是未用完。
            //
            // 真机实测的两套文案：
            //   有额度：灵魂匹配 / 今日剩余12次 / 开始匹配
            //   没额度：灵魂匹配 / 立即寻找懂你的人 / 开始匹配
            val leftCardTitle = nodes.firstOrNull {
                it.text.trim() == "灵魂匹配" && it.x1 < Screen.width() * 0.5
            }
            val leftSubNoQuota = nodes.any {
                it.x1 < Screen.width() * 0.5 &&
                        (it.text.contains("立即寻找懂你的人") ||
                                it.text.contains("明日再来") ||
                                it.text.contains("已用完"))
            }
            val hasStartBtn = nodes.any { it.rid == "tv_button_left_bottom" }

            if (leftCardTitle != null && leftSubNoQuota && hasStartBtn) {
                android.util.Log.w(
                    "SoulMate",
                    "Matcher: 左侧卡片显示「${nodes.firstOrNull {
                        it.x1 < Screen.width() * 0.5 && it.text.contains("立即寻找")
                    }?.text}」→ 判定额度已用完"
                )
                return Quota(0, "今日已用完（卡片无剩余次数）")
            }

            if (attempt < 3) {
                Thread.sleep(1500)
                // 重新确认在星球页
                if (Ui.snap().none { it.rid == "tv_button_left_bottom" }) {
                    tapPlanetTab()
                    Thread.sleep(1800)
                }
            }
        }

        // 读不到 → -1，让调用方决定重试，绝不当 0
        return Quota(-1, "读不到次数")
    }

    fun tapPlanetTab() {
        val nodes = Ui.snap()
        val h = Screen.height()
        val t = nodes.firstOrNull { it.rid == "tab_planet" }
            ?: nodes.firstOrNull { it.text == "星球" && it.y1 > h * 0.85 }
        if (t != null) Ui.tap(t.cx, t.cy)
        else Ui.tap(Screen.px(0.117), Screen.fromBottomDp(20))
        Thread.sleep(1500)
    }

    /**
     * 点「开始匹配」并进到能发消息的对话页。
     *
     * 实测流程（2026-11-23 凌晨验证）：
     *   星球页点「开始匹配」
     *     → 进 UserHomeActivity（对方主页，有「关注」「私聊」两个按钮）
     *     → 点「私聊」→ 才到 ConversationActivity
     *
     * 坑：
     *   1) tv_button_left_bottom 的 clickable=false，但点它的中心坐标有效
     *   2) 星球页有活动横幅会拦点击，误入 H5 要返回重试
     *   3) 主页可能弹「打个招呼」对话框
     */
    fun startMatch(): Boolean {
        if (Ui.currentActivity() != SoulBot.MAIN) {
            SoulBot.gotoMain()
        }
        // 确保在星球页
        if (Ui.snap().none { it.rid == "tv_button_left_bottom" }) {
            tapPlanetTab()
            Thread.sleep(1500)
        }

        fun tapStart(): Boolean {
            val ns = Ui.snap()
            val btn = ns.firstOrNull { it.rid == "tv_button_left_bottom" }
                ?: ns.firstOrNull { it.text.trim() == "开始匹配" }
            if (btn == null) return false
            Ui.tap(btn.cx, btn.cy)
            Thread.sleep(2500)
            return true
        }

        if (!tapStart()) return false

        // ★ 这个自救器专门处理「点完开始匹配后，页面变成没见过的东西」
        //
        // 触发条件：40 秒没有任何进展（匹配动画一般 1-6 秒，
        // 40 秒还没进对话基本就是卡住了）
        // 目标描述要给模型说清楚「用户想干什么」，它才知道该点哪
        val rescue = com.love.soulmate.engine.StuckRescue(
            tag = "匹配进对话",
            goal = "进入和匹配到的那个人的聊天对话页（通常是点「私聊」「立即私聊」" +
                    "「开始聊天」这类按钮，或者关掉挡在前面的弹窗）",
            idleMs = 40_000L,
            onLog = { msg, level -> android.util.Log.println(
                if (level == "error") android.util.Log.ERROR else android.util.Log.WARN,
                "SoulMate", msg) },
        )

        /**
         * ★ 关键：点完「开始匹配」后有一段「TA正在光速赶来」的动画页
         *   （planet.soul.xxx），持续 1-6 秒。这段不算失败，要一直等。
         *   踩坑：早期代码在这里就判定「没进对话」，导致 3 次全失败。
         *
         * 另一坑：额度用完时会停在 目标应用MatchActivity 显示
         *   「今天灵魂匹配次数已用完，明天继续～」，必须立刻识别并退出，
         *   否则会一直干等到超时。
         */
        var waited = 0
        while (waited < 30) {
            val a = Ui.currentActivity()

            /**
             * 停在 目标应用MatchActivity 时的三种情况（2026-11-25 真机重写）
             *
             * ── 原来的 bug ──
             * 只要页面上出现 bad 列表里任一关键词就判失败退出。
             * 但 目标应用MatchActivity 是**匹配进行中**的页面，
             * 上面一直轮播这些提示：
             *   「涉及网贷、投资、要钱、兼职等行为都是诈骗哦～」
             *   「年龄性别等关键信息可不能随意修改哦～」
             * 其中「投资」「网贷」这类词一旦命中 bad 列表，
             * 就会在匹配**还没结果**时被判成失败 —— 表现为
             * 「好几次什么都没说就退出聊天」。
             *
             * ── 现在的判定 ──
             * ① 先看是不是「确定没戏」的终态文案 → 才判失败
             * ② 否则一律当作「还在匹配」继续等
             */
            if (a.contains("SoulMatch")) {
                val snap = Ui.snap()
                val all = snap.joinToString(" ") { it.text }

                // 只有这些才是真正的终态失败（明确说用完/不在线/失败）
                val hardFail = listOf(
                    "次数已用完", "今天灵魂匹配次数已用完", "明天继续",
                    "不在线", "一会儿再来试试",
                    "匹配失败", "额度不足", "金币不足",
                )
                if (hardFail.any { all.contains(it) }) {
                    android.util.Log.w(
                        "SoulMate",
                        "Matcher: 匹配终态失败（命中：${
                            hardFail.first { all.contains(it) }
                        }）"
                    )
                    Ui.back()
                    Thread.sleep(1500)
                    return false
                }

                // 还在匹配中（有 matchingMsgTv / 有干扰提示行）
                val matching = snap.any {
                    it.rid == "matchingMsgTv" ||
                            it.text.contains("正在匹配") ||
                            it.text.contains("光速赶来") ||
                            it.text.contains("正在为你")
                }
                if (matching) {
                    android.util.Log.i(
                        "SoulMate",
                        "Matcher: 匹配进行中，继续等（已等 ${waited}s）"
                    )
                }
                // 注意：这里**不再**因为出现「诈骗」「投资」等词就退出
            }

            if (a == SoulBot.CONV) {
                rescue.progress()      // 进对话页 = 有进展，重置自救计时
                return true
            }

            // ★ RnContainerActivity：目标应用的 React Native 容器页
            //
            // 踩坑（2026-11-25 真机，两轮才搞清）：
            // 点「开始匹配」后，如果**额度已用完**，目标应用 会跳到这个 RN 页面
            // 显示付费方案，而不是停在 目标应用MatchActivity：
            //   「今日免费匹配机会已用完 (20/20)」
            //   「完成活跃任务获取次数」「购次数卡x1」…
            //
            // 一开始我把它当「误入活动页」，反复 back + 重爬 → 死循环，
            // 日志表现为连续「第 N 次匹配失败（没进对话）」。
            //
            // 正确做法：先看页面文案。是额度用完就立刻收手，
            // 不要当活动页重试（那会白白消耗时间，也拿不到额度）。
            val isRn = a.contains("RnContainer")

            if (isRn) {
                val rnText = Ui.snap().joinToString(" ") { it.text }

                // 额度用完（免费次数耗尽，只剩付费方案）
                val quotaGone = listOf(
                    "匹配机会已用完", "机会已用完", "今日免费匹配机会已用完",
                    "次数已用完", "免费匹配已用完",
                ).any { rnText.contains(it) }

                if (quotaGone) {
                    android.util.Log.w(
                        "SoulMate",
                        "Matcher: 额度已用完（$a 显示付费方案），本轮停止匹配"
                    )
                    Ui.back()
                    Thread.sleep(1500)
                    return false
                }

                // 其他 RN 页面（活动/广告）→ 返回重试
                android.util.Log.w("SoulMate", "Matcher: 误入 RN 页（不是额度页），返回重试")
                Ui.back()
                Thread.sleep(2500)
                if (!Ui.currentActivity().endsWith(SoulBot.MAIN)) {
                    Ui.back()
                    Thread.sleep(1500)
                }
                tapPlanetTab()
                Thread.sleep(1500)
                if (!tapStart()) return false
                continue
            }

            // 误入 H5 / 其他活动页 → 返回重试
            if (a.contains("H5") || a.contains("SoulHouse") || a.contains("Party")) {
                android.util.Log.w("SoulMate", "Matcher: 误入活动页（$a），返回重试")
                Ui.back()
                Thread.sleep(2500)
                if (!Ui.currentActivity().endsWith(SoulBot.MAIN)) {
                    Ui.back()
                    Thread.sleep(1500)
                }
                tapPlanetTab()
                Thread.sleep(1500)
                if (!tapStart()) return false
                continue
            }

            // 落在对方主页（UserHomeActivity）→ 点「私聊」
            if (a == SoulBot.HOME || a.contains("UserHome")) {
                val ns = Ui.snap()
                val greet = ns.firstOrNull { it.text.trim() == "打个招呼" }
                if (greet != null) {
                    Ui.tap(greet.cx, greet.cy)
                    Thread.sleep(3500)
                    continue
                }
                val chat = ns.firstOrNull { it.text.trim() == "私聊" }
                    ?: ns.firstOrNull { it.rid == "ivChat" }
                if (chat != null) {
                    Ui.tap(chat.cx, chat.cy)
                    Thread.sleep(3500)
                    if (Ui.currentActivity() == SoulBot.CONV) return true
                }
            }

            // ★★★ 走到这里说明：页面不属于上面任何一个已知类型 ★★★
            //
            // 用户需求（2026-11-25）：
            // 「加个异常模块，卡在某个地方半天，就用视觉模型临时接入，
            //   看下截下来要点哪里去继续下去」
            //
            // 这正是最该自救的时刻：目标应用 又冒出了没见过的页面
            // （新版活动页/会员推广/青少年提示/风控弹窗），
            // 代码里没有对应分支，只能干等 30 秒然后判失败。
            //
            // 自救逻辑：截屏 → 给可点击元素编号 → 问视觉模型
            // 「为了实现<进入匹配对话页>，该点哪个编号？」→ 点它。
            if (rescue.check()) {
                // 刚点了一下，重新判断页面状态，别继续傻等
                continue
            }

            Thread.sleep(2000)
            waited += 2
        }

        return Ui.currentActivity() == SoulBot.CONV
    }

    /**
     * 读匹配卡片上的信息（在对话页顶部）。
     * 比跳主页读资料快，用来先做一轮粗筛。
     *
     * ── 昵称位置因设备而异（2026-11-25 真机踩坑）──
     * 原来写死 `n.y1 < Screen.py(0.06)`（屏高 3192 时 = 191px），
     * 但真机 MEIZU 上 tv_title 的 y1 = 196px，**刚好超出 5 像素**，
     * 结果昵称永远读不到，全部退化成「匹配用户N」。
     *
     * 日志证据：
     *   c_ct_chat_title_info  y=196  861x66
     *   tv_title              y=196
     *
     * 改成 0.12（= 383px），足够覆盖各种刘海/状态栏高度，
     * 同时仍在顶部资料卡片（y=327 开始）之上，不会误读成标签。
     */
    fun readCard(): Pair<String, List<String>> {
        val nodes = Ui.snap()
        val nameLimit = Screen.py(0.12)     // 昵称一定在最顶部
        val cardLimit = Screen.py(0.30)     // 资料卡片范围（标签）
        val tags = ArrayList<String>()
        var name = ""

        // ① 昵称：优先 rid=tv_title 且在标题栏范围内
        val titleNode = nodes
            .filter { it.rid == "tv_title" && it.y1 < nameLimit && it.text.isNotBlank() }
            .minByOrNull { it.y1 }
        if (titleNode != null) name = titleNode.text.trim()

        // ② 兜底：标题栏附近（y < nameLimit）最长的非系统文本
        if (name.isBlank()) {
            name = nodes
                .filter {
                    it.y1 < nameLimit && it.text.isNotBlank() &&
                            it.text.trim().length in 1..20 &&
                            it.rid !in setOf("clock", "battery_percent") &&
                            it.text.trim() != "在线" &&
                            !it.text.contains("关注") &&
                            it.x1 < Screen.width() * 0.7
                }
                .maxByOrNull { it.text.trim().length }
                ?.text?.trim() ?: ""
        }

        // ③ 标签：资料卡片范围内的短文本
        for (n in nodes) {
            if (n.y1 < nameLimit || n.y1 > cardLimit || n.text.isBlank()) continue
            if (n.text.trim() == name) continue
            if (n.text.length in 1..24) tags.add(n.text.trim())
        }
        return name to tags
    }
}
