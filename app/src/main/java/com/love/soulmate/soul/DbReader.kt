package com.love.soulmate.soul

import com.love.soulmate.core.Env
import com.love.soulmate.core.Shell
import org.json.JSONObject

/**
 * 从 目标应用的 ObjectBox 数据库里读「活跃会话清单」。
 *
 * ── 为什么需要 ──
 * 之前完全靠 UI 扫列表：滚 46 个会话、逐个判断，一轮 4 分钟。
 * 用户提的规则是「超过两天的对话不再聊」——
 * 那完全可以从数据库里读出「哪些会话是最近 2 天的」，
 * 只对这些走 UI 验证，砍掉大量无效扫描。
 *
 * ── 已确认的数据库结构（2026-11-23 实测）──
 * 路径：/data/data/cn.soulapp.android/files/objectbox/<我的userId>db/data.mdb
 * 我的 userId = 367769344（ObjectBox 目录名就是这个）
 *
 * 关键发现：会话的扩展信息是**明文 JSON**，里面有：
 *   "matchSuccessTimeStr":"2026-11-23 21:17"   ← 匹配时间
 *   "userId":467091942                          ← 对方 ID
 *   "myMsgCount":2                              ← 我发过几条
 *   "enterCount":1                              ← 我进过几次
 *   "IS_HIDE_CONVERSATION":false                ← 是否隐藏
 *
 * ── 拿不到的 ──
 * unReadCount / lastMsgText / senderId 在 ObjectBox 的二进制记录里，
 * 需要精确的 27 个 index UID 才能解。暂时放弃，改用「匹配时间 + UI 验证」。
 */
object DbReader {

    /** 会话基本信息 */
    data class Session(
        val peerId: Long,
        /** 对方昵称（从 SQLite im_user_bean.signature 取），可能为空 */
        val name: String = "",
        /** 匹配时间（"2026-11-23 21:17" 格式），可能为空 */
        val matchedAt: String,
        /** 我发过几条 */
        val myMsgCount: Int,
        val enterCount: Int,
        val hidden: Boolean,
    ) {
        /** 解析出的时间戳（毫秒），失败返回 0 */
        val matchedMillis: Long
            get() = try {
                if (matchedAt.isBlank()) 0L
                else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                    .parse(matchedAt)?.time ?: 0L
            } catch (_: Throwable) {
                0L
            }

        /** 是否在 N 天内 */
        fun withinDays(days: Int): Boolean {
            val t = matchedMillis
            if (t <= 0) return false
            return System.currentTimeMillis() - t <= days * 24L * 3600_000
        }
    }

    private const val OBJBOX_DIR = "/data/data/cn.soulapp.android/files/objectbox"

    /**
     * 从 SQLite 的 im_user_bean 读「peerId → 昵称」映射。
     *
     * ── 实测（2026-11-23）──
     * 表 im_user_bean 的 signature 字段存的**就是昵称**（不是签名）：
     *   userId=23567005  signature="B✨"
     *   userId=66913261  signature="云深"
     *   userId=254814565 signature="财神钦赐锦鲤"
     * 13 个会话 13 个都能对上。
     *
     * 路径：/data/data/cn.soulapp.android/databases/chat_<base64>
     */
    fun readNameMap(): Map<Long, String> {
        val map = HashMap<Long, String>()
        val dbPath = try {
            Shell.exec(
                "ls $DB_DIR/chat_* 2>/dev/null | grep -v wal | grep -v shm | head -1"
            ).trim()
        } catch (_: Throwable) {
            ""
        }
        if (dbPath.isBlank()) {
            android.util.Log.w("SoulMate", "DbReader: 找不到 chat sqlite")
            return map
        }

        // 同样要复制出来并改所有者（App 进程读不到 /data/data）
        val ctx = Env.context() ?: return map
        val tmp = ctx.cacheDir.absolutePath + "/soul_im.db"
        val uid = android.os.Process.myUid()

        try {
            Shell.run(
                "cp -f '$dbPath' '$tmp' 2>/dev/null; " +
                        "chown $uid:$uid '$tmp' 2>/dev/null; " +
                        "chmod 660 '$tmp' 2>/dev/null"
            )
        } catch (_: Throwable) {
            return map
        }

        try {
            // 用 sqlite3 查（设备自带），直接查复制出来的文件
            val out = Shell.exec(
                "sqlite3 '$tmp' \"SELECT userId, signature FROM im_user_bean " +
                        "WHERE signature IS NOT NULL AND signature != ''\" 2>/dev/null"
            )
            if (out.isBlank()) {
                android.util.Log.w("SoulMate", "DbReader: sqlite 查询无结果")
                return map
            }

            for (line in out.lines()) {
                val idx = line.indexOf('|')
                if (idx <= 0) continue
                val uidV = line.substring(0, idx).trim().toLongOrNull() ?: continue
                val name = line.substring(idx + 1).trim()
                if (name.isNotBlank()) map[uidV] = name
            }
            android.util.Log.i("SoulMate", "DbReader: 昵称映射 ${map.size} 条")
        } catch (t: Throwable) {
            android.util.Log.w("SoulMate", "DbReader: 读昵称失败 ${t.message}")
        }
        return map
    }

