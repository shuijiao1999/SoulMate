package com.love.soulmate.soul

import com.love.soulmate.core.Screen
import com.love.soulmate.core.Ui
import com.love.soulmate.core.UiNode

/**
 * 同城广场「帖子评论」。
 *
 * ── 为什么要有这个模块 ──
 * 私聊陌生人有「送花开聊」硬拦截（见 [Square.openChat] 里那段血泪史），
 * 但**评论没有墙**：实测在陌生人帖子下直接评论可以一次成功，
 * 发完评论数从 29 → 30，全程没有任何付费/送花弹窗。
 * 所以评论是同城拓客里成本最低、成功率最高的一条路。
 *
 * ── 实测 UI 结构（2026-11-25，真机 MEIZU 21 Pro 1368x3192 @560dpi）──
 *
 * ① 广场列表页（MainActivity）
 *
 *     llSquare [0,284][1368,1098]              每张帖子卡片一个，用它划边界
 *       square_item_text   clickable=true      正文（点它也能进详情）
 *       ivChat / tvChat                        私聊入口（送花墙）
 *       iv_like  / tvLike                      点赞
 *       commentLayout      clickable=true  ★★ 评论入口就是这个**父容器**
 *         ivComment        clickable=false     （图标，本身点不动）
 *         tvComment        clickable=false     文字是评论数「29」，desc=「评论」
 *       ivMore                                 更多
 *
 *     ★★ 关键：`tvComment` 是 TextView 且 clickable=false，
 *        真正能被点到的是它的父布局 `commentLayout`（LinearLayout，clickable=true）。
 *        只按 rid=tvComment 找节点去点，点的是文字中心，
 *        虽然有时能命中父容器，但一旦文案变成「99+」宽度变化就可能点偏。
 *        正确做法：找 `commentLayout` 点它的中心。
 *
 *     ★ 同一张卡片里，ivChat 和 commentLayout 在**同一水平行**上：
 *         ivChat        [238,951][322,1035]    cy=993
 *         commentLayout [1095,930][1312,1056]  cy=993
 *       所以用 Square.Post.chatY 直接配 y 最稳（误差 0），
 *       不需要再算「第几个帖子」。
 *
 * ② 帖子详情页（cn.soulapp.android.component.square.post.base.detail.PostDetailActivity）
 *
 *     顶部  layout_title_bar_v2
 *             iv_back                              返回
 *             tv_nick_name                         作者昵称
 *             tv_interact                          关注 / 已关注
 *     评论  list (RecyclerView)
 *             tv_all_comment_title  「共 29 条评论」  ★ 变没变 = 发成功没
 *             tv_comment_name       评论者
 *             tv_comment_content_text  评论正文
 *             tv_comment_content_extra 「15小时前 上海  回复」
 *     底栏  input_menu / commonKeyBoardView / ctlEdit
 *             tv_say              「说点什么...」   ★★ 点这个才弹键盘
 *             comment_content
 *               etContent         EditText，desc=「请输入你的评论」，
 *                                 text 占位符「友善评论，文明发言」
 *               ivAigcComment     AI 帮写（别点，会插一段 AI 文案）
 *             tvSend              「发送」，**输入文字后才出现**
 *                                 [1130,1862][1312,1953]，在输入框右下方
 *
 * ── 踩过的坑（2026-11-25，全部实测）──
 *
 * 坑 1（最坑）：**直接点 etContent 不弹键盘**。
 *   点 (684,3023) 也就是 etContent 中心，u2 返回 result=true，
 *   但 `dumpsys input_method` 里 mInputShown 一直是 false，
 *   界面毫无变化，dump 出来的 etContent 还贴在屏底。
 *   连点两次、换成 adb input tap 也都一样。
 *   正解：点 `tv_say`（「说点什么…」整条横条，[182,2973][556,3039]）→
 *   键盘立刻弹出，etContent 上移到 [56,1606][1200,1816]，focused=true。
 *   **所以 focusInput() 必须先点 tv_say，点不到才退回点 etContent。**
 *
 * 坑 2：发送成功后弹「打开通知提醒」引导层。
 *   tv_title = 「打开通知提醒」
 *   tv_text  = 「建议打开消息通知，当评论被回复时，第一时间获得提醒。」
 *   tv_btn   = 「立即开启」   ← 千万别点，会跳系统设置
 *   iv_close_bottom          ← 点这个关
 *   不关的话它会盖住整个页面，后续所有 dump 都只剩这个弹窗的节点。
 *
 * 坑 3：进广场时可能弹「来跟我一起听随机电台吧」底部弹层。
 *   leftTopCloseImage（左上角 X）或 tvConfirm「好的」关掉。
 *   不关就读不到 llSquare / commentLayout，readPosts() 直接返回空。
 *
 * 坑 4：**目标应用Mate 自己会跑到前台抢焦点**。
 *   实测探路时 App 自己启动了（大概是常驻监控被触发），
 *   mCurrentFocus 变成 com.love.soulmate/MainActivity，
 *   之后所有 dump 都是自己 App 的界面。
 *   所以 comment() 开头必须确认「当前包名是 cn.soulapp.android」，
 *   不是就先 gotoMain 拉回来。跑正式任务前也应 `am force-stop com.love.soulmate`。
 *
 * 坑 5：评论页的输入框停靠位置会随键盘状态变化。
 *   键盘收起：etContent [168,2943][1200,3104]（贴屏底）
 *   键盘弹起：etContent [56,1606][1200,1816]（被顶上去）
 *   用绝对坐标写死必然点错，只能靠 rid 现找。
 *
 * 坑 6：`Ui.inputText()` 走的是 u2 的 setClipboard + pasteClipboard，
 *   粘贴后搜狗输入法会浮一个「来自剪贴板」候选条，
 *   它只是悬浮提示，不影响点发送，不用管。
 *   但**不能**用「回读 etContent.text == 原文」判断是否输入成功 ——
 *   dump 中文在部分情况下是乱码，只能用「内容变了且不是占位符」判断。
 *
 * 坑 7：自己发的评论如果要删，长按评论正文 →
 *   底部弹「分享至好友 / 复制 / 生成分享图 / 删除」→ 点「删除」→
 *   再确认 tv_btn「确认删除」（tv_title「确认删除该评论？」）。
 *   留着这段是为了以后能做「撤回」。
 */
