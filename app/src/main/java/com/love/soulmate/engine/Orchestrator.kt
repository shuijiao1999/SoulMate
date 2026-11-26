package com.love.soulmate.engine

import android.content.Context
import com.love.soulmate.core.Screen
import com.love.soulmate.core.Ui
import com.love.soulmate.llm.Replier
import com.love.soulmate.platform.ChatDetail
import com.love.soulmate.platform.ChatRow
import com.love.soulmate.platform.Msg
import com.love.soulmate.platform.Profile
import com.love.soulmate.soul.SoulBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class LogItem(val time: String, val level: String, val text: String)

data class EngineState(
    val running: Boolean = false,
    val phase: String = "idle",       // idle / match / scan / reply
    val pending: Int = 0,
    val sentThisHour: Int = 0,
    val lastRun: String = "",
    val intervalMin: Int = 5,
    val account: String = "",         // 当前 Soul 账号昵称
)

/**
 * 编排器：扫列表 → 靠预览判断谁没回 → 逐个打开处理 → 发送。
 *
 * 关键设计（在电脑版上实盘验证过）：
 *   列表预览就是「最后一条消息」。只要预览 == 我上次发的那句，
 *   她肯定没回，直接跳过不打开。这样 50 多个会话通常只需要打开 0-3 个。
 */
class Orchestrator(private val ctx: Context) {

    companion object {
        private const val SAME_PERSON_GAP = 45_000L   // 同一人最少隔 45 秒
        private const val GLOBAL_GAP = 20_000L        // 任意两条最少隔 20 秒
        private const val HOURLY_CAP = 25
        private const val DAILY_CAP = 120
        private const val BLOCKED = "野源葵|做你的小太阳☀|顾倍瑜|两忘"
    }

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    private val _logs = MutableStateFlow<List<LogItem>>(emptyList())
    val logs: StateFlow<List<LogItem>> = _logs

    private val blockedNames = BLOCKED.split("|")

    /**
     * 写一条日志。
     *
     * 从 private 改成 public（2026-11-24）：
     * Monitor 常驻监控需要往同一个日志流里写，
     * 否则界面收不到监控状态。
     */
    fun log(text: String, level: String = "info") {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _logs.value = (_logs.value + LogItem(t, level, text)).takeLast(300)
        // 同时写 logcat，方便 adb 侧排查（早期只写内存，导致日志看不见）
        try {
            when (level) {
                "error" -> android.util.Log.e("SoulMate", text)
                "warn" -> android.util.Log.w("SoulMate", text)
                else -> android.util.Log.i("SoulMate", text)
            }
        } catch (_: Throwable) {
        }
    }

    /** 供 UI 层写日志（点在 UI 线程之外也能调） */
    fun logExternal(text: String, level: String = "info") = log(text, level)

    // ---------- 持久化（按账号昵称分区，不按用户空间）----------

    /** 当前账号 key，空则用 _default */
    private fun accKey(): String {
        val n = com.love.soulmate.soul.Account.lastKnown()
        if (n.isBlank()) return "_default"
        return n.replace(Regex("""[\\/:*?"<>|\s]"""), "_").take(30)
    }

    private fun handledFile() = File(ctx.filesDir, "handled_${accKey()}.json")
    private fun sendLogFile() = File(ctx.filesDir, "send_log_${accKey()}.json")
    private fun sentMsgFile() = File(ctx.filesDir, "sent_msg_${accKey()}.json")

    // ---------- 发过的消息记录（核心判断依据）----------

    /**
     * 一条「我发出去的消息」记录。
     *
     * ── 为什么用这个代替原来的 handled 预览对比 ──
     * 用户提的思路（更简单可靠）：
     *   记住自己发过的内容 → 列表预览 == 我发的 → 说明她没回，跳过
     *                      → 预览 != 我发的 → 她说了新的，需要回
     *
     * 比原来的方案好在：
     *   · 不依赖未读角标（那是一次性信号，点开就没了）
     *   · 不依赖「谁发的」语义猜测（容易猜错）
     *   · 列表预览就是最后一条消息，直接比对文字，零歧义
     */
    private class SentMsg(
        val text: String,     // 我发的内容
        val at: Long,         // 发送时间
    )

    /**
     * 读「我发过的消息」。
     *
     * ★ 2026-11-25 改成读数据库（原来读 sent_msg_xxx.json）。
     * 旧的 JSON 已不再写入，但为了不丢历史，第一次读时会把 JSON 迁移进库。
     */
    private fun loadSentMsgs(): MutableMap<String, SentMsg> {
        val m = HashMap<String, SentMsg>()
        val acc = accKey()
        try {
            // 迁移旧 JSON（只做一次，成功后删掉）
            val f = sentMsgFile()
            if (f.exists()) {
                try {
                    val j = JSONObject(f.readText())
                    j.keys().forEach { name ->
                        val o = j.optJSONObject(name) ?: return@forEach
                        val tx = o.optString("t", "")
                        val at = o.optLong("at", 0L)
                        if (tx.isNotBlank() && at > 0) {
                            com.love.soulmate.core.Db.saveSent(acc, name, tx, at)
                        }
                    }
                    f.delete()
                } catch (_: Throwable) {
                }
            }

            com.love.soulmate.core.Db.getAllSent(acc).forEach { (name, v) ->
                if (v.first.isNotBlank() && v.second > 0) {
                    m[name] = SentMsg(v.first, v.second)
                }
            }
        } catch (_: Throwable) {
        }
        return m
    }

    private fun saveSentMsgs(m: Map<String, SentMsg>) {
        try {
            val acc = accKey()
            m.forEach { (name, s) ->
                com.love.soulmate.core.Db.saveSent(acc, name, s.text, s.at)
            }
        } catch (_: Throwable) {
        }
    }

    /** 记一条我发出去的消息 */
    private fun recordSentMsg(name: String, text: String) {
        val m = loadSentMsgs()
        m[name] = SentMsg(text.trim(), System.currentTimeMillis())
        saveSentMsgs(m)
    }

    /**
     * 标记「这条会话我主动跳过了」（冷淡词 / 软收尾 / IP 不符）。
     *
     * ── 踩过的坑（2026-11-24，严重）──
     * 原来直接把「当前预览」写进 sentMsgs 当「我发的内容」。
     * 但预览是**她说的**，不是我说的！
     *
     * 后果：needReply 下一轮比对时，预览和她那句话一致 → 判定「她没回」→ 永远跳过。
     * 实测漏掉了 8 个未读会话（截图里明明 9 个红点，代码只认出 1 个）。
     *
     * ── 正确做法 ──
     * 只记录「这次已经判断过了」这个事实，用**独立字段**区分，
     * 而不是伪装成「我发过的话」。
     */
    private fun markSkipped(name: String, preview: String) {
        if (preview.isBlank()) return
        val m = loadSkips()
        m[name] = preview.trim()
        saveSkips(m)
    }

    // ---------- 跳过记录（与「我发过的话」严格分开）----------

    private fun skipsFile() = File(ctx.filesDir, "skipped_${accKey()}.json")

    /**
     * 跳过记录：昵称 → 当时看到的预览。
     *
     * 与 sentMsgs 的区别：
     *   sentMsgs   = 我**真的发出去**的话（发送成功后记录）
     *   skipsFile  = 我**决定不回**的会话（冷淡词/IP不符等）
     *
     * 用途：同一个预览只跳过一次。她一旦说了新内容，预览变化 → 会重新处理。
     */
    /**
     * 读「我判定跳过的会话」。
     *
     * ★ 关键改进（2026-11-25 用户实测发现问题后改）：
     * 原来是永久的 —— 她预览文字不变就永远不再处理。
     * 实测误判过：「她回了『挺好』」「她发了个表情」被写进 skips 后彻底死掉。
     *
     * 现在带 24 小时有效期：过期后允许重新判断一次。
     */
    private fun loadSkips(): MutableMap<String, String> {
        val m = HashMap<String, String>()
        val acc = accKey()
        try {
            // 迁移旧 JSON
            val f = skipsFile()
            if (f.exists()) {
                try {
                    val j = JSONObject(f.readText())
                    val now = System.currentTimeMillis()
                    j.keys().forEach { name ->
                        val pv = j.optString(name)
                        if (pv.isNotBlank()) {
                            // 迁移时给当前时间，这样 24h 后就会重新判断
                            com.love.soulmate.core.Db.saveSkipped(acc, name, pv)
                        }
                    }
                    f.delete()
                } catch (_: Throwable) {
                }
            }

            com.love.soulmate.core.Db.getActiveSkipped(acc).forEach { (k, v) ->
                m[k] = v
            }
        } catch (_: Throwable) {
        }
        return m
    }

    private fun saveSkips(m: Map<String, String>) {
        try {
            val acc = accKey()
            m.forEach { (k, v) ->
                com.love.soulmate.core.Db.saveSkipped(acc, k, v)
            }
        } catch (_: Throwable) {
        }
    }

    /** IP 属地记录的存储文件（和 sentMsgs 放一起） */
    private fun ipRecordsFile(): File =
        File(ctx.filesDir, "ip_records.json")

    /** 读「每个人的 IP 属地」记录 */
    private fun loadIpRecords(): MutableMap<String, String> {
        val m = HashMap<String, String>()
        try {
            val f = ipRecordsFile()
            if (f.exists()) {
                val j = JSONObject(f.readText())
                j.keys().forEach { m[it] = j.optString(it) }
            }
        } catch (_: Throwable) {
        }
        return m
    }

    /**
     * 记下某个人的 IP 属地。
     *
     * ── 为什么要记（2026-11-25）──
     * 用户要求「删除废弃会话」的规则之一是「不在我选择的地区」。
     * 但聊天列表页**看不到 IP**，必须点进会话才能读到。
     * 为了不在删除时逐个点进去（每人 20-40 秒），
     * 就在平时筛人/读资料时把 IP 顺手记下来，删除时直接查表。
     *
     * 没记过的人一律不删（宁可留着，不误删）。
     */
    private fun recordIp(name: String, ip: String) {
        val v = ip.trim()
        if (name.isBlank() || v.isBlank()) return
        try {
            val m = loadIpRecords()
            if (m[name] == v) return          // 没变就不写盘
            m[name] = v
            val j = JSONObject()
            m.forEach { (k, val2) -> j.put(k, val2) }
            ipRecordsFile().writeText(j.toString())
        } catch (_: Throwable) {
        }
    }

    /**
     * 判断「这条会话需不需要回」。
     *
     * 判断顺序（从可靠到宽松）：
     *   1. 预览 == 我上次真发出去的话        → 她没回，跳过
     *   2. 预览 == 我上次决定跳过的内容      → 已判断过，跳过
     *   3. 其它（预览变了 / 没记录）         → 需要看
     *
     * @return true = 需要回
     */
    private fun needReply(
        name: String,
        preview: String,
        sent: Map<String, SentMsg>,
        skips: Map<String, String>,
    ): Boolean {
        val pv = preview.trim()
        if (pv.isBlank()) return false

        val norm = { s: String -> s.replace(Regex("""\s+"""), "").trimEnd('.', '…', '。') }
        val b = norm(pv)

        // ① 我的话 → 她没回
        sent[name]?.let { rec ->
            val a = norm(rec.text)
            if (a.isNotBlank() && (b.startsWith(a.take(12)) || a.startsWith(b.take(12)))) {
                return false
            }
        }

        // ② 上次跳过的内容 → 不重复判断
        skips[name]?.let { sk ->
            val a = norm(sk)
            if (a.isNotBlank() && (b.startsWith(a.take(12)) || a.startsWith(b.take(12)))) {
                return false
            }
        }

        // ③ 其它 → 需要看
        return true
    }

