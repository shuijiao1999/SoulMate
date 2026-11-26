package com.love.soulmate.soul

import com.love.soulmate.core.Screen
import com.love.soulmate.core.Ui
import com.love.soulmate.core.UiNode

/**
 * 同城广场：找上海市的人，主动私聊。
 *
 * ── 流程 ──
 *   1. 底部「广场」tab
 *   2. 顶部切到「同城」标签
 *   3. 读当前屏的帖子（昵称 + 正文 + IP + 私聊按钮）
 *   4. 交给 Excluder 筛人
 *   5. 点 ivChat → 进对话页
 *   6. 读卡片资料 → 生成开场白 → 发送
 *
 * ── 实测 UI（2026-11-24，平板 1600x2560）──
 *
 *   昵称     无 rid，在 flAvatar 右侧、tvTitle 上方
 *            例：y=335 x=132-272 '星子回来了'
 *   时间标签  rid=tvTitle  '近期发布' / '3小时以前'
 *   正文     rid=square_item_text
 *            例：'我的大脸盘子 不理我就一直发#圆圆脸 #日常照'
 *   配图     rid=flImageOne
 *   AI提示   rid=riskTipText  '该内容可能由AI生成'
 *   IP属地   rid=square_item_location  '上海市'
 *   私聊按钮  rid=ivChat（x≈136）★ 必须用这个
 *            rid=tvChat（x≈188）会跳到用户详情页，不能发消息
 *   点赞     rid=iv_like + tvLike
 *   评论     rid=commentLayout + tvComment
 *   头像     rid=flAvatar（clickable）/ avatar
 *   更多     rid=ivMore
 *   关注     rid=tvInteraction
 *
 * ── 踩坑（2026-11-24，血的教训）──
 * u2 的 adb forward 端口被模拟器占用，导致 dump 拿到的是**模拟器**的 UI 树，
 * 但操作的是**平板**的 目标应用 → 两边对不上，昵称读成「1小时以前」「去发布」，
 * 正文全读成 0 字，私聊点到详情页。
 * 修复：`adb forward --remove-all` 再只给平板建 forward。
 */
object Square {

    /** 一个同城帖子 */
    data class Post(
        val name: String,       // 作者昵称
        val content: String,    // 帖子正文（纯图帖为空）
        val ip: String,         // IP 属地（如「上海市」）
        val chatX: Int,         // ivChat 中心 x
        val chatY: Int,         // ivChat 中心 y
        val liked: Int = 0,     // 点赞数（用来判断热度，可参考）
        /** 纯图无文字。这种帖子没法从文字切入，但可以看配图说话 */
        val pureImage: Boolean = false,
        /** 配图区域的 y 范围（用于人像识别时裁剪），空表示没图 */
        val imageTop: Int = 0,
        val imageBottom: Int = 0,
        /** 人像评分（null = 没评估 / 不是人像） */
        val portraitScore: com.love.soulmate.llm.Portrait.Score? = null,
    )

    /**
     * 切到同城广场。
     *
     * @return 是否成功
     */
    fun gotoSquare(): Boolean {
        throwIfStop()
        val nodes = Ui.snap()
        if (nodes.isEmpty()) return false

        // ① 点底部「广场」
        nodes.firstOrNull { it.rid == "main_tab_square" }?.let {
            Ui.tap(it.cx, it.cy)
            Thread.sleep(2500)
        }

        throwIfStop()

        // ② 点顶部「同城」（如果已经在同城，点了也无害）
        val ns = Ui.snap()
        val cityTab = ns.firstOrNull { it.text.trim() == "同城" }
            ?: ns.firstOrNull { it.rid == "tv_city" || it.rid == "tv_same_city" }
        if (cityTab != null) {
            Ui.tap(cityTab.cx, cityTab.cy)
            Thread.sleep(2500)
        }

        // ③ 确认在广场：有 square_item_text 说明读到了帖子
        val check = Ui.snap()
        return check.any { it.rid == "square_item_text" } ||
                check.any { it.rid == "ivChat" }
    }

