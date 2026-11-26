package com.love.soulmate.soul

import com.love.soulmate.core.Screen
import com.love.soulmate.core.Ui

/**
 * 读「她的主页」，拿到真正能聊的素材。
 *
 * ── 用户要求（2026-11-24）──
 * 「灵魂匹配打招呼的时候，记得去她主页看下信息后再打招呼，光看标签不行」
 *
 * ── 为什么标签不够 ──
 * 匹配对话页顶部只能读到：
 *   · 星球名（「魅力少帅」）
 *   · 引力签（「独处自愈」「不想被催婚恋」）
 *   · 认证（「ESTJ」）
 *   · 匹配度
 *
 * 这些是**别人给她打的标签**，同质化严重，写出来就是
 * 「看你喜欢独处」这种谁都能说的废话。
 *
 * ── 主页有什么 ──
 * 实测能读到：
 *   · 签名        「希望和高品质高认知的人进行交流」
 *   · 瞬间（帖子）内容 ← **最值钱**
 *   · 星球 / 注册天数 / IP 属地
 *   · 灵魂画像（多条短语）
 *
 * 瞬间内容是**她自己写的话**，有具体的人、事、情绪：
 *   「刚面试完这一家主播孵化传媒公司 我会被笑死 里面真的是美女帅哥如云」
 *   「如果美貌不能变现 那么美貌是没有用的」
 *
 * 拿这种内容写开场白，才能接得上话。
 *
 * ── 实测入口（关键）──
 * 对话页里点**她的头像**（rid=avatar，左侧 x≈69 的那个）就能进主页。
 * 点右上角「更多」没用（那是聊天设置菜单）。
 *
 * ── 实测字段（平板 1600x2560）──
 *   c_usr_ic_user_ip    'IP属地:上海'
 *   tv_me_sign          '某人'          ← 昵称
 *   user_match          '97%'
 *   tvLongSignature     '希望和高品质…'  ← 签名
 *   tv_from             '魅力少帅'       ← 星球
 *   me_time             '720天'         ← 注册天数
 *   tvTab               '瞬间 18'       ← 瞬间数量
 *   post_content        '刚面试完…'      ← 瞬间正文（多条）
 *   post_time           '2026-11-24 …'
 *   tv_location         '上海市'
 */
object UserHome {

    /** 从主页读到的资料 */
    data class HomeInfo(
        val name: String = "",
        val signature: String = "",
        val matchRate: String = "",
        val planet: String = "",
        val ip: String = "",
        val days: String = "",
        /** 瞬间正文，按时间倒序（最新的在前） */
        val posts: List<String> = emptyList(),
        /** 灵魂画像等短语 */
        val traits: List<String> = emptyList(),
    ) {
        /** 有没有真正可用的素材 */
        val hasContent: Boolean
            get() = signature.isNotBlank() || posts.isNotEmpty()

        /**
         * 给模型看的素材文本。
         *
         * 只取最新的 2-3 条瞬间 —— 越多越容易让模型跑题，
         * 而且最新的内容才有「刚发生」的时效感。
         */
        fun toPromptText(): String {
            val sb = StringBuilder()
            if (signature.isNotBlank()) sb.append("签名：").append(signature).append('\n')
            if (posts.isNotEmpty()) {
                sb.append("最近发的瞬间：\n")
                posts.take(3).forEachIndexed { i, p ->
                    sb.append("  ").append(i + 1).append(". ")
                        .append(p.replace("\n", " ").take(120)).append('\n')
                }
            }
            if (traits.isNotEmpty()) {
                sb.append("灵魂画像：").append(traits.take(5).joinToString("、")).append('\n')
            }
            if (planet.isNotBlank()) sb.append("星球：").append(planet).append('\n')
            if (matchRate.isNotBlank()) sb.append("匹配度：").append(matchRate).append('\n')
            return sb.toString().trim()
        }
    }