    private const val DB_DIR = "/data/data/cn.soulapp.android/databases"

    /**
     * 找主数据文件的路径（文件名带 userId，不同账号不同）。
     * 排除 MineCompat_db 和 *_chat_box_db（那些是小库）。
     */
    private fun findMainDb(): String {
        return try {
            val out = Shell.exec(
                "ls -S $OBJBOX_DIR/*/data.mdb 2>/dev/null | grep -v MineCompat | grep -v chat_box | head -1"
            ).trim()
            if (out.isNotBlank()) out else ""
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * 读活跃会话清单。
     *
     * @param maxAgeDays 只要这个天数内的（默认 2 天，用户规则）
     * @return 会话列表；读失败返回空
     */
    fun readSessions(maxAgeDays: Int = 2): List<Session> {
        val db = findMainDb()
        if (db.isBlank()) {
            android.util.Log.w("SoulMate", "DbReader: 找不到主数据库")
            return emptyList()
        }

        // ── 关键：App 进程不是 root，读不到 /data/data ──
        // 必须用 Shizuku（root 身份）把文件复制到 App 自己的目录，
        // 并且**改所有者**成 App 的 uid，否则 App 还是读不了（EACCES）。
        val ctx = Env.context()
            ?: return emptyList()
        val tmp = ctx.cacheDir.absolutePath + "/soul_db.mdb"
        val uid = android.os.Process.myUid()

        try {
            Shell.run(
                "cp -f '$db' '$tmp' 2>/dev/null; " +
                        "chown $uid:$uid '$tmp' 2>/dev/null; " +
                        "chmod 660 '$tmp' 2>/dev/null"
            )
            val len = Shell.exec("stat -c %s '$tmp' 2>/dev/null").trim().toLongOrNull() ?: 0
            if (len < 1000) {
                android.util.Log.w("SoulMate", "DbReader: 复制失败 len=$len")
                return emptyList()
            }
        } catch (_: Throwable) {
            return emptyList()
        }

        val bytes = try {
            java.io.File(tmp).readBytes()
        } catch (t: Throwable) {
            android.util.Log.w("SoulMate", "DbReader: 读取失败 ${t.message}")
            return emptyList()
        }
        android.util.Log.i("SoulMate", "DbReader: 库 ${bytes.size / 1024} KB, path=$db")

        return parseSessions(bytes, maxAgeDays)
    }

    /**
     * 从原始字节里提取会话。
     *
     * 原理：会话的 extInfo 是明文 JSON，格式：
     *   ..."matchSuccessTimeStr":"2026-11-23 21:17"...\"userId\":467091942...
     *   同一个记录里还可能有 "myMsgCount":2
     *
     * 做法：找每个 matchSuccessTimeStr，在它前后 1200 字节内找 userId / myMsgCount。
     */
    private fun parseSessions(data: ByteArray, maxAgeDays: Int): List<Session> {
        val text = String(data, Charsets.ISO_8859_1)   // 单字节映射，保留原始
        val positions = ArrayList<Int>()
        var idx = 0
        while (true) {
            val p = text.indexOf("matchSuccessTimeStr", idx)
            if (p < 0) break
            positions.add(p)
            idx = p + 10
        }

        val MY_ID = findMyId(text)
        android.util.Log.i("SoulMate", "DbReader: 我的 userId=$MY_ID, 找到 ${positions.size} 个时间点")

        val result = LinkedHashMap<Long, Session>()

        for (pos in positions) {
            val lo = (pos - 1200).coerceAtLeast(0)
            val hi = (pos + 1200).coerceAtMost(text.length)
            val ctx = text.substring(lo, hi)

            val matchedAt = Regex("""matchSuccessTimeStr\\?"\s*:\s*\\?"([^"\\]+)""")
                .find(ctx)?.groupValues?.get(1) ?: continue

            // 找离 pos 最近的 userId（排除自己）
            var bestPeer = 0L
            var bestDist = Int.MAX_VALUE
            for (m in Regex("""userId\\?"\s*:\s*(\d{6,12})""").findAll(ctx)) {
                val v = m.groupValues[1].toLongOrNull() ?: continue
                if (v == MY_ID) continue
                val dist = kotlin.math.abs((lo + m.range.first) - pos)
                if (dist < bestDist) {
                    bestDist = dist
                    bestPeer = v
                }
            }
            if (bestPeer == 0L) continue

            val myMsg = Regex("""myMsgCount\\?"\s*:\s*(\d+)""")
                .find(ctx)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val enter = Regex("""enterCount\\?"\s*:\s*(\d+)""")
                .find(ctx)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val hidden = Regex("""IS_HIDE_CONVERSATION\\?"\s*:\s*(\w+)""")
                .find(ctx)?.groupValues?.get(1) == "true"

            val s = Session(
                peerId = bestPeer,
                name = "",          // 昵称稍后从 SQLite 补
                matchedAt = matchedAt,
                myMsgCount = myMsg,
                enterCount = enter,
                hidden = hidden,
            )
            // 同一个人可能有多条（进过多次），保留最新的
            val old = result[bestPeer]
            if (old == null || s.matchedAt > old.matchedAt) {
                result[bestPeer] = s
            }
        }

        // ★ 不做时间硬过滤
        //
        // 踩坑（2026-11-24）：matchSuccessTimeStr 是「匹配成功的时间」，
        // **不是她最后说话的时间**。按它过滤 2 天会漏掉「3 天前匹配、昨晚才聊」的人。
        //
        // 所以这里只过滤「隐藏的」，其余全部返回，交给 needReply 用预览判断。
        val filtered = result.values.filter { !it.hidden }

        // ★ 补上昵称（从 SQLite 的 im_user_bean 读）
        val nameMap = readNameMap()
        val withNames = filtered.mapNotNull { s ->
            val n = nameMap[s.peerId] ?: ""
            // 过滤掉"自己"这种假会话（奇遇铃生成的）
            if (n == "我" || n.isBlank()) {
                if (n.isBlank()) {
                    android.util.Log.w("SoulMate", "DbReader: peer=${s.peerId} 没有昵称，跳过")
                }
                null
            } else {
                s.copy(name = n)
            }
        }

        android.util.Log.i(
            "SoulMate",
            "DbReader: 共 ${result.size} 个会话，非隐藏 ${filtered.size} 个，" +
                    "补上昵称后 ${withNames.size} 个"
        )

        return withNames.sortedByDescending { it.matchedAt }
    }

    /** 从数据里推断「我的 userId」= 出现次数最多的 9 位数字 */
    private fun findMyId(text: String): Long {
        val counts = HashMap<Long, Int>()
        for (m in Regex("""\b(\d{6,10})\b""").findAll(text)) {
            val v = m.groupValues[1].toLongOrNull() ?: continue
            counts[v] = (counts[v] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: 0L
    }

    /**
     * 快速判断库有没有更新（用文件 mtime）。
     */
    fun dbMtime(): Long {
        val db = findMainDb()
        if (db.isBlank()) return -1
        return try {
            val s = Shell.exec("stat -c %Y '$db' 2>/dev/null").trim()
            (s.toLongOrNull() ?: 0) * 1000
        } catch (_: Throwable) {
            -1
        }
    }
}