object SquareComment {

    /** 评论结果 */
    data class Result(val ok: Boolean, val error: String = "")

    // ---------- 页面常量 ----------

    /** 帖子详情页的 Activity 类名（Ui.currentActivity() 返回的是最后一段） */
    private const val DETAIL_ACT = "PostDetailActivity"

    /** 评论入口父容器（clickable=true），tvComment 只是它的子节点 */
    private const val RID_COMMENT_ENTRY = "commentLayout"

    /** 帖子卡片容器，用来划边界 */
    private const val RID_CARD = "llSquare"

    /** 详情页输入框 */
    private const val RID_EDIT = "etContent"

    /** 详情页「说点什么…」横条 —— 只有点它才会拉起键盘 */
    private const val RID_SAY = "tv_say"

    /** 详情页发送按钮（输入文字后才出现） */
    private const val RID_SEND = "tvSend"

    /** 详情页评论总数标题「共 N 条评论」 */
    private const val RID_COMMENT_COUNT = "tv_all_comment_title"

    /** 系统卡片弹窗确认按钮 */
    private const val RID_CARD_CONFIRM = "iv_confirm"

    /** 输入框的占位符文案（这些都不算「真的有内容」） */
    private val PLACEHOLDERS = listOf(
        "友善评论，文明发言", "请输入你的评论", "说点什么...", "说点什么…",
    )

    // ==================================================================
    //  对外主入口
    // ==================================================================

