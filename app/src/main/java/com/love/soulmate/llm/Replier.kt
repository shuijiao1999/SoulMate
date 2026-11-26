package com.love.soulmate.llm

import com.love.soulmate.platform.Msg
import com.love.soulmate.platform.Profile

/**
 * 回复生成 + 质量校验。
 *
 * 这里的每条规则都来自真实对话复盘，不是拍脑袋想的：
 * 漏一条就会生成出被对方识破的 AI 腔。
 */
object Replier {

    // 硬冷淡：她这么说就是不想聊了
    private val COLD = setOf(
        ".", "。", "呃", "哦", "嗯", "哈哈", "嗯嗯", "哦哦", "哦哦哦",
        "嗯嗯嗯", "没有", "不知道", "。。"
    )

    // 软收尾：她在礼貌地结束，不该硬接
    private val SOFT_END = listOf(
        "慢慢来", "随缘", "好的吧", "你忙吧", "不打扰了", "改天聊",
        "有空再说", "再说吧", "看情况", "睡了", "先这样"
    )

    // 可接受属地
    private val GOOD_IP = listOf(
        "上海", "江苏", "南京", "苏州", "无锡", "常州", "南通", "徐州", "扬州",
        "镇江", "泰州", "盐城", "淮安", "连云港", "宿迁",
        "浙江", "杭州", "宁波", "温州", "嘉兴", "湖州", "绍兴", "金华",
        "衢州", "舟山", "台州", "丽水"
    )
    private val FAR_IP = listOf("徐州", "连云港", "宿迁", "淮安", "盐城", "丽水", "衢州", "温州")

    /**
     * System prompt 的基础部分（风格规则）。
     *
     * ★ 2026-11-26 开源前重写：
     *   原来这里写死了开发者本人的完整档案（出生年份/老家/现居地/职业/身高/学历/
     *   作息/爱好），开源等于公开个人信息。现在拆成两部分：
     *     · 这段（STYLE_RULES）：说话风格，通用规则，可以硬编码
     *     · Persona：我是谁，用户自己填，没填时模型不编造
     */
    private const val STYLE_RULES = """你在帮用户写社交消息。

【说话风格】
- 平均 8 个字，最多 12 个字
- 不用句号，不用感叹号
- 问句几乎不用问号，用「吗/啊/呢/吧/嘛」收尾
- 用空格代替标点
- 允许不完整、允许口语

绝对不要：
- 书面语（「我觉得您」「很高兴认识」）
- 一次问多个问题
- 复述对方说过的话
- 解释自己为什么发这条
- emoji
- 回头解释/纠正之前发过的错消息（乱码、发串的都自然过去，不要主动道歉或澄清）

开口词纪律：
- 「好家伙」这类口头禅严禁连续使用，每 3 条最多 1 次
- 最好的做法是不加开口词，直接接她的具体细节

要做到三件事里至少一件：
1. 答+问（一答一问，禁止两问）
2. 自曝（她说 1 你说 0.7）
3. 具体化（把泛泛的话变成画面）

写完问自己：如果我是她，看到这句会想回吗？不想回就重写。"""

    /**
     * 组装完整的 system prompt。
     *
     * @param p 用户人设（含事实和风格）。为空时自动从配置加载。
     */
    private fun systemPrompt(p: Persona? = null): String {
        val persona = p ?: com.love.soulmate.core.Env.context()?.let { Persona.load(it) }
            ?: Persona()
        return buildString {
            appendLine(STYLE_RULES)
            appendLine()
            appendLine(persona.factsBlock())
            appendLine()
            appendLine("只输出一句话本身，不要 JSON，不要解释。")
        }
    }


    fun isCold(text: String): Boolean = text.trim() in COLD

    fun isSoftEnd(text: String): Boolean = SOFT_END.any { text.contains(it) }

    /**
     * 属地是否放行。
     *
     * ★ 加一层防误判：只有「看起来像地名」的才参与判断。
     *   踩坑：「水象星座」曾被当成 IP，把一条正常会话整条跳过。
     *   现在要求：2-6 个字符、纯中文（不含数字/符号）、无「星座/座/岁/恋爱」等非地名词。
     */
    fun ipAllowed(ip: String): Boolean {
        if (ip.isBlank()) return true
        if (!looksLikeCity(ip)) return true          // 不像地名 → 不拿来卡人
        return GOOD_IP.any { ip.contains(it) }
    }

    fun isFar(ip: String): Boolean {
        if (ip.isBlank()) return false
        if (!looksLikeCity(ip)) return false
        return FAR_IP.any { ip.contains(it) }
    }