    /**
     * 读当前屏的同城帖子。
     *
     * ── 配对逻辑（关键）──
     * 每屏可能 2-5 个帖子，各元素按 y 坐标配对：
     *
     *   y=335   '星子回来了'                 ← 昵称
     *   y=381   tvTitle '近期发布'            ← 时间
     *   y=436   square_item_text '正文'       ← 正文
     *   y=919   square_item_location '上海市'
     *   y=983   ivChat                       ← 私聊按钮（这个帖子的「锚点」）
     *
     * ── 帖子边界（重要修正）──
     * 用 `llSquare` 做容器边界。每个帖子的结构是：
     *
     *   llSquare [y=1358..1990]
     *     ├─ flAvatar / avatar       头像
     *     ├─ 无 rid 短文本            昵称  ← y=1397 '没什么大不了'
     *     ├─ tvTitle                 时间  ← y=1443 '22小时以前'
     *     ├─ square_item_text        正文（可能没有！纯图帖就没有）
     *     ├─ flImageOne / ivImage    配图（可能没有）
     *     ├─ square_item_location    IP 属地
     *     └─ ivChat / tvChat         私聊按钮
     *
     * ★ 踩坑（2026-11-24）：纯图帖没有 square_item_text，
     *   之前按「往上找最近的正文」会把**上一个帖子**的正文配过来，
     *   导致两个帖子昵称/正文全重复。
     *   现在先按 llSquare 划边界，在边界内找，找不到就标记为纯图帖。
     *
     * @return 帖子列表（可能为空）
     */
    fun readPosts(): List<Post> {
        throwIfStop()
        val nodes = Ui.snap()
        if (nodes.isEmpty()) return emptyList()

        // 私聊按钮（每帖一个），按 y 升序
        val chats = nodes.filter { it.rid == "ivChat" && it.y1 > Screen.py(0.10) }
            .sortedBy { it.y1 }
        if (chats.isEmpty()) return emptyList()

        // 帖子容器（每个帖子一个 llSquare）
        val cards = nodes.filter { it.rid == "llSquare" }
            .sortedBy { it.y1 }

        /** 找某个 y 属于哪个 llSquare 卡片 */
        fun cardOf(y: Int): Pair<Int, Int>? {
            // llSquare 的 bounds 是 [top, bottom]；找包含 y 的那个
            for (c in cards) {
                if (y >= c.y1 && y <= c.y2) return c.y1 to c.y2
            }
            // 找不到就用「上一个 llSquare 的 top」和「下一个 llSquare 的 top」兜底
            val prev = cards.lastOrNull { it.y1 <= y }?.y1 ?: 0
            val next = cards.firstOrNull { it.y1 > y }?.y1 ?: Screen.height()
            return prev to next
        }

        val NOT_NAME = listOf(
            "近期发布", "小时以前", "分钟以前", "刚刚", "昨天", "前天",
            "该内容可能由AI生成", "展开", "收起", "关注", "已关注", "私聊",
            "打招呼", "评论", "长图", "视频", "去发布", "开启",
            "发现同城的小美好", "同城", "推荐", "上海市", "筛选",
        )

        val result = ArrayList<Post>()
        for (chat in chats) {
            val (top, bottom) = cardOf(chat.y1) ?: (0 to Screen.height())

            // 只在这个卡片的 y 范围内找元素
            fun inCard(n: UiNode) = n.y1 >= top && n.y1 <= bottom

            // 昵称：卡片内、头像右侧、2-20 字、无 rid 的短文本
            val name = nodes.filter { n ->
                inCard(n) &&
                        n.rid.isBlank() &&
                        n.x1 in 110..500 &&
                        n.y1 < chat.y1 &&
                        n.text.trim().length in 2..20 &&
                        !n.text.matches(Regex("""^[\d+]+$""")) &&
                        !n.text.endsWith("以前") && !n.text.endsWith("之前") &&
                        NOT_NAME.none { n.text.contains(it) && n.text.length < 12 }
            }.minByOrNull { it.y1 }?.text?.trim() ?: ""

            if (name.isBlank()) continue

            // 正文：卡片内、私聊按钮上方
            val body = nodes.filter {
                it.rid == "square_item_text" && inCard(it) && it.y1 < chat.y1 &&
                        it.text.isNotBlank()
            }.minByOrNull { it.y1 }?.text?.trim() ?: ""

            // 纯图帖：没有正文但有配图
            //
            // ★ 踩坑（2026-11-24，真机 MEIZU 21 Pro 验证）：
            //   原来这里加了 `it.y1 < chat.y1`（只找私聊按钮**上方**的图），
            //   但 目标应用的卡片结构是：
            //       昵称/时间/正文 → 私聊按钮 → 配图
            //   配图在私聊按钮**下方**！
            //   结果就是「有图的帖子读不到图」，人像筛选完全失效。
            //
            //   正确做法：配图只要在卡片容器（llSquare）范围内就算这张卡片的。
            //   llSquare 的 bounds 已经精确覆盖整张卡片（实测
            //   卡片1 [0,516]-[1368,1330]，卡片2 [0,1330]-[1368,2932]，配图在里面）。
            val imgNodes = nodes.filter {
                inCard(it) &&
                        (it.rid == "flImageOne" || it.rid == "ivImage")
            }
            val hasImage = imgNodes.isNotEmpty()
            val imgTop = imgNodes.minOfOrNull { it.y1 } ?: 0
            val imgBottom = imgNodes.maxOfOrNull { it.y2 } ?: 0

            // IP：卡片内
            val ip = nodes.filter {
                it.rid == "square_item_location" && inCard(it) && it.text.isNotBlank()
            }.minByOrNull { it.y1 }?.text?.trim() ?: ""

            // 点赞数
            val like = nodes.firstOrNull {
                it.rid == "tvLike" && kotlin.math.abs(it.y1 - chat.y1) < 80
            }?.text?.trim()?.toIntOrNull() ?: 0

            result.add(
                Post(
                    name = name,
                    content = body,
                    ip = ip,
                    chatX = chat.cx,
                    chatY = chat.cy,
                    liked = like,
                    pureImage = hasImage && body.isBlank(),
                    imageTop = imgTop,
                    imageBottom = imgBottom,
                )
            )
        }

        return result
    }

