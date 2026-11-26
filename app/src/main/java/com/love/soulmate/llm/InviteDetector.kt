package com.love.soulmate.llm

/**
 * 邀约信号识别。
 *
 * ── 为什么需要（2026-11-24 用户要求）──
 * 「对方约你吃饭、见面或者要加微信的时候，要发个消息通知下」
 *
 * 这是整个流程里**价值最高的信号**：
 *   · 聊 100 个人，可能只有 1-2 个会主动提见面
 *   · 一提见面说明兴趣已经到位，是真正的转化点
 *   · 如果 App 自动回一句「好呀」然后没通知用户，机会就浪费了
 *
 * 所以：识别到邀约 → **立刻推微信 + 在 App 里高亮**，并且**不要自动回复**，
 * 交给用户自己决定怎么接。
 *
 * ── 识别方式：两段式 ──
 *   ① 关键词快筛（零成本，覆盖 90% 明说的）
 *   ② 关键词没命中但内容可疑时，调 LLM 二次判断（避免「改天一起吃个饭？」这种反问句漏掉）
 *
 * ── 分级 ──
 *   MEET    约见面/吃饭/看电影 → 最高优先级
 *   CONTACT 要微信/电话/QQ等联系方式 → 最高优先级
 *   HINT    强烈暗示但没明说（「你什么时候有空」）→ 中等
 *   NONE    无
 */
object InviteDetector {

    enum class Kind { MEET, CONTACT, HINT, NONE }

    data class Signal(
        val kind: Kind,
        /** 触发的那句话原文 */
        val trigger: String = "",
        /** 给用户看的说明 */
        val note: String = "",
    ) {
        val isHighValue: Boolean
            get() = kind == Kind.MEET || kind == Kind.CONTACT

        companion object {
            val NONE = Signal(Kind.NONE)
        }
    }

    // ────────── 关键词表 ──────────

    /** 约见面 —— 明说型 */
    private val MEET_WORDS = listOf(
        // 吃饭
        "一起吃饭", "一起吃个饭", "请你吃饭", "约饭", "吃个饭", "吃饭吗",
        "喝杯咖啡", "喝个咖啡", "喝咖啡", "一起喝", "请你喝",
        // 见面
        "见个面", "见面", "见一面", "出来玩", "出来见", "约一下", "约起来",
        "见面聊", "线下", "面基", "碰个面",
        // 活动
        "看电影", "一起看", "看展", "逛街", "一起逛", "去玩",
        // 直接邀约句式
        "什么时候有空", "哪天有空", "有空吗", "有时间的", "约吗",
        "要不要一起", "想不想一起", "一起吗",
    )

    /** 要联系方式 —— 明说型 */
    private val CONTACT_WORDS = listOf(
        "微信", "weixin", "vx", "v信", "威信", "加个好友", "加好友",
        "qq号", "扣扣", "电话", "手机号", "号码", "留个联系",
        "加一下", "给个联系", "怎么联系", "联系方式",
        "加我", "私聊转微", "换个地方聊",
    )

    /** 强烈暗示 —— 无明说但有动作 */
    private val HINT_WORDS = listOf(
        "你在哪个区", "住哪", "公司在哪", "上班在哪",
        "这周末", "下周末", "这周有空", "下周有空",
        "请你", "带你", "陪我去", "要不要来",
    )

    /**
     * 误报过滤器：这些话里含关键词但不是邀约。
     *
     * ★ 实测（2026-11-24）发现两类误报：
     *   「我周末在家躺了两天」→ 被「周末」命中 HINT
     *   「不想加微信」      → 被「加微信」命中 CONTACT
     *
     * 修法：
     *   · HINT 里去掉裸「周末」（太宽泛），改成「这周末/下周末」
     *   · 否定词判断要能救回 CONTACT，不能只看「下次/改天」
     */
    private val FALSE_POSITIVE = listOf(
        "不去", "不想去", "没空", "不方便", "算了",
        "不见", "不加", "不给", "别加", "不想加",
        "不想见", "不想聊", "拒绝",
    )