    /** 粗略判断是不是地名 */
    fun looksLikeCity(s: String): Boolean {
        val t = s.trim()
        if (t.length !in 2..6) return false
        if (!t.all { it in '\u4e00'..'\u9fff' }) return false
        val banned = listOf("星座", "座", "岁", "恋爱", "朋友", "毕业", "单身",
            "希望", "在线", "刚刚", "匹配", "星球", "认证", "共同", "属地")
        return banned.none { t.contains(it) }
    }

    /** 纯提问判定：没有任何自曝内容 */
    fun isPureQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.contains("？") || t.contains("?")) return true
        if (t.endsWith("吗") || t.endsWith("呢") || t.endsWith("吧") || t.endsWith("嘛")) {
            if (!t.contains("我")) return true
        }
        if (!t.contains("我") && t.contains("你")) return true
        return false
    }

    /** 话题打转检测：最近 12 条里同一片段出现 ≥3 次 */
    fun topicRounds(msgs: List<Msg>): Pair<Int, String> {
        val recent = msgs.takeLast(12)
        if (recent.size < 4) return 0 to ""
        val text = recent.joinToString("") { it.text }
        val han = text.filter { it in '\u4e00'..'\u9fa5' }
        if (han.length < 8) return 0 to ""

        val counts = HashMap<String, Int>()
        for (n in intArrayOf(2, 3)) {
            for (i in 0..(han.length - n)) {
                val w = han.substring(i, i + n)
                counts[w] = (counts[w] ?: 0) + 1
            }
        }
        val stop = setOf(
            "可以", "什么", "这个", "那个", "就是", "不是", "没有", "还是", "觉得",
            "知道", "一个", "我们", "你们", "他们", "所以", "因为", "但是", "而且",
            "然后", "已经", "应该", "可能", "一下", "一样", "不过", "有点", "好像",
            "其实", "不知道", "可以了", "什么的"
        )
        stop.forEach { counts.remove(it) }
        val top = counts.maxByOrNull { it.value } ?: return 0 to ""
        return if (top.value >= 3) top.value to top.key else 0 to ""
    }

    /** 生成回复。返回 null 表示放弃 */
    fun generate(msgs: List<Msg>, profile: Profile, name: String): String? {
        if (msgs.isEmpty()) return null

        val ctx = msgs.takeLast(8).joinToString("\n") {
            "${if (it.side == "me") "我" else "她"}：${it.text}"
        }
        val tags = profile.raw.take(10).joinToString(" / ")
        val ip = profile.ip.ifBlank { "未知" }
        val lastLen = msgs.last().text.length

        val extra = ArrayList<String>()

        val (rounds, hotWord) = topicRounds(msgs)
        if (rounds >= 3) {
            extra.add("【硬规则】「$hotWord」这个话题已经聊了 $rounds 轮，这条绝对不能出现相关词，必须换新话题。")
        }

        if (lastLen <= 3) {
            extra.add(
                "【硬规则】她上一条只有 $lastLen 个字，在收力。这条必须同时做两件事，一句话完成：\n" +
                        "① 接住她说的那个点（不要换话题）\n" +
                        "② 加一段你自己的具体经历（自曝）\n" +
                        "❌ 禁止只提问、禁止问号结尾\n" +
                        "❌ 禁止完全跳开她的话题\n" +
                        "✅ 示例：她说「我也是」→ 「我看粤语全靠字幕 剧情全靠猜」"
            )
        }

        val unread = msgs.filter {
            it.side == "her" && it.text.startsWith("[语音") && !it.text.contains("转写:")
        }
        if (unread.isNotEmpty()) {
            extra.add(
                "【硬规则】带 [语音] 的是语音消息，你听不到内容。\n" +
                        "❌ 绝对不要猜测语音说了什么，不要因为看到「晚上」「桌球」「礼物」" +
                        "这类词就以为她在说这些——那是界面按钮，不是聊天内容。\n" +
                        "✅ 只根据已转写的内容和文字消息回。"
            )
        }

        // ★ 多条连续语音要综合理解（2026-11-24 用户反馈）
        //
        // 用户场景：她连发两条语音（2s + 4s），要结合两条一起理解再回。
        //
        // 目标应用Bot.readChat 已经把连续语音合并成
        //   `[她连发2条语音：2s「xxx」／4s「yyy」]`
        // 这里再明确告诉模型怎么用。
        val multiVoice = msgs.lastOrNull { it.text.contains("她连发") }
        if (multiVoice != null) {
            extra.add(
                "【硬规则】她刚才**连发了多条语音**，那是一段话被切开说的，" +
                        "不是多个话题。\n" +
                        "✅ 必须把它们当成**一整段意思**综合理解后再回\n" +
                        "✅ 如果几条语音前后补充同一件事，就针对那件事回\n" +
                        "❌ 不要只回应最后一条语音\n" +
                        "❌ 不要逐条点评（「你说的第一点…第二点…」这种很怪）\n" +
                        "❌ 不要提「语音」这个词（显得你在念转写稿）"
            )
        }

        // ★ 把卡片资料变成具体切入点（之前完全没用，白白浪费）
        val signs = profile.raw.filter { !it.startsWith("星球:") && !it.startsWith("认证:") && !it.startsWith("共同点:") }
        val mbti = profile.raw.firstOrNull { it.startsWith("认证:") }?.removePrefix("认证:")
        val common = profile.raw.firstOrNull { it.startsWith("共同点:") }?.removePrefix("共同点:")

        if (signs.isNotEmpty() && msgs.size <= 3) {
            extra.add(
                "【素材】她主页的引力签：${signs.take(6).joinToString(" / ")}\n" +
                        "对话刚开始，可以挑其中**一个最具体的**当切入点（比如性格标签、拒绝项），\n" +
                        "✅ 挑一个说，别全列出来\n" +
                        "❌ 不要生硬照念标签原文"
            )
        }

        if (!mbti.isNullOrBlank()) {
            extra.add("【素材】她是 $mbti。如果要提，用轻松的方式带一句，别当话题主体。")
        }

        if (!common.isNullOrBlank()) {
            extra.add("【素材】你们都在 $common。这是很自然的话题点。")
        }

        if (isFar(ip)) {
            extra.add("【提示】她在$ip，离上海不近。现在别急着谈见面，先把话说上头。")
        }

        val prompt = buildString {
            append("对话：\n").append(ctx).append("\n\n")
            append("她的资料：").append(tags).append("\n")
            append("IP属地：").append(ip).append("\n\n")
            append("生成一条回复。要求：\n")
            append("- 5-12 字\n- 不用句号、感叹号\n- 问号最多 1 个\n")
            append("- 接她说的具体细节\n- 可以自曝，不要只问\n- 「好家伙」不要连续用\n")
            if (extra.isNotEmpty()) append("\n").append(extra.joinToString("\n"))
        }

        // ★ 最多重试 2 次：模型偶尔会输出带句号、超长的内容，
        //   直接放弃太浪费（一条回复值得多花 3 秒）
        repeat(2) { attempt ->
            val r = Llm.ask(systemPrompt(), prompt)
            if (!r.ok) {
                if (attempt == 1) return null
                return@repeat
            }

            var text = Llm.unwrap(r.text)

            // 不通过就先尝试「自动修正」：去句号/感叹号、截断
            if (!check(text)) {
                text = autoFix(text)
            }

            if (check(text)) return text
        }
        return null
    }

    /**
     * 生成「同城主动私聊」的开场白。
     *
     * ── 和 generate() 的区别 ──
     * generate() 是「延续已有对话」，有上下文可接。
     * 这个是「从零开始搭话」，唯一素材是：
     *   · 她的帖子内容（这是最重要的 —— 从帖子私聊，她那边会显示引用）
     *   · 她的资料卡（认证/引力签/共同点）
     *
     * ── 质量要求 ──
     * 好的开场白必须**明显看过她的帖子**，不是通用寒暄。
     * 例：她写「想找个00~05的上海独生子」，可以接「我99的 差一岁算及格吗」
     *     她写「半个阿宅 steam彩六」，可以接「彩六打了多久 我最近在打R6」
     */
    fun generateOpener(
        name: String,
        profile: Profile,
        postContent: String,
    ): String? {
        // 素材太少就别硬生成 —— 会写出「哈喽」这种没人想回的废话
        //
        // 实测（2026-11-24）：有人的帖子就写「哈喽」「？」两三个字，
        // 资料卡也空。这种情况模型生成的开口白质量极差，
        // 不如直接跳过这个人。
        val hasPost = postContent.trim().length >= 8
        val hasProfile = profile.raw.isNotEmpty()
        if (!hasPost && !hasProfile) return null

        val tags = profile.raw.take(8).joinToString(" / ")
        val ip = profile.ip.ifBlank { "未知" }

        val prompt = buildString {
            append("你要主动私聊一个人。\n\n")
            if (hasPost) {
                append("她的帖子：\n")
                append(postContent).append("\n\n")
            } else {
                append("她没发帖子内容。\n\n")
            }
            append("她的资料：").append(tags.ifBlank { "（无）" }).append("\n")
            append("IP属地：").append(ip).append("\n\n")
            append("生成一句开场白。要求：\n")
            append("- 5-12 字，不用句号、感叹号，问号最多 1 个\n")
            if (hasPost) {
                append("- 必须让我看出你看过她的帖子（提到她写的具体内容）\n")
            } else {
                append("- 从她的引力签/认证里挑一个具体的点切入\n")
            }
            append("- 可以自曝一点自己的情况\n")
            append("- 不要用「你好」「在吗」「嗨」这种通用开场\n")
            append("- 不要夸她好看（很廉价）\n")
            append("- 不要问「你是做什么的」这种查户口\n")
            append("\n只输出那一句话本身。")
        }

        return retryGenerate(prompt)
    }

    /**
     * 基于「她的主页」生成开场白。
     *
     * ── 用户要求（2026-11-24）──
     * 「灵魂匹配打招呼的时候，记得去她主页看下信息后再打招呼，光看标签不行」
     *
     * ── 和 [generateOpener] 的区别 ──
     * generateOpener 用于「广场帖子私聊」——素材是一个帖子。
     * 这个用于「灵魂匹配」——素材是她主页的签名 + 多条瞬间 + 灵魂画像。
     *
     * 瞬间内容是她自己写的话，有具体的人/事/情绪，
     * 比标签（「独处自愈」「魅力少帅」）好用得多。
     *
     * @param homeText 已经整理好的主页素材（见 UserHome.HomeInfo.toPromptText）
     */
    fun generateOpenerFromHome(
        name: String,
        profile: Profile,
        homeText: String,
    ): String? {
        if (homeText.trim().length < 8) return null

        val prompt = buildString {
            appendLine("你刚匹配到一个女生，要跟她打招呼。")
            appendLine()
            appendLine("她的主页资料：")
            appendLine(homeText.trim())
            appendLine()
            appendLine("生成一句开场白。要求：")
            appendLine("- 5-14 字，不用句号、感叹号，问号最多 1 个")
            appendLine("- 必须提到她主页里的具体内容（签名原话、某条瞬间里的事）")
            appendLine("- 优先接「瞬间」而不是标签 —— 瞬间是她自己写的话，更真实")
            appendLine("- 如果瞬间里有具体的事（面试/工作/心情/吐槽），就接那件事")
            appendLine("- 可以顺带自曝一点自己的情况，显得自然")
            appendLine("- 不要用「你好」「在吗」「嗨」这种通用开场")
            appendLine("- 不要夸她好看（廉价）")
            appendLine("- 不要问「你是做什么的」这种查户口")
            appendLine("- 不要复述她的原话，要接出下一句")
            appendLine()
            append("只输出那一句话本身，不要引号不要解释。")
        }

        return retryGenerate(prompt)
    }

    /** 带重试 + 自动修正的生成（内部用） */
    private fun retryGenerate(prompt: String): String? {
        repeat(2) {
            val r = Llm.ask(systemPrompt(), prompt)
            if (!r.ok) return null
            var text = Llm.unwrap(r.text)
            if (!check(text)) text = autoFix(text)
            if (check(text)) return text
        }
        return null
    }

    /**
     * 自动修正不规范的输出。
     *
     * 模型有时会写「九点了。」这种带句号的，或者稍微超长。
     * 与其丢弃，不如修一下 —— 这些规则纯粹是格式，内容通常是好的。
     */
    fun autoFix(text: String): String {
        var t = text.trim()
        // 去掉包裹的引号
        t = t.trim('"', '\'', '「', '」', '“', '”', ' ')
        // 去句号/感叹号（换行也去掉）
        t = t.replace("。", " ").replace("！", " ").replace("!", " ")
            .replace("\n", " ").trim()
        // 多个问号合并成一个
        while (t.contains("？？") || t.contains("??") || t.contains("？?")) {
            t = t.replace("？？", "？").replace("??", "?").replace("？?", "？")
        }
        // 超长就截到 16 字以内，尽量在标点处断开
        if (t.length > 16) {
            val cut = t.take(16)
            t = cut.substringBeforeLast(' ').ifBlank { cut }
        }
        // 压缩多余空格
        return t.replace(Regex("""\s{2,}"""), " ").trim()
    }

    fun check(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.length > 18) return false              // 略放宽（autoFix 已截到 16）
        if (t.contains("。") || t.contains("！")) return false
        val q = t.count { it == '？' || it == '?' }
        if (q > 1) return false
        // 明显的非人类输出：JSON 残留、解释文字
        if (t.startsWith("{") || t.contains("\"text\"")) return false
        if (t.startsWith("回复：") || t.startsWith("答：")) return false
        return true
    }
}
