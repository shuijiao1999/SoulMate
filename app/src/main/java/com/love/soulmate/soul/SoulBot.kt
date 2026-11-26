package com.love.soulmate.soul

import com.love.soulmate.core.Env
import com.love.soulmate.core.Screen
import com.love.soulmate.core.Shell
import com.love.soulmate.core.Ui
import com.love.soulmate.core.UiNode
import com.love.soulmate.core.Vision
import com.love.soulmate.platform.ChatDetail
import com.love.soulmate.platform.ChatRow
import com.love.soulmate.platform.Msg
import com.love.soulmate.platform.Profile
import com.love.soulmate.platform.SendResult

// 数据模型统一放在 com.love.soulmate.platform.Models.kt
// 这样 目标应用Bot 和以后的 ShesayDriver/MomoDriver 共用同一套类型，
// Orchestrator 只认一套结构，不用为每个平台写一套转换。

/**
 * 目标应用 界面自动化。
 *
 * 全机型通用的三条原则：
 *   1) 能用 resource-id 就用 id，绝不用坐标（id 在哪台机器都一样）
 *   2) 必须用坐标时，用相对比例（Screen.px/py），不写绝对像素
 *   3) 判断左右、判断上下边界，都按屏幕尺寸算
 */
object SoulBot {

    const val PKG = "cn.soulapp.android"
    const val MAIN_ACT = "$PKG/cn.soulapp.android.component.startup.main.MainActivity"
    const val CONV = "ConversationActivity"
    const val HOME = "UserHomeActivity"
    const val MAIN = "MainActivity"

    private val TIME_DIVIDER = Regex(
        "^(\\d{1,2}:\\d{2}|昨天.*|今天.*|\\d{1,2}月\\d{1,2}日.*|\\d{4}-\\d{2}-\\d{2}.*)$"
    )

    /**
     * 聊天列表里的系统行，不当作会话处理。
     *
     * ── 「收到的新招呼」为什么不在这个列表里（2026-11-24 改动）──
     *
     * 用户选定的方案 D：「只处理已建立会话的人（匹配来的、奇遇铃来的）」。
     *
     * 「收到的新招呼」是**别人主动跟你打招呼**的聚合入口——
     * 点进去是一批人对你发的第一句话。
     *
     * 这正是方案 D 最主要的「新人来源」，之前却当成系统通知跳过了，
     * 导致这些人永远进不来。
     *
     * 现在单独处理（见 [openNewGreetings]），不在这里过滤掉。
     */
    private val SKIP_PREFIX = listOf("有新朋友", "Soul通知", "Soul空间站")

    /**
     * 「新招呼」聚合行的名字特征。
     *
     * ── 实测发现这个入口有三种形态（真机 MEIZU 21 Pro，2026-11-25）──
     *
     *  ① `tv_greeting_title` = 「收到的新招呼」       ← 有未读时出现
     *  ② `c_ct_system_notice_name` = 「奇遇铃-稍后再聊」
     *     `latestSystemMessage` = 「有新朋友等你和Ta开启对话」  ← 也是新招呼
     *  ③ 读完之后整行消失，列表直接是真实会话
     *
     * 第 ② 种之前被 SKIP_PREFIX 里的「有新朋友」跳过 ——
     * 那正是新招呼的另一个入口，跳掉就永远处理不到。
     *
     * 所以这里除了看标题文字，还要看消息体里的特征词。
     */
    private val NEW_GREETING_MARKS = listOf(
        "收到的新招呼", "新招呼", "有新朋友等你", "开启对话", "新朋友等你",
    )

    /** 这一行是不是「新招呼」聚合入口 */
    private fun isNewGreetingRow(name: String, preview: String): Boolean {
        val t = "$name $preview"
        return NEW_GREETING_MARKS.any { t.contains(it) }
    }

    /**
     * 停止标志的引用。
     *
     * 为什么要放这里：扫列表、等加载这类耗时循环在 目标应用Bot 内部，
     * 用户点停止时它们也得能中断，不能等 30 秒轮询走完。
     *
     * 由 Orchestrator 在启动时注入 `{ orchestrator.isStopRequested() }`。
     */
    @Volatile
    var stopCheck: (() -> Boolean)? = null

    /** 内部用：要停就抛异常，一次跳出多层 */
    fun throwIfStop() {
        if (stopCheck?.invoke() == true) {
            throw com.love.soulmate.engine.Orchestrator.StopSignal()
        }
    }

    /**
     * 系统标签过滤。
     *
     * 踩坑：会话名显示「我」，但右边有个「奇遇铃」标签 ——
     * 这不是真人会话，是 目标应用 自动生成的。如果不过滤，
     * 它会占据列表首位，把所有人的位置往下推，导致 openByName 点偏。
     */
    private val SKIP_TAG = listOf(
        "奇遇铃", "新注册", "互动消息", "限时特权", "活动", "系统通知", "官方"
    )

    @Volatile
    var user: Int = 0

    // SendResult 已移到 com.love.soulmate.platform.Models.kt
    // 本文件顶部已 import，用法不变。

    /**
     * 确认当前在目标空间。
     *
     * **不再主动切换用户空间。**
     * 早期为了同时跑「主程序 + 克隆程序」会 force-stop 两个空间再 am start，
     * 这套逻辑非常容易出问题：
     *   · 两个空间互相抢前台，am start 变成 "brought to the front"
     *   · 一次失误用了 pm clear，把 u900 的数据整个清掉
     *
     * 现在改成：**只操作 App 当前所在的那个 目标应用**。
     * 换账号由用户在 目标应用 里自己切，App 不碰。
     */
    fun ensureCurrentSpace(): Boolean {
        val cur = Ui.currentUser()
        if (cur < 0) return false
        this.user = cur
        return Ui.isForeground(PKG)
    }

