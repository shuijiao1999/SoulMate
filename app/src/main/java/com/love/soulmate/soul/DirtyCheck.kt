package com.love.soulmate.soul

import com.love.soulmate.core.Shell

/**
 * 用 目标应用的数据文件「修改时间」判断有没有新消息。
 *
 * ── 为什么需要 ──
 * 之前每次检查都要完整扫一遍列表 + 逐个点开会话，即使一条新消息都没有。
 * 实测一次完整扫描要 4 分钟，而这 4 分钟里 90% 的时候其实什么都不用做。
 *
 * ── 原理 ──
 * 目标应用 用 ObjectBox 存聊天数据，主文件是：
 *   /data/data/cn.soulapp.android/files/objectbox/367769344db/data.mdb
 *
 * 每次收到或发出消息，目标应用 都会写这个文件，**修改时间随之变化**。
 * 所以：
 *   本次 mtime == 上次 mtime  →  没有新消息，直接返回
 *   本次 mtime != 上次 mtime  →  有新消息，进入扫描流程
 *
 * ── 实测数据（2026-11-23）──
 *   文件 311KB，ChatMsgDb / ChatSessionDb 两张表都存在
 *   消息内容是明文（946 段中文可读）
 *
 * ── 限制 ──
 * 只反映「数据变了」，不知道是谁的消息。所以只用来做「快速跳过」，
 * 真正判断是谁还得靠列表扫描或通知。
 * 但这已经能砍掉绝大多数无用扫描。
 */
object DirtyCheck {

    private const val OBJBOX_DIR = "/data/data/cn.soulapp.android/files/objectbox"

    /** 已知的数据目录候选（不同账号/版本目录名可能不同）*/
    private val CANDIDATE_PATHS = listOf(
        "$OBJBOX_DIR/367769344db/data.mdb",
        "$OBJBOX_DIR/MineCompat_db/data.mdb",
    )

    private const val PREF = "dirty_check"
    private const val KEY_MTIME = "last_mtime"

    /**
     * 读取 目标应用 聊天数据库当前的最新修改时间（毫秒）。
     *
     * @return 最大 mtime；读不到返回 -1
     */
    fun currentMtime(): Long {
        var maxMtime = -1L

        // 1) 先试已知路径
        for (p in CANDIDATE_PATHS) {
            val t = statMtime(p)
            if (t > maxMtime) maxMtime = t
        }

        // 2) 已知路径都不行，就让 shell 自己去找（兼容新版本改了目录名）
        if (maxMtime < 0) {
            try {
                val out = Shell.exec(
                    "ls -l $OBJBOX_DIR/*/data.mdb 2>/dev/null | wc -l"
                ).trim()
                if (out.toIntOrNull() ?: 0 > 0) {
                    // 取最新的那个
                    val newest = Shell.exec(
                        "ls -t $OBJBOX_DIR/*/data.mdb 2>/dev/null | head -1"
                    ).trim()
                    if (newest.isNotBlank()) {
                        val t = statMtime(newest)
                        if (t > maxMtime) maxMtime = t
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 3) 兜底：整个 objectbox 目录的 mtime（数据变了目录也可能变）
        if (maxMtime < 0) {
            try {
                val s = Shell.exec("stat -c %Y $OBJBOX_DIR 2>/dev/null").trim()
                s.toLongOrNull()?.let { maxMtime = it * 1000 }
            } catch (_: Throwable) {
            }
        }

        return maxMtime
    }

    /** 读单个文件的 mtime（毫秒） */
    private fun statMtime(path: String): Long {
        return try {
            // Android 的 stat 支持 -c %Y（秒）
            val s = Shell.exec("stat -c %Y '$path' 2>/dev/null").trim()
            val sec = s.toLongOrNull()
            if (sec != null && sec > 0) {
                sec * 1000
            } else {
                // 退化：用 ls 的时间（精确到分钟，够用）
                val ls = Shell.exec("ls -l '$path' 2>/dev/null").trim()
                if (ls.isBlank()) -1L else {
                    // 能读到文件就够了，用当前时间做标记
                    System.currentTimeMillis() / 60000 * 60000
                }
            }
        } catch (_: Throwable) {
            -1L
        }
    }

    /** 上次记录的 mtime */
    fun lastMtime(ctx: android.content.Context): Long =
        ctx.getSharedPreferences(PREF, 0).getLong(KEY_MTIME, -1L)

    fun saveMtime(ctx: android.content.Context, mtime: Long) {
        if (mtime <= 0) return
        ctx.getSharedPreferences(PREF, 0).edit().putLong(KEY_MTIME, mtime).apply()
    }

    /**
     * 判断是否有新消息。
     *
     * @return true = 数据有变化（可能有新消息）；探不到状态时**返回 true**
     *         （宁可多扫一次，也不要漏消息）
     */
    fun hasNewData(ctx: android.content.Context): Boolean {
        val now = currentMtime()
        if (now < 0) return true          // 读不到 → 保守起见照常扫

        val last = lastMtime(ctx)
        if (last < 0) {
            saveMtime(ctx, now)           // 首次运行，记录基线
            return true
        }

        return now != last
    }

    /** 一次检查结束后，把当前 mtime 存为基线 */
    fun markChecked(ctx: android.content.Context) {
        val now = currentMtime()
        if (now > 0) saveMtime(ctx, now)
    }
}
