package com.love.soulmate.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地数据库（SQLite）：把以前散落在 5 个 JSON 文件里的东西统一进来。
 *
 * ── 用户需求（2026-11-25）──
 * 「你能自己保存一份聊天记录吗? 这样每次回复或者发起灵魂匹配聊天的时候就记录一份。
 *   然后回复的时候就不会需要再去读一份聊天记录，而是通过自己的聊天记录和最新的消息回复了
 *   (避免记忆缺失或者回复不完整)。最好就也搞一个自己的数据库，
 *   之前的所有需要记录的内容就保存在这个数据库中」
 *
 * ── 为什么必须改（原方案的硬伤）──
 * 原来每次回复都要 `readChat()` 重新 dump 屏幕，只拿到**当前可见的几条消息**。
 * 长对话早滚出屏幕的内容完全丢失 → 模型缺上下文 → 回复重复/跑题/接不上。
 *
 * 现在：每次进对话都 upsert 进库，库里是**完整历史**；
 * 回复时以库里的历史为主，屏幕只用来补「最新几条」。
 *
 * ── 表结构 ──
 *
 *   chat_msg      聊天记录（核心）
 *     account     账号（某账号/另一账号…）
 *     peer        对方昵称
 *     side        me / her
 *     text        消息内容
 *     msg_key     去重键 = side + 归一化文本（同一句话不重复入库）
 *     created_at  首次入库时间
 *
 *   person        人物档案（IP、签名、匹配度、星球…）
 *     account, name, ip, signature, planet, match_rate, days, updated_at
 *
 *   sent          我发出的消息（判断「她回没回」用）
 *     account, name, text, at
 *
 *   skipped       判定跳过的（★ 新增过期时间，解决永久跳过问题）
 *     account, name, preview, at
 *
 *   send_log      发送节奏记录（限流用）
 *     account, name, at
 *
 * ── 为什么用 msg_key 去重 ──
 * 屏幕 dump 每次都会把当前可见消息全读一遍，同一句话会反复出现。
 * 用 (account, peer, side, 归一化文本) 做唯一键，重复就忽略。
 * 归一化 = 去掉空白、去掉尾部标点，避免「你好」和「你好。」算两条。
 *
 * ── 为什么不用 Room ──
 * 项目没引 Room/KSP，加进去要改构建链，风险大。
 * 直接 SQLiteOpenHelper 够用，表结构简单，没有任何 ORM 需求。
 */
object Db {

    private const val DB_NAME = "soulmate.db"
    private const val DB_VERSION = 1

    private var helper: Helper? = null

    /** 聊天记录一条 */
    data class Msg(
        val peer: String,
        val side: String,      // me / her
        val text: String,
        val at: Long,
    )

    /** 人物档案 */
    data class Person(
        val name: String,
        val ip: String = "",
        val signature: String = "",
        val planet: String = "",
        val matchRate: String = "",
        val days: String = "",
        val updatedAt: Long = 0,
    )