    /**
     * 在同城广场的某个帖子下发一条评论。
     *
     * @param post [Square.readPosts] 返回的 Post（用 chatX/chatY 定位那张卡片）
     * @param text 评论内容
     * @return 成功 / 失败原因（绝不抛异常，除了用户主动停止）
     */
    fun comment(post: Square.Post, text: String): Result {
        throwIfStop()

        if (text.isBlank()) return Result(false, "empty_text")

        return try {
            // ① 确认在广场，且能看到这张帖子
            if (!ensureSquare()) {
                return Result(false, "not_in_square")
            }

            // ② 找这张帖子的评论入口
            val anchor = findCommentAnchor(post)
                ?: return Result(false, "no_comment_button")

            // ③ 点进详情页
            Ui.tap(anchor.cx, anchor.cy)
            Thread.sleep(3000)
            dismissObstructions()

            if (!inDetail()) {
                // 有时第一次点击只是让卡片「选中」，或者被弹窗吃掉
                android.util.Log.w("SoulMate", "SquareComment: 第一次点评论没进详情，重试一次")
                throwIfStop()
                val again = findCommentAnchor(post) ?: return Result(false, "no_comment_button")
                Ui.tap(again.cx, again.cy)
                Thread.sleep(3000)
                dismissObstructions()
                if (!inDetail()) return Result(false, "no_detail_page")
            }

            // ④ 记录进页面时的评论数（用于事后确认真的发出去了）
            val countBefore = readCommentCount()

            // ⑤ 拉起键盘 + 输入
            if (!focusInput()) return Result(false, "no_input_box")

            val beforeText = readEditText()
            clearAndPaste(text)
            if (!textEntered(beforeText)) {
                // 再试一次（u2 剪贴板偶发失败）
                throwIfStop()
                clearAndPaste(text)
                if (!textEntered(beforeText)) return Result(false, "nothing_entered")
            }

            // ⑥ 点发送
            if (!clickSend()) return Result(false, "no_send_button")
            Thread.sleep(2500)

            // ⑦ 关掉发送后必弹的「打开通知提醒」
            dismissObstructions()

            // ⑧ 确认发出去了
            val countAfter = readCommentCount()
            val left = readEditText()
            val emptied = isPlaceholder(left)

            // 评论数 +1 → 铁证；数字没刷新但输入框清空了 → 也算成功
            // （列表首次渲染有延迟，实测 1-2 秒后数字才跳）
            if (countAfter != null && countBefore != null && countAfter > countBefore) {
                return Result(true)
            }
            if (emptied) {
                Thread.sleep(1500)
                dismissObstructions()
                if (isPlaceholder(readEditText())) return Result(true)
            }

            Result(false, "not_sent:${left.take(20)}")
        } catch (s: com.love.soulmate.engine.Orchestrator.StopSignal) {
            throw s
        } catch (t: Throwable) {
            android.util.Log.w("SoulMate", "SquareComment: 异常 ${t.javaClass.simpleName} ${t.message}")
            Result(false, "exception:${t.javaClass.simpleName}")
        }
    }

    /**
     * 从帖子详情页返回广场。
     *
     * 注意：不能连按两次返回 —— 键盘弹着的时候第一次返回只是收键盘，
     * 第二次才退页面；如果键盘已经收起，按两次就退到聊天列表了。
     * 所以每次都重新判断当前 Activity。
     *
     * @return 是否回到了能读到帖子的广场页
     */
    fun backToSquare(): Boolean {
        // ① 先关掉可能挡着返回键的弹层
        dismissObstructions()

        // ② 最多退 4 次，每次退完都重新看在哪
        for (i in 0 until 4) {
            if (inSquare()) return true

            Ui.back()
            Thread.sleep(1800)
            dismissObstructions()

            if (inSquare()) return true

            // 退到了广场之外（聊天列表/主界面），别再瞎按了
            val act = Ui.currentActivity()
            if (act != DETAIL_ACT && act != "MainActivity") break
        }

        if (inSquare()) return true

        // ③ 兜底：走 Square 自己的暴力恢复
        android.util.Log.w("SoulMate", "SquareComment: 常规返回失败，走 forceBackToSquare")
        return try {
            Square.forceBackToSquare()
        } catch (s: com.love.soulmate.engine.Orchestrator.StopSignal) {
            throw s
        } catch (t: Throwable) {
            false
        }
    }