    /**
     * 关掉「送花开聊」全屏墙。
     *
     * ── 实测（2026-11-24，平板 1600x2560）──
     *   rid=tv_btn_close  文字「狠心错过」  位置约 (1548,1576)
     *   rid=tv_btn_gift_send  文字「6 送花开聊」  ← 这个是花钱的，别点
     *
     * ── 【重要】点「狠心错过」不只是关弹窗，还会直接退回主界面 ──
     * 实测：
     *   关之前：ConversationActivity
     *   点关闭后：MainActivity     ← 痛点：它顺带把对话页也关了
     *
     * 所以**点完不要按返回**！按返回会把已经回到主界面的状态再退回去，
     * 然后又弹送花墙，形成「退 7 次还在对话页」的死循环（实测过）。
     *
     * @return 是否关掉了
     */
    fun killGiftWall(): Boolean {
        return try {
            val nodes = Ui.snap()
            if (nodes.isEmpty()) return false

            val isGiftWall = nodes.any { it.rid == "tv_btn_gift_send" } ||
                    nodes.any { it.text.contains("送花开聊") } ||
                    nodes.any { it.text.contains("狠心错过") }
            if (!isGiftWall) return false

            val close = nodes.firstOrNull { it.rid == "tv_btn_close" }
                ?: nodes.firstOrNull { it.text.contains("狠心错过") }
            if (close != null) {
                Ui.tap(close.cx, close.cy)
                // 多等一会儿：它关的不只是弹窗，还会切页面
                Thread.sleep(2000)
                android.util.Log.i(
                    "SoulMate",
                    "SoulBot: 关掉送花墙，当前 ${Ui.currentActivity()}"
                )
                return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 暴力回到主界面。
     *
     * ── 关键认知（2026-11-24 实测修正）──
     * 之前的实现是「关花墙 → 按返回 → am start」循环 8 次，
     * **完全错误**，会导致死循环：
     *
     *   送花墙点「狠心错过」→ 不只是关弹窗，还直接退回 MainActivity
     *   → 我们再按返回 → 从 MainActivity 退到上一层（可能是对话页）
     *   → 对话页又弹送花墙 → 循环往复 → 退 7 次还在 ConversationActivity
     *
     * ── 正确顺序 ──
     *   1. 先看是不是 MainActivity → 是就直接返回
     *   2. 有送花墙 → 只点关闭，**然后立刻重新检查**（可能已经回主界面了）
     *   3. 还没到 → 才按返回
     *   4. 还不行 → am start
     *   5. 都不行 → 重启 目标应用
     */
    fun forceGotoMain(): Boolean {
        repeat(5) {
            // ① 已经是主界面，收工
            if (Ui.currentActivity() == MAIN) return true

            // ② 有送花墙 → 只点关闭，点完再判（它可能顺带把对话页关了）
            if (killGiftWall()) {
                if (Ui.currentActivity() == MAIN) return true
                // 点完还不在主界面，说明下面还有对话页，继续走后面步骤
            }

            // ③ 按一次返回
            Ui.back()
            Thread.sleep(1000)

            // ④ 返回后可能又弹花墙
            if (killGiftWall()) {
                if (Ui.currentActivity() == MAIN) return true
            }

            if (Ui.currentActivity() == MAIN) return true

            // ⑤ am start 强拉
            Shell.run("am start --user $user -n $MAIN_ACT")
            Thread.sleep(2000)
            if (Ui.currentActivity() == MAIN) return true
        }

        if (Ui.currentActivity() == MAIN) return true

        // 最后手段：重启 目标应用（登录态存在服务端，重开不用重新登）
        android.util.Log.w("SoulMate", "SoulBot: 反复退不出，重启 Soul")
        Shell.run("am force-stop $PKG")
        Thread.sleep(2500)
        Shell.run("am start --user $user -n $MAIN_ACT")
        repeat(20) {
            Thread.sleep(1500)
            killGiftWall()
            if (Ui.isForeground(PKG) && Ui.currentActivity() == MAIN) return true
        }
        return Ui.currentActivity() == MAIN
    }

    fun gotoMain(): Boolean {
        var guard = 0
        while (!Ui.isForeground(PKG) && guard++ < 3) {
            Shell.run("am start --user $user -n $MAIN_ACT")
            Thread.sleep(3500)
        }
        if (!Ui.isForeground(PKG)) return false

        // ★ 送花墙必须优先处理（踩坑 2026-11-24，卡了整整两轮测试）
        //
        // 目标应用 对新注册用户，**每条对话页都弹**全屏「送花开聊」引导：
        //   限时活动：看广告获得免费宠爱玫瑰
        //   🌸 宠爱玫瑰 / 初见樱花 / ……
        //   [6 送花开聊]
        //
        // 【关键】点它的「狠心错过」不只是关弹窗，还会**顺带退回 MainActivity**。
        // 所以：点完先判是不是已经到主界面了。
        // 【绝对不要】点完再按返回 —— 那会把刚回到的主界面又退回去，
        //   然后对话页再弹花墙，形成「退 7 次还在这」的死循环（实测过）。
        if (killGiftWall()) {
            if (Ui.currentActivity() == MAIN) return true
        }

        if (Ui.currentActivity() == MAIN) return true

        // 常规手段：按一次返回（每次按完都检查花墙和主界面）
        repeat(3) {
            Ui.back()
            Thread.sleep(1200)
            // 返回后可能弹花墙；点了它可能直接回主界面
            if (killGiftWall()) {
                if (Ui.currentActivity() == MAIN) return true
            }
            if (Ui.currentActivity() == MAIN) return true
        }
        Shell.run("am start --user $user -n $MAIN_ACT")
        Thread.sleep(3500)
        if (Ui.currentActivity() == MAIN) return true

        // ★ 常规手段全失败 → 上暴力方案
        android.util.Log.w("SoulMate", "SoulBot: gotoMain 常规失败，forceGotoMain")
        return forceGotoMain()
    }

    // ---------- 列表 ----------

    /**
     * 读列表。
     *
     * ★ 未读角标是关键信号：目标应用 在她发新消息时会在行尾显示红色数字，
     *   这是判断「这条需要回」最可靠的依据 —— 比让模型猜「谁发的」准得多，
     *   而且点开会话后角标会自动消失，天然就是已读标记。
     *
     * ── 实测结构（2026-11-24，平板 1600x2560）──
     *
     * ```
     * 每行容器：rid=list_itease_layout (y 471-624)
     *   头像    rid=avatar
     *   昵称    rid=name          y=504-543   text='媛小美'
     *   来源标   rid=tv_from       y=510-536   text='新注册' / '广场' / '匹配'
     *   时间    rid=time          y=507-539   text='18:55'
     *   预览    rid=message       y=551-588   text='那你努力吧'
     *   角标容器 rid=unread_msg_number  y=549-590
     *     角标数  无 rid，text='1'  y=553-585  x=1549-1561
     * ```
     *
     * ── 踩坑 1（2026-11-24）：角标识别不到 → 全部判为「不需要回」──
     *
     * 原来只找「纯数字 + 无 rid + cx > 屏宽60%」的节点。
     * 实测角标确实是纯数字无 rid，**但它嵌在 `unread_msg_number` 容器里**，
     * 而容器本身没有 text，所以外层的可见性判断会把它漏掉。
     *
     * 修法：**优先按 `unread_msg_number` 容器定位**，再取其内部数字；
     * 找不到容器时才退回「裸数字」启发式（兼容其他版本）。
     *
     * ── 踩坑 2：name 和 message 的 y 不在同一区间 ──
     * name 在 y=504-543，message 在 y=551-588，差近 50px。
     * 原来用 `it.y1 - n.y1 in 0..rowGap`（rowGap≈144）能覆盖，
     * 但如果某行 name 缺失（比如系统行），后续会整体错位。
     * 现在改成「取该行容器内、name 之下最近的 message」。
     */
    private fun readList(): List<ChatRow> {
        val nodes = Ui.snap()
        val names = nodes.filter { it.rid == "name" && it.text.isNotBlank() }
            .sortedBy { it.y1 }
        val prevs = nodes.filter { it.rid == "message" && it.text.isNotBlank() }
            .sortedBy { it.y1 }
        val times = nodes.filter { it.rid == "time" && it.text.isNotBlank() }
            .sortedBy { it.y1 }

        val w = Screen.width()
        val h = Screen.height()

        // ① 未读角标 —— 优先按容器 rid 找（最可靠）
        val badgeNums = ArrayList<Pair<Int, Int>>()   // (y中心, 数值)

        // 1a. unread_msg_number 容器直接带 text 的版本
        nodes.filter { it.rid == "unread_msg_number" }.forEach { c ->
            val direct = c.text.trim().toIntOrNull()
            if (direct != null) {
                badgeNums.add(c.y1 to direct)
            }
        }

        // 1b. 容器不带 text，数字在子节点里（实测是这种）
        //     用「x 落在 unread_msg_number 容器范围内」的数字节点配对
        val badgeContainers = nodes.filter { it.rid == "unread_msg_number" }
        for (n in nodes) {
            val t = n.text.trim()
            if (t.isEmpty() || !t.all { it.isDigit() }) continue
            val v = t.toIntOrNull() ?: continue
            if (v !in 1..99) continue
            if (n.rid == "time" || n.rid == "name") continue
            // 是否落在某个角标容器里
            val inside = badgeContainers.any { c ->
                n.cx >= c.x1 && n.cx <= c.x2 + 40 && n.y1 >= c.y1 - 10 && n.y1 <= c.y2 + 10
            }
            if (inside) {
                badgeNums.add(n.y1 to v)
            }
        }

        // 1c. 兜底：裸数字启发式（兼容没有 unread_msg_number 的版本）
        if (badgeNums.isEmpty()) {
            nodes.filter { n ->
                val t = n.text.trim()
                t.isNotEmpty() && t.all { it.isDigit() } &&
                        (t.toIntOrNull() ?: 0) in 1..99 &&
                        n.cx > w * 0.6 &&
                        n.cy < h * 0.90 &&
                        n.rid != "time" && n.rid != "name"
            }.forEach { badgeNums.add(it.y1 to (it.text.trim().toIntOrNull() ?: 0)) }
        }

        // ② 系统标签 rid=tv_from
        val tags = nodes.filter {
            it.rid == "tv_from" && it.text.trim().isNotEmpty() && it.y1 < h * 0.92
        }

        // ③ 红心标记
        val hearts = nodes.filter { it.rid == "img_big_heart" }

        // ④ 每行容器（用来做边界，避免跨行配对）
        val itemRoots = nodes.filter { it.rid == "list_itease_layout" }
            .sortedBy { it.y1 }

        val rowGap = Screen.px(0.09).coerceAtLeast(80)

        /**
         * 找某个 y 属于哪个行容器。
         *
         * ★ 踩坑（2026-11-24）：原来用「y 差值 < 110px」的容差配对角标，
         *   但实测行高约 153px，一个角标会同时匹配到上下两行 →
         *   「4 个会话都显示有未读」，实际只有 2 个。
         *
         * 现在改成：**先找所在行容器的 [top, bottom]，再在该区间内配对**。
         */
        fun rowBoundsOf(y: Int): Pair<Int, Int> {
            itemRoots.firstOrNull { y >= it.y1 && y <= it.y2 }?.let {
                return it.y1 to it.y2
            }
            // 找不到容器就用最近的两个分界点
            val prev = itemRoots.lastOrNull { it.y1 <= y }?.y1 ?: 0
            val next = itemRoots.firstOrNull { it.y1 > y }?.y1 ?: (y + 200)
            return prev to next
        }

        return names.map { n ->
            val (rowTop, rowBottom) = rowBoundsOf(n.y1)

            // ① 角标：必须落在**同一个行容器**内
            val bd = badgeNums.firstOrNull { it.first in rowTop..rowBottom }
            val unread = bd?.second ?: 0

            // ② 预览：同一行容器内、name 之下最近的 message
            val pv = prevs.firstOrNull {
                it.y1 in rowTop..rowBottom && it.y1 > n.y1 - 20
            }?.text
                ?: prevs.firstOrNull { it.y1 - n.y1 in 0..rowGap }?.text
                ?: ""

            val tm = times.firstOrNull {
                kotlin.math.abs(it.y1 - n.y1) < 60 && it.y1 in rowTop..rowBottom
            }?.text ?: ""

            val tag = tags.firstOrNull {
                kotlin.math.abs(it.y1 - n.y1) < 60 && it.x1 > n.x1
            }?.text?.trim() ?: ""

            val hearted = hearts.any {
                kotlin.math.abs(it.y1 - n.y1) < 60 && it.y1 in rowTop..rowBottom
            }

            ChatRow(n.text, pv, tm, unread, tag, hearted)
        }.filter { row ->
            val byName = SKIP_PREFIX.none { row.name.startsWith(it) }
            val byTag = SKIP_TAG.none { row.tag.contains(it) }
            byName && byTag
        }
    }

    /**
     * 收集会话列表。
     *
     * ★ 关键：扫完之后必须**滚回顶部并重读**。
     *   踩坑（2026-11-23）：扫完停在列表底部，导致后面 openByName 从底部开始找，
     *   顶部最新的会话（刚来的奇遇铃/新消息）全被漏掉，
     *   表现为「明明顶上有新消息，却一个都没处理」。
     *
     * ★ 另一个坑：顶部数据要用**滚回顶部后重读的**，不能用扫描过程中
     *   第一次读到的 —— 那时可能还在动画中，位置不准。
     */
    /**
     * 扫完整聊天列表。
     *
     * @param maxScreens 最多滚几屏。默认 30 —— 实测 66 个会话约需 4-5 屏，
     *                   但行高会随内容变化，给足余量避免漏尾部的会话。
     *                   （原默认 10，用户实测 66 个会话后要求去掉限制）
     */
    fun collectAllChats(maxScreens: Int = 30): List<ChatRow> {
        // 先用分级处理确保真的在聊天列表
        if (ensureChatList().isEmpty()) return emptyList()

        val cx = Screen.width() / 2

        fun swipeUp(ms: Int = 250) {
            Ui.swipe(cx, Screen.py(0.31), cx, Screen.py(0.86), ms)
        }

        fun swipeDown(ms: Int = 480) {
            Ui.swipe(cx, Screen.py(0.82), cx, Screen.py(0.27), ms)
        }

        fun backToTop() {
            repeat(12) { swipeUp(200); Thread.sleep(150) }
            Thread.sleep(800)
        }

        // 先滚到顶部，保证从最新开始
        backToTop()

        val seen = LinkedHashMap<String, ChatRow>()
        var zeroStreak = 0
        for (i in 0 until maxScreens) {
            throwIfStop()          // 用户点停止就立刻跳出，不要扫完 10 屏
            val rows = readList()
            if (rows.isEmpty()) break
            var added = 0
            for (r in rows) {
                if (!seen.containsKey(r.name)) {
                    seen[r.name] = r
                    added++
                }
            }
            // ★ 连续 2 次没新增才停（原来 1 次就停）
            //   踩坑：行高随内容变化，下滑一次可能刚好滑到分隔线，
            //   那一屏 0 新增，但再滑一次又有了 → 1 次就停会漏掉尾部会话
            if (added == 0) {
                zeroStreak++
                if (zeroStreak >= 2) break
            } else {
                zeroStreak = 0
            }
            swipeDown()
            Thread.sleep(1300)
        }

        // ★ 扫完滚回顶部，并用顶部的最新数据覆盖
        backToTop()
        val topRows = readList()
        for (r in topRows) {
            // 顶部的数据最新（预览/未读/红心都可能是刚变的）
            seen[r.name] = r
        }

        return seen.values.toList()
    }

    /**
     * 确保停在「聊天列表页」并能读到会话。
     *
     * 踩坑总结（实测 5 种原因，按频率排）：
     *   1. 停在对话页/子页 —— 最常见。goto_main 返回后可能还在 ConversationActivity，
     *      或者回到主界面但不在聊天 tab，于是 name 节点为 0，被误判「读不到列表」
     *   2. 弹窗遮挡（下次一定 / 是否恢复聊天列表 / 打个招呼）
     *   3. 在广场页或星球页
     *   4. u2 server 真的死了
     *   5. 确实没有会话
     *
     * 处理顺序必须便宜到贵：先按需返回/关弹窗/切 tab，最后才考虑重启 u2。
     * （早期一遇到空列表就重启 u2，30 秒起步，纯属浪费。）
     */
    fun ensureChatList(maxTry: Int = 6): List<UiNode> {
        for (i in 0 until maxTry) {
            val a = Ui.currentActivity()
            val ns = Ui.snap()

            if (ns.isEmpty()) {
                // dump 空 → u2 可能没连上，等它恢复
                Thread.sleep(1500)
                continue
            }

            // 1) 先关弹窗
            //
            // ★ 奇遇铃必须单独处理（2026-11-24）
            //   它没有文字按钮可匹配，但有固定 rid=iv_love_close。
            //   不关掉的话，它会盖住整个聊天列表，
            //   表现为「name 节点为 0」，被误判成「读不到列表」。
            val bellClose = ns.firstOrNull { it.rid == "iv_love_close" }
            if (bellClose != null) {
                Ui.tap(bellClose.cx, bellClose.cy)
                Thread.sleep(1200)
                continue
            }

            val allText = ns.joinToString(" ") { it.text }
            val popupBtn = when {
                allText.contains("下次一定") -> "下次一定"
                allText.contains("是否恢复聊天列表") -> "确认"
                allText.contains("消息同步上线") -> "暂不考虑"
                allText.contains("喜欢SOUL吗") -> "取消"
                else -> null
            }
            if (popupBtn != null) {
                val btn = ns.firstOrNull { it.text.trim() == popupBtn }
                if (btn != null) {
                    Ui.tap(btn.cx, btn.cy)
                    Thread.sleep(2000)
                    continue
                }
            }

            // 2) 已经有会话 → 成功
            val names = ns.filter { it.rid == "name" && it.text.isNotBlank() }
            if (names.isNotEmpty()) return ns

            // 3) 不在 目标应用 → 拉起
            if (!Ui.isForeground(PKG)) {
                Shell.run("am start --user $user -n $MAIN_ACT")
                Thread.sleep(7000)
                continue
            }

            // 4) 在子页面 → 返回；回主界面后补点聊天 tab
            if (a != MAIN) {
                Ui.back()
                Thread.sleep(2000)
                if (Ui.currentActivity() == MAIN) {
                    val tab = Ui.snap().firstOrNull { it.rid == "main_tab_msg_text" }
                    if (tab != null) {
                        Ui.tap(tab.cx, tab.cy)
                        Thread.sleep(2500)
                    }
                }
                continue
            }

            // 5) 在主界面但不在聊天 tab
            val tab = ns.firstOrNull { it.rid == "main_tab_msg_text" }
            if (tab != null) {
                Ui.tap(tab.cx, tab.cy)
                Thread.sleep(2500)
                continue
            }

            Thread.sleep(1800)
        }
        return emptyList()
    }

    /**
     * 分层点击：找一个元素并点它。
     *
     * 四层策略（从准到贵）：
     *   1) resource-id 精确匹配        —— 最快最准，0 成本
     *   2) text / content-desc 匹配    —— 次选
     *   3) ★ 视觉选编号（Vision）      —— 解决「坐标点不准 / 找不到」
     *   4) 比例兜底                     —— 最糊，只在给定了 fallback 时用
     *
     * 为什么要有第 3 层：
     *   `main_tab_msg_text` 只有 50x34 px，按屏幕比例算会偏 46px；
     *   视觉标注法让模型选编号，坐标始终来自 dump 的真实 bounds，永不点偏。
     *   视觉调用有成本，所以只在第 1、2 层都失败时才走。
     */
    fun clickSmart(
        byRid: String? = null,
        byText: String? = null,
        byDesc: String? = null,
        visionHint: String? = null,
        minY: Int = 0,
        maxY: Int = Int.MAX_VALUE,
        fallback: Pair<Int, Int>? = null,
    ): Boolean {
        val nodes = Ui.snap()
        val inRange = nodes.filter { it.y1 in minY..maxY }

        // ① rid
        if (byRid != null) {
            val n = inRange.firstOrNull { it.rid == byRid }
            if (n != null) {
                Ui.tap(n.cx, n.cy)
                return true
            }
        }

        // ② text
        if (byText != null) {
            val n = inRange.firstOrNull { it.text.trim() == byText }
                ?: inRange.firstOrNull { it.text.contains(byText) }
            if (n != null) {
                Ui.tap(n.cx, n.cy)
                return true
            }
        }

        // ② desc
        if (byDesc != null) {
            val n = inRange.firstOrNull { it.desc.trim() == byDesc }
                ?: inRange.firstOrNull { it.desc.contains(byDesc) }
            if (n != null) {
                Ui.tap(n.cx, n.cy)
                return true
            }
        }

        // ③ 视觉
        if (visionHint != null) {
            val picked = Vision.tapByVision(visionHint, nodes)
            if (picked != null) return true
        }

        // ④ 比例兜底
        if (fallback != null) {
            Ui.tap(fallback.first, fallback.second)
            return true
        }
        return false
    }

    /**
     * 读「收到的新招呼」里有哪些人。
     *
     * ── 页面结构（真机 MEIZU 21 Pro 实测 2026-11-25）──
     * 聊天列表顶部有一行 `tv_greeting_title`「收到的新招呼」，
     * 点进去是 `NewGreetingDetailActivity`：
     *
     *   tv_title             「收到的新招呼」
     *   tv_tips              「发消息后对话框将会被放出哦」← 关键语义
     *   rv_new_greeting_list RecyclerView
     *     └─ list_itease_layout（每行）
     *          name          昵称
     *          tv_from       「匹配」
     *          time          时间
     *          message       招呼语（如「[樱花]和今日份奇遇打个招呼吧」）
     *          unread_msg_number  未读角标
     *          avatar        头像
     *
     * ── 为什么值得处理（用户 2026-11-25 要求）──
     * 「收到的新招呼你也处理下，里面也会开启私聊」
     * 这些人**主动跟你打了招呼**，回复意愿最强，
     * 而且点进去发消息就直接开启私聊（目标应用的规则就是这样）。
     *
     * @return 招呼列表；读不到返回空
     */
    fun readNewGreetings(): List<Greeting> {
        if (!Ui.isForeground(PKG)) return emptyList()
        val act = Ui.currentActivity()
        if (!act.contains("NewGreetingDetail")) return emptyList()

        val nodes = Ui.snap()
        val rows = nodes.filter { it.rid == "list_itease_layout" }
            .sortedBy { it.y1 }
        if (rows.isEmpty()) return emptyList()

        val names = nodes.filter { it.rid == "name" && it.text.isNotBlank() }
            .sortedBy { it.y1 }
        val froms = nodes.filter { it.rid == "tv_from" && it.text.isNotBlank() }
            .sortedBy { it.y1 }
        val times = nodes.filter { it.rid == "time" && it.text.isNotBlank() }
            .sortedBy { it.y1 }
        val msgs = nodes.filter { it.rid == "message" && it.text.isNotBlank() }
            .sortedBy { it.y1 }

        val unreadYs = ArrayList<Int>()
        for (n in nodes) {
            if (n.rid != "unread_msg_number") continue
            // 角标容器里再找数字
            nodes.firstOrNull {
                it.text.trim().toIntOrNull() != null &&
                        it.cx in n.x1..n.x2 && it.y1 in n.y1..n.y2
            }?.let { unreadYs.add(it.y1) }
        }

        val out = ArrayList<Greeting>()
        for (r in rows) {
            // 这一行内的元素（用行 bounds 配对，比相邻判断稳）
            fun inRow(n: UiNode) = n.y1 >= r.y1 - 10 && n.y1 <= r.y2 + 10

            val nm = names.firstOrNull { inRow(it) }?.text?.trim() ?: continue
            val from = froms.firstOrNull { inRow(it) }?.text?.trim() ?: ""
            val tm = times.firstOrNull { inRow(it) }?.text?.trim() ?: ""
            val msg = msgs.firstOrNull { inRow(it) }?.text?.trim() ?: ""
            val unread = unreadYs.any { it in r.y1..r.y2 }

            out.add(
                Greeting(
                    name = nm,
                    from = from,
                    time = tm,
                    message = msg,
                    unread = unread,
                    tapX = (r.x1 + r.x2) / 2,
                    tapY = (r.y1 + r.y2) / 2,
                    y = r.y1,
                )
            )
        }
        return out
    }

    /** 新招呼里的一条 */
    data class Greeting(
        val name: String,
        val from: String,
        val time: String,
        val message: String,
        val unread: Boolean,
        val tapX: Int,
        val tapY: Int,
        val y: Int,
    )

    /**
     * 从聊天列表进入「收到的新招呼」页。
     *
     * @return 是否成功进入
     */
    fun openNewGreetingsPage(): Boolean {
        gotoMain()
        tapChatTab()
        Thread.sleep(1500)

        // 回到列表顶部（招呼入口在最上面）
        repeat(3) {
            Ui.swipe(Screen.px(0.5), Screen.px(0.35),
                Screen.px(0.5), Screen.px(0.75), 300)
            Thread.sleep(400)
        }

        val nodes = Ui.snap()

        // ★ 只认 tv_greeting_title「收到的新招呼」
        //
        // 踩坑（2026-11-25 实测）：
        // 聊天列表里还有两行看起来「像」新招呼入口的系统提示：
        //   「奇遇铃-稍后再聊 / 有新朋友等你和Ta开启对话」
        //   「目标应用空间站 / Hi~欢迎来到目标应用星球…」
        // 但它们点进去是 H5Activity（网页）和 TagSquareActivity2（标签广场），
        // **不是新招呼页**。按文字特征去猜会跳错页面。
        //
        // 真正的入口只有 tv_greeting_title 一个，
        // 而且它**只在有新招呼时出现**（读过就消失）。
        // 所以找不到就说明今天没有新招呼，直接跳过。
        val entry = nodes.firstOrNull { it.rid == "tv_greeting_title" }
            ?: return false

        Ui.tap(entry.cx, entry.cy)
        Thread.sleep(3000)

        if (Ui.currentActivity().contains("NewGreetingDetail")) return true

        // 有时点到了行内文字没点中容器，再试一次
        val n2 = Ui.snap()
        val e2 = n2.firstOrNull { it.rid == "tv_greeting_title" }
        if (e2 != null) {
            Ui.tap(e2.cx, e2.cy)
            Thread.sleep(2500)
        }
        return Ui.currentActivity().contains("NewGreetingDetail")
    }

    /**
     * 打开某个新招呼的人，进入对话页（= 开启私聊）。
     *
     * 实测：从 NewGreetingDetailActivity 点那一行，直接进 ConversationActivity，
     * 里面有 et_sendmessage 输入框，可以直接发消息。
     * 目标应用的规则是「发消息后对话框将会被放出」，所以发出去就建立会话了。
     *
     * @return 进入后的对话页信息；失败返回 null
     */
    fun openGreetingChat(g: Greeting): ChatDetail? {
        if (!Ui.currentActivity().contains("NewGreetingDetail")) return null

        // 重新读一下位置（列表可能滚过）
        val cur = readNewGreetings()
        val target = cur.firstOrNull { it.name == g.name } ?: g

        Ui.tap(target.tapX, target.tapY)
        Thread.sleep(3500)

        if (Ui.currentActivity() != CONV) {
            // 可能弹了送花墙
            if (killGiftWall()) {
                if (Ui.currentActivity() == CONV) {
                    return try {
                        readChat(transcribe = false)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            // 还是没进去
            if (Ui.currentActivity() != CONV) return null
        }

        return try {
            readChat(transcribe = false)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 点底部「聊天」tab。
     *
     * 优先级（从稳到不稳）：
     *   1) main_tab_msg        —— 整个 tab 容器，大目标，最不容易点偏
     *   2) main_tab_msg_text   —— 只有文字本身，可能只有 50x34 px
     *   3) content-desc="聊天" —— 无障碍描述
     *   4) 视觉选编号           —— 上面都没有时兜底
     *   5) 按 dp 从底部量      —— 最后兜底
     */
    fun tapChatTab() {
        val nodes = Ui.snap()
        val h = Screen.height()

        // ① 整个 tab 容器（最大，最稳）
        val tab = nodes.firstOrNull { it.rid == "main_tab_msg" }
            ?: nodes.firstOrNull {
                it.rid == "main_tab_msg_text" && it.text.trim() == "聊天"
            }
            ?: nodes.firstOrNull {
                it.text.trim() == "聊天" && it.y1 > h * 0.85
            }
            ?: nodes.firstOrNull {
                it.desc.trim() == "聊天" && it.y1 > h * 0.85
            }

        if (tab != null) {
            Ui.tap(tab.cx, tab.cy)
        } else {
            // 兜底：视觉识别，再不行按 dp 量
            val picked = Vision.tapByVision("底部导航栏里的「聊天」标签", nodes)
            if (picked == null) {
                Ui.tap(Screen.px(0.657), Screen.fromBottomDp(28))
            }
        }
        Thread.sleep(1800)
    }

    /**
     * 按名字打开会话。
     *
     * ★ 踩坑（2026-11-23）：
     *   列表结构会变 —— 比如「奇遇铃」自动会话突然插到首位，
     *   把下面所有会话往下推一行。此时用旧坐标/旧位置去找必然点偏，
     *   表现为「XX 打不开」接连失败。
     *
     * 应对：
     *   1) 每次都重新 dump，绝不缓存坐标
     *   2) 第一屏找不到就滚动找
     *   3) 还找不到，用视觉兜底（截图标注选号）
     */
    fun openByName(name: String): Boolean {
        if (Ui.currentActivity() != MAIN) {
            if (!gotoMain()) return false
        }
        tapChatTab()
        Thread.sleep(1500)

        // ★ 先滚到顶部再从下找：列表是时间倒序，越靠上越可能是活跃会话
        repeat(12) {
            Ui.swipe(Screen.width() / 2, Screen.py(0.30), Screen.width() / 2, Screen.py(0.85), 200)
            Thread.sleep(150)
        }
        Thread.sleep(600)

        fun find(): UiNode? = Ui.snap().firstOrNull {
            it.rid == "name" && it.text.isNotBlank() && it.text.contains(name.take(6))
        }

        fun tapIt(n: UiNode): Boolean {
            Ui.tap(n.cx, n.cy)
            Thread.sleep(3000)
            // 有时点开会弹「打个招呼」之类，多点一次
            if (Ui.currentActivity() != CONV) {
                val ns = Ui.snap()
                val greet = ns.firstOrNull { it.text.trim() == "打个招呼" }
                if (greet != null) {
                    Ui.tap(greet.cx, greet.cy)
                    Thread.sleep(3000)
                }
            }
            return Ui.currentActivity() == CONV
        }

        // ① 当前屏直接找
        find()?.let { if (tapIt(it)) return true }

        // ② 往下滚找（最多 6 屏）
        repeat(6) {
            Ui.swipe(Screen.width() / 2, Screen.py(0.82), Screen.width() / 2, Screen.py(0.27), 480)
            Thread.sleep(1300)
            find()?.let { if (tapIt(it)) return true }
        }

        // ③ 滚回顶部再试（列表可能在滚动过程中刷新了）
        repeat(9) {
            Ui.swipe(Screen.width() / 2, Screen.py(0.31), Screen.width() / 2, Screen.py(0.86), 250)
            Thread.sleep(200)
        }
        Thread.sleep(600)
        find()?.let { if (tapIt(it)) return true }

        // ④ 视觉兜底：截图标注，让模型选
        return try {
            val picked = Vision.tapByVision("聊天列表里名字叫「$name」的那一行", Ui.snap())
            if (picked != null) {
                Thread.sleep(3500)
                Ui.currentActivity() == CONV
            } else false
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 对话 ----------

    /**
     * 读对话页顶部卡片上「她」的资料信息。
     *
     * ── 为什么需要 ──
     * 之前只看聊天内容生成回复，但对话页顶部其实有大量可用素材：
     *
     *   匹配度 95% ／ Ta守护天使星球
     *   Ta的认证：ISFP
     *   Ta的引力签：习惯一个人 / 共情力满格 / 拒绝负能量 /
     *               情绪分享 / 拒绝已婚人士打扰 / 颜控声控身高控 / 厌蠢
     *   你们的共同点：上海
     *
     * 这些比「匹配度0%」这种废话有用得多，是回复的绝佳切入点。
     * 实测 rid（模拟器 1080x2340 + 平板 1600x2560 通用）：
     *   pipeidu_new / percentTv_new  匹配度
     *   planetTv_new                 星球名
     *   tv_tag_text                  认证（ISFP）
     *   （无 rid 的 TextView）        引力签、共同点
     */
    fun readChatCard(): Profile {
        val nodes = Ui.snap()
        // 卡片区域：标题栏以下、第一条消息以上
        val cardBottom = nodes.filter { it.rid == "content_text" }
            .minOfOrNull { it.y1 } ?: Screen.py(0.5)
        val top = nodes.filter { it.rid == "tv_title" }.minOfOrNull { it.y1 } ?: 0
        val cardTop = Screen.py(0.06)

        val raw = ArrayList<String>()
        var match = ""
        var ip = ""

        for (n in nodes) {
            if (n.y1 < cardTop || n.y1 > cardBottom) continue
            val t = n.text.trim()
            if (t.isBlank()) continue

            when (n.rid) {
                "percentTv_new" -> if (match.isBlank()) match = t
                "pipeidu_new" -> { /* 「匹配度」标签，忽略 */ }
                "planetTv_new" -> raw.add("星球:$t")
                "tv_tag_text" -> raw.add("认证:$t")
                "tv_title" -> {
                    // 「你们的共同点：上海」—— 这是唯一可信的属地来源
                    // ★ 但要校验：目标应用 也会显示「你们的共同点：水象星座」
                    //   这类不是地名，不能当 IP 用（踩坑两次）
                    if (t.contains("共同点")) {
                        val city = t.substringAfter("共同点").trimStart(':', '：', ' ')
                        if (city.isNotBlank() && city.length <= 6) {
                            val looksCity = city.all { it in '\u4e00'..'\u9fff' } &&
                                    listOf("星座", "座", "岁", "恋爱", "朋友",
                                        "毕业", "单身", "希望", "标签")
                                        .none { city.contains(it) }
                            raw.add("共同点:$city")
                            if (looksCity) ip = city
                        }
                    }
                }
                else -> {
                    // ★ ip 只在「共同点」里赋值，绝不从其它字段猜
                    //   （踩坑：「水象星座」曾被当成 IP，导致整条会话被误判跳过）
                    val isNoise =
                        t.contains("匹配度") || t.contains("查看主页") ||
                                t.contains("关注后可") || t.startsWith("Ta的") ||
                                t == "关注" || t.contains("星球") ||
                                // 时间格式
                                t.matches(Regex("""^\d+秒$""")) ||
                                t.matches(Regex("""^\d+分(钟)?前$""")) ||
                                t.matches(Regex("""^\d+小时前$""")) ||
                                t.matches(Regex("""^\d{1,2}:\d{2}$""")) ||
                                t.matches(Regex("""^\d+[SsＳ秒]$""")) ||
                                t.matches(Regex("""^\d+$""")) ||
                                t.contains("刚刚") || t.contains("在线") ||
                                // 星座、年龄段、交友意向 —— 不是引力签
                                t.contains("星座") || t.contains("希望Ta") ||
                                t.contains("岁") || t.contains("恋爱") ||
                                t.contains("朋友") || t.contains("毕业") ||
                                t.contains("单身") || t.contains("扩列") ||
                                t.contains("聊") || t.contains("找") ||
                                // ★ 自己 App 的悬浮停止按钮 —— 踩坑（2026-11-24）
                                //   悬浮窗是独立 Window，u2 dump 会把它一起读进来，
                                //   落在卡片 y 区间内就被当成引力签。
                                //   实测日志出现「资料：■ 停止 星球:Ta魅力少帅星球 …」
                                t.contains("停止") || t.contains("■") ||
                                t.contains("SoulMate")

                    if (!isNoise && t.length in 2..12 &&
                        n.y1 > cardTop + Screen.py(0.02)
                    ) {
                        raw.add(t)
                    }
                }
            }
        }

        return Profile(ip, raw.distinct(), match)
    }

    /**
     * 向上翻页加载更早的聊天记录。
     *
     * ── 用户需求（2026-11-25）──
     * 「AI 能更智能点吗，就是可以再读完历史会话之后再回话」
     *
     * ── 问题 ──
     * readChat() 只读**当前屏幕可见**的消息（一般 8-15 条）。
     * 聊得久的会话，前面的内容全在屏幕上方外面，模型根本看不到。
     * 于是：她提到过的事我忘了、她问过的问题我又问一遍、
     * 话题断片 —— 都是「记性差」的表现。
     *
     * ── 做法 ──
     * 在对话页里**向下滑动**（手指下滑 = 内容下移 = 露出更早的消息），
     * 每滑一次读一遍，把新读到的并进来，直到连续两次没有新内容为止。
     * 最后必须**滑回底部**，否则发送按钮可能不在视野里。
     *
     * ── 关键设计 ──
     * · 用「消息文本 + 侧别」做去重键，不靠 y 坐标
     *   （滑动后同一句话的 y 会变，坐标不能当身份）
     * · 连续 2 次没新增才停（1 次容易误判，因为滑动有惯性）
     * · 最多翻 [maxPages] 页（防止超长会话卡住，也防止无限滑）
     * · 只保留最近 [keep] 条给模型（上下文太长反而效果差）
     *
     * @param first 当前 already 读到的消息（readChat 的结果）
     * @param maxPages 最多翻几页
     * @param keep 最终保留多少条（从最新往前数）
     * @return 合并后的完整消息列表（旧→新）
     */
    fun loadHistory(
        first: List<Msg>,
        maxPages: Int = 6,
        keep: Int = 60,
    ): List<Msg> {
        if (first.isEmpty()) return first

        // 对话区范围：资料卡片以下、输入框以上
        val nodes = Ui.snap()
        val inputTop = nodes.firstOrNull {
            it.rid == "et_sendmessage" || it.rid == "rl_bottom"
        }?.y1 ?: Screen.py(0.90)
        val cardBottom = nodes.filter { it.rid == "content_text" }
            .minOfOrNull { it.y1 } ?: Screen.py(0.35)

        // 滑动区间：从消息区中下部 → 中上部
        val sx = Screen.width() / 2
        val yBottom = (inputTop - Screen.py(0.02)).coerceAtLeast(cardBottom + 200)
        val yTop = (cardBottom + Screen.py(0.03)).coerceAtMost(yBottom - 150)

        if (yBottom - yTop < 120) {
            // 可视区域太小（横屏/小屏），不折腾了
            return first
        }

        // 去重键：侧别 + 归一化文本
        //
        // 为什么不用坐标：滑动后同一句话的 y 完全不同，
        // 拿坐标做身份必然重复入库。
        fun key(m: Msg): String {
            val t = m.text.replace(Regex("""\s+"""), "").take(80)
            return "${m.side}|$t"
        }

        val acc = LinkedHashMap<String, Msg>()
        for (m in first) acc[key(m)] = m

        var zeroStreak = 0
        var pages = 0

        while (pages < maxPages) {
            // 用户点停止就立刻退，不要卡在翻页里
            if (stopCheck?.invoke() == true) break

            // ★ 滑动方向（踩坑，2026-11-25 第一版写反了）
            //
            // 聊天记录里「更早的消息在上方」。要露出上方内容，
            // 手指必须**向下**移动（从 yTop 拖到 yBottom），
            // 内容随之下移，上方旧消息进入视野。
            //
            // 第一版写成了 yBottom → yTop（手指向上），那是往**更新**的方向翻，
            // 结果永远读不到新东西，日志里也不会打印任何「翻页读到」。
            Ui.swipe(sx, yTop, sx, yBottom, 420)
            Thread.sleep(650)

            // ★★ 防「滑过头」（2026-11-25 实测发现）★★
            //
            // 短会话（消息不满一屏）时，向下滑会把整个消息区推出视野，
            // 只露出上方的资料卡片 → 读到 0 条。
            // 这时如果继续滑，会白白浪费 6 页时间，还可能把页面搞乱。
            //
            // 判断：这一页读到的条数**少于之前的 60%** 就是滑过头了，
            // 立刻滑回底部并结束翻页。
            val page = readChat(transcribe = false).messages
            if (page.size < acc.size * 0.6) {
                // 滑过头，回底部收工
                break
            }
            if (page.isEmpty()) {
                zeroStreak++
                if (zeroStreak >= 2) break
                pages++
                continue
            }

            var added = 0
            for (m in page) {
                val k = key(m)
                if (!acc.containsKey(k)) {
                    acc[k] = m
                    added++
                }
            }
            pages++

            if (added == 0) {
                zeroStreak++
                if (zeroStreak >= 2) break
            } else {
                zeroStreak = 0
            }
        }

        // ★ 必须滑回底部
        //
        // 踩坑预警：不回底部的话，发送按钮/输入框可能不在视野里，
        // 后面的 send() 会直接失败（表现为 no_input）。
        //
        // 方向：手指**向上**（yBottom → yTop），内容上移，
        // 露出下方的最新消息。与上面翻旧消息的方向正好相反。
        repeat(8) {
            Ui.swipe(sx, yBottom, sx, yTop, 260)
            Thread.sleep(180)
        }
        Thread.sleep(500)

        // 按「在屏幕上的顺序」重排
        //
        // 注意：不同页读到的 msg 的 y 不可比（来自不同滚动位置），
        // 所以这里不排序，而是保持「后读到的更靠前」这个自然顺序：
        // 我们的 acc 是 LinkedHashMap，first 里的（最新屏）先插入，
        // 后续翻页读到的都是更早的消息，所以把后面的插到前面去。
        val ordered = ArrayList<Msg>()
        // first 是当前屏（最新的一段）
        ordered.addAll(first)
        // 其余（翻页读到的、不在 first 里的）都是更早的，放前面
        val firstKeys = first.map { key(it) }.toHashSet()
        val older = acc.values.filter { key(it) !in firstKeys }
        // older 是「越往后翻页越早」，所以倒序才是时间正序
        ordered.addAll(0, older.reversed())

        return if (ordered.size > keep) ordered.takeLast(keep) else ordered
    }

    fun readChat(transcribe: Boolean = false): ChatDetail {
        val nodes = Ui.snap()
        val topLimit = Screen.py(0.10)

        val who = nodes.firstOrNull { it.rid == "tv_title" && it.y1 < topLimit }?.text ?: ""

        val voiceLen = HashMap<Int, String>()
        nodes.filter { it.rid == "tv_length" && it.text.isNotBlank() }
            .forEach { voiceLen[it.y1] = it.text }

        val msgs = ArrayList<Msg>()
        val bubbles = ArrayList<Pair<UiNode, String>>()

        for (n in nodes) {
            when (n.rid) {
                "content_text" -> {
                    if (n.text.isBlank() || TIME_DIVIDER.matches(n.text)) continue
                    msgs.add(Msg(Screen.sideOf(n.x1), n.text, n.y1))
                }
                "voice_bubble_container" -> {
                    val dur = voiceLen.entries.firstOrNull {
                        kotlin.math.abs(it.key - n.y1) < 80
                    }?.value ?: ""
                    val side = Screen.sideOf(n.x1)
                    msgs.add(Msg(side, "[语音$dur]", n.y1))
                    if (side == "her") bubbles.add(n to dur)
                }
            }
        }
        msgs.sortBy { it.y }

        // 语音转写：转完她所有未转写的语音
        if (transcribe) {
            val targets = msgs.filter { it.side == "her" && it.text.startsWith("[语音") }
            for (t in targets.reversed()) {
                val b = bubbles.firstOrNull { kotlin.math.abs(it.first.y1 - t.y) < 120 }
                if (b != null) {
                    val said = transcribeVoice(b.first)
                    if (said.isNotBlank()) {
                        val idx = msgs.indexOf(t)
                        if (idx >= 0) {
                            msgs[idx] = t.copy(text = "[语音${b.second}转写:$said]")
                        }
                    }
                }
            }
        }

        // ★★★ 合并连续语音（2026-11-24 用户反馈）★★★
        //
        // 用户场景：她连发两条语音（2s + 4s），截图显示两条挨着。
        //
        // 之前的行为：两条各自变成
        //   [语音2s转写:xxx]
        //   [语音4s转写:yyy]
        // 丢给模型。虽然模型能看到两条，但**没有明确告诉它这是同一段话被切开**，
        // 容易只针对最后一条回。
        //
        // 而且语音转写本身有误差（同音字、断句），
        // 拆成两条反而更难理解 —— 一个词的半个字可能在第一条，另半个在第二条。
        //
        // 现在合并成一个逻辑消息：
        //   [她连发2条语音：2s「xxx」／4s「yyy」]
        //
        // 这样模型明确知道「她在连着说」，会综合理解整段意思。
        val merged = mergeConsecutiveVoice(msgs)
        return ChatDetail(who, merged)
    }

    /**
     * 把连续的同侧语音合并成一条消息。
     *
     * 规则：
     *   · 只合并**她的**语音（我的语音不用管，我不需要回自己）
     *   · 「连续」= 中间没有其他消息（文字/图片都不行）
     *   · 合并后文本格式：`[她连发2条语音：2s「xxx」／4s「yyy」]`
     *   · 单条语音保持原格式，不合并（避免破坏已有逻辑）
     */
    private fun mergeConsecutiveVoice(msgs: List<Msg>): List<Msg> {
        if (msgs.size < 2) return msgs

        val out = ArrayList<Msg>()
        var i = 0
        while (i < msgs.size) {
            val cur = msgs[i]
            val isHerVoice = cur.side == "her" && cur.text.startsWith("[语音")

            if (!isHerVoice) {
                out.add(cur)
                i++
                continue
            }

            // 往后数连续的她的语音
            var j = i
            while (j < msgs.size &&
                msgs[j].side == "her" &&
                msgs[j].text.startsWith("[语音")
            ) {
                j++
            }

            val run = msgs.subList(i, j)
            if (run.size == 1) {
                // 单条，原样保留
                out.add(cur)
            } else {
                // 多条 → 合并
                val parts = run.map { m -> parseVoicePart(m.text) }
                val combined = "[她连发${run.size}条语音：" +
                        parts.joinToString("／") + "]"
                out.add(Msg("her", combined, cur.y))
                android.util.Log.i(
                    "SoulMate",
                    "mergeVoice: 合并 ${run.size} 条连续语音 → ${combined.take(80)}"
                )
            }
            i = j
        }
        return out
    }

    /**
     * 把一条语音消息的文本解析成「时长＋转写内容」。
     *
     * 输入可能是：
     *   `[语音2s]`                  → ("2s", "")
     *   `[语音2s转写:今天好累]`        → ("2s", "今天好累")
     *   `[语音2s转写:今天好累`         → ("2s", "今天好累")   ← 没闭合也认
     *
     * ★ 不用正则（2026-11-24）：转写内容里可能有 ] 或其他符号，
     *   正则容易失配。手动按固定前缀拆更稳。
     */
    private fun parseVoicePart(text: String): String {
        if (!text.startsWith("[语音")) return text

        var inner = text.removePrefix("[语音")
        if (inner.endsWith("]")) inner = inner.dropLast(1)

        val dur: String
        val said: String
        when {
            inner.contains("转写:") -> {
                val p = inner.split("转写:", limit = 2)
                dur = p[0].trim()
                said = p.getOrElse(1) { "" }.trim()
            }
            inner.contains("转写：") -> {
                val p = inner.split("转写：", limit = 2)
                dur = p[0].trim()
                said = p.getOrElse(1) { "" }.trim()
            }
            else -> {
                dur = inner.trim()
                said = ""
            }
        }
        val d = dur.ifBlank { "?" }
        return if (said.isBlank()) "$d（未转写）" else "$d「$said」"
    }

    /**
     * 检测对话里有没有「非文字消息」（图片/表情包）。
     *
     * 这类消息 content_text 读不到，会变成盲区 —— 模型看不到她在发图，
     * 回复就会文不对题。这里只做「有没有」的探测，用来提示模型。
     *
     * 实测已知的消息类型：
     *   content_text           文字
     *   voice_bubble_container 语音
     *   （图片/表情待补：当前测试账号的会话里没有图片消息样本）
     */
    fun hasNonTextMessage(nodes: List<UiNode>): Boolean {
        return nodes.any {
            it.rid.contains("img", ignoreCase = true) ||
                    it.rid.contains("pic", ignoreCase = true) ||
                    it.rid.contains("emoji", ignoreCase = true) ||
                    it.rid.contains("sticker", ignoreCase = true) ||
                    it.rid.contains("gift", ignoreCase = true)
        }
    }

    /**
     * 进对话页后等页面稳住。
     *
     * ── 为什么需要（用户反馈 2026-11-25）──
     * 「打开回复界面我觉得可以停 2s 再识别语音，因为这个页面还会弹出
     *   别的内容导致点击到空白处」
     *
     * ── 进对话页后的 1-2 秒里发生了什么 ──
     *   1. 顶部资料卡片（匹配度 / 引力签 / 共同点）淡入，
     *      容器高度从 0 长到 800px 左右 —— 消息列表被往下推，
     *      这期间 dump 出来的 y 坐标全是**动画中间态**。
     *   2. 消息列表自动滚到底部。
     *   3. 可能弹「送花墙」「开启通知」「会员」浮层。
     *
     * 在这期间做 longPress 转写语音 → 按旧坐标点下去 → 点到空白处
     * → 转写失败 → 模型看不到她说的话 → 回复文不对题。
     *
     * ── 做法 ──
     *   ① 静置 2000ms（用户指定的时长）
     *   ② 连续两次 dump，节点数量一致才算稳定（最多等 6 秒）
     *   ③ 顺手关掉已知浮层（送花墙）
     */
    fun settleChatPage(quietMs: Long = 2000, maxWaitMs: Long = 6000) {
        // ① 静置
        Thread.sleep(quietMs)

        // ② 等布局稳定：连续两次 dump 的节点数和消息区顶部一致
        var stable = 0
        var lastCount = -1
        var lastTop = -1
        val deadline = System.currentTimeMillis() + maxWaitMs

        while (System.currentTimeMillis() < deadline && stable < 2) {
            val ns = try {
                Ui.snap()
            } catch (_: Throwable) {
                emptyList()
            }
            if (ns.isEmpty()) {
                Thread.sleep(300)
                continue
            }
            val count = ns.size
            // 用「第一条消息气泡的顶部」当布局锚点
            val top = ns.filter { it.rid == "content_text" || it.rid == "voice_bubble_container" }
                .minOfOrNull { it.y1 } ?: -1

            if (count == lastCount && top == lastTop) {
                stable++
            } else {
                stable = 0
                lastCount = count
                lastTop = top
            }
            Thread.sleep(300)
        }

        // ③ 关掉已知浮层（送花墙）
        try {
            if (Ui.snap().any { it.rid == "tv_btn_close" }) {
                killGiftWall()
                Thread.sleep(600)
            }
        } catch (_: Throwable) {
        }
    }

    fun transcribeVoice(bubble: UiNode): String {
        var nodes = Ui.snap()
        var col = nodes.firstOrNull { it.text == "点击收起" }

        if (col == null) {
            Ui.longPress(bubble.cx, bubble.cy, 1500)
            Thread.sleep(1600)
            nodes = Ui.snap()
            val btn = nodes.firstOrNull { it.text.trim() == "转文字" }
            if (btn != null) {
                Ui.tap(btn.cx, btn.cy)
                Thread.sleep(2200)
                nodes = Ui.snap()
                col = nodes.firstOrNull { it.text == "点击收起" }
            }
            if (col == null) return ""
        }

        val by = bubble.y2
        val cyLine = col!!.y1
        val said = nodes
            .filter {
                it.cls == "TextView" && it.text.isNotBlank() &&
                        it.text != "点击收起" && it.y1 in (by + 1) until cyLine &&
                        it.x1 < Screen.width() / 2
            }
            .joinToString(" ") { it.text }
            .trim()

        Ui.tap(col!!.cx, col!!.cy)
        Thread.sleep(400)
        return said
    }

    fun readProfile(): Profile {
        if (Ui.currentActivity() != CONV) return Profile("", emptyList(), "")
        val nodes = Ui.snap()
        val avatar = nodes.filter { it.rid == "chat_avatar" }
            .minByOrNull { it.x1 }
            ?: nodes.firstOrNull { it.rid == "userAvatar" }
            ?: return Profile("", emptyList(), "")

        Ui.tap(avatar.cx, avatar.cy)
        Thread.sleep(3000)

        if (Ui.currentActivity() != HOME) {
            Ui.back()
            Thread.sleep(1500)
            return Profile("", emptyList(), "")
        }

        val pn = Ui.snap()
        val raw = ArrayList<String>()
        pn.filter { it.text.isNotBlank() && it.y1 > Screen.py(0.08) }
            .sortedBy { it.y1 }
            .forEach { if (raw.size < 24) raw.add(it.text) }

        val ip = raw.firstOrNull { it.contains("IP属地") }
            ?.substringAfter("IP属地")?.trimStart(':', '：', ' ') ?: ""
        val match = raw.firstOrNull { it.contains("%") } ?: ""

        Ui.back()
        Thread.sleep(1500)
        return Profile(ip, raw, match)
    }

    // ---------- 发送 ----------

    /**
     * 发送消息。
     *
     * 踩过的坑（真实反馈：能找到要回的人但没发出去）：
     *   1) 【主因】ADBKeyboard 广播会让 目标应用的输入框节点直接消失。
     *      改用 u2 的剪贴板（setClipboard + pasteClipboard）。
     *   2) 键盘弹起后输入框从 y=2431 上移到 y=1504，
     *      所以只按 rid 找节点，绝不依赖 y 坐标判断。
     *   3) 点发送前必须按返回收键盘，否则点击被输入法吞掉。
     *   4) 收键盘后按钮位置又变，要重新 dump。
     */
    /**
     * 判断 目标应用 是不是停在登录页。
     *
     * ── 为什么需要 ──
     * 踩坑（2026-11-24）：目标应用掉登录后停在登录页，App 还去找聊天列表/输入框，
     * 找不到就一直重试到超时，表现就是用户说的「回复时卡住」。
     *
     * 实测特征（平板 1600x2560）：
     *   「手机号码」（无 rid）
     *   tv_login      → 「密码登录」
     *   tvConfirm     → 「获取验证码」
     *   login_new_agreement → 「目标应用用户协议」
     */
    fun isLoginPage(): Boolean {
        val nodes = try {
            Ui.snap()
        } catch (_: Throwable) {
            return false
        }
        if (nodes.isEmpty()) return false

        val texts = nodes.map { it.text }
        // 强特征：「获取验证码」只在登录页出现
        if (texts.any { it.trim() == "获取验证码" }) return true
        if (texts.any { it.trim() == "手机号码" }) return true
        // 弱特征：同时有「密码登录」和「用户协议」
        val byRid = nodes.any { it.rid == "tvConfirm" } &&
                nodes.any { it.rid == "login_new_agreement" }
        return byRid
    }

    /**
     * 发送前的准备：确保真的在对话页、且输入框可见。
     *
     * ── 为什么需要（2026-11-24，同城批量 no_input 的根因）──
     *
     * 时间线（实测日志）：
     *   0s    openChat() 点 ivChat → 进入 ConversationActivity ✅
     *   1s    同时弹出「送花墙」，盖住整个对话页
     *   1-10s readChatCard() 读资料（此时已经看不到输入框，但不影响读卡片）
     *   10-40s generateOpener() 调模型
     *   ~40s  send() → 找不到 et_sendmessage → no_input
     *
     * 日志证据：每次 no_input 之前都有：
     *   `目标应用Bot: 关掉送花墙，当前 MainActivity`
     * 说明关弹窗那一刻，页面已经不在对话页了 ——
     * 因为点「狠心错过」会把对话页也关掉。
     *
     * 所以发送前必须重新确认：
     *   1. 关掉挡路的送花墙
     *   2. 确认 Activity 是 ConversationActivity
     *   3. 确认 et_sendmessage 存在
     *
     * @return true 表示可以发了
     */
    fun prepareToSend(): Boolean {
        // ① 先关挡路的弹窗
        killGiftWall()
        Thread.sleep(500)

        // ② 关弹窗可能把对话页一起关了 —— 检查
        if (Ui.currentActivity() != CONV) {
            android.util.Log.w(
                "SoulMate",
                "prepareToSend: 当前不在对话页(${Ui.currentActivity()})"
            )
            return false
        }

        // ③ 确认输入框真的在
        repeat(3) {
            val nodes = Ui.snap()
            if (nodes.any { it.rid == "et_sendmessage" }) return true

            // 又弹了？关掉再看
            if (killGiftWall()) {
                Thread.sleep(1000)
                if (Ui.currentActivity() != CONV) return false
            } else {
                Thread.sleep(800)
            }
        }

        // ④ 最后一搏：等 2 秒
        Thread.sleep(2000)
        if (killGiftWall() && Ui.currentActivity() != CONV) return false
        if (Ui.snap().any { it.rid == "et_sendmessage" }) return true

        // ★ ⑤ 视觉自救（2026-11-25 用户要求）
        //
        // 「卡在某个地方半天，就用视觉模型临时接入，
        //   看下截下来要点哪里去继续下去」
        //
        // 走到这里说明：既不在对话页、也找不到输入框、
        // 弹窗也关不掉 —— 典型的「卡在没见过的地方」。
        // 让视觉模型看一眼该点哪，往往点一下就通了
        //（比如没见过的新版引导页、会员页的关闭按钮）。
        val rescue = com.love.soulmate.engine.StuckRescue(
            tag = "找到输入框",
            goal = "让当前页面出现聊天输入框（找到并点击「发消息」「说点什么」这类输入框，" +
                    "或者关掉挡住页面的弹窗/引导页）",
            idleMs = 0L,          // 到这里已经卡很久了，直接允许自救
            onLog = { msg, level -> android.util.Log.println(
                if (level == "error") android.util.Log.ERROR else android.util.Log.WARN,
                "SoulMate", msg) },
        )
        if (rescue.rescue()) {
            Thread.sleep(2000)
        }

        return Ui.snap().any { it.rid == "et_sendmessage" }
    }

    fun send(text: String): SendResult {
        // ★ 踩坑（2026-11-24，同城私聊批量 no_input 的根因）
        //
        // 实测时间线：
        //   点 ivChat → 1.0s 进入 ConversationActivity ✅
        //              → 但同时弹出「送花墙」，盖住整个对话页
        //              → et_sendmessage 读不到
        //              → send() 返回 no_input
        //   然后 killGiftWall() 点「狠心错过」→ 直接退回 MainActivity
        //
        // 所以 send() 里必须先处理送花墙，否则：
        //   · 输入框被挡住 → no_input
        //   · 事后关弹窗又把对话页一起关了 → 下一条更没戏
        //
        // 处理顺序改成：
        //   1. 关送花墙（它可能挡着输入框）
        //   2. 再判 Activity（关弹窗可能把页面也关了）
        //   3. 不在对话页才 gotoMain 兜底

        killGiftWall()

        if (Ui.currentActivity() != CONV) {
            // 不在对话页时先回主界面。但如果 目标应用掉登录了，
            // gotoMain 会失败或把 目标应用 拉到登录页 —— 这里提前判断，
            // 直接返回 login_expired，让上层推送告警，别傻等超时。
            if (isLoginPage()) return SendResult(false, "login_expired")
            if (!gotoMain()) return SendResult(false, "not_in_chat")
        }

        fun findInput(): UiNode? =
            Ui.snap().firstOrNull { it.rid == "et_sendmessage" }

        fun readInput(): String = findInput()?.text ?: ""

        fun isPlaceholder(s: String): Boolean =
            s.isBlank() || s.contains("发消息") || s.contains("文明聊天") || s.contains("按住说话")

        var ed = findInput()
        if (ed == null) {
            // ★ 可能又被送花墙挡住了 —— 关掉再找
            killGiftWall()
            Thread.sleep(800)
            ed = findInput()
        }
        if (ed == null) {
            // 还给一次机会：等页面完全加载
            Thread.sleep(2000)
            killGiftWall()
            ed = findInput()
        }
        if (ed == null) return SendResult(false, "no_input")

        try {
            if (!isPlaceholder(ed.text)) {
                Ui.tap(ed.cx, ed.cy)
                Thread.sleep(900)
                Ui.clearInput()
                Thread.sleep(500)
            }

            ed = findInput() ?: ed
            Ui.tap(ed.cx, ed.cy)
            Thread.sleep(1500)

            // 记录输入前的内容（用于判断「是否真的输进去了」）
            val beforeText = readInput()

            Ui.inputText(text)
            Thread.sleep(2000)

            // ★ 不再用「回读内容包含原文」判断！
            //   dump 读回中文可能是乱码（实测读出 ????），但文字其实已经进去了。
            //   改用「内容是否发生了变化」判断，这个判断与编码无关。
            var afterText = readInput()
            fun changed(): Boolean =
                afterText.trim() != beforeText.trim() && !isPlaceholder(afterText)

            if (!changed()) {
                val e2 = findInput() ?: ed
                Ui.tap(e2.cx, e2.cy)
                Thread.sleep(1000)
                Ui.inputText(text)
                Thread.sleep(2000)
                afterText = readInput()
            }

            if (!changed()) {
                return SendResult(false, "nothing_entered")
            }

            // 收键盘（不收点不到发送按钮）
            Ui.back()
            Thread.sleep(1500)

            fun clickSend(): Boolean {
                val ns = Ui.snap()
                val b = ns.firstOrNull { it.rid == "btn_send" }
                if (b != null) {
                    Ui.tap(b.cx, b.cy)
                    return true
                }
                val e2 = ns.firstOrNull { it.rid == "et_sendmessage" }
                if (e2 != null) {
                    Ui.tap(e2.x2 + Screen.px(0.04), e2.cy)
                    return true
                }
                return false
            }

            if (!clickSend()) return SendResult(false, "no_send_button")
            Thread.sleep(2200)

            // 判断是否发出：输入框应回到占位符状态
            var left = readInput()
            if (!isPlaceholder(left)) {
                clickSend()
                Thread.sleep(2200)
                left = readInput()
            }

            // ★ 只要输入框回到占位符（空），就认为发出去了。
            //   不能用「原文是否还在」判断——dump 读中文可能是乱码。
            val ok = isPlaceholder(left)
            return if (ok) SendResult(true, "")
            else SendResult(false, "not_sent:" + left.take(20))
        } catch (e: Throwable) {
            return SendResult(false, "exception:" + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------- 弹窗 ----------

    private val SYS_AGREE = listOf(
        "允许", "始终允许", "允许运行", "同意", "确定", "我知道了",
        "知道了", "好的", "继续", "仅在使用中允许"
    )

    /**
     * 轻量检查：有没有奇遇铃弹窗？有就点「立即私聊」进去。
     *
     * ── 为什么要单独一个方法 ──
     * 奇遇铃是**随时可能弹出**的（对方靠近时推送），
     * 不是只在流程开头出现。原来只在 killDialogs 里处理，
     * 而 killDialogs 只在整个流程开始/准备匹配前调两次，
     * 中途弹出来就完全错过（用户反馈过两次）。
     *
     * 这个方法很轻（一次 dump + 文本判断），可以高频调。
     *
     * @return 是否点进了奇遇铃的对话
     */
    /**
     * 检查奇遇铃弹窗并**关掉它**。
     *
     * ── 改动历史（重要）──
     * 2026-11-23：用户说「奇遇铃记得点立即私聊」→ 实现成点进去聊天
     * 2026-11-24：发现这是灾难 —— 回复流程扫到一半弹窗出现，
     *            点进对话后后面所有会话都处理不了，实测卡住 5 分钟。
     *
     * ── 现在的行为 ──
     * 关掉弹窗，继续干正事。
     * 如果用户要让奇遇铃的人进聊天，另走主动搭话流程（点「立即私聊」）。
     *
     * @return 是否关掉了弹窗
     */
    fun checkMiracleBell(): Boolean {
        return try {
            val nodes = Ui.snap()
            if (nodes.isEmpty()) return false

            val closeBtn = nodes.firstOrNull { it.rid == "iv_love_close" }
            if (closeBtn == null) return false

            Ui.tap(closeBtn.cx, closeBtn.cy)
            Thread.sleep(1200)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 检测有没有奇遇铃弹窗（但不做任何操作）。
     *
     * @return 弹窗的「立即私聊」按钮；没有弹窗返回 null
     */
    fun findMiracleBell(): UiNode? {
        return try {
            val nodes = Ui.snap()
            if (nodes.none { it.rid == "iv_love_close" }) return null
            nodes.firstOrNull { it.text.trim() == "立即私聊" }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 处理奇遇铃弹窗：**点「立即私聊」进去搭话，然后回到原任务**。
     *
     * ── 用户要求（2026-11-24）──
     * 「如果遇到奇遇铃就点一下立即私聊，然后再回到还没完成的功能中」
     *
     * ── 为什么这样设计 ──
     * 奇遇铃是「对方主动靠近」的推送，含金量比自己在广场里找人高得多 ——
     * 因为是她先注意到你。所以不能像以前那样直接关掉。
     *
     * 但也不能像更早的版本那样「进去聊天就再也不回来」——
     * 那会让整个回复/私聊流程卡死。
     *
     * ── 怎么「回到原任务」──
     * 不需要记坐标。原任务的调用方（回复循环 / 同城循环）都持有自己的上下文：
     *   · 回复循环：靠 openByName(nm) 重新打开，名字还在
     *   · 同城循环：靠 gotoSquare() + scrollNext() 重新走到广场
     * 所以这里只需要：
     *   1. 点进去，搭话（生成开场白 + 发送）
     *   2. 退出来，回到聊天列表（两个循环都从这里继续）
     *   3. 返回 true 告诉调用方「我刚处理了一个，你重新定位一下」
     *
     * @param greet 生成并发送开场白的回调（由 Orchestrator 注入，用它拿 LLM 和记录）
     * @return 是否真的处理了一个奇遇铃
     */
    fun handleMiracleBell(greet: (bellName: String) -> Unit): Boolean {
        // 先在弹窗上把昵称读出来 —— 弹窗有 tv_love_signature，
        // 比进去后读 tv_title 更可靠（对话页标题有时是空的）
        val nodes0 = try {
            Ui.snap()
        } catch (_: Throwable) {
            emptyList()
        }
        if (nodes0.none { it.rid == "iv_love_close" }) return false

        val nameFromPopup = nodes0.firstOrNull { it.rid == "tv_love_signature" }
            ?.text?.trim() ?: ""

        val btn = nodes0.firstOrNull { it.text.trim() == "立即私聊" } ?: return false

        val entered = try {
            Ui.tap(btn.cx, btn.cy)
            Thread.sleep(4500)
            true
        } catch (_: Throwable) {
            false
        }
        if (!entered) return false

        // ★★ 关键修复（2026-11-25 用户实测反馈）★★
        //
        // 用户说：「点了但是好像到了那个人的主页，然后就退出了，没有进行私聊」
        //
        // 原因：奇遇铃点「立即私聊」后，目标应用 **不一定直接进 ConversationActivity**，
        // 而是先落到对方的 UserHomeActivity（主页，有「关注」「私聊」两个按钮）。
        // 原代码只做了一次「再点一次立即私聊」的兜底，
        // 但主页上的按钮叫「私聊」不是「立即私聊」，所以点不到 → 判定失败 → 退出。
        //
        // 正确做法要按页面类型分三种情况处理：
        //   ① 直接在对话页 → 收工
        //   ② 落到对方主页 → 点「私聊」按钮（rid=ivChat 或文字「私聊」）
        //   ③ 其他页（详情/弹窗）→ 再找「立即私聊/开始聊天/打招呼」
        //
        // 而且循环几轮，因为可能「主页 → 私聊 → 又弹个招呼框」。
        var round = 0
        while (round < 4 && Ui.currentActivity() != CONV) {
            round++
            val a = Ui.currentActivity()
            val ns = Ui.snap()

            // ② 对方主页 → 点「私聊」
            if (a == HOME || a.contains("UserHome")) {
                android.util.Log.i(
                    "SoulMate",
                    "奇遇铃：落在对方主页，点「私聊」（第 $round 轮）"
                )
                // 先处理可能弹的「打个招呼」对话框
                val greetDlg = ns.firstOrNull { it.text.trim() == "打个招呼" }
                if (greetDlg != null) {
                    Ui.tap(greetDlg.cx, greetDlg.cy)
                    Thread.sleep(3500)
                    continue
                }
                val chat = ns.firstOrNull { it.text.trim() == "私聊" }
                    ?: ns.firstOrNull { it.rid == "ivChat" }
                    ?: ns.firstOrNull {
                        it.text.trim() in listOf("开始聊天", "发消息", "去聊天")
                    }
                if (chat != null) {
                    Ui.tap(chat.cx, chat.cy)
                    Thread.sleep(4000)
                    continue
                }
                android.util.Log.w("SoulMate", "奇遇铃：主页上找不到「私聊」按钮")
                break
            }

            // ③ 其他页 → 找立即私聊/开始聊天/打招呼
            val again = ns.firstOrNull {
                it.text.trim() in listOf("立即私聊", "开始聊天", "打招呼", "私聊")
            }
            if (again != null) {
                android.util.Log.i(
                    "SoulMate",
                    "奇遇铃：在 $a 点「${again.text.trim()}」"
                )
                Ui.tap(again.cx, again.cy)
                Thread.sleep(4000)
            } else {
                android.util.Log.w("SoulMate", "奇遇铃：停在 $a，没有可点按钮")
                break
            }
        }

        if (Ui.currentActivity() != CONV) {
            // 没进去，退回列表
            android.util.Log.w(
                "SoulMate",
                "奇遇铃：最终没进对话（${Ui.currentActivity()}），退回列表"
            )
            gotoMain(); tapChatTab(); Thread.sleep(1200)
            return false
        }

        // 读是谁：优先用弹窗上的昵称，其次读对话页标题
        val detail = try {
            readChat(transcribe = false)
        } catch (_: Throwable) {
            null
        }
        val bellName = nameFromPopup.ifBlank {
            detail?.with?.trim() ?: ""
        }.ifBlank { "奇遇铃用户" }

        try {
            greet(bellName)
        } catch (t: Throwable) {
            android.util.Log.w("SoulMate", "奇遇铃搭话失败: ${t.message}")
        }

        // ★ 关键：回到聊天列表，让原任务能继续
        val back = gotoMain()
        tapChatTab()
        Thread.sleep(1500)
        return back
    }

    /**
     * 主动点奇遇铃：「立即私聊」进对话。
     *
     * 只用在明确要「搭话奇遇铃的人」时，不要在扫列表流程里调用。
     *
     * @return 是否成功进入对话
     */
    fun openMiracleBellChat(): Boolean {
        return try {
            val nodes = Ui.snap()
            val btn = nodes.firstOrNull { it.text.trim() == "立即私聊" }
            if (btn == null) return false

            Ui.tap(btn.cx, btn.cy)
            Thread.sleep(4000)
            Ui.currentActivity() == CONV
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 长按会话行 → 点「删除」，把这个会话从列表里清掉。
     *
     * ── 用户要求（2026-11-25）──
     * 「如果这个会话你判定不需要回复了，我觉得可以直接长按这个会话删除掉」
     * 后续确认：「自动删，不用问我」
     *
     * ── 真机长按后的菜单（MEIZU 21 Pro 实测）──
     *   置顶
     *   删除
     *   批量清理
     * 点「删除」会二次确认（点「删除」/「确定」）。
     *
     * @param name 要删的会话昵称（必须和列表里显示的完全一致）
     * @return 是否删除成功
     */
    fun deleteChat(name: String): Boolean {
        if (Ui.currentActivity() != MAIN) {
            gotoMain()
            tapChatTab()
            Thread.sleep(1500)
        }

        // ① 找到目标行
        val nodes = Ui.snap()
        val itemRoots = nodes.filter { it.rid == "list_itease_layout" }
        val target = nodes.firstOrNull {
            it.rid == "name" && it.text.trim() == name &&
                    it.y1 > Screen.py(0.08)
        } ?: return false

        // 行容器（长按要点整行，点 name 有时不触发）
        val row = itemRoots.firstOrNull {
            target.y1 >= it.y1 - 10 && target.y1 <= it.y2 + 10
        }
        val px = row?.cx ?: target.cx
        val py = row?.cy ?: target.cy

        android.util.Log.i("SoulMate", "删除会话：长按「$name」($px,$py)")

        // ② 长按
        try {
            Ui.longPress(px, py, 1200)
        } catch (_: Throwable) {
            return false
        }
        Thread.sleep(1500)

        // ③ 找「删除」
        val menu = Ui.snap()
        val del = menu.firstOrNull { it.text.trim() == "删除" }
        if (del == null) {
            // 菜单没出来（可能长按位置不对），点空白关掉
            android.util.Log.w("SoulMate", "删除会话：长按后没出现菜单")
            Ui.back()
            Thread.sleep(800)
            return false
        }
        Ui.tap(del.cx, del.cy)
        Thread.sleep(1500)

        // ④ 可能有二次确认
        val confirm = Ui.snap()
        val ok = confirm.firstOrNull {
            it.text.trim() in listOf("删除", "确定", "确认", "确认删除")
        }
        if (ok != null && ok.text.trim() != "取消") {
            // 确认框里的按钮通常是一对，取文字为删除/确定的那个
            Ui.tap(ok.cx, ok.cy)
            Thread.sleep(1500)
        }

        // ⑤ 校验：她还在不在列表里
        val after = Ui.snap()
        val still = after.any {
            it.rid == "name" && it.text.trim() == name
        }
        if (still) {
            android.util.Log.w("SoulMate", "删除会话：删完「$name」还在列表里")
            return false
        }
        android.util.Log.i("SoulMate", "删除会话：已删除「$name」")
        return true
    }

    /**
     * 批量删除若干个会话（判定不需要回复的）。
     *
     * 每个都重新读列表，因为删一个之后列表会整体上移，
     * 缓存的坐标全部失效。
     *
     * @return 实际删掉的个数
     */
    fun deleteChats(names: List<String>): Int {
        if (names.isEmpty()) return 0
        var done = 0
        for (nm in names) {
            try {
                if (deleteChat(nm)) done++
            } catch (t: Throwable) {
                android.util.Log.w("SoulMate", "删除「$nm」出错: ${t.message}")
            }
            Thread.sleep(800)
        }
        return done
    }

    fun killDialogs(): List<String> {
        val closed = ArrayList<String>()
        repeat(4) {
            val pkg = Ui.currentPackage()
            val nodes = Ui.snap()
            val all = nodes.joinToString(" ") { it.text }

            // ★ 送花墙优先处理（模态全屏，不关掉别的都干不了）
            //   踩坑：它吃掉返回键，导致 gotoMain 死循环
            if (nodes.any { it.rid == "tv_btn_gift_send" } ||
                nodes.any { it.text.contains("狠心错过") }
            ) {
                val close = nodes.firstOrNull { it.rid == "tv_btn_close" }
                    ?: nodes.firstOrNull { it.text.contains("狠心错过") }
                if (close != null) {
                    Ui.tap(close.cx, close.cy)
                    closed.add("送花墙")
                    Thread.sleep(1500)
                    return@repeat
                }
            }

            if (pkg != PKG && pkg.isNotBlank()) {
                val b = nodes.firstOrNull { n -> SYS_AGREE.any { n.text.trim() == it } }
                if (b != null) {
                    Ui.tap(b.cx, b.cy)
                    closed.add("sys_popup")
                    Thread.sleep(1500)
                    return@repeat
                }
                Shell.run("am start --user $user -n $MAIN_ACT")
                Thread.sleep(4000)
                closed.add("relaunch")
                return@repeat
            }

            /**
             * 奇遇铃弹窗：**默认关掉**，不要点「立即私聊」。
             *
             * ── 踩坑记录（2026-11-24，严重）──
             * 之前这里写的是「点立即私聊进去聊天」，本意是不错过主动上门的线索，
             * 但结果很糟：
             *   · 回复流程扫到一半，弹窗出现 → 点进对话 → 后面所有会话都处理不了
             *   · 实测卡住 5 分钟没动静（日志停在一条发送之后）
             *
             * ── 正确做法 ──
             * 扫列表 / 回复会话时：**关掉弹窗**，继续干正事。
             * 只有用户明确要求「主动搭话奇遇铃」时，才点「立即私聊」。
             *
             * 实测关闭按钮：rid=iv_love_close，content-desc="关闭弹窗"
             */
            if (nodes.any { it.rid == "iv_love_close" } || all.contains("立即私聊")) {
                val closeBtn = nodes.firstOrNull { it.rid == "iv_love_close" }
                    ?: nodes.firstOrNull { it.desc.contains("关闭") }
                if (closeBtn != null) {
                    Ui.tap(closeBtn.cx, closeBtn.cy)
                    closed.add("奇遇铃(关掉)")
                    Thread.sleep(1200)
                    return@repeat
                }
                // 兜底：按比例点右上角
                Ui.tap(Screen.px(0.664), Screen.py(0.050))
                closed.add("奇遇铃(坐标)")
                Thread.sleep(1200)
                return@repeat
            }

            val target = when {
                all.contains("是否恢复聊天列表") -> "确认"
                all.contains("消息同步上线") -> "暂不考虑"
                all.contains("喜欢SOUL吗") -> "取消"
                else -> null
            }
            if (target == null) return closed

            val btn = nodes.firstOrNull { it.text == target }
            if (btn != null) {
                Ui.tap(btn.cx, btn.cy)
                closed.add(target)
                Thread.sleep(1500)
            }
        }
        return closed
    }
}