    /**
     * 清理超过 N 天的记录。
     *
     * 用户要求：超过两天的对话不再聊，记忆也清掉。
     * 每天首次启动时清理，避免记录无限膨胀。
     */
    private fun cleanupOldSent(days: Int = 2): Int {
        val cutoff = System.currentTimeMillis() - days * 24L * 3600_000
        val m = loadSentMsgs()
        val before = m.size
        m.entries.removeAll { it.value.at < cutoff }
        if (m.size != before) {
            saveSentMsgs(m)
            return before - m.size
        }
        return 0
    }

    private fun loadHandled(): MutableMap<String, String> {
        val m = HashMap<String, String>()
        try {
            val f = handledFile()
            if (f.exists()) {
                val j = JSONObject(f.readText())
                j.keys().forEach { m[it] = j.optString(it) }
            }
        } catch (_: Throwable) {
        }
        return m
    }

    private fun saveHandled(m: Map<String, String>) {
        try {
            val j = JSONObject()
            m.forEach { (k, v) -> j.put(k, v) }
            handledFile().writeText(j.toString())
        } catch (_: Throwable) {
        }
    }

    private class SendEvent(val at: Long, val user: Int, val name: String)

    private fun loadSendLog(): MutableList<SendEvent> {
        val list = ArrayList<SendEvent>()
        try {
            val f = sendLogFile()
            if (f.exists()) {
                val arr = org.json.JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(SendEvent(o.optLong("at"), o.optInt("user"), o.optString("name")))
                }
            }
        } catch (_: Throwable) {
        }
        return list
    }

    private fun saveSendLog(list: List<SendEvent>) {
        try {
            val cut = System.currentTimeMillis() - 3L * 86400_000
            val arr = org.json.JSONArray()
            list.filter { it.at > cut }.forEach {
                arr.put(JSONObject().put("at", it.at).put("user", it.user).put("name", it.name))
            }
            sendLogFile().writeText(arr.toString())
        } catch (_: Throwable) {
        }
    }

    /** 发送前的风控检查 */
    private fun checkRate(user: Int, name: String): String? {
        val now = System.currentTimeMillis()
        val ev = loadSendLog()
        val same = ev.filter { it.user == user && it.name == name }.maxOfOrNull { it.at } ?: 0
        if (same > 0 && now - same < SAME_PERSON_GAP) {
            return "同一人冷却 ${(SAME_PERSON_GAP - (now - same)) / 1000}s"
        }
        val any = ev.maxOfOrNull { it.at } ?: 0
        if (any > 0 && now - any < GLOBAL_GAP) {
            return "全局冷却 ${(GLOBAL_GAP - (now - any)) / 1000}s"
        }
        if (ev.count { now - it.at < 3600_000 } >= HOURLY_CAP) return "本小时已达上限"
        if (ev.count { now - it.at < 86400_000 } >= DAILY_CAP) return "今日已达上限"
        return null
    }

    /**
     * 用视觉确认页面健康，异常则尝试恢复。
     *
     * 为什么需要：之前流程卡死全靠人工看截图猜原因。
     * 现在每进一个关键阶段前看一眼，能自动认出「额度用完 / 网络错误 / 弹窗」并处理掉。
     *
     * @param stage 当前阶段名，仅用于日志
     * @return 是否健康（或已恢复成功）
     */
    private fun ensureHealthy(stage: String): Boolean {
        val act = Ui.currentActivity()
        val st = com.love.soulmate.core.StateReader.read(act)
        val label = com.love.soulmate.core.StateReader.label(st.state)

        val healthy = st.state in setOf(
            com.love.soulmate.core.StateReader.PageState.MAIN,
            com.love.soulmate.core.StateReader.PageState.CHAT_LIST,
            com.love.soulmate.core.StateReader.PageState.CONVERSATION,
            com.love.soulmate.core.StateReader.PageState.PLANET,
            com.love.soulmate.core.StateReader.PageState.MATCH_PAGE,
        )

        if (healthy) {
            log("  [$stage] 页面正常：$label", "info")
            return true
        }

        log("  [$stage] 页面异常：$label「${st.reason}」", "warn")

        if (!st.canRecover) {
            log("  [$stage] 该状态无法自动恢复", "error")
            return false
        }

        if (com.love.soulmate.core.StateReader.tryRecover(st.state)) {
            Thread.sleep(1500)
            val after = com.love.soulmate.core.StateReader.read(Ui.currentActivity())
            val ok = after.state in setOf(
                com.love.soulmate.core.StateReader.PageState.MAIN,
                com.love.soulmate.core.StateReader.PageState.CHAT_LIST,
                com.love.soulmate.core.StateReader.PageState.PLANET,
            )
            log(
                "  [$stage] 恢复后：${com.love.soulmate.core.StateReader.label(after.state)}" +
                        if (ok) " ✓" else " ✗",
                if (ok) "info" else "warn"
            )
            return ok
        }
        return false
    }

    /**
     * 匹配阶段主循环。抽出来是为了让主流程更清晰，也方便单独跳过。
     */
    private fun runMatchLoop(
        user: Int,
        dryRun: Boolean,
        matcher: com.love.soulmate.soul.Matcher,
        q: com.love.soulmate.soul.Matcher.Quota,
    ) {
        if (q.left == 0) {
            log("u$user 匹配额度为 0，跳过匹配", "info")
            return
        }

        var matched = 0
        var fails = 0
        var round = 0
        val maxRounds = if (q.left > 0) q.left + 3 else 6

        // 原来 fails < 5，偶发几次界面抖动就整体放弃匹配。
        // 放宽到 12 次（额度本来就有限，别因为抖动浪费）
        while (matched < maxRounds && fails < 12 && round < maxRounds) {
            round++
            val live = matcher.readQuota()
            if (live.left == 0) {
                log("u$user 匹配额度已用完", "info")
                break
            }

            SoulBot.gotoMain()
            matcher.tapPlanetTab()
            Thread.sleep(1800)

            if (!matcher.startMatch()) {
                fails++
                log("u$user 第 $round 次匹配失败（没进对话）", "warn")
                continue
            }

            val (nm, tags) = matcher.readCard()
            val name = nm.ifBlank { "匹配用户$round" }

            // ★★★ 去她主页读真实资料（用户要求 2026-11-24）★★★
            //
            // 「灵魂匹配打招呼的时候，记得去她主页看下信息后再打招呼，
            //   光看标签不行」
            //
            // 匹配对话页顶部只有标签（星球名/引力签/认证/匹配度），
            // 这些都是别人给她打的标签，同质化严重，
            // 写出来就是「看你喜欢独处」这种谁都能说的废话。
            //
            // 主页上有她自己写的东西：
            //   · 签名
            //   · 瞬间（帖子）内容 ← 最值钱，有人有事有情绪
            //   · 灵魂画像
            //
            // 代价：多花 6-10 秒（进主页 + 读 + 返回）。
            // 但换来的是「接得上话」的开场白，值得。
            val homeInfo = try {
                com.love.soulmate.soul.UserHome.readFromConversation()
            } catch (s: StopSignal) {
                throw s
            } catch (t: Throwable) {
                log("  $name 读主页失败：${t.message}", "warn")
                null
            }

            if (homeInfo != null) {
                log(
                    "  $name 主页：签名「${homeInfo.signature.take(20)}」" +
                            " 瞬间 ${homeInfo.posts.size} 条"
                            + if (homeInfo.posts.isNotEmpty())
                        " 最新「${homeInfo.posts.first().replace("\n", " ").take(30)}」"
                    else "",
                    "info"
                )
            } else {
                log("  $name 主页读不到，退回用标签", "warn")
            }

            // 排除规则：先用主页资料过一遍（已婚带娃常在签名/瞬间里）
            val homeProf = Profile(
                ip = homeInfo?.ip ?: "",
                raw = buildList {
                    if (homeInfo != null) {
                        if (homeInfo.signature.isNotBlank()) add(homeInfo.signature)
                        addAll(homeInfo.posts.take(3))
                        addAll(homeInfo.traits)
                        if (homeInfo.planet.isNotBlank()) add(homeInfo.planet)
                    }
                    addAll(tags)
                },
                match = homeInfo?.matchRate ?: "",
            )

            val ex = com.love.soulmate.soul.Excluder.check(
                name = name,
                tags = homeProf.raw,
                post = homeInfo?.posts?.joinToString(" ").orEmpty(),
            )
            if (ex.exclude) {
                log("  $name 跳过：${ex.reason}", "info")
                SoulBot.gotoMain()
                continue
            }

            // ★ 生成开场白：优先用主页素材
            val opener = if (homeInfo != null && homeInfo.hasContent) {
                Replier.generateOpenerFromHome(name, homeProf, homeInfo.toPromptText())
            } else {
                // 退回原来的标签方式
                Replier.generate(
                    listOf(Msg("her", tags.joinToString(" / ").take(80), 0)),
                    homeProf, name
                )
            }
            if (opener == null) {
                log("$name 开场白生成失败", "warn")
                fails++
                SoulBot.gotoMain()
                continue
            }

            if (dryRun) {
                log("$name [演练] 开场：$opener", "info")
            } else {
                val sr = SoulBot.send(opener)
                if (sr.ok) {
                    matched++
                    fails = 0
                    recordSend(user, name)
                    log("u$user 匹配 $name → $opener", "sent")
                } else {
                    fails++
                    log("$name 开场发送失败（${sr.error}）", "error")
                    SoulBot.gotoMain()
                }
            }
            Thread.sleep(3000)
        }
        log("u$user 匹配完成，共打开 $matched 个对话", "info")
    }

    private fun recordSend(user: Int, name: String) {
        val ev = loadSendLog()
        ev.add(SendEvent(System.currentTimeMillis(), user, name))
        saveSendLog(ev)
    }

    // ---------- 主流程 ----------

    /** 处理当前空间，返回真正发出的条数 */
    fun processSpace(user: Int, dryRun: Boolean = false): Int {
        SoulBot.user = user
        Screen.reset()

        // 账号昵称运行时读，不写死
        val who = com.love.soulmate.soul.Account.lastKnown().ifBlank { "（未识别）" }
        log("── 账号：$who · 屏幕 ${Screen.desc()} ──", "info")

        // ★ 先把 目标应用 拉到前台再判断（踩坑：刚点完按钮 App 自己还在前台，
        //   此时直接判断会误报「不在 目标应用」，整轮回复被跳过）
        if (!Ui.isForeground(SoulBot.PKG)) {
            com.love.soulmate.core.Shell.run(
                "am start --user $user -n ${SoulBot.MAIN_ACT}"
            )
            // 目标应用 冷启动实测要 10-20 秒（模拟器更慢），必须轮询等它真起来
            var waitSoul = 0
            while (waitSoul < 30 && !Ui.isForeground(SoulBot.PKG)) {
                Thread.sleep(1000)
                waitSoul++
            }
            // 起来了再多给一点渲染时间
            if (Ui.isForeground(SoulBot.PKG)) Thread.sleep(3000)
            if (!Ui.isForeground(SoulBot.PKG)) {
                log("u$user 等 30 秒仍不可见，重启一次", "warn")
                com.love.soulmate.core.Shell.run("am force-stop ${SoulBot.PKG}")
                Thread.sleep(1500)
                com.love.soulmate.core.Shell.run(
                    "am start --user $user -n ${SoulBot.MAIN_ACT}"
                )
                var retry = 0
                while (retry < 40 && !Ui.isForeground(SoulBot.PKG)) {
                    Thread.sleep(1000)
                    retry++
                }
                Thread.sleep(3000)
            }
        }
        // ★ 先确保焦点稳定，再读当前空间。
        //   踩坑：App 刚启动/刚从后台切走时，mCurrentFocus 会短暂为空，
        //   这时读 currentUser() 拿到 -1，整轮就被跳过了。
        if (!Ui.waitFocusReady(8000)) {
            log("等待前台焦点超时，强制拉起目标应用后重试", "warn")
            com.love.soulmate.core.Shell.run(
                "am start --user 0 -n ${SoulBot.MAIN_ACT}"
            )
            Thread.sleep(5000)
            Ui.waitFocusReady(10000)
        }

        if (!SoulBot.ensureCurrentSpace()) {
            log("u$user 拉不起目标应用（焦点未稳定）", "error")
            com.love.soulmate.core.Pusher.alertTargetBroken(
                ctx, "拉不起目标应用",
                "等待 30 秒 + 强制重启都没能进入目标应用，本轮放弃。"
            )
            return 0
        }

        // ★ 检查是不是掉登录了
        //
        // 踩过的坑（2026-11-24）：目标应用 被登出后停在登录页，
        // App 还傻乎乎地找聊天列表，找不到就卡到超时，
        // 表现就是「回复时卡住」。必须一进来就检查。
        run {
            val st = com.love.soulmate.core.StateReader.read(Ui.currentActivity())
            if (st.state == com.love.soulmate.core.StateReader.PageState.LOGIN_EXPIRED) {
                log("u$user 目标应用掉登录了，需要你手动登录", "error")
                com.love.soulmate.core.Pusher.alert(
                    ctx,
                    "目标应用掉登录了",
                    """
                    |Soul 停在登录页，App 没法发消息。
                    |
                    |请在平板上手动登录 Soul（输手机号 + 验证码），
                    |登录后点 App 的「立即检查」就会继续。
                    |
                    |页面特征：${st.reason}
                    """.trimMargin(),
                    force = true,
                )
                return 0
            }
        }

        // ★ 先识别当前账号（目标应用 里的昵称），所有数据按账号分区
        val real = com.love.soulmate.soul.Account.readCurrent()
        if (real.isNotBlank()) {
            com.love.soulmate.soul.Account.setCurrent(real)
            _state.value = _state.value.copy(account = real)
            log("  ✓ 当前账号：$real", "info")
        } else {
            val last = com.love.soulmate.soul.Account.lastKnown()
            if (last.isNotBlank()) {
                _state.value = _state.value.copy(account = last)
                log("  ⚠ 读不到昵称，沿用上次：$last", "warn")
            } else {
                log("  ⚠ 读不到昵称，请先打开一次目标应用的「自己」页面", "warn")
            }
        }
        SoulBot.gotoMain()
        SoulBot.killDialogs()

        // ===== 阶段 0：灵魂匹配（只在勾选「匹配」时跑）=====
        //
        // 用户要求（2026-11-24）：每个功能独立开关，没勾就不跑。
        val matcher = com.love.soulmate.soul.Matcher
        _state.value = _state.value.copy(phase = "match")

        if (!features.match) {
            log("u$user 未勾选「灵魂匹配」，跳过", "info")
        } else {
            // ★ 进入匹配前先用视觉确认页面状态，异常则处理掉再进
            ensureHealthy("准备匹配")

            var q = matcher.readQuota()
            log("u$user 灵魂匹配剩余 ${if (q.left < 0) "读不到" else "${q.left} 次"}（${q.raw}）", "info")

            // ★ 读不到额度时，用视觉查明真实原因并尝试恢复
            if (q.left < 0) {
                val st = com.love.soulmate.core.StateReader.read(Ui.currentActivity())
                log("  页面状态：${com.love.soulmate.core.StateReader.label(st.state)}「${st.reason}」", "warn")

                // 额度用完是「正常但不可行动」的状态，直接跳过匹配
                if (st.state == com.love.soulmate.core.StateReader.PageState.QUOTA_EMPTY) {
                    log("  匹配额度已用完，跳过匹配阶段（继续跑回复）", "info")
                    com.love.soulmate.core.StateReader.tryRecover(st.state)
                    q = com.love.soulmate.soul.Matcher.Quota(0, "额度已用完")
                } else if (st.canRecover) {
                    if (com.love.soulmate.core.StateReader.tryRecover(st.state)) {
                        log("  已恢复，重新读额度", "info")
                        Thread.sleep(2000)
                        q = matcher.readQuota()
                        log("  重读额度：${if (q.left < 0) "仍读不到" else "${q.left} 次"}", "info")
                    } else {
                        log("  恢复失败，跳过匹配阶段", "warn")
                        SoulBot.gotoMain()
                        q = com.love.soulmate.soul.Matcher.Quota(0, "恢复失败")
                    }
                }
            }

            runMatchLoop(user, dryRun, matcher, q)
        }

        // ★ 删除废弃会话（用户要求 2026-11-25）
        //
        // 「删除废弃会话这个加到灵魂匹配这一轮操作后」
        //
        // ── 为什么放在这里（匹配之后、回复之前）──
        //   ① 匹配跑完后列表会多出刚聊的新人，正好一起清理
        //   ② 必须在扫回复列表**之前**清，否则死会话还占着顶部，
        //      真正有新消息的人被挤出 topN(8) —— 那正是
        //      「有些人回我话了但没被处理」的根因
        //
        // ── 删哪些 ──
        // 只删「最后一条是我发的、她一直没回」的会话。
        // 不删从没聊过的、也不删已经回我的（那些要回）。
        if (features.deleteDeadChats) {
            try {
                checkStop()
                log("u$user 清理废弃会话…", "info")
                if (!SoulBot.gotoMain()) {
                    log("  回不到主界面，跳过清理", "warn")
                } else {
                    SoulBot.tapChatTab()
                    Thread.sleep(1500)
                    val dead = collectDeadChats()
                    if (dead.isEmpty()) {
                        log("  没有废弃会话需要清理", "info")
                    } else {
                        log(
                            "  找到 ${dead.size} 个废弃会话：" +
                                    dead.take(8).joinToString("、") +
                                    if (dead.size > 8) "…" else "",
                            "info"
                        )
                        if (dryRun) {
                            log("  [演练] 不实际删除", "info")
                        } else {
                            val n = SoulBot.deleteChats(dead)
                            log("  已删除 $n / ${dead.size} 个废弃会话", "info")
                        }
                    }
                }
            } catch (s: StopSignal) {
                throw s
            } catch (t: Throwable) {
                log("  清理废弃会话出错：${t.message}", "warn")
            }
        } else {
            log("u$user 未勾选「删除废弃会话」，跳过", "info")
        }

        // ===== 阶段 1：回复已有会话（只在勾选「回复」时跑）=====
        //
        // 用户要求（2026-11-24）：每个功能独立开关。
        if (!features.reply) {
            log("u$user 未勾选「回复会话」，跳过", "info")
            return 0
        }

        if (!SoulBot.gotoMain()) {
            log("u$user 回不到主界面", "error")
            return 0
        }
        SoulBot.killDialogs()

        // ★ 快速跳过：目标应用的数据文件没变化 = 没有新消息，不用扫
        //
        // 依据：目标应用 用 ObjectBox 存聊天数据（files/objectbox/*/data.mdb），
        // 收/发消息都会写这个文件，mtime 随之变化。
        // 实测一次完整扫描要 4 分钟，而大多数时候其实没有新消息。
        //
        // 注意：dryRun 时不跳过（演练就是想看效果）。
        if (!dryRun && !forceScan && !com.love.soulmate.soul.DirtyCheck.hasNewData(ctx)) {
            val mt = com.love.soulmate.soul.DirtyCheck.currentMtime()
            val whenStr = if (mt > 0) {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(mt))
            } else "?"
            log("u$user 数据无变化（上次写入 $whenStr），跳过扫描", "info")
            return 0
        }

        // 用分级处理确保在聊天列表（不再一遇空列表就重启 u2）
        var listNodes = SoulBot.ensureChatList()
        if (listNodes.isEmpty()) {
            // ★ 读不到列表时，用视觉看一眼到底卡在什么页面
            log("u$user 读不到列表，视觉检查页面状态...", "warn")
            val st = com.love.soulmate.core.StateReader.read(Ui.currentActivity())
            log("  页面状态：${com.love.soulmate.core.StateReader.label(st.state)}「${st.reason}」", "warn")

            if (st.canRecover && com.love.soulmate.core.StateReader.tryRecover(st.state)) {
                log("  已尝试恢复，重试读列表", "info")
                listNodes = SoulBot.ensureChatList()
            }
            if (listNodes.isEmpty()) {
                log("u$user 仍读不到列表，放弃本轮", "error")
                com.love.soulmate.core.Pusher.alertTargetBroken(
                    ctx, "读不到聊天列表",
                    "已尝试恢复（关弹窗/重启目标应用）仍未读到会话列表。"
                )
                return 0
            }
        }

        // ===== 阶段 1：先查数据库拿候选名单，再对列表做定向验证 =====
        //
        // 用户规则（2026-11-23）：超过两天的对话不再聊。
        // 数据库里能直接读到「每个会话的匹配时间」，所以先按这个筛一遍：
        //   匹配时间在 2 天内 + 没隐藏 + 有昵称  → 候选
        // 然后只对候选走 UI 验证，不用盲扫 46 个。
        _state.value = _state.value.copy(phase = "scan")

        val dbSessions = try {
            com.love.soulmate.soul.DbReader.readSessions(maxAgeDays = 2)
        } catch (t: Throwable) {
            log("  读数据库失败（${t.javaClass.simpleName}），回退到全列表扫描", "warn")
            emptyList()
        }

        val dbNames = dbSessions.map { it.name }.toSet()
        if (dbSessions.isNotEmpty()) {
            log(
                "u$user 数据库：${dbSessions.size} 个 2 天内的会话 → " +
                        dbSessions.take(8).joinToString(", ") { it.name },
                "info"
            )
        } else {
            log("  数据库没读到会话，回退到全列表扫描", "warn")
        }

        val allChats = SoulBot.collectAllChats()
        if (allChats.isEmpty()) {
            log("u$user 列表为空（可能确实没有会话）", "warn")
            return 0
        }
        // ★ 不再截断（2026-11-25 用户指出这是不合理限制）
        //
        // 原来的 `allChats.take(topN)` 只处理顶部 8 个，
        // 但实测列表有 66 个会话 —— 第 9 名之后**永远不会被处理**。
        // 那 45 个「昨天」的会话就是被这个截断漏掉的，
        // 表现就是用户反馈的「有些人回我话了但没被处理」。
        //
        // 现在全量处理。代价是慢（每个会话约 20-25 秒，
        // 66 个约 25 分钟），但有「停止」按钮随时可以中断，
        // 而且限流（每小时 25 条 / 每天 120 条）会自动兜住发送量。
        val chats = allChats
        log("u$user 共 ${allChats.size} 个会话，全部处理（不再只看顶部 8 个）")

        // ★ 每天首次运行清掉超过 2 天的发送记录（用户要求）
        val purged = cleanupOldSent(2)
        if (purged > 0) {
            log("  已清理 $purged 条超过 2 天的发送记录", "info")
        }

        // ── 列表粗筛：宁滥勿缺（2026-11-25 用户实测后改）──
        //
        // ── 原来的做法（精确但会漏人）──
        // 用「预览文字 vs 我发过的话」比对，判断是谁发的最后一条。
        // 看起来可靠，其实**本质是猜**，两种情况必错：
        //   ① 我上次发的话和上上次一样 → 误判成「她没回」
        //   ② 她说的正好和我发过的短句重合（如「嗨」）→ 误判成「我说的」
        //
        // ── 实测证据（2026-11-25）──
        // 逐个点进 6 个会话用视觉模型看气泡位置（左右分明，100% 确定）：
        //   其中包含「她先打招呼」「我最后说话」等 6 种情况
        // 视觉判定 6/6 与「看气泡左右」一致；
        // 但列表判定只挑出 3 个，**漏了两个会话**。
        //
        // ── 现在的做法 ──
        // 列表只做**粗筛**（排除明显不用回的），真正要不要回在对话页决定：
        //   排除：① 预览就是我上次发的话（确定她没回）
        //         ② 24 小时内的 skips（避免立刻重复看同一个）
        //   其余全部进 todo → 进对话页看气泡 → 精确判定
        //
        // 代价：每轮多进几个会话（每个约 20 秒），但**保证不漏人**。
        // 这个取舍是用户明确选的：「就这么改吧」。
        val sentMsgs = loadSentMsgs()
        val skips = loadSkips()
        val todo = ArrayList<ChatRow>()
        var newOnes = 0
        var heartHit = 0

        for (c in chats) {
            if (blockedNames.any { c.name.contains(it) }) continue
            if (c.preview.isBlank()) continue
            if (c.hearted) heartHit++

            // ① 预览就是我上次发的话 → 确定她没回，不用进去
            val rec = sentMsgs[c.name]
            if (rec != null) {
                val norm = { s: String ->
                    s.replace(Regex("""\s+"""), "").trimEnd('.', '…', '。')
                }
                val a = norm(rec.text)
                val b = norm(c.preview)
                if (a.isNotBlank() && b.isNotBlank() &&
                    (b.startsWith(a.take(12)) || a.startsWith(b.take(12)))
                ) {
                    continue
                }
            }

            // ② skips 不再用来过滤列表（2026-11-25 第二次修正）
            //
            // 原来的设计：判定跳过 → 记 skips → 下次列表直接过滤掉。
            //
            // 但实测暴露问题：skips 里存了「她回了『挺好』」「她发了个表情」这类
            // **正常回应**（不在冷淡词/软收尾表里，是早期误判留下的），
            // 结果这两个人永远进不来。
            //
            // 现在改成：列表不查 skips，所有人都进对话页看气泡。
            // skips 只在**对话页判定之后**用（命中冷淡词/软收尾时记一下，
            // 避免下一轮又把同一个「哦」当新消息处理）。
            //
            // 代价：每轮多看几个会话（每人约 20 秒），但保证不漏人。

            // ③ 其余全部进 todo，进对话页精确判定
            todo.add(c)
            if (rec == null) newOnes++
        }

        // ★ 用数据库名单做「排序参考」，而不是「硬过滤」
        //
        // ── 踩过的坑（2026-11-24）──
        // 一开始按 matchSuccessTimeStr（匹配时间）过滤 2 天内的，
        // 结果漏掉一堆会话：这个字段是「当初匹配成功的时间」，
        // **不是她最后说话的时间**。3 天前匹配的人昨晚才发消息，会被误过滤。
        //
        // ── 正确用法 ──
        // 数据库名单只用来「把最近的会话排到前面」，
        // 是否要回仍然由 needReply（比对预览）决定，绝不用时间卡死。
        if (dbSessions.isNotEmpty()) {
            val dbNames = dbSessions.map { it.name }.toSet()

            // 数据库里的会话优先排在前面（按匹配时间倒序）
            val ordered = ArrayList<ChatRow>()
            for (s in dbSessions) {
                todo.firstOrNull { it.name == s.name }?.let { ordered.add(it) }
            }
            // 其余保持在列表里的原顺序
            for (row in todo) {
                if (row.name !in dbNames) ordered.add(row)
            }
            todo.clear()
            todo.addAll(ordered)

            log(
                "  数据库 ${dbSessions.size} 个会话作为排序参考" +
                        "（匹配时间≠最后消息时间，不做硬过滤）",
                "info"
            )
        }

        // 带红心的优先（点过「喜欢」的，有基础好感，值得优先维护）
        todo.sortByDescending { if (it.hearted) 1 else 0 }

        log(
            "u$user 最终需处理 ${todo.size} 个" +
                    "（${newOnes} 个没记录过，${heartHit} 个红心优先）",
            "info"
        )
        _state.value = _state.value.copy(pending = todo.size)

        if (todo.isEmpty()) return 0

        // ===== 阶段 2：逐个处理 =====
        _state.value = _state.value.copy(phase = "reply")
        var sent = 0
        var sendFailCount = 0      // 发送失败次数（用于判断要不要告警）
        var openFailCount = 0      // 打不开次数
        var inviteCount = 0        // 检测到的邀约数量（高价值信号）

        for (row in todo) {
            val nm = row.name

            // 用户点了停止就立刻退出（每处理一个人检查一次）
            checkStop()

            // ★ 遇到奇遇铃：点进去搭话，聊完回到这里继续
            //
            // 用户要求（2026-11-24）：
            // 「如果遇到奇遇铃就点一下立即私聊，然后再回到还没完成的功能中」
            // 加上功能开关：没勾「奇遇铃」就直接关掉弹窗，不进去。
            if (features.miracleBell) {
                if (SoulBot.handleMiracleBell { bellName ->
                        handleBellGreeting(bellName, user, dryRun) { sent++ }
                    }) {
                    log("  处理完奇遇铃，回到「$nm」", "info")
                    // handleMiracleBell 已经回到聊天列表，这里重新打开当前会话
                    if (!SoulBot.openByName(nm)) {
                        log("$nm 重新打开失败，下轮再试", "warn")
                        openFailCount++
                        continue
                    }
                }
            } else if (SoulBot.checkMiracleBell()) {
                log("  未勾选「奇遇铃」，直接关掉弹窗", "info")
            }

            if (!SoulBot.openByName(nm)) {
                log("$nm 打不开，下轮重试", "warn")
                openFailCount++
                continue
            }

            // 打开会话后也可能弹（概率低但见过）—— 这种情况直接关掉，
            // 不要再点进去（已经进过对话了，再进去会乱）
            if (SoulBot.checkMiracleBell()) {
                log("  会话页又弹奇遇铃，关掉后重新打开会话", "info")
                if (!SoulBot.openByName(nm)) continue
            }

            // ★ 进对话页后先等页面稳住，再读消息 / 转写语音
            //
            // 用户反馈（2026-11-25）：
            // 「打开回复界面我觉得可以停2s再识别语音，因为这个页面还会弹出
            //   别的内容导致点击到空白处」
            //
            // 现象：刚进对话页的 1-2 秒里，目标应用 会依次做这些事：
            //   1. 顶部卡片（匹配度/引力签）淡入，布局高度在变
            //   2. 消息列表滚到底
            //   3. 可能弹「送花墙」「开通会员」这类浮层
            // 这期间 dump 出来的节点坐标是**动画中间态**，
            // 拿去 longPress 转写语音就会点到空白处 —— 转写失败，
            // 模型看不到她说的话，回复文不对题。
            //
            // 所以先静置 2 秒，再检查有没有浮层，然后才读。
            SoulBot.settleChatPage()

            val d = SoulBot.readChat(transcribe = true)
            if (d.messages.isEmpty()) {
                log("$nm 读不到消息，下轮重试", "warn")
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
                continue
            }

            // ★ 读对话页顶部卡片上的资料，作为生成回复的素材
            //   （匹配度 / 星球 / ISFP认证 / 引力签 / 共同点）
            val cardProf = SoulBot.readChatCard()
            // ★ 顺手记下她的 IP 属地（给「删除废弃会话」用）
            //   列表页看不到 IP，只能在这里读到时记下来
            if (cardProf.ip.isNotBlank()) {
                recordIp(nm, cardProf.ip)
            }
            if (cardProf.raw.isNotEmpty()) {
                log("  $nm 资料：${cardProf.raw.take(6).joinToString(" ")}" +
                        if (cardProf.match.isNotBlank()) " 匹配${cardProf.match}" else "")
            }

            // ★★★ 聊天记录入库 + 用库里的完整历史做上下文（2026-11-25）★★★
            //
            // 用户要求：
            // 「每次回复或者发起灵魂匹配聊天的时候就记录一份。然后回复的时候
            //   就不会需要再去读一份聊天记录，而是通过自己的聊天记录和最新的消息
            //   回复了(避免记忆缺失或者回复不完整)」
            //
            // ── 原来的硬伤 ──
            // readChat() 只能读到**屏幕当前可见的几条**消息。
            // 长对话早滚出屏幕的内容全丢了 → 模型缺上下文 →
            // 回复重复、跑题、接不上上面说过的话。
            //
            // ── 现在的做法 ──
            //   ① readChat 读当前屏
            //   ② loadHistory 向上翻页读更早的（见下面的新代码）
            //   ③ 全部 upsert 进库（去重）
            //   ④ 从库里取完整历史给模型
            //
            // ★ 2026-11-25：旧的「只存 d.messages」代码已删除，
            //   统一由下面的 loadHistory + saveMessages 处理。

            // ★★★ 向上翻页读完整历史（2026-11-25 用户要求）★★★
            //
            // 「AI 能更智能点吗，就是可以再读完历史会话之后再回话」
            //
            // 现在流程：
            //   ① readChat 读当前屏（一般 8-15 条）
            //   ② loadHistory 向上翻页，把更早的也读进来
            //   ③ 入库
            //   ④ 从库里取完整历史给模型（库比屏幕更全）
            //
            // 只有一种情况才翻页：**她最后一句是新的**（需要我回）。
            // 如果最后一条是我说的（她没回），翻了也白翻，直接跳过。
            val needReply = d.lastSide == "her"
            val fullMsgs: List<com.love.soulmate.platform.Msg> = if (needReply) {
                try {
                    val h = SoulBot.loadHistory(d.messages, maxPages = 6, keep = 60)
                    if (h.size > d.messages.size) {
                        log(
                            "  $nm 翻页读到 ${h.size} 条历史" +
                                    "（当前屏只有 ${d.messages.size} 条）",
                            "info"
                        )
                    }
                    h
                } catch (t: Throwable) {
                    log("  $nm 翻页失败（用当前屏）：${t.message}", "warn")
                    d.messages
                }
            } else {
                d.messages
            }

            val acc = accKey()
            val added = com.love.soulmate.core.Db.saveMessages(
                acc, nm,
                fullMsgs.map { com.love.soulmate.core.Db.Msg(nm, it.side, it.text, 0) }
            )
            if (added > 0) {
                log("  $nm 新增 $added 条聊天记录入库", "info")
            }

            // 人物档案也入库（IP/签名/匹配度等，下次不用重读）
            com.love.soulmate.core.Db.savePerson(
                acc,
                com.love.soulmate.core.Db.Person(
                    name = nm,
                    ip = cardProf.ip,
                    signature = "",
                    planet = cardProf.raw.firstOrNull { it.startsWith("星球:") }
                        ?.removePrefix("星球:") ?: "",
                    matchRate = cardProf.match,
                    days = "",
                )
            )

            // 用库里的完整历史（如果没有就用刚才翻页读到的）
            val histFromDb = com.love.soulmate.core.Db.readMessages(acc, nm, limit = 60)
            val historyMsgs = if (histFromDb.size > fullMsgs.size) {
                log(
                    "  $nm 用库里的 ${histFromDb.size} 条历史" +
                            "（翻页读到 ${fullMsgs.size} 条）",
                    "info"
                )
                histFromDb.map { com.love.soulmate.platform.Msg(it.side, it.text, 0) }
            } else {
                fullMsgs
            }

            if (d.lastSide != "her") {
                // 我最后说的 → 她没回，不用动
                // 顺手把我的话补进记录表（万一之前没记上）
                if (d.lastText.isNotBlank()) recordSentMsg(nm, d.lastText)
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
                continue
            }

            val last = d.lastText.trim()

            // ★★★ 最高价值检查：她有没有约见面 / 要联系方式 ★★★
            //
            // 用户要求（2026-11-24）：
            // 「对方约你吃饭、见面或者要加微信的时候，要发个消息通知下」
            //
            // 这是整个流程唯一的「转化信号」，命中就：
            //   1. 立刻推微信（force=true 绕过节流）
            //   2. **不自动回复**，交给用户决定
            //   3. 在日志里高亮
            //
            // 为什么不能自动回：她提见面是在等你的态度，
            // 机器人回「好呀」会显得敷衍，而且后面要不要见面得你本人定。
            // 用标志位代替 continue（run{} 里不能直接 continue）
            var handledAsInvite = false
            run {
                val msgs = historyMsgs.map { it.side to it.text }
                var signal = com.love.soulmate.llm.InviteDetector.scanHerMessages(msgs)

                // 关键词没命中，但这句话稍长 → 让模型二次判断
                // （覆盖「你说得我都想试试了」这种暗示型）
                if (signal.kind == com.love.soulmate.llm.InviteDetector.Kind.NONE &&
                    last.length >= 8
                ) {
                    try {
                        com.love.soulmate.llm.InviteDetector.askLlm(last)?.let {
                            signal = it
                        }
                    } catch (_: Throwable) {
                    }
                }

                if (signal.kind != com.love.soulmate.llm.InviteDetector.Kind.NONE) {
                    val kindStr = signal.kind.name
                    val label = when (signal.kind) {
                        com.love.soulmate.llm.InviteDetector.Kind.MEET -> "想约见面"
                        com.love.soulmate.llm.InviteDetector.Kind.CONTACT -> "想要联系方式"
                        com.love.soulmate.llm.InviteDetector.Kind.HINT -> "在探你时间/位置"
                        else -> ""
                    }

                    // 高价值（约会/联系方式）→ 高亮日志 + 推送 + 不自动回
                    if (signal.isHighValue) {
                        log("🔥🔥 $nm $label：「${signal.trigger}」", "sent")
                        log("    已推送通知，这条不自动回复，等你决定", "info")

                        val recent = d.messages.takeLast(6)
                            .joinToString("\n") {
                                "${if (it.side == "me") "我" else "她"}：${it.text}"
                            }

                        if (!dryRun) {
                            com.love.soulmate.core.Pusher.alertInvite(
                                ctx = ctx,
                                who = nm,
                                kind = kindStr,
                                trigger = signal.trigger,
                                note = signal.note,
                                recentChat = recent,
                            )
                        } else {
                            log("    [演练] 会推送邀约通知", "info")
                        }

                        // 记录成「已处理」，避免下轮又推一遍
                        recordSentMsg(nm, last)
                        inviteCount++
                        handledAsInvite = true
                    } else {
                        // HINT 级别：只记录，仍自动回复（因为还没明说）
                        log("  $nm $label：「${signal.trigger}」（继续自动回）", "info")
                    }
                }
            }

            if (handledAsInvite) {
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
                continue
            }
            if (Replier.isCold(last)) {
                log("$nm 冷淡词「$last」，跳过", "info")
                markSkipped(nm, row.preview)
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
                continue
            }
            if (Replier.isSoftEnd(last)) {
                log("$nm 软收尾「${last.take(16)}」，跳过", "info")
                markSkipped(nm, row.preview)
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
                continue
            }

            // 属地校验：优先用卡片上的「共同点」（如「你们的共同点：上海」），
            // 没有才去资料页读 IP（那次跳转很慢，能省则省）
            var prof = cardProf
            if (prof.ip.isBlank()) {
                val p2 = SoulBot.readProfile()
                if (p2.ip.isNotBlank()) {
                    prof = prof.copy(
                        ip = p2.ip,
                        raw = (prof.raw + p2.raw).distinct(),
                        match = prof.match.ifBlank { p2.match }
                    )
                }
                if (!SoulBot.openByName(nm)) {
                    log("$nm 重开失败", "warn")
                    continue
                }
            }

            if (prof.ip.isNotBlank() && !Replier.ipAllowed(prof.ip)) {
                log("$nm IP ${prof.ip} 不在江浙沪，跳过", "info")
                markSkipped(nm, row.preview)
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
                continue
            }

            // 生成
            var text = Replier.generate(historyMsgs, prof, nm)
            if (text == null) {
                log("$nm 生成失败", "warn")
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
                continue
            }

            // 她说很短时不许纯提问
            if (last.length <= 3 && Replier.isPureQuestion(text)) {
                val t2 = Replier.generate(historyMsgs, prof, nm)
                if (t2 != null && !Replier.isPureQuestion(t2)) {
                    text = t2
                } else {
                    log("$nm 她只说${last.length}字，我的回复是纯提问，放弃", "warn")
                    SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
                    continue
                }
            }

            if (dryRun) {
                log("$nm [演练] 会回：$text", "info")
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
                continue
            }

            val rate = checkRate(user, nm)
            if (rate != null) {
                log("$nm 限流跳过（$rate）", "warn")
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
                continue
            }

            val sr = SoulBot.send(text)
            if (sr.ok) {
                recordSentMsg(nm, text)
                recordSend(user, nm)
                sent++
                log("u$user $nm 说「${last.take(18)}」→ 回「$text」", "sent")
            } else {
                log("$nm 发送失败（${sr.error}）", "error")
                // ★ 发送失败是必须人介入的（可能卡输入法/界面变了/被风控）
                com.love.soulmate.core.Pusher.alertSendFailed(ctx, nm, sr.error, text)
                sendFailCount++
            }

            Thread.sleep(2500)
            SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
        }

        // 记下当前数据文件的 mtime，下次检查时用它判断「有没有新消息」
        com.love.soulmate.soul.DirtyCheck.markChecked(ctx)

        // ★ 本轮结果汇总告警
        //
        // 只在「明显不对」时推，避免刷屏：
        //   · 有会话要处理，但一个都没成功（全打不开 / 全发送失败）
        //   · 连续大量打不开（目标应用界面结构可能变了）
        if (!dryRun) {
            when {
                todo.isNotEmpty() && sent == 0 && sendFailCount == 0 && openFailCount == todo.size -> {
                    log("本轮 ${todo.size} 个会话全部打不开，推送告警", "warn")
                    com.love.soulmate.core.Pusher.alert(
                        ctx,
                        "所有会话都打不开",
                        """
                        |本轮要处理 ${todo.size} 个会话，但一个都打不开。
                        |
                        |可能情况：
                        |· 目标应用界面结构变了（按钮/列表 ID 改了）
                        |· Soul 卡在某个弹窗里
                        |· 登录态失效
                        |
                        |建议：打开 Soul 看一眼，或点一次 App 的「演练」看日志
                        """.trimMargin()
                    )
                }

                sent == 0 && sendFailCount > 0 -> {
                    log("本轮 ${sendFailCount} 次发送全失败，已告警", "warn")
                    com.love.soulmate.core.Pusher.alertAllFailed(
                        ctx, 1, "$sendFailCount 次发送失败"
                    )
                }
            }
        }

        _state.value = _state.value.copy(
            sentThisHour = loadSendLog().count {
                System.currentTimeMillis() - it.at < 3600_000
            }
        )
        return sent
    }

    /** 由 UI 层注入：把 App 压到后台 / 拉回前台 */
    var moveToBack: (() -> Unit)? = null
    var bringToFront: (() -> Unit)? = null

    /** 当前勾选的平台。默认只有 目标应用 */
    private var enabledPlatforms: Set<String> = setOf("soul")

    /**
     * 功能开关（用户要求 2026-11-24：每个功能独立勾选，没勾就不跑）。
     */
    @Volatile
    var features: Features = Features()

    /**
     * 只看列表顶部的 N 个会话。
     *
     * 依据：聊天列表按时间倒序，顶部是最近活跃的，后面基本断联。
     * 实测扫全部 49 个约 25 分钟，只看顶部 8 个约 4 分钟。
     */
    @Volatile
    var topN: Int = 8

    /**
     * 强制扫描，忽略「数据没变化就跳过」的优化。
     * 用户手动点「立即检查」时应该置 true（他点了就是想跑）。
     */
    @Volatile
    var forceScan: Boolean = false

    // ---------- 同城主动私聊 ----------

    /**
     * 同城私聊目标人数 —— 快捷读写 [features] 里的字段。
     *
     * 保留这个属性是为了兼容旧调用点；新代码请直接用 features.squareProactive。
     */
    var proactiveCount: Int
        get() = features.proactiveCount
        set(v) {
            features = features.copy(proactiveCount = v, squareProactive = v > 0)
        }

    /** 运行时把配置「锁住」——开始跑之后 UI 不再允许改 */
    @Volatile
    private var configLocked: Boolean = false

    fun isConfigLocked(): Boolean = configLocked

    // ---------- 停止机制 ----------

    /**
     * 停止标志。用户点「停止」时置 true，各步骤会检查它并尽快退出。
     *
     * 为什么用 @Volatile 而不是 cancel coroutine：
     *   流程里大量 Thread.sleep 和 Shell 调用，协程取消不穿透这些阻塞点，
     *   实测点了停止后还要等好几分钟。用标志位在每个关键节点主动检查更可靠。
     */
    @Volatile
    private var stopRequested: Boolean = false

    fun isStopRequested(): Boolean = stopRequested

    /** 用户点「停止」 */
    fun requestStop() {
        stopRequested = true
        log("收到停止请求，正在收尾…", "warn")
    }

    /**
     * 在流程各节点调用：如果用户要停，就在这里抛出去。
     * 用异常而不是 return，能一次跳出多层嵌套。
     */
    private fun checkStop() {
        if (stopRequested) throw StopSignal()
    }

    /**
     * 找出「废弃会话」。
     *
     * ── 用户规则（2026-11-25）──
     * 「删除的规则是超过3天没有新的聊天，然后不在我选择的地区」
     *
     * **两个条件必须同时满足**才删：
     *   ① 超过 2 天没有新聊天
     *   ② IP 属地不在用户选的地区里
     *
     * ── ① 「超过3天」怎么判断 ──
     * 聊天列表的 time 只有「09:38」和「昨天」两种，看不出 3 天。
     * 所以用**自己的发送时间戳**（SentMsg.at）：我最后一次发消息超过 3 天
     * → 说明这段对话已经冷了。
     *
     * 但「我发过消息」不等于「她后来没回」。所以还要确认
     * 列表预览还是我那句（她没回）—— 两个都满足才算冷掉。
     *
     * ── ② 「不在选定地区」怎么判断 ──
     * 列表页没有 IP。用**之前筛人时记录下的 IP**（ipRecords）。
     * 没记录过的（比如很早就聊过、在我加这个功能之前）一律**不删** ——
     * 宁可留着，不要误删。
     *
     * @return 可以删除的会话名列表
     */
    private fun collectDeadChats(): List<String> {
        return try {
            // 全量扫（原来只扫 2 屏，66 个会话根本覆盖不到）
            val chats = SoulBot.collectAllChats()
            val sentMsgs = loadSentMsgs()
            val ipRecords = loadIpRecords()
            val allowed = features.regions
            val now = System.currentTimeMillis()
            // 用户要求 2026-11-25：从 3 天改成 2 天
            val staleMs = 2L * 24 * 3600_000

            val norm = { s: String ->
                s.replace(Regex("""\s+"""), "").trimEnd('.', '…', '。')
            }

            chats.filter { c ->
                // ① 我发过消息，且超过 3 天
                val rec = sentMsgs[c.name] ?: return@filter false
                if (now - rec.at < staleMs) return@filter false

                // ①b 列表预览还是我那句 → 她确实没回（否则说明有新消息，不删）
                val a = norm(rec.text)
                val b = norm(c.preview)
                val sheReplied = !(a.isNotBlank() && b.isNotBlank() &&
                        (b.startsWith(a.take(12)) || a.startsWith(b.take(12))))
                if (sheReplied) return@filter false

                // ② IP 不在选定地区
                val ip = ipRecords[c.name] ?: return@filter false   // 没记录不删
                if (ip.isBlank()) return@filter false
                // 不在白名单里 → 可以删
                allowed.none { ip.contains(it) }
            }.map { it.name }
        } catch (s: StopSignal) {
            throw s
        } catch (t: Throwable) {
            log("  扫废弃会话出错：${t.message}", "warn")
            emptyList()
        }
    }

    /**
     * 处理「收到的新招呼」。
     *
     * ── 用户要求（2026-11-25）──
     * 「收到的新招呼你也处理下，里面也会开启私聊」
     *
     * ── 为什么这批人值得优先回 ──
     * 他们是**主动来打招呼**的，回复意愿最强（比同城广场陌生搭话高得多）。
     * 而且 目标应用 把规则写在页面上：
     *   「发消息后对话框将会被放出哦」
     * 也就是在这里点进去发一条，就直接建立了私聊会话。
     *
     * ── 页面结构（真机 MEIZU 21 Pro 实测）──
     * 聊天列表顶部 tv_greeting_title「收到的新招呼」
     *   → NewGreetingDetailActivity
     *      rv_new_greeting_list / list_itease_layout
     *        name / tv_from「匹配」/ time / message（招呼语）/ avatar
     *   → 点某人 → ConversationActivity（标准对话页，有 et_sendmessage）
     *
     * ── 和奇遇铃的区别 ──
     * 奇遇铃是弹窗、一次一个；这里是列表，能批量处理。
     * 每处理完一个都要退回主界面再重进招呼页，否则后面流程会找不到聊天列表。
     */
    private fun runGreetings(user: Int, dryRun: Boolean): Int {
        log("u$user 检查「收到的新招呼」…", "info")

        if (!SoulBot.openNewGreetingsPage()) {
            log("  进不去「收到的新招呼」页面（可能今天没有新招呼）", "info")
            SoulBot.gotoMain()
            SoulBot.tapChatTab()
            Thread.sleep(1200)
            return 0
        }

        val greetings = try {
            SoulBot.readNewGreetings()
        } catch (s: StopSignal) {
            throw s
        } catch (t: Throwable) {
            log("  读新招呼列表失败：${t.message}", "warn")
            emptyList()
        }

        if (greetings.isEmpty()) {
            log("  新招呼列表是空的", "info")
            SoulBot.gotoMain()
            SoulBot.tapChatTab()
            Thread.sleep(1200)
            return 0
        }

        log(
            "  新招呼 ${greetings.size} 个：" +
                    greetings.joinToString("、") { it.name }.take(120),
            "info"
        )

        val sentMsgs = loadSentMsgs()
        var sent = 0
        var tried = 0
        var skipped = 0

        // 超时保护：招呼列表最多处理 6 分钟
        val startAt = System.currentTimeMillis()
        // 原来 6 分钟，但招呼多时跑不完（每个约 25 秒，6 分钟只够 14 个）
        // 用户要求去掉不合理限制 → 放宽到 20 分钟
        val maxMs = 20 * 60_000L

        for (g in greetings) {
            checkStop()

            if (System.currentTimeMillis() - startAt > maxMs) {
                log("  新招呼处理超时（6 分钟），收尾", "warn")
                break
            }

            // ① 排除规则（名字/招呼语里带「已婚」「带娃」等）
            val v = com.love.soulmate.soul.Excluder.check(
                name = g.name,
                tags = emptyList(),
                post = g.message,
            )
            if (v.exclude) {
                log("    ${g.name} 跳过：${v.reason}", "info")
                skipped++
                continue
            }

            // ② 之前聊过就不重复
            if (sentMsgs.containsKey(g.name)) {
                log("    ${g.name} 之前聊过了，跳过", "info")
                skipped++
                continue
            }

            // ③ 限流
            val rate = checkRate(user, g.name)
            if (rate != null) {
                log("    ${g.name} 限流（$rate），本轮新招呼结束", "warn")
                break
            }

            tried++

            // ④ 点进去（= 开启私聊）
            val detail = try {
                SoulBot.openGreetingChat(g)
            } catch (s: StopSignal) {
                throw s
            } catch (t: Throwable) {
                log("    ${g.name} 进不去对话：${t.message}", "warn")
                null
            }

            if (detail == null) {
                log("    ${g.name} 进不去对话，跳过", "warn")
                skipped++
                if (!SoulBot.openNewGreetingsPage()) break
                continue
            }

            // ⑤ 读资料卡片
            val prof = try {
                SoulBot.readChatCard()
            } catch (_: Throwable) {
                Profile("", emptyList(), "")
            }
            // 顺手记 IP（给「删除废弃会话」用）
            if (prof.ip.isNotBlank()) recordIp(g.name, prof.ip)

            val v2 = com.love.soulmate.soul.Excluder.check(
                name = g.name,
                tags = prof.raw,
                post = g.message,
            )
            if (v2.exclude) {
                log("    ${g.name} 跳过：${v2.reason}", "info")
                skipped++
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1200)
                if (!SoulBot.openNewGreetingsPage()) break
                continue
            }

            // ⑥ 生成回复
            //
            // 她是主动打招呼的，但招呼语往往是系统模板
            // （如「[樱花]和今日份奇遇打个招呼吧」），
            // 所以这里**当开场白处理**：用她主页/卡片素材起话头，
            // 而不是机械地回那句模板话。
            val opener = Replier.generateOpener(
                name = g.name,
                profile = prof,
                postContent = g.message,
            )
            if (opener == null) {
                log("    ${g.name} 生成失败", "warn")
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1200)
                if (!SoulBot.openNewGreetingsPage()) break
                continue
            }

            if (dryRun) {
                log("    ${g.name} [演练] 会发：$opener", "info")
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1200)
                if (!SoulBot.openNewGreetingsPage()) break
                continue
            }

            // ⑦ 发送前确认还在对话页（生成花了几十秒，页面可能跑了）
            if (!SoulBot.prepareToSend()) {
                log("    ${g.name} 发送前不在对话页，跳过", "warn")
                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1200)
                if (!SoulBot.openNewGreetingsPage()) break
                continue
            }

            val sr = SoulBot.send(opener)
            if (sr.ok) {
                recordSentMsg(g.name, opener)
                recordSend(user, g.name)
                sent++
                log("u$user 新招呼 ${g.name} → $opener", "sent")
            } else {
                log("    ${g.name} 发送失败（${sr.error}）", "error")
                com.love.soulmate.core.Pusher.alertSendFailed(ctx, g.name, sr.error, opener)
            }

            // ⑧ 回主界面 → 重新进招呼页继续下一个
            SoulBot.gotoMain()
            SoulBot.tapChatTab()
            Thread.sleep(1200)
            if (!SoulBot.openNewGreetingsPage()) {
                log("  重进招呼页失败，本批结束", "warn")
                break
            }

            Thread.sleep(GLOBAL_GAP)
        }

        log(
            "u$user 新招呼处理完：发出 $sent，试了 $tried，跳过 $skipped",
            "info"
        )

        SoulBot.gotoMain()
        SoulBot.tapChatTab()
        Thread.sleep(1500)
        return sent
    }

    class StopSignal : RuntimeException("user requested stop")

    fun setPlatforms(ids: Set<String>) {
        // 兜底：无论外部传什么，只保留已实现的
        enabledPlatforms = com.love.soulmate.platform.Platform.sanitize(ids)
    }

    /**
     * 奇遇铃搭话：读资料 → 生成开场白 → 发送。
     *
     * 由 [目标应用Bot.handleMiracleBell] 在进入对话页后回调。
     * 这里不负责导航（进/退都由 目标应用Bot 管），只管「说什么」。
     *
     * @param bellName 对话对象昵称
     * @param onSent   发送成功后的回调（用来给外层 sent 计数 +1）
     */
    private fun handleBellGreeting(
        bellName: String,
        user: Int,
        dryRun: Boolean,
        onSent: () -> Unit,
    ) {
        // 已经聊过就不重复开场
        val sentMsgs = loadSentMsgs()
        if (sentMsgs.containsKey(bellName)) {
            log("    奇遇铃 $bellName 之前聊过，跳过", "info")
            return
        }

        // 读对话，如果她已经说话了就直接回复，否则自己开场
        val d = try {
            SoulBot.readChat(transcribe = false)
        } catch (_: Throwable) {
            null
        }

        val prof = try {
            SoulBot.readChatCard()
        } catch (_: Throwable) {
            Profile("", emptyList(), "")
        }
        if (prof.ip.isNotBlank()) recordIp(bellName, prof.ip)

        val text = if (d != null && d.lastSide == "her" && d.lastText.isNotBlank()) {
            // 她先开口了 → 正常回复
            Replier.generate(d.messages, prof, bellName)
        } else {
            // 她还没说话 → 主动开场
            Replier.generateOpener(
                name = bellName,
                profile = prof,
                postContent = prof.raw.joinToString(" "),
            )
        }

        if (text == null) {
            log("    奇遇铃 $bellName 生成失败", "warn")
            return
        }

        if (dryRun) {
            log("    奇遇铃 $bellName [演练] 会发：$text", "info")
            return
        }

        val rate = checkRate(user, bellName)
        if (rate != null) {
            log("    奇遇铃 $bellName 限流（$rate）", "warn")
            return
        }

        val sr = SoulBot.send(text)
        if (sr.ok) {
            recordSentMsg(bellName, text)
            recordSend(user, bellName)
            onSent()
            log("u$user 奇遇铃 $bellName → $text", "sent")
        } else {
            log("    奇遇铃 $bellName 发送失败（${sr.error}）", "error")
            com.love.soulmate.core.Pusher.alertSendFailed(ctx, bellName, sr.error, text)
        }
    }

    /**
     * 同城主动私聊。
     *
     * 用户要求（2026-11-24）：
     *   「同城主动私聊 15 个，配置到 soul 选项旁边，开始跑之后不能再修改」
     *
     * ── 流程 ──
     *   1. 广场 → 同城标签
     *   2. 读当前屏帖子（昵称/正文/IP/私聊按钮）
     *   3. Excluder 筛掉：不聊天、已婚带娃、找搭子、年龄不符
     *   4. 点私聊 → 读卡片资料 → 生成开场白 → 发送
     *   5. 发够了 / 滚到底 / 用户停止 就结束
     *
     * ── 风控 ──
     *   · 每次间隔至少 GLOBAL_GAP（20 秒）
     *   · 每天上限由调用方（proactiveCount）控制
     *   · 已私聊过的人记在 sent_msg 里，不重复
     *
     * @return 实际发出条数
     */
    private fun runProactive(user: Int, limit: Int, dryRun: Boolean): Int {
        log("u$user 开始同城主动私聊，目标 $limit 个", "info")

        _state.value = _state.value.copy(phase = "proactive")
        var sent = 0
        var tried = 0
        var skipped = 0
        var rounds = 0
        val seenNames = HashSet<String>()

        // ★ 整段超时保护（2026-11-24 加的）
        //
        // 踩坑：送花墙堵住每个帖子都要浪费 40 秒然后失败，
        // 12 屏 × 4 帖 = 48 个 × 40s = 30 分钟，把整个监控轮次拖死。
        //
        // 现在限制整段最多 8 分钟，超了就收尾。
        val startAt = System.currentTimeMillis()
        // 原来 8 分钟，同理放宽到 20 分钟
        val maxDurationMs = 20 * 60_000L

        // 连续失败保护：好几次进不去对话就说明这条路不通（送花墙），直接放弃
        var enterFailStreak = 0
        val maxEnterFailStreak = 3

        if (!com.love.soulmate.soul.Square.gotoSquare()) {
            log("  进不去同城广场，尝试强制恢复…", "warn")
            if (!com.love.soulmate.soul.Square.forceBackToSquare()) {
                log("  回不到广场，跳过主动私聊", "warn")
                return 0
            }
        }

        val sentMsgs = loadSentMsgs()
        // 连续「读不到帖子」的次数（用来判断是不是已经不在广场了）
        var emptyRounds = 0

        while (sent < limit && rounds < 12) {
            checkStop()

            // 超时 / 连续失败 → 收尾
            if (System.currentTimeMillis() - startAt > maxDurationMs) {
                log("  同城私聊超时（8 分钟），本轮结束", "warn")
                break
            }
            if (enterFailStreak >= maxEnterFailStreak) {
                log(
                    "  连续 $enterFailStreak 个都进不去对话（送花墙拦截），放弃本轮同城",
                    "warn"
                )
                log(
                    "  提示：目标应用对「没发过消息就想私聊陌生人」有硬拦截，" +
                            "需要先送花解锁，或改用奇遇铃/匹配来的对话",
                    "info"
                )
                break
            }

            rounds++

            // ★ 每屏开头先看有没有奇遇铃：有就点进去搭话，完了再回广场
            //   （只在勾选「奇遇铃」时进；没勾就关掉弹窗）
            if (features.miracleBell) {
                if (SoulBot.handleMiracleBell { bellName ->
                        handleBellGreeting(bellName, user, dryRun) { sent++ }
                    }) {
                    log("  处理完奇遇铃，回到广场继续", "info")
                    if (!com.love.soulmate.soul.Square.gotoSquare()) {
                        log("  回不到广场，本轮同城结束", "warn")
                        break
                    }
                }
            } else if (SoulBot.checkMiracleBell()) {
                log("  未勾选「奇遇铃」，关掉弹窗继续", "info")
            }

            val posts = try {
                com.love.soulmate.soul.Square.readPosts()
            } catch (s: StopSignal) {
                throw s
            } catch (t: Throwable) {
                log("  读帖子失败：${t.message}", "warn")
                emptyList()
            }

            if (posts.isEmpty()) {
                emptyRounds++
                log("  当前屏没读到帖子（第 $emptyRounds 次），滚一屏再看", "info")

                // ★ 连续读不到就说明不在广场了，别再空滚
                if (emptyRounds >= 3) {
                    log("  连续 $emptyRounds 次读不到帖子，强制回广场", "warn")
                    if (!com.love.soulmate.soul.Square.forceBackToSquare()) {
                        log("  回不到广场，本轮同城结束", "warn")
                        break
                    }
                    emptyRounds = 0
                    continue
                }

                com.love.soulmate.soul.Square.scrollNext()
                continue
            }
            emptyRounds = 0

            log("  第 $rounds 屏：${posts.size} 条帖子", "info")

            for (p in posts) {
                if (sent >= limit) break
                checkStop()
                if (p.name in seenNames) continue
                seenNames.add(p.name)

                // ① 筛人
                val verdict = com.love.soulmate.soul.Excluder.check(
                    name = p.name,
                    tags = emptyList(),
                    post = p.content,
                )
                if (verdict.exclude) {
                    log("    ${p.name} 跳过：${verdict.reason}", "info")
                    skipped++
                    continue
                }

                // ①.5 帖子太短、没内容可接 → 跳过
                //     实测有人只发「哈喽」「？」两三个字，
                //     这种硬搭话只能写出废话，不如不搭。
                //
                //     纯图帖（content 为空但 pureImage=true）也跳过 ——
                //     没有文字素材，模型只能瞎猜，不如等她说点什么。
                val bodyLen = p.content.trim().length
                if (p.pureImage || bodyLen < 8) {
                    log(
                        "    ${p.name} 跳过：" +
                                if (p.pureImage) "纯图帖（无文字）" else "帖子太短（$bodyLen 字）",
                        "info"
                    )
                    skipped++
                    continue
                }

                // ② IP 属地：必须上海或江浙沪
                if (p.ip.isNotBlank() && !Replier.ipAllowed(p.ip)) {
                    log("    ${p.name} 跳过：IP ${p.ip} 不在江浙沪", "info")
                    skipped++
                    continue
                }

                // ②.5 人像审美筛选（用户要求 2026-11-24）
                //
                // 「如果空间有人像图的话，我希望你能识别下人像，
                //   我喜欢的女孩子是比较瘦，然后图片的美颜不要太严重，
                //   长相比较可爱的」
                //
                // 有配图才评（没图的跳过这一步）。
                // 评分维度：瘦 40% + 自然(美颜轻) 35% + 可爱 25%，60 分及格。
                //
                // 代价：每张图多花 3-5 秒。
                // 所以只在「有配图」时评，而且一旦判定不合格立刻跳过，
                // 省掉后面读资料 + 生成开场白的时间（那要 20-40 秒）。
                if (features.portraitFilter && p.imageBottom > p.imageTop) {
                    val ps = try {
                        com.love.soulmate.llm.Portrait.evaluateRegion(
                            intArrayOf(0, p.imageTop, com.love.soulmate.core.Screen.width(), p.imageBottom)
                        )
                    } catch (_: Throwable) {
                        null
                    }

                    if (ps != null && ps.isPortrait) {
                        log(
                            "    ${p.name} 人像：${com.love.soulmate.llm.Portrait.describeScore(ps)}" +
                                    "「${ps.reason.take(24)}」",
                            "info"
                        )
                        if (!com.love.soulmate.llm.Portrait.passes(ps)) {
                            log("    ${p.name} 跳过：人像不符合偏好", "info")
                            skipped++
                            continue
                        }
                    } else if (ps != null && !ps.isPortrait) {
                        // 不是人像（风景/食物等）→ 不因颜值筛掉，继续走文字
                        log("    ${p.name} 配图非人像，忽略颜值筛选", "info")
                    }
                }

                // ③ 已经私聊过（记录里有她）
                if (sentMsgs.containsKey(p.name)) {
                    log("    ${p.name} 已经聊过了，跳过", "info")
                    continue
                }

                // ④ 进去
                tried++
                if (!com.love.soulmate.soul.Square.openChat(p)) {
                    enterFailStreak++
                    log(
                        "    ${p.name} 进不去对话（连续第 $enterFailStreak 次），强制回广场",
                        "warn"
                    )
                    // ★ 关键：点 ivChat 失败后页面可能停在各种奇怪状态，
                    //   必须强行回到广场，否则后面会连续「读不到帖子」空转
                    if (!com.love.soulmate.soul.Square.forceBackToSquare()) {
                        log("  回不到广场，本轮同城结束", "warn")
                        break
                    }
                    continue
                }
                enterFailStreak = 0   // 成功进去就重置

                // ⑤ 读资料（卡片上有认证/引力签/共同点）
                val prof = try {
                    SoulBot.readChatCard()
                } catch (_: Throwable) {
                    Profile("", emptyList(), "")
                }
                if (prof.ip.isNotBlank()) recordIp(p.name, prof.ip)

                // 卡片资料也过一遍排除规则（已婚带娃常在引力签里）
                val v2 = com.love.soulmate.soul.Excluder.check(
                    name = p.name,
                    tags = prof.raw,
                    post = p.content,
                )
                if (v2.exclude) {
                    log("    ${p.name} 跳过：${v2.reason}", "info")
                    skipped++
                    SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1200)
                    continue
                }

                // ⑥ 生成开场白
                //    同城帖子是「从她的帖子来的」，所以要把帖子内容给模型
                val opener = Replier.generateOpener(
                    name = p.name,
                    profile = prof,
                    postContent = p.content,
                )
                if (opener == null) {
                    log("    ${p.name} 开场白生成失败", "warn")
                    SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1200)
                    continue
                }

                if (dryRun) {
                    log("    ${p.name} [演练] 开场白：$opener", "info")
                    SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1500)
                    continue
                }

                // ⑦ 限流
                val rate = checkRate(user, p.name)
                if (rate != null) {
                    log("    ${p.name} 限流（$rate），本轮结束主动私聊", "warn")
                    SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1200)
                    return sent
                }

                // ⑧ 发送
                //
                // ★ 踩坑（2026-11-24，同城批量 no_input 的真正根因）
                //
                // 时间线：
                //   openChat 成功进对话页（1 秒内）
                //   → readChatCard() 花 5-10 秒
                //   → generateOpener() 调模型花 10-30 秒
                //   → 这期间送花墙又弹出来，把对话页关了
                //   → send() 找不到 et_sendmessage → no_input
                //
                // 日志证据：每次 no_input 前都有「关掉送花墙，当前 MainActivity」，
                // 说明关弹窗时页面已经不在对话页了。
                //
                // 修法：发送前重新确认在对话页，不在就重进一次。
                var sendTarget = opener
                var ready = SoulBot.prepareToSend()
                if (!ready) {
                    log("    发送前不在对话页，重进一次", "warn")
                    if (com.love.soulmate.soul.Square.openChat(p)) {
                        ready = SoulBot.prepareToSend()
                    }
                }
                if (!ready) {
                    log("    ${p.name} 重进仍未就绪，跳过", "error")
                    SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1200)
                    if (!com.love.soulmate.soul.Square.gotoSquare()) break
                    continue
                }

                val sr = SoulBot.send(sendTarget)
                if (sr.ok) {
                    recordSentMsg(p.name, opener)
                    recordSend(user, p.name)
                    sent++
                    log("u$user 同城 $p.name → $opener", "sent")
                } else {
                    log("    ${p.name} 发送失败（${sr.error}）", "error")
                }

                SoulBot.gotoMain(); SoulBot.tapChatTab(); Thread.sleep(1200)
                if (!com.love.soulmate.soul.Square.gotoSquare()) break

                // ⑨ 间隔
                Thread.sleep(GLOBAL_GAP)
            }

            if (sent >= limit) break
            checkStop()
            com.love.soulmate.soul.Square.scrollNext()
        }

        log(
            "u$user 同城私聊结束：发出 $sent / $limit，" +
                    "试了 $tried 个，跳过 $skipped 个",
            "info"
        )
        return sent
    }

    // ==================================================================
    //  同城广场「帖子评论」
    // ==================================================================

    /**
     * 在同城广场的帖子下逐个发评论。
     *
     * ── 为什么值得单独跑 ──
     * 私聊陌生人要过「送花开聊」的墙（见 [runProactive] 里那条 20 分钟的教训），
     * 而评论**实测没有墙**：在陌生人帖子下直接评论一次成功，
     * 评论数 29 → 30，全程零付费弹窗。
     *
     * 所以同城拓客的正确顺序是「先评论（免费、无墙、公开曝光），
     * 再私聊（有墙、要送花）」。
     *
     * ── 流程 ──
     *   切广场 → readPosts → 逐条：生成评论 → 点评论入口 → 粘贴 → 发送
     *   → 回广场 → 下一条
     *
     * ── 三道保护（照抄 runProactive 的经验）──
     *   ① 整段超时：8 分钟。评论单条约 20-40 秒（含 LLM），
     *      5 条正常 3 分钟内跑完；超时说明卡在弹窗/回不到广场了。
     *   ② 连续失败 3 次即放弃：说明这条路当前不通（被风控 / 页面结构变了），
     *      继续试只会烧时间。
     *   ③ 每条之间 GLOBAL_GAP 间隔，和私聊共用同一套风控节奏。
     *
     * @param user   当前 目标应用 用户空间（仅用于日志，单空间运行）
     * @param limit  本轮最多发几条评论
     * @param dryRun 演练：只生成并打印，不进入详情页、不发送
     * @return 实际发出的评论条数
     */
    fun runSquareComments(user: Int, limit: Int, dryRun: Boolean): Int {
        log("u$user 开始同城帖子评论，目标 $limit 条", "info")

        _state.value = _state.value.copy(phase = "square_comment")

        var done = 0          // 成功发出
        var tried = 0         // 尝试过（含失败）
        var skipped = 0       // 主动跳过（素材不足/地区不符）
        var rounds = 0        // 滚屏轮次
        val seen = HashSet<String>()   // 按「昵称+正文前 20 字」去重

        val startAt = System.currentTimeMillis()
        // 8 分钟够跑 5 条（每条 20-40 秒）；超了就是卡住了
        val maxDurationMs = 8 * 60_000L

        // 连续失败保护：连 3 个发不出去 → 大概率被拦或页面结构变了
        var failStreak = 0
        val maxFailStreak = 3

        // ① 先去广场
        if (!com.love.soulmate.soul.Square.gotoSquare()) {
            log("  进不去同城广场，尝试强制恢复…", "warn")
            if (!com.love.soulmate.soul.Square.forceBackToSquare()) {
                log("  回不到广场，跳过帖子评论", "warn")
                return 0
            }
        }

        var emptyRounds = 0

        while (done < limit && rounds < 10) {
            checkStop()

            if (System.currentTimeMillis() - startAt > maxDurationMs) {
                log("  帖子评论超时（8 分钟），本轮结束", "warn")
                break
            }
            if (failStreak >= maxFailStreak) {
                log("  连续 $failStreak 条评论发不出去，放弃本轮", "warn")
                log("  可能原因：评论区被风控限制 / 页面结构变化 / 账号需要验证", "info")
                break
            }

            rounds++

            val posts = try {
                com.love.soulmate.soul.Square.readPosts()
            } catch (s: StopSignal) {
                throw s
            } catch (t: Throwable) {
                log("  读帖子失败：${t.message}", "warn")
                emptyList()
            }

            if (posts.isEmpty()) {
                emptyRounds++
                log("  当前屏没读到帖子（第 $emptyRounds 次），滚一屏再看", "info")
                if (emptyRounds >= 3) {
                    log("  连续 $emptyRounds 次读不到帖子，强制回广场", "warn")
                    if (!com.love.soulmate.soul.Square.forceBackToSquare()) {
                        log("  回不到广场，本轮结束", "warn")
                        break
                    }
                    emptyRounds = 0
                    continue
                }
                com.love.soulmate.soul.Square.scrollNext()
                continue
            }
            emptyRounds = 0
            log("  第 $rounds 屏：${posts.size} 条帖子", "info")

            for (p in posts) {
                if (done >= limit) break
                checkStop()

                // 去重：同一个人可能发多条帖子，也可能滚回去又看到
                val key = p.name + "|" + p.content.take(20)
                if (key in seen) continue
                seen.add(key)

                // ② 地区过滤：和私聊共用同一套「江浙沪」规则
                if (p.ip.isNotBlank() && !Replier.ipAllowed(p.ip)) {
                    log("    ${p.name} 跳过：IP ${p.ip} 不在江浙沪", "info")
                    skipped++
                    continue
                }

                // ③ 纯图帖评不了：没有文字素材，模型只能瞎猜，
                //    出来的评论和内容对不上，一眼就是机器人。
                if (p.pureImage || p.content.trim().length < 8) {
                    log(
                        "    ${p.name} 跳过：" +
                                if (p.pureImage) "纯图帖（无文字）"
                                else "帖子太短（${p.content.trim().length} 字）",
                        "info"
                    )
                    skipped++
                    continue
                }

                // ④ 生成评论
                val text = com.love.soulmate.llm.CommentWriter.generate(
                    postContent = p.content,
                    authorName = p.name,
                    ip = p.ip,
                )
                if (text == null) {
                    log("    ${p.name} 评论生成失败（素材不足或模型无输出），跳过", "warn")
                    skipped++
                    continue
                }

                tried++

                // ⑤ 演练：只打印
                if (dryRun) {
                    log("    ${p.name} [演练] 评论：$text", "info")
                    done++
                    continue
                }

                // ⑥ 真发
                val r = com.love.soulmate.soul.SquareComment.comment(p, text)
                if (r.ok) {
                    done++
                    failStreak = 0
                    recordSend(user, p.name)
                    log("u$user 同城评论 ${p.name} → $text", "sent")
                } else {
                    failStreak++
                    log("    ${p.name} 评论失败（${r.error}），连续第 $failStreak 次", "error")
                }

                // ⑦ 不管成功失败都先回到广场，否则下一条没法定位
                if (!com.love.soulmate.soul.SquareComment.backToSquare()) {
                    log("  回不到广场，本轮结束", "warn")
                    return done
                }

                // ⑧ 间隔（和私聊共用节奏）
                if (done < limit) Thread.sleep(GLOBAL_GAP)
            }

            if (done >= limit) break
            checkStop()
            com.love.soulmate.soul.Square.scrollNext()
        }

        log(
            "u$user 同城评论结束：发出 $done / $limit，" +
                    "试了 $tried 条，跳过 $skipped 条",
            "info"
        )
        return done
    }

    fun runOnce(dryRun: Boolean = false) {
        // 重置停止标志（上一轮可能停过）
        stopRequested = false
        // 锁住配置：开始跑之后不允许改（用户要求）
        configLocked = true
        // 把停止检查注入 目标应用Bot，让它内部的扫描循环也能被打断
        SoulBot.stopCheck = { stopRequested }

        _state.value = _state.value.copy(running = true)
        try {
            moveToBack?.invoke()

            // 等 App 真的退到后台再继续。
            // ★ 踩坑：moveTaskToBack 后 1-2 秒内 mCurrentFocus 会短暂为空，
            //   原来把「空」当「已退出」，接着读当前包名拿到空串，整轮失败
            //   （日志表现为「读不到当前用户空间」）。
            Ui.waitUntilNotForeground(ctx.packageName, 8000)
            // 等焦点彻底稳定
            Ui.waitFocusReady(6000)

            // 只有 目标应用有实现，其他平台先提示，方便后续接入
            val platforms = enabledPlatforms.mapNotNull {
                com.love.soulmate.platform.Platform.byId(it)
            }
            if (platforms.isEmpty()) {
                log("没有选择任何平台", "error")
                return
            }

            var total = 0
            for (p in platforms) {
                if (!p.implemented) {
                    log("${p.label}：尚未接入（${p.note}）", "warn")
                    continue
                }
                if (p.id != "soul") {
                    log("${p.label}：驱动未实现，跳过", "warn")
                    continue
                }

                // 单空间运行：只操作 App 当前所在的这个 目标应用。
                // 不切空间、不碰分身 —— 换账号由用户在 目标应用 里自己切，
                // App 只负责读当前账号的会话并回复。
                var u = Ui.currentUser()
                if (u < 0) {
                    // 焦点可能还没稳定，等一下重试
                    Ui.waitFocusReady(5000)
                    u = Ui.currentUser()
                }
                if (u < 0) {
                    log("读不到当前用户空间（焦点未稳定）", "error")
                    continue
                }
                // ★ 收到的新招呼（用户要求 2026-11-25）
                //
                // 「收到的新招呼你也处理下，里面也会开启私聊」
                //
                // 这些人**主动跟你打了招呼**，回复意愿最强。
                // 而且 目标应用的规则是「发消息后对话框将会被放出」——
                // 点进去发一条就直接建立私聊会话。
                //
                // 放在已有会话回复**之前**：
                //   新人回复率最高，而且只花一条消息的成本。
                //   已有会话可能有几十个，先跑完就没时间管新人了。
                if (features.greetings) {
                    checkStop()
                    total += runGreetings(u, dryRun)
                } else {
                    log("u$u 未勾选「新招呼」，跳过", "info")
                }

                total += processSpace(u, dryRun)

                // ★ 同城主动私聊（只在勾选时跑）
                //
                // 放在回复之后：先处理已有会话（价值更高），
                // 有余力再主动开拓新人。
                if (features.squareProactive && features.proactiveCount > 0) {
                    checkStop()
                    total += runProactive(
                        user = u,
                        limit = features.proactiveCount,
                        dryRun = dryRun,
                    )
                } else {
                    log("u$u 未勾选「同城私聊」，跳过", "info")
                }

                // ★ 同城帖子评论（2026-11-25 新增，只在勾选时跑）
                //
                // 为什么排在私聊**之后**：
                //   评论是「无墙、免费、公开」的曝光，价值高但节奏要慢。
                //   先跑私聊是因为私聊有会话建立的一次性价值；
                //   评论每轮 5 条就够，跑多了很像是刷屏小号。
                //
                // 另外这两块共用同一个广场页面，串行跑不会有页面冲突；
                // 并行跑必然互相切页面（实测过，两边全失败）。
                if (features.squareComment && features.squareCommentCount > 0) {
                    checkStop()
                    total += runSquareComments(
                        user = u,
                        limit = features.squareCommentCount,
                        dryRun = dryRun,
                    )
                } else if (!features.squareComment) {
                    log("u$u 未勾选「同城评论」，跳过", "info")
                }
            }

            val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            _state.value = _state.value.copy(lastRun = now)
            if (total == 0) {
                // ★ 演练模式下 sent 恒为 0，不能笼统说「没有可回的消息」
                //   （会让人误以为一个都匹配不上，其实只是没真发）
                val generated = _state.value.pending
                if (dryRun && generated > 0) {
                    log("本轮演练完成：看了 $generated 个会话，未实际发送", "info")
                } else {
                    log("本轮没有可回的消息", "info")
                }
            }
        } catch (s: StopSignal) {
            log("已按你的要求停止", "warn")
        } catch (t: Throwable) {
            log("异常：${t.javaClass.simpleName} ${t.message ?: ""}", "error")
        } finally {
            stopRequested = false
            configLocked = false
            _state.value = _state.value.copy(running = false, phase = "idle", pending = 0)
            bringToFront?.invoke()
        }
    }
}