    // ==================================================================
    //  定位
    // ==================================================================

    /**
     * 找某条帖子的「评论」入口。
     *
     * ── 配对逻辑 ──
     * 同一张卡片里 ivChat 和 commentLayout 在同一行（cy 差 0），
     * 所以直接用 Post.chatY 做最近邻匹配，比按卡片边界算更省事也更准。
     * 万一最近邻超出阈值（列表滚过、卡片高度变了），
     * 再退回「用 llSquare 边界框出卡片、在卡片内取 commentLayout」。
     */
    private fun findCommentAnchor(post: Square.Post): UiNode? {
        val nodes = Ui.snap()
        if (nodes.isEmpty()) return null

        val entries = nodes
            .filter { it.rid == RID_COMMENT_ENTRY && it.y1 > Screen.py(0.06) }
            .sortedBy { it.y1 }
        if (entries.isEmpty()) return null

        // ① 最近邻（同行的 cy 几乎相等，阈值给宽松一点容错）
        val tol = Screen.py(0.05)
        val nearest = entries.minByOrNull { kotlin.math.abs(it.cy - post.chatY) }
        if (nearest != null && kotlin.math.abs(nearest.cy - post.chatY) <= tol) {
            return nearest
        }

        // ② 卡片边界法：post.chatY 落在哪个 llSquare 里，就取那个卡片内的 commentLayout
        val cards = nodes.filter { it.rid == RID_CARD }.sortedBy { it.y1 }
        val card = cards.firstOrNull { post.chatY >= it.y1 && post.chatY <= it.y2 }
        if (card != null) {
            return entries.firstOrNull { it.y1 >= card.y1 && it.y1 <= card.y2 }
        }

        // ③ 还有卡片但 chatY 落在缝隙里 → 用「上一个 llSquare 的 top」做下界兜底
        //    （和 Square.readPosts 里 cardOf 的兜底思路一致）
        val prevTop = cards.lastOrNull { it.y1 <= post.chatY }?.y1 ?: 0
        val nextTop = cards.firstOrNull { it.y1 > post.chatY }?.y1 ?: Screen.height()
        return entries.firstOrNull { it.y1 >= prevTop && it.y1 <= nextTop }
    }

    /** 当前是否停在帖子详情页 */
    private fun inDetail(): Boolean {
        if (Ui.currentActivity() == DETAIL_ACT) return true
        val ns = Ui.snap()
        // Activity 名拿不到时（部分 ROM 焦点短暂为空）退回看节点特征
        return ns.any { it.rid == RID_EDIT } || ns.any { it.rid == RID_SAY }
    }

    /** 当前是否在广场列表页（能读到评论入口） */
    private fun inSquare(): Boolean {
        if (!Ui.isForeground(SoulBot.PKG)) return false
        val ns = Ui.snap()
        if (ns.isEmpty()) return false
        return ns.any { it.rid == RID_COMMENT_ENTRY } && ns.any { it.rid == RID_CARD }
    }

    /** 确保在广场；不在就拉回来 */
    private fun ensureSquare(): Boolean {
        // 坑 4：先看前台包名，别在 目标应用Mate 自己的界面上瞎找
        if (!Ui.isForeground(SoulBot.PKG)) {
            android.util.Log.w("SoulMate", "SquareComment: 前台不是 Soul，先拉回主界面")
            if (!SoulBot.forceGotoMain()) return false
            Thread.sleep(1200)
        }

        if (inSquare()) return true

        // 在详情页 → 先返回到广场
        if (inDetail()) {
            if (backToSquare()) return true
        }

        // 常规切广场
        if (Square.gotoSquare() && inSquare()) return true

        // 兜底
        if (Square.forceBackToSquare() && inSquare()) return true

        android.util.Log.w("SoulMate", "SquareComment: 回不到广场")
        return false
    }