    /**
     * 点某条帖子的「私聊」按钮，进入对话页。
     *
     * ── 踩坑（2026-11-24，卡了一整轮）──
     * 点 ivChat 后，目标应用有时先弹一个**卡片提示**（不是跳转失败）：
     *   card_title   = "提示"
     *   card_content = "用户名密码错误"（或别的系统提示）
     *   iv_confirm   = "确认"
     *
     * 这个弹窗挡住了一切，表现为「进不去对话」，
     * 然后 gotoSquare() 也回不来，后面连续 11 次「读不到帖子」。
     *
     * 修法：点完 ivChat 后如果没进 ConversationActivity，
     * 先查有没有 card_title / iv_confirm，有点掉再判断。
     */
    fun openChat(post: Post): Boolean {
        throwIfStop()

        // ★★★ 核心解法（2026-11-24，实测确认）★★★
        //
        // 送花墙的死循环终于搞清楚了：
        //   点 ivChat（从广场帖子私聊陌生人）
        //     → 进入 ConversationActivity ✅
        //     → 同时弹「送花开聊」全屏墙
        //     → 这堵墙只能点「狠心错过」(tv_btn_close) 关
        //     → 但点它会连对话页一起关掉（退回 UserHome/Main）
        //     → 找不到 et_sendmessage → no_input
        //
        // 穷举测过：点空白、滑动、双击、按返回，都关不掉。
        // 唯一关闭方式就是「狠心错过」，而它有副作用。
        //
        // ── 但是！实测发现 ──
        // 关掉墙之后，**会话已经建立了**。
        // 从聊天列表重新点这个人进去，就不再弹墙、输入框正常可见。
        //
        // 所以正确流程是：
        //   1. 点 ivChat 建立会话（会弹墙）
        //   2. 关掉墙（哪怕退回主界面也认）
        //   3. 从聊天列表重进这个人 ← 关键
        //   4. 此时无墙、有输入框，可以发
        //
        // 首轮会慢一点（进两次），但这是唯一可靠的路。

        Ui.tap(post.chatX, post.chatY)
        Thread.sleep(3500)

        // ① 处理可能出现的各种弹窗（卡片提示 / 送花墙）
        var giftWallHit = false

        for (round in 0 until 4) {
            // 送花墙：点了会离开对话页，但我们知道后续怎么补救
            if (SoulBot.killGiftWall()) {
                giftWallHit = true
                Thread.sleep(1800)
                break   // 墙关了，直接走「从聊天列表重进」流程
            }

            val ns = Ui.snap()

            // 已经在对话页且有输入框 → 完美，直接返回
            if (Ui.currentActivity() == SoulBot.CONV && ns.any { it.rid == "et_sendmessage" }) {
                return true
            }

            // 卡片弹窗：card_title 存在且有 iv_confirm
            val confirm = ns.firstOrNull { it.rid == "iv_confirm" }
            if (confirm != null) {
                val title = ns.firstOrNull { it.rid == "card_title" }?.text ?: ""
                val body = ns.firstOrNull { it.rid == "card_content" }?.text ?: ""
                android.util.Log.w("SoulMate", "Square: 弹窗「$title」「$body」，点确认")
                Ui.tap(confirm.cx, confirm.cy)
                Thread.sleep(2500)
                continue
            }

            // 其他确认按钮
            val yes = listOf("确定", "继续", "同意", "立即私聊", "开始聊天", "知道了")
            val btn = ns.firstOrNull { it.text.trim() in yes }
            if (btn != null) {
                Ui.tap(btn.cx, btn.cy)
                Thread.sleep(2500)
                continue
            }
            break
        }

        // ② 没撞到送花墙，且已经在对话页 → 直接成功
        if (!giftWallHit) {
            val ns = Ui.snap()
            if (Ui.currentActivity() == SoulBot.CONV && ns.any { it.rid == "et_sendmessage" }) {
                return true
            }
        }

        // ③ 撞到送花墙（或还没进对话）→ 从聊天列表重进这个人
        //
        // 这是本项目最脏但最有效的一招：会话已建立，重进不弹墙。
        if (giftWallHit) {
            android.util.Log.i(
                "SoulMate",
                "Square: 撞到送花墙，从聊天列表重进「${post.name}」"
            )
            return reenterFromChatList(post.name)
        }

        return false
    }