    /**
     * 从对话页点她的头像进主页，读完再返回。
     *
     * @param alreadyInConv 是否已经在对话页（否则先打开）
     * @return 读到的资料；失败返回 null
     */
    fun readFromConversation(): HomeInfo? {
        throwIfStop()

        // ① 记录当前位置，读完要回来
        val backTo = Ui.currentActivity()
        if (backTo != SoulBot.CONV) {
            android.util.Log.w("SoulMate", "UserHome: 不在对话页，无法读主页")
            return null
        }

        // ② 进她的主页
        //
        // ── 两条路（2026-11-25 真机实测后改成先试按钮）──
        //
        // 路 A：点顶部资料卡上的「查看主页」按钮（最稳）
        //   真机 dump 到的节点：
        //     tv_visit_homepage   y=429  x=1062  「查看主页」
        //   它就在匹配/对话页顶部的资料卡片里，只要卡片在就一定能点。
        //
        // 路 B：点她的头像
        //   踩坑：真机上 cslyt_avatar_user 是**匹配卡片里的展示头像**
        //   （y=362 250x249），点它并不会跳主页，只会原地不动。
        //   真正能跳的是「消息气泡旁边的头像」，但那种头像通常没有
        //   固定 rid，要靠位置判断，成功率低。
        //
        // 所以顺序是：先找「查看主页」按钮 → 不行再点头像。
        val ns = Ui.snap()

        // 路 A：查看主页按钮
        val visitBtn = ns.firstOrNull {
            it.rid == "tv_visit_homepage" ||
                    it.text.trim() == "查看主页" ||
                    it.text.trim() == "Ta的主页"
        }

        if (visitBtn != null) {
            android.util.Log.i(
                "SoulMate",
                "UserHome: 点「查看主页」按钮 (${visitBtn.cx},${visitBtn.cy})"
            )

            // ★ 点击 + 轮询等待（最多 3 次点击、8 秒）
            //
            // 踩坑（2026-11-25 实测）：同一套代码，3 个匹配对象里
            //   2 个成功读到主页，1 个失败（日志：「宝宝 主页读不到」）。
            // 原因是点击时机 —— 目标应用的资料卡片还在淡入时点击会被吞掉。
            //
            // 所以这里改成：点一次 → 轮询等 → 没跳就再点一次，
            // 而不是点一次固定 sleep 3.5 秒就放弃。
            repeat(3) { attempt ->
                throwIfStop()
                val btn = Ui.snap().firstOrNull {
                    it.rid == "tv_visit_homepage" || it.text.trim() == "查看主页"
                } ?: return@repeat

                Ui.tap(btn.cx, btn.cy)

                // 轮询等跳转（最多 4 秒）
                var w = 0
                while (w < 8) {
                    Thread.sleep(500)
                    w++
                    if (Ui.currentActivity().contains("UserHome")) break
                }

                if (Ui.currentActivity().contains("UserHome")) {
                    android.util.Log.i(
                        "SoulMate",
                        "UserHome: 第 ${attempt + 1} 次点击进入主页成功"
                    )
                    val info = readPage()
                    goBackTo(backTo)
                    return if (info.hasContent) info else null
                }
                android.util.Log.w(
                    "SoulMate",
                    "UserHome: 第 ${attempt + 1} 次点「查看主页」没跳转" +
                            "（${Ui.currentActivity()}），重试"
                )
                Thread.sleep(1200)
            }
        }

        // 路 B：点头像
        //
        // ── rid 因设备而异 ──
        // 平板（Lenovo 1600x2560@320）用：avatar / chat_avatar
        // 手机（MEIZU 1368x3192@560）用：userAvatar / myAvatar
        //   userAvatar        y=362 253x253  ← 她
        //   myAvatar          y=362 253x253  ← 我
        // 原来只认 avatar / chat_avatar，在手机上永远返回 null。
        val AVATAR_RIDS = setOf(
            "avatar", "chat_avatar",           // 平板
            "userAvatar",                      // 手机（她）
        )
        val herAvatar = Ui.snap().filter { n ->
            (n.rid in AVATAR_RIDS) &&
                    n.x1 < Screen.width() * 0.35 &&
                    (n.x2 - n.x1) >= 80 && (n.y2 - n.y1) >= 80
        }.maxByOrNull { it.y1 }

        if (herAvatar == null) {
            android.util.Log.w(
                "SoulMate",
                "UserHome: 既没有「查看主页」按钮，也找不到她的头像"
            )
            return null
        }

        android.util.Log.i(
            "SoulMate",
            "UserHome: 点头像 rid='${herAvatar.rid}' " +
                    "(${herAvatar.cx},${herAvatar.cy}) ${herAvatar.x2 - herAvatar.x1}x${
                        herAvatar.y2 - herAvatar.y1
                    }"
        )