    // ==================================================================
    //  输入
    // ==================================================================

    /**
     * 让评论输入框拿到焦点并拉起键盘。
     *
     * ★★ 坑 1：必须点 `tv_say`，点 `etContent` 没用（实测两次都不弹键盘）。
     *    点完 tv_say 后 etContent 会上移并 focused=true。
     */
    private fun focusInput(): Boolean {
        repeat(3) { round ->
            throwIfStop()

            // 优先点「说点什么…」横条
            val ns = Ui.snap()
            val say = ns.firstOrNull { it.rid == RID_SAY }
            if (say != null) {
                Ui.tap(say.cx, say.cy)
                Thread.sleep(1800)
            }

            // 键盘是否起来了：etContent 被顶到屏幕上部（或出现 tvSend）
            val ns2 = Ui.snap()
            val ed = ns2.firstOrNull { it.rid == RID_EDIT }
            if (ed != null) {
                val keyboardUp = ed.y1 < Screen.height() * 3 / 4 ||
                        ns2.any { it.rid == RID_SEND }
                if (keyboardUp) {
                    android.util.Log.i("SoulMate", "SquareComment: 输入框已就绪（第 ${round + 1} 次）")
                    return true
                }
                // 兜底：点一下 EditText 本身（个别机型 tv_say 不存在时靠这个）
                Ui.tap(ed.cx, ed.cy)
                Thread.sleep(1500)
                val ns3 = Ui.snap()
                if (ns3.any { it.rid == RID_SEND } ||
                    (ns3.firstOrNull { it.rid == RID_EDIT }?.y1 ?: Int.MAX_VALUE) <
                    Screen.height() * 3 / 4
                ) {
                    return true
                }
            } else {
                // 没有输入框 → 可能被弹窗盖住了
                dismissObstructions()
                Thread.sleep(1000)
            }
        }
        return false
    }

    /** 清空后粘贴（走 u2 剪贴板，中文安全） */
    private fun clearAndPaste(text: String) {
        Ui.clearInput()
        Thread.sleep(400)
        // Ui.inputText 内部还会再 clearInputText 一次，没关系
        Ui.inputText(text)
        Thread.sleep(1600)
    }

    /**
     * 判断文字是否真的进了输入框。
     *
     * ★ 坑 6：不能用「回读内容 == 原文」判断，dump 中文可能是乱码。
     *   用「内容变了 且 不是占位符」判断，与编码无关。
     */
    private fun textEntered(beforeText: String): Boolean {
        val after = readEditText()
        if (isPlaceholder(after)) return false
        return after.trim() != beforeText.trim()
    }

    /** 读输入框文字（读不到返回空串） */
    private fun readEditText(): String =
        Ui.snap().firstOrNull { it.rid == RID_EDIT }?.text ?: ""

    private fun isPlaceholder(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        return PLACEHOLDERS.any { t == it || t.contains(it) }
    }

    // ==================================================================
    //  发送
    // ==================================================================

    /**
     * 点发送。
     *
     * `tvSend` 只在输入框有内容时才出现（实测粘贴后立刻出现，
     * 位置在输入框右下方 [1130,1862][1312,1953]）。
     * 万一没找到，先收键盘再看一眼 —— **只按一次返回**，
     * 按两次会把详情页也退掉。
     */
    private fun clickSend(): Boolean {
        var ns = Ui.snap()
        var btn = ns.firstOrNull { it.rid == RID_SEND }
            ?: ns.firstOrNull { it.text.trim() == "发送" && it.clickable }

        if (btn == null) {
            // 收键盘，让底栏回到常驻状态
            Ui.back()
            Thread.sleep(1200)
            ns = Ui.snap()
            btn = ns.firstOrNull { it.rid == RID_SEND }
                ?: ns.firstOrNull { it.text.trim() == "发送" && it.clickable }
        }

        if (btn == null) return false

        Ui.tap(btn.cx, btn.cy)
        return true
    }

