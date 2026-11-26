package com.love.soulmate.engine

import android.content.Context

/**
 * 功能开关：把整条流程拆成独立可勾选的模块。
 *
 * ── 用户要求（2026-11-24）──
 * 「把所有的功能都拆分出来吧，比如匹配、回复、同城私聊、奇遇铃，
 *   这些都弄成可勾选项，没勾的就不用这个功能」
 *
 * ── 为什么这样设计更好 ──
 * 之前是硬编码顺序「匹配 → 回复 → 同城」，想只测同城必须去关「跳过匹配」，
 * 而且奇遇铃是隐式行为，用户不知道它在跑。
 *
 * 现在每个功能都有明确开关，勾了才跑，顺序固定但可裁剪。
 *
 * ── 执行顺序（按价值排序）──
 *   1. 匹配       —— 主动找新人（消耗额度）
 *   2. 回复会话   —— 维护已有对话（价值最高，几乎免费）
 *   3. 奇遇铃     —— 她主动靠近，含金量最高
 *   4. 同城私聊   —— 主动开拓（消耗风控额度）
 *
 * 顺序不可调，因为「先处理已有的，再开拓新的」是更合理的资源分配。
 */
data class Features(
    /** 灵魂匹配：点「开始匹配」消耗额度，进来的人会自动打招呼 */
    val match: Boolean = false,
    /** 回复已有会话：扫聊天列表，回她最后说的话 */
    val reply: Boolean = true,
    /** 奇遇铃：弹窗出现时点「立即私聊」搭话，聊完回原任务 */
    val miracleBell: Boolean = true,
    /** 同城主动私聊：去广场同城找人搭话 */
    val squareProactive: Boolean = false,
    /** 同城私聊每轮目标人数（只在 squareProactive=true 时生效） */
    val proactiveCount: Int = 15,
    /**
     * 同城广场「帖子评论」（2026-11-25 新增）。
     *
     * ── 为什么单独做一个功能，而不是私聊的副产品 ──
     * 私聊陌生人有「送花开聊」硬拦截，实测 3 个连不上就得放弃整轮；
     * 评论**没有墙**，实测在陌生人帖子下直接评论一次成功。
     * 换句话说：评论是同城拓客里唯一不受风控限制的曝光方式。
     *
     * 而且评论是公开的 —— 她、她的评论区朋友、刷到这条帖子的人都看得见，
     * 一次评论的曝光量比一条私聊大得多。
     */
    val squareComment: Boolean = false,
    /** 同城评论每轮条数（只在 squareComment=true 时生效） */
    val squareCommentCount: Int = 5,
    /**
     * 人像审美筛选（用户要求 2026-11-24）。
     *
     * 「空间有人像图的话，识别下人像。我喜欢比较瘦、美颜不重、长相可爱的」
     *
     * 开了之后：有配图的帖子会先让视觉模型打分，
     * 不合格的直接跳过（省掉后面 20-40 秒的读资料+生成）。
     * 代价：每张图多 3-5 秒。
     */
    val portraitFilter: Boolean = false,
    /**
     * 处理「收到的新招呼」（用户要求 2026-11-25）。
     *
     * 「收到的新招呼你也处理下，里面也会开启私聊」
     *
     * 这批人是**主动来打招呼**的，回复意愿最强。
     * 点进去发一条就直接建立私聊会话（目标应用的规则：
     * 「发消息后对话框将会被放出哦」）。
     */
    val greetings: Boolean = true,
    /**
     * 删除废弃会话（用户要求 2026-11-25，规则细化于同日）。
     *
     * 删除条件**必须同时满足**：
     *   ① 超过 3 天没有新的聊天（用我自己的发送时间戳判断，列表 time 只有「昨天」看不出 3 天）
     *   ② IP 属地不在 [regions] 里（用之前筛人时记录下来的 IP）
     *
     * 数据不够（没记过 IP、没发过消息）一律不删 —— 宁可留着。
     */
    val deleteDeadChats: Boolean = true,
    /**
     * 可接受的地区（多选）。默认江浙沪。
     *
     * 用户要求（2026-11-25）：「这个也可以做一个多选框配置，默认是江浙沪」
     */
    val regions: Set<String> = DEFAULT_REGIONS,
) {
    companion object {
        private const val PREF = "features"

        fun load(ctx: Context): Features {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return Features(
                match = sp.getBoolean("match", false),
                reply = sp.getBoolean("reply", true),
                miracleBell = sp.getBoolean("miracleBell", true),
                squareProactive = sp.getBoolean("squareProactive", false),
                proactiveCount = sp.getInt("proactiveCount", 15),
                squareComment = sp.getBoolean("squareComment", false),
                squareCommentCount = sp.getInt("squareCommentCount", 5),
                portraitFilter = sp.getBoolean("portraitFilter", false),
                greetings = sp.getBoolean("greetings", true),
                deleteDeadChats = sp.getBoolean("deleteDeadChats", true),
                regions = sp.getStringSet("regions", DEFAULT_REGIONS)?.toSet()
                    ?: DEFAULT_REGIONS,
            )
        }

        fun save(ctx: Context, f: Features) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("match", f.match)
                .putBoolean("reply", f.reply)
                .putBoolean("miracleBell", f.miracleBell)
                .putBoolean("squareProactive", f.squareProactive)
                .putInt("proactiveCount", f.proactiveCount)
                .putBoolean("squareComment", f.squareComment)
                .putInt("squareCommentCount", f.squareCommentCount)
                .putBoolean("portraitFilter", f.portraitFilter)
                .putBoolean("greetings", f.greetings)
                .putBoolean("deleteDeadChats", f.deleteDeadChats)
                .putStringSet("regions", f.regions)
                .apply()
        }

        /** 所有可选的地区（多选框用） */
        val ALL_REGIONS = listOf(
            "上海",
            "江苏", "南京", "苏州", "无锡", "常州", "南通", "扬州",
            "镇江", "泰州", "盐城", "淮安", "连云港", "宿迁", "徐州",
            "浙江", "杭州", "宁波", "温州", "嘉兴", "湖州", "绍兴", "金华",
            "衢州", "舟山", "台州", "丽水",
            "北京", "广州", "深圳", "成都", "武汉", "西安", "重庆",
        )

        /** 默认地区：江浙沪 */
        val DEFAULT_REGIONS: Set<String> = setOf(
            "上海",
            "江苏", "南京", "苏州", "无锡", "常州", "南通", "扬州",
            "镇江", "泰州", "盐城", "淮安", "连云港", "宿迁", "徐州",
            "浙江", "杭州", "宁波", "温州", "嘉兴", "湖州", "绍兴", "金华",
            "衢州", "舟山", "台州", "丽水",
        )
    }

    /** 至少勾一个，否则跑起来什么都不做 */
    val any: Boolean
        get() = match || reply || miracleBell || squareProactive || greetings || squareComment

    /** 给日志用的一句话摘要 */
    fun summary(): String {
        val on = ArrayList<String>()
        if (match) on.add("匹配")
        if (reply) on.add("回复")
        if (miracleBell) on.add("奇遇铃")
        if (greetings) on.add("新招呼")
        if (squareProactive) {
            on.add(if (portraitFilter) "同城×$proactiveCount+看脸" else "同城×$proactiveCount")
        }
        if (squareComment) on.add("同城评论×$squareCommentCount")
        return if (on.isEmpty()) "（什么都没勾）" else on.joinToString(" + ")
    }
}