    /**
     * 从聊天列表重新进入某个人的对话（用于绕开送花墙）。
     *
     * 前提：会话已经建立（之前点过私聊，哪怕弹了墙退出来了）。
     *
     * @return 是否成功进入且有输入框
     */
    private fun reenterFromChatList(name: String): Boolean {
        // ① 回到主界面
        if (!SoulBot.forceGotoMain()) {
            android.util.Log.w("SoulMate", "Square: 回不到主界面，无法重进")
            return false
        }

        // ② 进聊天列表
        SoulBot.tapChatTab()
        Thread.sleep(1500)

        // ③ 按名字找。找不到就往下滑
        if (SoulBot.openByName(name)) {
            Thread.sleep(2500)
            // 确认真的有输入框（不再有墙）
            repeat(2) {
                val ns = Ui.snap()
                if (ns.any { it.rid == "et_sendmessage" }) {
                    android.util.Log.i("SoulMate", "Square: 重进成功，输入框可见")
                    return true
                }
                SoulBot.killGiftWall()
                Thread.sleep(1200)
            }
        }

        android.util.Log.w("SoulMate", "Square: 重进「$name」失败")
        return false
    }

    /**
     * 从任何异常页面强行回到同城广场。
     *
     * 用于「点了私聊之后流程乱了」的恢复。
     * 逐级尝试：关弹窗 → 按返回 → 点广场 tab → 点同城。
     *
     * @return 是否成功回到能读到帖子的广场页
     */
    fun forceBackToSquare(): Boolean {
        // ① 有卡片弹窗先点掉
        for (i in 0 until 3) {
            val ns = Ui.snap()
            val confirm = ns.firstOrNull { it.rid == "iv_confirm" }
            if (confirm == null) break
            Ui.tap(confirm.cx, confirm.cy)
            Thread.sleep(1500)
        }

        // ② 按返回退到主界面（最多 4 次）
        for (i in 0 until 4) {
            val act = Ui.currentActivity()
            if (act == SoulBot.MAIN) break
            Ui.back()
            Thread.sleep(1500)
        }

        // ③ 点广场 tab + 同城
        for (attempt in 0 until 2) {
            val ns = Ui.snap()
            ns.firstOrNull { it.rid == "main_tab_square" }?.let {
                Ui.tap(it.cx, it.cy)
                Thread.sleep(2500)
            }
            val ns2 = Ui.snap()
            ns2.firstOrNull { it.text.trim() == "同城" }?.let {
                Ui.tap(it.cx, it.cy)
                Thread.sleep(2500)
            }
            // 确认有帖子
            val ns3 = Ui.snap()
            if (ns3.any { it.rid == "ivChat" }) return true
        }
        return false
    }

    /** 往下滚一屏（换一批帖子） */
    fun scrollNext() {
        val cx = Screen.width() / 2
        Ui.swipe(cx, Screen.py(0.80), cx, Screen.py(0.25), 450)
        Thread.sleep(2000)
    }

    private fun throwIfStop() {
        if (SoulBot.stopCheck?.invoke() == true) {
            throw com.love.soulmate.engine.Orchestrator.StopSignal()
        }
    }
}