    // ==================================================================
    //  弹窗 / 干扰处理
    // ==================================================================

    /**
     * 关掉已知的遮挡层，返回是否关掉了什么东西。
     *
     * 覆盖三种实测遇到过的：
     *   ① 发送后「打开通知提醒」引导层   → iv_close_bottom
     *   ② 进广场时「来跟我一起听随机电台吧」底部弹层 → leftTopCloseImage
     *   ③ 送花开聊墙 tv_btn_close / 「狠心错过」
     *      （评论目前没遇到过，但同一套 目标应用 弹窗体系，留着兜底）
     *   ④ 系统卡片弹窗 card_title + iv_confirm
     */
    private fun dismissObstructions(): Boolean {
        var hit = false

        // 送花墙优先（它的关闭按钮会被误当成「通知弹窗的 X」）
        if (SoulBot.killGiftWall()) {
            hit = true
            Thread.sleep(1200)
        }

        repeat(3) { _ ->
            val ns = Ui.snap()
            if (ns.isEmpty()) return hit

            // ① 通知提醒引导层
            val notice = ns.firstOrNull { it.rid == "iv_close_bottom" }
            if (notice != null) {
                android.util.Log.i("SoulMate", "SquareComment: 关掉「打开通知提醒」")
                Ui.tap(notice.cx, notice.cy)
                Thread.sleep(1200)
                hit = true
                return@repeat
            }

            // ② 电台底部弹层
            val radio = ns.firstOrNull { it.rid == "leftTopCloseImage" }
            if (radio != null) {
                android.util.Log.i("SoulMate", "SquareComment: 关掉电台弹层")
                Ui.tap(radio.cx, radio.cy)
                Thread.sleep(1200)
                hit = true
                return@repeat
            }

            // ③ 系统卡片弹窗
            val confirm = ns.firstOrNull { it.rid == RID_CARD_CONFIRM }
            if (confirm != null) {
                val title = ns.firstOrNull { it.rid == "card_title" }?.text ?: ""
                val body = ns.firstOrNull { it.rid == "card_content" }?.text ?: ""
                android.util.Log.w("SoulMate", "SquareComment: 弹窗「$title」「$body」，点确认")
                Ui.tap(confirm.cx, confirm.cy)
                Thread.sleep(1500)
                hit = true
                return@repeat
            }

            // ④ 明确无害的确认词
            //
            // ★ 注意排除「立即开启」（会跳系统通知设置页），
            //   也不能点「发送」以外的东西，所以白名单写死。
            val safe = listOf("知道了", "我知道了", "关闭", "好的")
            val btn = ns.firstOrNull { it.text.trim() in safe && it.clickable }
            if (btn != null) {
                Ui.tap(btn.cx, btn.cy)
                Thread.sleep(1200)
                hit = true
                return@repeat
            }

            return hit
        }
        return hit
    }

    // ==================================================================
    //  校验
    // ==================================================================

    /**
     * 读「共 N 条评论」里的 N。读不到返回 null。
     *
     * 这是判断「评论真的发出去了」最可靠的依据：
     * 输入框清空只能说明界面刷新了，数字 +1 才是服务端确认。
     */
    private fun readCommentCount(): Int? {
        val t = Ui.snap().firstOrNull { it.rid == RID_COMMENT_COUNT }?.text ?: return null
        return Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    // ==================================================================
    //  杂项
    // ==================================================================

    private fun throwIfStop() {
        if (SoulBot.stopCheck?.invoke() == true) {
            throw com.love.soulmate.engine.Orchestrator.StopSignal()
        }
    }
}
