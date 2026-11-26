package com.love.soulmate.llm

/**
 * 生成「同城广场帖子」的评论内容。
 *
 * ── 为什么单独一个文件，不塞进 [Replier] ──
 * Replier 里的 generateOpener / generateOpenerFromHome 都是「私聊开场白」，
 * 要求是「5-12 字、问她一个问题、能接出下一句」。
 * 评论完全不是一回事：
 *   · 评论是**公开**的，所有人（包括帖主的朋友）都能看到
 *   · 评论区不能显得像搭讪，得像「路过的人留了一句」
 *   · 评论不该带问题（问了帖主不一定回，反而显得急）
 *   · 评论短的更自然，长了像小作文，容易被当成营销号
 *
 * 分开写还有个好处：以后要调评论风格，不会碰到私聊那条已经调好的 prompt。
 *
 * ── 风格依据（用户本人的真实说话习惯，见 my-voice 统计）──
 *   · 中位数 8 字，68% 不超过 10 字
 *   · 句末标点（。！？）占比 1.1% —— 基本不用
 *   · 疑问句用「啊 / 吗 / 吧 / 呢」收尾，不用问号
 *   · 高频词：感觉 / 就是 / 可以 / 确实 / 怎么
 *   · 不复述对方原话（复述 = 一眼假）
 *   · 不夸好看（廉价，且评论区全是这种）
 *
 * ── 为什么评论要「不带钩子」──
 * 评论的唯一目的是**在她那儿留个印象**，让她点进我主页。
 * 所以评论要有信息量（我是什么人 / 我的看法），但不要钓鱼。
 * 加了「要不要认识一下」这种钩子，反而像微商。
 */
object CommentWriter {

    /**
     * 生成一条评论。
     *
     * @param postContent 帖子正文（纯图帖传空串）
     * @param authorName  帖主昵称（仅用于日志，不给模型——给了容易写出「XX你好」）
     * @param ip          帖主 IP 属地，可为空
     * @return 评论内容；素材不足或模型失败返回 null
     */
    fun generate(postContent: String, authorName: String, ip: String): String? {
        val body = postContent.trim()

        // 帖子太短（「哈喽」「？」）就别硬评 —— 只能写出「哈哈哈」这种废话，
        // 反而暴露是机器人在刷评论。
        if (body.length < 8) {
            android.util.Log.i("SoulMate", "CommentWriter: $authorName 的帖子太短，不评")
            return null
        }

        val prompt = buildString {
            append("你在目标应用的「同城广场」刷到一条帖子，想留一条评论。\n\n")
            append("帖子内容：\n")
            append(body).append("\n")
            if (ip.isNotBlank()) {
                append("\n她显示在同城（").append(ip).append("）。\n")
            }
            append("\n写一条评论。硬性要求：\n")
            append("- 6-14 个字，绝对不能超过 16 个字\n")
            append("- 不要用句号、感叹号、省略号\n")
            append("- 不要问问题（评论不是搭讪，问了显得急）\n")
            append("- 必须让人看出你认真看了这条内容（提到她写的具体东西）\n")
            append("- 不要复述她的话，要接出你自己的反应或看法\n")
            append("- 不要夸她好看 / 夸身材（评论区全是这种，很廉价）\n")
            append("- 不要用「小姐姐」「美女」「你好」\n")
            append("- 可以自嘲、可以吐槽、可以说点自己的情况\n")
            append("- 语气像刷手机时随手留的一句，不要像评论员写作文\n")
            append("\n只输出评论本身，不要引号，不要解释。")
        }

        repeat(2) {
            val r = Llm.ask(SYSTEM, prompt)
            if (!r.ok) return null
            var text = Llm.unwrap(r.text)
            if (!check(text)) text = autoFix(text)
            if (check(text)) return text
        }
        return null
    }

    private const val SYSTEM =
        "你在帮一个男生在社交 App 的公开评论区留言。" +
                "他的说话习惯：极短、几乎不用句末标点、不用 emoji、不客套。" +
                "只输出评论内容本身，不带任何解释或引号。"

    /**
     * 修正格式问题。
     *
     * 模型经常写「确实。」「太真实了！」这种带标点的，
     * 或者偶尔写长成小作文。格式问题不该整条丢掉，修一下还能用。
     */
    fun autoFix(text: String): String {
        var t = text.trim()
        t = t.trim('"', '\'', '「', '」', '“', '”', ' ')
        t = t.replace("。", " ").replace("！", " ").replace("!", " ")
            .replace("…", " ").replace("...", " ")
            .replace("\n", " ").trim()
        // 评论不该带问号：句尾问号直接去掉（内容本身留着）
        t = t.replace("？", "").replace("?", "").trim()
        if (t.length > 16) {
            val cut = t.take(16)
            t = cut.substringBeforeLast(' ').ifBlank { cut }
        }
        return t.replace(Regex("""\s{2,}"""), " ").trim()
    }

    /**
     * 校验。规则比私聊开场白更严（评论是公开的，写崩了很丢人）。
     */
    fun check(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.length > 16) return false
        if (t.contains("。") || t.contains("！") || t.contains("!")) return false
        if (t.contains("？") || t.contains("?")) return false
        // 明显的非人类输出
        if (t.startsWith("{") || t.contains("\"text\"")) return false
        if (t.startsWith("评论：") || t.startsWith("回复：")) return false
        // 廉价的通用评论
        val banned = listOf("小姐姐", "美女", "你好", "在吗", "私聊")
        if (banned.any { t.contains(it) }) return false
        return true
    }
}