    /**
     * 快速判断一句话里有没有邀约信号。
     *
     * @param text 她说的那句
     * @return 识别结果；NONE 表示没命中关键词
     */
    fun quickScan(text: String): Signal {
        val t = text.trim()
        if (t.isBlank()) return Signal.NONE

        // 先排除否定语境
        val negative = FALSE_POSITIVE.firstOrNull { t.contains(it) }
        if (negative != null) {
            // 例外：「下次吧」「改天吧」这种拒绝里，若同时说了「一起/吃饭/见」
            // 仍算邀约（「下次一起吃饭吧」是真邀约）
            val maybeInvite = (t.contains("下次") || t.contains("改天")) &&
                    (t.contains("一起") || t.contains("吃饭") || t.contains("见")) &&
                    !t.contains("吧")   // 「下次吧」是拒绝，「下次一起吃饭吧」是邀约
            val realInvite = (t.contains("下次") || t.contains("改天")) &&
                    (t.contains("一起") || t.contains("吃饭") || t.contains("见")) &&
                    !t.contains("不")
            if (!maybeInvite && !realInvite) return Signal.NONE
        }

        CONTACT_WORDS.firstOrNull { t.contains(it) }?.let {
            return Signal(Kind.CONTACT, t, "她提到了联系方式「$it」")
        }

        MEET_WORDS.firstOrNull { t.contains(it) }?.let {
            return Signal(Kind.MEET, t, "她提到约见面「$it」")
        }

        HINT_WORDS.firstOrNull { t.contains(it) }?.let {
            return Signal(Kind.HINT, t, "她在探你的位置/时间「$it」")
        }

        return Signal.NONE
    }

    /**
     * 扫描最近几条她的消息，返回最高优先级的信号。
     *
     * 只看她说的，不看自己说的（自己说「一起吃饭吧」是我们在邀约）
     */
    fun scanHerMessages(
        messages: List<Pair<String, String>>,   // (side, text)
        lookBack: Int = 3,
    ): Signal {
        val hers = messages
            .filter { it.first == "her" }
            .map { it.second }
            .takeLast(lookBack)

        var best = Signal.NONE
        for (t in hers) {
            val s = quickScan(t)
            when {
                s.kind == Kind.CONTACT -> return s           // 联系方式最优先
                s.kind == Kind.MEET && best.kind != Kind.CONTACT -> best = s
                s.kind == Kind.HINT && best.kind == Kind.NONE -> best = s
            }
        }
        return best
    }

    /**
     * LLM 二次判断。
     *
     * 用在哪：关键词没命中，但这句话比较长、不像是简单寒暄时。
     * 成本约 0.05 credit，只在可疑时花。
     *
     * @return 是否含邀约意图
     */
    fun askLlm(text: String): Signal? {
        val prompt = buildString {
            appendLine("判断这句话里，对方有没有约见面、或者要联系方式的意图。")
            appendLine()
            appendLine("她说：「$text」")
            appendLine()
            appendLine("只输出一个 JSON：")
            appendLine("{\"kind\": \"MEET|CONTACT|HINT|NONE\", \"why\": \"一句话理由\"}")
            appendLine()
            appendLine("说明：")
            appendLine("MEET = 想见面/吃饭/看电影/出来玩")
            appendLine("CONTACT = 想交换微信/电话等联系方式")
            appendLine("HINT = 在探你的时间或位置，但没明说")
            appendLine("NONE = 普通聊天")
        }

        val r = Llm.ask(
            "你是一个意图识别器。只输出 JSON，不要解释。",
            prompt,
        )
        if (!r.ok) return null

        val txt = r.text
        val kind = when {
            txt.contains("\"CONTACT\"") -> Kind.CONTACT
            txt.contains("\"MEET\"") -> Kind.MEET
            txt.contains("\"HINT\"") -> Kind.HINT
            else -> Kind.NONE
        }
        if (kind == Kind.NONE) return null

        val why = Regex("\"why\"\\s*:\\s*\"([^\"]*)\"")
            .find(txt)?.groupValues?.get(1) ?: ""

        return Signal(kind, text, why)
    }
}
