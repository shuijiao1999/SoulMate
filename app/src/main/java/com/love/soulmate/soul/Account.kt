package com.love.soulmate.soul

import com.love.soulmate.core.Env
import com.love.soulmate.core.Screen
import com.love.soulmate.core.Shell
import com.love.soulmate.core.Ui

/**
 * 目标应用 账号档案。
 *
 * 设计变更（2026-11-23）：
 *   早期按「Android 用户空间」分数据（handled_u0 / handled_u900），
 *   但那套要操作分身，容易破坏数据，已经废弃。
 *
 *   现在改用 **目标应用 自带的「切换账号」**：
 *   用户在 目标应用 里切换，App 只负责识别「当前是哪个昵称」，
 *   然后所有状态（已回复记录 / 发送限流 / 匹配记录）按昵称分文件。
 *
 *   好处：
 *     · App 不碰登录、不碰用户空间 —— 零风险
 *     · 无论原生多账号还是分身，只要 目标应用 能切过去，App 就能识别
 *     · 各账号数据完全隔离，不会串
 */
data class Account(
    val nickname: String,
    val firstSeenAt: Long = 0,
) {
    /** 用于文件名的安全 key */
    val key: String
        get() = nickname.replace(Regex("""[\\/:*?"<>|\s]"""), "_").take(30)

    companion object {
        private const val PREFS = "accounts"
        private const val KEY_CURRENT = "current"
        private const val KEY_LIST = "list"

        /**
         * 昵称合法性校验。
         *
         * ── 为什么需要（2026-11-24 严重 bug）──
         * 悬浮球文字「■ 停止」被误存成账号名：
         *   `<string name="current">■ 停止</string>`
         *
         * 后果：所有状态文件都变成 `sent_msg_■_停止.json`，
         * 之前「某账号」账号的记录全部失效 → 大量重复回复风险。
         *
         * 根因：悬浮窗是独立 Window，u2 dump 会把它读进来，
         * 而「自己」页的昵称识别走了兜底分支，把任意短文本都当了昵称。
         *
         * 这里加一道最后防线：**任何来源的昵称都要过这个校验**。
         */
        private fun isValidNickname(s: String): Boolean {
            val t = s.trim()
            if (t.length !in 1..16) return false
            // 不能含图形符号（悬浮球/图标文字常见）
            if (t.any { it in "■□●○▶◀◆◇★☆▲△" }) return false
            // 不能是纯符号/纯空白
            if (t.none { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }) return false
            // 不能是已知的非昵称文本
            val bad = listOf("停止", "SoulMate", "再点一次", "自己", "聊天",
                             "广场", "星球", "发布", "通讯录")
            if (bad.any { t == it || t.contains(it) && t.length < 8 }) return false
            return true
        }

        /** 读上次识别到的账号名（App 启动时用，不必马上读屏） */
        fun lastKnown(): String {
            val ctx = Env.context() ?: return ""
            val v = ctx.getSharedPreferences(PREFS, 0)
                .getString(KEY_CURRENT, "") ?: ""
            // ★ 脏数据自动清理：读到不认识的直接当没有
            if (v.isNotBlank() && !isValidNickname(v)) {
                android.util.Log.w("SoulMate", "Account: 清理脏账号名「$v」")
                ctx.getSharedPreferences(PREFS, 0).edit()
                    .remove(KEY_CURRENT).apply()
                return ""
            }
            return v
        }

        fun setCurrent(nickname: String) {
            val name = nickname.trim()
            if (name.isBlank()) return
            // ★ 关键：不合法的昵称一律拒绝写入
            if (!isValidNickname(name)) {
                android.util.Log.w("SoulMate", "Account: 拒绝写入非法昵称「$name」")
                return
            }
            val ctx = Env.context() ?: return
            val sp = ctx.getSharedPreferences(PREFS, 0)
            val list = (sp.getStringSet(KEY_LIST, emptySet()) ?: emptySet()).toMutableSet()
            // 顺手清理列表里的脏数据
            list.removeAll { !isValidNickname(it) }
            list.add(name)
            sp.edit()
                .putString(KEY_CURRENT, name)
                .putStringSet(KEY_LIST, list)
                .apply()
        }

        /** 历史上识别过的所有账号名 */
        fun knownList(): List<String> {
            val ctx = Env.context() ?: return emptyList()
            return (ctx.getSharedPreferences(PREFS, 0)
                .getStringSet(KEY_LIST, emptySet()) ?: emptySet()).toList().sorted()
        }

        /**
         * 实地读当前账号昵称：点「自己」tab → 读昵称节点。
         *
         * 实测节点（模拟器 1080x2340 + 真机 1600x2560 都验证过）：
         *   rid=tv_me_sign  → 昵称（如「某账号」）
         *   rid=tvLongSignature → 签名
         *   rid=me_time → 天数（如「987天」）
         * 有精确 rid 就绝不用位置猜。
         */
        fun readCurrent(): String {
            return try {
                val ns = Ui.snap()
                val me = ns.firstOrNull { it.rid == "meTextView" }
                    ?: ns.firstOrNull { it.text == "自己" && it.y1 > Screen.height() * 0.82 }
                if (me == null) return ""
                Ui.tap(me.cx, me.cy)
                Thread.sleep(2500)

                val pn = Ui.snap()

                // ① 最优：直接用确定的 rid
                pn.firstOrNull { it.rid == "tv_me_sign" && it.text.isNotBlank() }
                    ?.let { return it.text.trim() }

                // ② 退而求其次：找「自己」页里最靠上的短文本（排除 UI 噪音）
                val SKIP = listOf(
                    "免费测测", "立即揭秘", "关注", "被关注", "看过我",
                    "我的展示标识", "+", "Ta ", "数字藏馆", "Soul币中心",
                    "超级星人", "个性商城", "娱乐中心", "瞬间", "视频", "收藏",
                    "未设置", "已读就回", "切换账号", "设置", "IP属地",
                    // ★ 自己 App 的悬浮球文字（踩坑 2026-11-24）
                    //   悬浮窗是独立 Window，u2 dump 会读进来，
                    //   它落在「自己」页顶部区域时会被当成昵称。
                    //   实测账号被读成「■ 停止」。
                    "停止", "■", "SoulMate", "再点一次"
                )
                val cand = pn.filter { n ->
                    val t = n.text.trim()
                    t.length in 1..15 &&
                            t.none { it.isDigit() } &&
                            !SKIP.any { s -> t.contains(s) } &&
                            !t.endsWith("天") && !t.endsWith("前") &&
                            // 额外：昵称不会含这些符号
                            !t.contains("■") && !t.contains("●") && !t.contains("▶") &&
                            n.y1 in (Screen.height() * 0.15).toInt()..(Screen.height() * 0.40).toInt()
                }.minByOrNull { it.y1 }
                cand?.text?.trim() ?: ""
            } catch (_: Throwable) {
                ""
            }
        }

        /**
         * 打开 目标应用的「切换账号」页面，供用户手动切换。
         * 路径：自己 tab → 设置 → 切换账号
         * 找不到就返回 false，让用户自己找。
         */
        fun openSwitchAccount(): Boolean {
            return try {
                // 1. 点「自己」
                val ns = Ui.snap()
                val me = ns.firstOrNull { it.rid == "meTextView" }
                    ?: ns.firstOrNull { it.text == "自己" && it.y1 > Screen.height() * 0.85 }
                if (me != null) {
                    Ui.tap(me.cx, me.cy)
                    Thread.sleep(2500)
                }

                // 2. 找「设置」入口（齿轮或文字）
                val ns2 = Ui.snap()
                val gear = ns2.firstOrNull {
                    it.text.trim() == "设置" || it.rid == "settingImageView" ||
                            it.rid == "iv_setting" || it.rid == "settings"
                }
                if (gear != null) {
                    Ui.tap(gear.cx, gear.cy)
                    Thread.sleep(2500)
                } else {
                    // 兜底：设置图标通常在右上角
                    Ui.tap(Screen.px(0.92), Screen.py(0.055))
                    Thread.sleep(2500)
                }

                // 3. 滚到「切换账号」
                repeat(5) {
                    val ns3 = Ui.snap()
                    val sw = ns3.firstOrNull { it.text.trim() == "切换账号" }
                    if (sw != null) {
                        Ui.tap(sw.cx, sw.cy)
                        Thread.sleep(2000)
                        return true
                    }
                    // 往上滑找
                    Ui.swipe(Screen.width() / 2, Screen.py(0.75),
                        Screen.width() / 2, Screen.py(0.35), 300)
                    Thread.sleep(800)
                }
                false
            } catch (_: Throwable) {
                false
            }
        }
    }
}
