package com.love.soulmate.soul

/**
 * 排除规则：判断这个人值不值得搭话。
 *
 * ── 数据来源（不是猜的）──
 * 在真机上采集了一批真实引力签样本，观察到两类信号：
 *   1) 引力签里的字段（性格标签、职业、地域、感情状态等）
 *   2) 昵称里的括号补充说明（如「（不聊天）」「（纯聊天版）」）
 *
 * 中性样本（正常人的引力签）：
 *   追剧 / 礼仪标兵 / 穿搭 / 温柔 / 研发工程师 / 芯片设计工程师 /
 *   永远在减肥 / 美食 / 购物 / ISFP / 朋友圈摄影师 / 猫控 / 自由工作者
 *
 * 需要排除的样本（已脱敏，只保留特征）：
 *   · 引力签含「XX年已婚带娃」「背景图是本人」「再问就拉黑」
 *   · 昵称含「（纯聊天版）」「（不聊天）」
 *
 * 观察到：「已婚带娃」「不聊天」是少数情况，但出现位置有两个：
 *   1) 引力签里
 *   2) 昵称里
 * 所以两个地方都要查。
 *
 * ── 用户要求 ──
 * 「标注不聊天和已婚带娃的排除掉」
 *
 * ── 设计原则 ──
 * 宁可漏，不要错杀。
 * 「单身」和「已婚」只差一个字，规则必须精确，不能把「单身汪」误判成已婚。
 */
object Excluder {

    /** 结果：是否排除 + 原因 */
    data class Verdict(val exclude: Boolean, val reason: String = "") {
        companion object {
            val PASS = Verdict(false)
            fun deny(why: String) = Verdict(true, why)
        }
    }

    // ---------- A. 明确说不想聊 ----------

    /**
     * 昵称里带这些 → 她本人就不想被搭话。
     * 实测样本：昵称末尾带「（不聊天）」「（纯聊天版）」这类补充说明的。
     */
    private val NO_CHAT_IN_NAME = listOf(
        "不聊天", "纯聊天", "闲聊勿扰", "勿扰", "只看看", "不私聊",
        "别私聊", "拒绝闲聊", "只看不聊", "不回复", "免开尊口",
    )

    /**
     * 引力签里带这些 → 同上。
     * 注意：「纯聊天」出现在昵称里是「只聊不约」的意思，
     *      但如果出现在引力签里，多数是「拒绝深聊」。
     */
    private val NO_CHAT_IN_TAG = listOf(
        "不聊天", "别找我聊", "只看看", "勿扰", "不私聊", "拒绝闲聊",
    )

    // ---------- B. 已婚 / 带娃 / 非单身 ----------

    /**
     * 已婚或带娃的强信号。
     * 实测样本：引力签里直接写「XX年已婚带娃」的。
     */
    private val MARRIED_STRONG = listOf(
        "已婚", "带娃", "有娃", "有孩子", "已育", "宝妈", "孩儿他妈", "孩他妈",
        "老公", "老婆", "结婚了", "结婚中", "俩娃", "两娃", "一娃",
        "娃已生", "生了", "有人夫", "有妇之夫",
    )

    /**
     * ★ 关键：这些词里含「婚」或「娃」，但**不是**已婚的意思，必须放过。
     * 比如「不婚主义」是单身，「单身汪」当然也是单身。
     *
     * 判定顺序：先看白名单，命中就跳过黑名单。
     */
    private val MARRIED_WHITELIST = listOf(
        "未婚", "不婚", "单身", "离异无孩", "无娃", "没娃", "不想要娃",
        "不想带娃", "拒婚托", "反婚托",
    )

    // ---------- C. 只找搭子不谈恋爱 ----------

    /** 实测样本：「找搭子 · 上海浦东约饭约电影群」 */
    private val JUST_BUDDY = listOf(
        "找搭子", "搭子", "约饭群", "约电影群", "游戏搭子", "旅游搭子",
        "开黑群", "拼车", "拼单",
    )

    // ---------- D. 明确要求年龄不符 ----------

    /**
     * 用户是 99 年的（2026 年时 27 岁）。
     * 如果对方明确写了年龄区间且不含 27，就排除。
     *
     * 实测样本：欣安那「想找个00~05的上海独生子」
     *   用户 99 年不在 00-05 区间 → 排除
     */
    private val AGE_RANGE = Regex("""(\d{2})\s*[~～\-—到至]\s*(\d{2})\s*年?""")

    /**
     * 判断是否排除这个人。
     *
     * @param name  昵称
     * @param tags  引力签列表（从卡片读到）
     * @param post  帖子原文（同城广场能拿到；聊天列表来的传空）
     */
    fun check(
        name: String,
        tags: List<String>,
        post: String = "",
        myBirthYear: Int = 1999,
    ): Verdict {
        // A1. 昵称说不聊
        NO_CHAT_IN_NAME.firstOrNull { name.contains(it) }?.let {
            return Verdict.deny("昵称写「$it」，本人不想被搭话")
        }

        val tagText = tags.joinToString(" ")
        val allText = "$name $tagText $post"

        // A2. 引力签说不聊
        NO_CHAT_IN_TAG.firstOrNull { tagText.contains(it) }?.let {
            return Verdict.deny("引力签写「$it」")
        }

        // B. 已婚/带娃 —— 先查白名单，别错杀「单身汪」
        val hasWhitelist = MARRIED_WHITELIST.any { allText.contains(it) }
        if (!hasWhitelist) {
            MARRIED_STRONG.firstOrNull { allText.contains(it) }?.let {
                return Verdict.deny("已婚/带娃（命中「$it」）")
            }
        }

        // C. 只找搭子
        JUST_BUDDY.firstOrNull { post.contains(it) || tagText.contains(it) }?.let {
            return Verdict.deny("只找搭子（命中「$it」）")
        }

        // D. 年龄区间不含我
        AGE_RANGE.find(post)?.let { m ->
            val lo = m.groupValues[1].toIntOrNull() ?: 0
            val hi = m.groupValues[2].toIntOrNull() ?: 99
            // 把 2 位数年份换算成 19xx/20xx（00-30 视为 2000+）
            val loY = if (lo <= 30) 2000 + lo else 1900 + lo
            val hiY = if (hi <= 30) 2000 + hi else 1900 + hi
            if (myBirthYear !in loY..hiY) {
                return Verdict.deny("她要求 ${loY}-${hiY} 年生，我是 $myBirthYear")
            }
        }

        return Verdict.PASS
    }
}