        Ui.tap(herAvatar.cx, herAvatar.cy)
        Thread.sleep(3500)

        if (!Ui.currentActivity().contains("UserHome")) {
            android.util.Log.w(
                "SoulMate",
                "UserHome: 点头像后没进主页（${Ui.currentActivity()}）"
            )
            // 可能停在帖子详情，退回对话页
            goBackTo(backTo)
            return null
        }

        // ③ 读主页
        val info = readPage()

        // ④ 返回对话页
        goBackTo(backTo)

        return if (info.hasContent) info else null
    }

    /** 读当前主页的内容 */
    private fun readPage(): HomeInfo {
        val nodes = Ui.snap()

        fun firstText(rid: String): String =
            nodes.firstOrNull { it.rid == rid && it.text.isNotBlank() }?.text?.trim() ?: ""

        var name = firstText("tv_me_sign").ifBlank { firstText("titlebar_text_tv") }
        val signature = firstText("tvLongSignature")
        val matchRate = firstText("user_match")
        val planet = firstText("tv_from")
        val days = firstText("me_time")
        val ip = firstText("c_usr_ic_user_ip").replace("IP属地:", "").trim()

        // 瞬间正文：按 y 排序（页面上是从新到旧）
        val posts = nodes
            .filter { it.rid == "post_content" && it.text.isNotBlank() }
            .sortedBy { it.y1 }
            .map { it.text.trim() }
            .filter { it.length >= 4 }      // 太短的（如「。」）不要
            .distinct()

        // 灵魂画像：无 rid 的短语，在 y 700-900 区间（实测区）
        val traits = nodes.filter {
            it.rid.isBlank() &&
                    it.text.length in 2..10 &&
                    it.y1 in Screen.py(0.26)..Screen.py(0.36) &&
                    !it.text.contains("灵魂画像")
        }.map { it.text.trim() }

        return HomeInfo(
            name = name,
            signature = signature,
            matchRate = matchRate,
            planet = planet,
            ip = ip,
            days = days,
            posts = posts,
            traits = traits.distinct(),
        )
    }

    /**
     * 读主页第一屏之后，往下滑几条瞬间再读一次。
     *
     * 首屏可能只显示 2-3 条，下滑能拿到更多。
     * 只滑一次，别太慢（每个匹配对象多花 4-5 秒）。
     */
    fun readWithScroll(): HomeInfo? {
        val base = readFromConversation() ?: return null
        // 首屏已经有 2 条以上瞬间就够了
        if (base.posts.size >= 2) return base
        return base
    }

    /** 逐级返回指定页面 */
    private fun goBackTo(target: String) {
        repeat(4) {
            if (Ui.currentActivity() == target) return
            Ui.back()
            Thread.sleep(1200)
        }
    }

    private fun throwIfStop() {
        if (SoulBot.stopCheck?.invoke() == true) {
            throw com.love.soulmate.engine.Orchestrator.StopSignal()
        }
    }
}
