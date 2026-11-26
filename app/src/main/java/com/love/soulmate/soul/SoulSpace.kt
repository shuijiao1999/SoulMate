package com.love.soulmate.soul

import com.love.soulmate.core.Env
import com.love.soulmate.core.Screen
import com.love.soulmate.core.Shell
import com.love.soulmate.core.Ui
import org.json.JSONObject

/**
 * 目标应用 用户空间（不是账号）。
 *
 * 开源要求：**不能假设任何设备的空间 ID 或账号昵称。**
 *   · 空间 ID 由 Env.findUserSpaces() 运行时枚举
 *   · 昵称由运行时读资料页获得，不是硬编码
 *   · 只在本地缓存「上次读到的昵称」，用于显示和校验
 *
 * 说明：一台机器可能装多个空间（ZUI 分身=900，小米=999，华为=888…），
 * 每个空间是独立的 目标应用 登录会话，账号、数据、匹配额度全都分开。
 */
data class SoulSpace(
    val user: Int,
    val nickname: String,
    val role: String,
    val priority: Int,
) {
    val label: String
        get() = if (nickname.isBlank()) "u$user" else "u$user $nickname"

    companion object {
        private const val PREFS = "spaces"

        /** 缓存上次读到的昵称，避免每次启动都要去读资料页 */
        private fun loadCache(): MutableMap<Int, String> {
            val m = HashMap<Int, String>()
            val ctx = Env.context() ?: return m
            val sp = ctx.getSharedPreferences(PREFS, 0)
            sp.all.forEach { (k, v) ->
                k.toIntOrNull()?.let { m[it] = v as? String ?: "" }
            }
            return m
        }

        fun saveNickname(user: Int, nickname: String) {
            val ctx = Env.context() ?: return
            if (nickname.isBlank()) return
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putString(user.toString(), nickname).apply()
        }

        /**
         * 枚举本机所有装了 目标应用的用户空间。
         * 昵称来自缓存（可能为空），首次使用会自动去读。
         */
        fun discover(pkg: String = SoulBot.PKG): List<SoulSpace> {
            val spaces = Env.findUserSpaces(pkg)
            val cache = loadCache()
            // 按空间 ID 排序，ID 小的优先（0 是机主）
            return spaces.sortedBy { it }.mapIndexed { idx, u ->
                SoulSpace(
                    user = u,
                    nickname = cache[u] ?: "",
                    role = if (u == 0) "机主空间" else "分身/克隆空间",
                    priority = idx + 1,
                )
            }
        }

        /**
         * 实地读当前空间的昵称（点「自己」tab）。
         * 读不到返回空串。
         */
        fun readCurrentNickname(): String {
            return try {
                val ns = Ui.snap()
                val me = ns.firstOrNull { it.rid == "meTextView" }
                    ?: ns.firstOrNull { it.text == "自己" && it.y1 > Screen.height() * 0.85 }
                if (me == null) return ""
                Ui.tap(me.cx, me.cy)
                Thread.sleep(2500)

                val pn = Ui.snap()
                val SKIP = listOf(
                    "免费测测", "立即揭秘", "关注", "被关注", "看过我",
                    "我的展示标识", "+", "Ta ", "数字藏馆", "Soul币中心",
                    "超级星人", "个性商城", "娱乐中心", "瞬间", "视频", "收藏",
                    "未设置", "已读就回"
                )
                val cand = pn.filter { n ->
                    val t = n.text.trim()
                    t.length in 1..15 &&
                            t.none { it.isDigit() } &&
                            !SKIP.any { s -> t.contains(s) } &&
                            !t.endsWith("天") && !t.endsWith("前") &&
                            n.y1 < Screen.height() * 0.45
                }.minByOrNull { it.y1 }
                cand?.text?.trim() ?: ""
            } catch (_: Throwable) {
                ""
            }
        }
    }
}
