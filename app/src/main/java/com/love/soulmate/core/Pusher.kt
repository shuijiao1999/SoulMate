package com.love.soulmate.core

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 微信推送告警。
 *
 * ── 为什么需要 ──
 * App 在后台自动跑，出问题时没人知道。
 * 用户要求：遇到问题发消息给我。
 *
 * ── 什么情况该推 ──
 * 推得太勤会被无视，所以只推「需要人介入」的：
 *   · 发消息失败（输入框失效、找不到按钮）
 *   · 目标应用被弹窗卡死/权限失效
 *   · 连续多轮回复失败
 *   · 模型调用失败（API key 过期、余额不足）
 *
 * 不推：正常跳过（冷淡词/IP不符）、本轮没消息、单次网络抖动。
 *
 * ── 配置 ──
 * WxPusher 的 spt（Simple Push Token）存在 SharedPreferences 的 "push" 里。
 * 用户在设置页填，不写死在代码里。
 */
object Pusher {

    /** 同类告警的最小间隔，避免刷屏 */
    private const val THROTTLE_MS = 10 * 60_000L   // 10 分钟

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 推送配置。
     *
     * ── 为什么拆成多个开关（2026-11-24 用户要求）──
     * 「这个通知机制我要加到 app 中」
     *
     * 不同类型的通知价值差别很大，应该能分别开关：
     *   · 邀约（她约见面/要微信）—— 最高价值，几乎一定要开
     *   · 错误（发送失败/目标应用卡死）—— 排障用，可以关掉省得烦
     *
     * 所以配置项：
     *   enabled      总开关
     *   spt          WxPusher Token
     *   onInvite     邀约通知（默认开）
     *   onError      错误通知（默认开）
     *   quietHours   免打扰时段（比如 23:00-08:00 不推错误，但邀约照推）
     */
    data class PushConfig(
        val enabled: Boolean = false,
        val spt: String = "",
        /** 邀约通知：她约见面/要联系方式 */
        val onInvite: Boolean = true,
        /** 错误通知：发送失败/目标应用卡死/模型挂掉 */
        val onError: Boolean = true,
        /** 免打扰开始小时（24 制），-1 = 不启用 */
        val quietStart: Int = -1,
        /** 免打扰结束小时 */
        val quietEnd: Int = -1,
    ) {
        companion object {
            fun load(ctx: Context): PushConfig {
                val sp = ctx.getSharedPreferences("push", Context.MODE_PRIVATE)
                return PushConfig(
                    enabled = sp.getBoolean("enabled", false),
                    spt = sp.getString("spt", "") ?: "",
                    onInvite = sp.getBoolean("onInvite", true),
                    onError = sp.getBoolean("onError", true),
                    quietStart = sp.getInt("quietStart", -1),
                    quietEnd = sp.getInt("quietEnd", -1),
                )
            }

            fun save(ctx: Context, c: PushConfig) {
                ctx.getSharedPreferences("push", Context.MODE_PRIVATE).edit()
                    .putBoolean("enabled", c.enabled)
                    .putString("spt", c.spt)
                    .putBoolean("onInvite", c.onInvite)
                    .putBoolean("onError", c.onError)
                    .putInt("quietStart", c.quietStart)
                    .putInt("quietEnd", c.quietEnd)
                    .apply()
            }
        }

        /** 当前是否在免打扰时段 */
        fun inQuietHours(): Boolean {
            if (quietStart < 0 || quietEnd < 0) return false
            if (quietStart == quietEnd) return false
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return if (quietStart < quietEnd) {
                h in quietStart until quietEnd
            } else {
                // 跨零点，如 23 → 8
                h >= quietStart || h < quietEnd
            }
        }
    }

    /** 上次推送时间，按「标题」去重 */
    private val lastPush = HashMap<String, Long>()

    /**
     * 推送一条告警。
     *
     * @param title 摘要，会显示在通知栏。同类告警会按它节流。
     * @param body  详情
     * @param force 绕过节流（比如首次故障）
     * @param kind  通知类型，用于按类型开关过滤
     *              INVITE = 邀约（高价值，免打扰时段也推）
     *              ERROR  = 错误（免打扰时段不推）
     *              OTHER  = 其他（跟随 ERROR 的开关）
     * @return 是否真的发出去了
     */
    enum class Kind { INVITE, ERROR, OTHER }

    fun alert(
        ctx: Context,
        title: String,
        body: String,
        force: Boolean = false,
        kind: Kind = Kind.OTHER,
    ): Boolean {
        val cfg = PushConfig.load(ctx)
        if (!cfg.enabled || cfg.spt.isBlank()) {
            android.util.Log.i("SoulMate", "Pusher: 未配置，跳过推送「$title」")
            return false
        }

        // ① 按类型过滤
        when (kind) {
            Kind.INVITE -> if (!cfg.onInvite) {
                android.util.Log.i("SoulMate", "Pusher: 邀约通知已关闭，跳过「$title」")
                return false
            }
            Kind.ERROR, Kind.OTHER -> if (!cfg.onError) {
                android.util.Log.i("SoulMate", "Pusher: 错误通知已关闭，跳过「$title」")
                return false
            }
        }

        // ② 免打扰：错误类不推，但邀约照推（邀约时效性强，错过就没了）
        if (kind != Kind.INVITE && cfg.inQuietHours()) {
            android.util.Log.i("SoulMate", "Pusher: 免打扰时段，跳过「$title」")
            return false
        }

        // ③ 同类告警节流
        val now = System.currentTimeMillis()
        val key = title.take(20)
        if (!force) {
            val last = lastPush[key] ?: 0L
            if (now - last < THROTTLE_MS) {
                android.util.Log.i("SoulMate", "Pusher: 「$title」10 分钟内已推送过，跳过")
                return false
            }
        }
        lastPush[key] = now

        return send(cfg.spt, title, body)
    }