    private class Helper(ctx: Context) : SQLiteOpenHelper(
        ctx.applicationContext, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE chat_msg (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  account TEXT NOT NULL,
                  peer TEXT NOT NULL,
                  side TEXT NOT NULL,
                  text TEXT NOT NULL,
                  msg_key TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  UNIQUE(account, peer, msg_key)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_msg_peer ON chat_msg(account, peer, id)")

            db.execSQL(
                """
                CREATE TABLE person (
                  account TEXT NOT NULL,
                  name TEXT NOT NULL,
                  ip TEXT,
                  signature TEXT,
                  planet TEXT,
                  match_rate TEXT,
                  days TEXT,
                  updated_at INTEGER,
                  PRIMARY KEY(account, name)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE sent (
                  account TEXT NOT NULL,
                  name TEXT NOT NULL,
                  text TEXT NOT NULL,
                  at INTEGER NOT NULL,
                  PRIMARY KEY(account, name)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE skipped (
                  account TEXT NOT NULL,
                  name TEXT NOT NULL,
                  preview TEXT NOT NULL,
                  at INTEGER NOT NULL,
                  PRIMARY KEY(account, name)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE send_log (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  account TEXT NOT NULL,
                  name TEXT NOT NULL,
                  at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_log_at ON send_log(account, at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            // 表结构简单，升级直接重建（数据可从屏幕重新采集）
            db.execSQL("DROP TABLE IF EXISTS chat_msg")
            db.execSQL("DROP TABLE IF EXISTS person")
            db.execSQL("DROP TABLE IF EXISTS sent")
            db.execSQL("DROP TABLE IF EXISTS skipped")
            db.execSQL("DROP TABLE IF EXISTS send_log")
            onCreate(db)
        }
    }

    fun init(ctx: Context) {
        if (helper == null) helper = Helper(ctx)
    }

    private fun db(): SQLiteDatabase? = try {
        helper?.writableDatabase
    } catch (_: Throwable) {
        null
    }

    /** 归一化：去空白、去尾部标点，用作去重键 */
    private fun norm(s: String): String =
        s.replace(Regex("""\s+"""), "").trimEnd('.', '…', '。', '!', '！', '?', '？')

    // ───────────── 聊天记录 ─────────────

    /**
     * 把一次对话的消息批量入库（重复的自动忽略）。
     *
     * @return 新增了几条
     */
    fun saveMessages(account: String, peer: String, msgs: List<Msg>): Int {
        val d = db() ?: return 0
        if (account.isBlank() || peer.isBlank() || msgs.isEmpty()) return 0
        var added = 0
        val now = System.currentTimeMillis()
        try {
            d.beginTransaction()
            for (m in msgs) {
                val t = m.text.trim()
                if (t.isBlank()) continue
                val key = m.side + "|" + norm(t)
                val cv = ContentValues().apply {
                    put("account", account)
                    put("peer", peer)
                    put("side", m.side)
                    put("text", t)
                    put("msg_key", key)
                    put("created_at", now)
                }
                val id = d.insertWithOnConflict(
                    "chat_msg", null, cv, SQLiteDatabase.CONFLICT_IGNORE
                )
                if (id > 0) added++
            }
            d.setTransactionSuccessful()
        } catch (_: Throwable) {
        } finally {
            try {
                d.endTransaction()
            } catch (_: Throwable) {
            }
        }
        return added
    }

    /**
     * 读某个人的完整聊天记录（按入库顺序 = 屏幕顺序，旧→新）。
     *
     * @param limit 最多取最近 N 条（给模型看的上下文别太长）
     */
    fun readMessages(account: String, peer: String, limit: Int = 40): List<Msg> {
        val d = db() ?: return emptyList()
        val out = ArrayList<Msg>()
        try {
            d.rawQuery(
                """
                SELECT side, text, created_at FROM chat_msg
                WHERE account = ? AND peer = ?
                ORDER BY id DESC LIMIT ?
                """.trimIndent(),
                arrayOf(account, peer, limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(Msg(peer, c.getString(0), c.getString(1), c.getLong(2)))
                }
            }
        } catch (_: Throwable) {
        }
        // 查出来是倒序，翻回来
        out.reverse()
        return out
    }

    /** 某人有多少条记录 */
    fun messageCount(account: String, peer: String): Int {
        val d = db() ?: return 0
        try {
            d.rawQuery(
                "SELECT COUNT(*) FROM chat_msg WHERE account = ? AND peer = ?",
                arrayOf(account, peer)
            ).use { c ->
                if (c.moveToFirst()) return c.getInt(0)
            }
        } catch (_: Throwable) {
        }
        return 0
    }

    // ───────────── 人物档案 ─────────────

    fun savePerson(account: String, p: Person) {
        val d = db() ?: return
        if (account.isBlank() || p.name.isBlank()) return
        try {
            val cv = ContentValues().apply {
                put("account", account)
                put("name", p.name)
                put("ip", p.ip)
                put("signature", p.signature)
                put("planet", p.planet)
                put("match_rate", p.matchRate)
                put("days", p.days)
                put("updated_at", System.currentTimeMillis())
            }
            d.insertWithOnConflict(
                "person", null, cv, SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (_: Throwable) {
        }
    }

    fun getPerson(account: String, name: String): Person? {
        val d = db() ?: return null
        try {
            d.rawQuery(
                "SELECT ip, signature, planet, match_rate, days, updated_at " +
                        "FROM person WHERE account = ? AND name = ?",
                arrayOf(account, name)
            ).use { c ->
                if (c.moveToFirst()) {
                    return Person(
                        name = name,
                        ip = c.getString(0) ?: "",
                        signature = c.getString(1) ?: "",
                        planet = c.getString(2) ?: "",
                        matchRate = c.getString(3) ?: "",
                        days = c.getString(4) ?: "",
                        updatedAt = c.getLong(5),
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    // ───────────── 我发过的消息 ─────────────

    fun saveSent(account: String, name: String, text: String, at: Long = System.currentTimeMillis()) {
        val d = db() ?: return
        try {
            val cv = ContentValues().apply {
                put("account", account)
                put("name", name)
                put("text", text.trim())
                put("at", at)
            }
            d.insertWithOnConflict("sent", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Throwable) {
        }
    }

    fun getAllSent(account: String): Map<String, Pair<String, Long>> {
        val d = db() ?: return emptyMap()
        val m = HashMap<String, Pair<String, Long>>()
        try {
            d.rawQuery(
                "SELECT name, text, at FROM sent WHERE account = ?",
                arrayOf(account)
            ).use { c ->
                while (c.moveToNext()) {
                    m[c.getString(0)] = (c.getString(1) ?: "") to c.getLong(2)
                }
            }
        } catch (_: Throwable) {
        }
        return m
    }

    fun deleteSentBefore(account: String, cutoff: Long) {
        val d = db() ?: return
        try {
            d.delete("sent", "account = ? AND at < ?",
                arrayOf(account, cutoff.toString()))
        } catch (_: Throwable) {
        }
    }

    // ───────────── 跳过的会话（带过期）─────────────

    /**
     * 记下「这次判定跳过」。
     *
     * ★ 关键改进（2026-11-25）：
     * 原来 skips 是永久的 —— 她预览文字不变就永远不再处理。
     * 实测误判过：「挺好」「[抠鼻]」都被写进 skips 后彻底死掉。
     *
     * 现在带 at 时间戳，读的时候只看 TTL 内的。
     */
    fun saveSkipped(account: String, name: String, preview: String) {
        val d = db() ?: return
        try {
            val cv = ContentValues().apply {
                put("account", account)
                put("name", name)
                put("preview", preview.trim())
                put("at", System.currentTimeMillis())
            }
            d.insertWithOnConflict("skipped", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Throwable) {
        }
    }

    /**
     * 读「还在有效期内」的跳过记录。
     *
     * @param ttlMs 有效期，默认 24 小时。超过就从表里删掉，让这条会话重新参与判断。
     */
    fun getActiveSkipped(account: String, ttlMs: Long = 24 * 3600_000L): Map<String, String> {
        val d = db() ?: return emptyMap()
        val m = HashMap<String, String>()
        val cutoff = System.currentTimeMillis() - ttlMs
        try {
            // 先清过期的
            d.delete("skipped", "account = ? AND at < ?",
                arrayOf(account, cutoff.toString()))
            d.rawQuery(
                "SELECT name, preview FROM skipped WHERE account = ?",
                arrayOf(account)
            ).use { c ->
                while (c.moveToNext()) {
                    m[c.getString(0)] = c.getString(1) ?: ""
                }
            }
        } catch (_: Throwable) {
        }
        return m
    }

    // ───────────── 发送节奏 ─────────────

    fun logSend(account: String, name: String) {
        val d = db() ?: return
        try {
            val cv = ContentValues().apply {
                put("account", account)
                put("name", name)
                put("at", System.currentTimeMillis())
            }
            d.insert("send_log", null, cv)
        } catch (_: Throwable) {
        }
    }

    /** 读发送记录（限流用）：返回 [(name, at)] */
    fun getSendLog(account: String): List<Pair<String, Long>> {
        val d = db() ?: return emptyList()
        val out = ArrayList<Pair<String, Long>>()
        try {
            d.rawQuery(
                "SELECT name, at FROM send_log WHERE account = ? ORDER BY at",
                arrayOf(account)
            ).use { c ->
                while (c.moveToNext()) {
                    out.add((c.getString(0) ?: "") to c.getLong(1))
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /** 统计与清理 */
    fun stats(account: String): String {
        val d = db() ?: return "库未初始化"
        return try {
            fun cnt(sql: String): Int {
                d.rawQuery(sql, arrayOf(account)).use { c ->
                    if (c.moveToFirst()) return c.getInt(0)
                }
                return 0
            }
            "聊天记录 ${cnt("SELECT COUNT(*) FROM chat_msg WHERE account=?")} 条 · " +
                    "人物 ${cnt("SELECT COUNT(*) FROM person WHERE account=?")} 个 · " +
                    "发送 ${cnt("SELECT COUNT(*) FROM send_log WHERE account=?")} 次 · " +
                    "跳过 ${cnt("SELECT COUNT(*) FROM skipped WHERE account=?")} 个"
        } catch (_: Throwable) {
            "统计失败"
        }
    }
}