    private fun send(spt: String, title: String, body: String): Boolean {
        val full = buildString {
            appendLine(body)
            appendLine()
            appendLine("────────────")
            append("SoulMate · ")
            append(
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date())
            )
        }

        val json = JSONObject().apply {
            put("spt", spt)
            put("summary", title.take(98))
            put("content", full)
            put("content_type", 1)
        }

        val req = Request.Builder()
            .url("https://wxpusher.zjiecode.com/api/send/message/simple-push")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                val ok = txt.contains("\"code\":1000")
                if (ok) {
                    android.util.Log.i("SoulMate", "Pusher: 已推送「$title」")
                } else {
                    android.util.Log.w("SoulMate", "Pusher: 失败 $txt")
                }
                ok
            }
        } catch (t: Throwable) {
            android.util.Log.w("SoulMate", "Pusher: 异常 ${t.message}")
            false
        }
    }

    // ---------- 预置的告警场景 ----------

    /**
     * 🔥 高价值邀约通知。
     *
     * ── 用户要求（2026-11-24）──
     * 「对方约你吃饭、见面或者要加微信的时候，要发个消息通知下」
     *
     * ── 为什么这条最重要 ──
     * 这是整个流程唯一的「转化信号」：
     *   · 聊 100 个人可能只有 1-2 个会主动提见面
     *   · 一提说明兴趣到位，是真正能约出来的
     *   · 错过这条，前面的功夫都白费
     *
     * 所以这条通知：
     *   · 标题带 🔥 让它显眼
     *   · force=true 绕过节流（不能因为 10 分钟内推过别的就漏掉）
     *   · 内容里列出她说的原话，方便你直接判断怎么接
     */
    fun alertInvite(
        ctx: Context,
        who: String,
        kind: String,          // MEET / CONTACT / HINT
        trigger: String,       // 她说的那句原话
        note: String = "",
        recentChat: String = "",
    ) {
        val kindLabel = when (kind) {
            "MEET" -> "💐 想约见面"
            "CONTACT" -> "📱 想要联系方式"
            "HINT" -> "👀 在探你时间/位置"
            else -> "❓ 有邀约迹象"
        }

        alert(
            ctx,
            "🔥 $who $kindLabel",
            """
            |$who $kindLabel
            |
            |她说：「$trigger」
            |${if (note.isNotBlank()) "\n分析：$note\n" else ""}
            |${if (recentChat.isNotBlank()) "\n最近对话：\n$recentChat\n" else ""}
            |⚠️ App 已暂停自动回复这一条，等你决定怎么接。
            |
            |这一条价值最高 —— 聊再多也不如一个愿意见面的。
            """.trimMargin(),
            force = true,
            kind = Kind.INVITE,
        )
    }

    /** 发消息失败（输入框失效 / 找不到发送按钮） */
    fun alertSendFailed(ctx: Context, who: String, reason: String, text: String) {
        alert(
            ctx,
            "发消息失败：$who",
            """
             |发送失败，需要你看一眼
             |
             |对象：$who
             |原因：$reason
             |想发的内容：$text
             |
             |可能情况：
             |· 输入框被键盘挡住
             |· 目标应用界面结构变了
             |· 被风控限制了
            """.trimMargin()
        )
    }

    /** 目标应用出问题（被弹窗卡死、登录失效、闪退） */
    fun alertTargetBroken(ctx: Context, what: String, detail: String) {
        alert(
            ctx,
            "目标应用异常：$what",
            """
             |主动检查时发现目标应用状态不对
             |
             |现象：$what
             |详情：$detail
             |
             |可能情况：
             |· 登录态失效（需要你手动登录）
             |· 被某个弹窗卡住
             |· 网络异常
            """.trimMargin()
        )
    }

    /** 模型调用失败 */
    fun alertLlmFailed(ctx: Context, error: String) {
        alert(
            ctx,
            "模型调用失败",
            """
             |AI 回复生成不了
             |
             |错误：$error
             |
             |可能情况：
             |· API key 过期
             |· 余额不足
             |· 网关挂了
            """.trimMargin()
        )
    }

    /** 连续多轮什么都没做成 */
    fun alertAllFailed(ctx: Context, rounds: Int, lastError: String) {
        alert(
            ctx,
            "连续 $rounds 轮都没成功",
            """
             |App 在跑，但一直没做成事
             |
             |最后一轮错误：$lastError
             |
             |建议：打开 App 看一眼日志
            """.trimMargin()
        )
    }
}
