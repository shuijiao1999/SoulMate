package com.love.soulmate.llm

import android.content.Context

/**
 * 用户人设配置 —— 让模型生成的回复"像本人说的"。
 *
 * ── 为什么要做成配置（2026-11-26 开源前重写）──
 *
 * 原来这一大段是**硬编码在 Replier.kt 里的**，里面写死了开发者本人的：
 * 出生年份、老家、现居城市和区、职业、身高、学历、作息、爱好…
 *
 * 一旦开源，等于把自己的个人档案公开了。而且别人拿去也用不了
 * —— 别人的身高年龄和这段描述完全不符，生成的话会很奇怪。
 *
 * ── 现在 ──
 * 全部改成用户自己在设置里填。没填时用中性默认值（不描述任何具体信息），
 * 模型只会模仿说话风格，不会编造具体事实。
 *
 * ── 设计要点 ──
 * 拆成「风格」和「事实」两部分：
 *   · 风格（style）：怎么说话 —— 长度、标点、口头禅。可以给默认值
 *   · 事实（facts）：我是谁 —— 年龄、职业、爱好。默认空，必须用户填
 *
 * 这样即使不填事实，回复也不会翻车（只是内容空泛些），
 * 而不是像硬编码那样必然说错。
 */
data class Persona(
    /** 性别，影响称呼和语气。male / female / other */
    val gender: String = "male",

    /**
     * 我的真实情况（自由文本，一行一条）。
     * 模型被要求"只能照这些说，不许编"。
     * 默认空 → 模型不会提及任何具体个人信息。
     */
    val facts: String = "",

    /**
     * 说话风格描述（自由文本）。
     * 默认给一份中性的"短句、口语、不用句号"风格，
     * 用户可以改成自己的。
     */
    val style: String = DEFAULT_STYLE,
) {
    companion object {
        private const val PREF = "persona"

        /**
         * 默认说话风格。
         *
         * 这是**通用的中文口语风格**，不针对任何具体的人：
         *   · 短句（8-12 字）
         *   · 不用句号感叹号
         *   · 用空格代标点
         *   · 允许不完整
         *
         * 用户可以在设置里换成自己的统计结果。
         */
        const val DEFAULT_STYLE = """- 平均 8 个字，最多 12 个字
- 不用句号，不用感叹号
- 问句几乎不用问号，用「吗/啊/呢/吧/嘛」收尾
- 用空格代替标点
- 允许不完整、允许口语"""

        fun load(ctx: Context): Persona {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return Persona(
                gender = sp.getString("gender", "male") ?: "male",
                facts = sp.getString("facts", "") ?: "",
                style = sp.getString("style", DEFAULT_STYLE) ?: DEFAULT_STYLE,
            )
        }

        fun save(ctx: Context, p: Persona) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("gender", p.gender)
                .putString("facts", p.facts)
                .putString("style", p.style)
                .apply()
        }
    }

    /** 性别对应的称呼（给 prompt 用） */
    val genderLabel: String
        get() = when (gender) {
            "female" -> "女生"
            "other" -> "人"
            else -> "男生"
        }

    /**
     * 拼出 system prompt 的"关于我"部分。
     *
     * ★ 关键：**facts 为空时明确告诉模型"不要提任何具体个人信息"**。
     *   否则模型会自动编造年龄职业，反而更容易穿帮。
     */
    fun factsBlock(): String {
        if (facts.isBlank()) {
            return """【关于你自己】
你没有具体的个人信息可依据（用户没填）。
因此：
- 不要主动提及年龄、职业、住址、身高、学历、收入
- 被问到时，用「这个先不说」「你猜」这类方式自然带过
- 可以聊当下的感受和兴趣，但不要给出具体数字或地名"""
        }
        return """【关于你自己的真实情况 —— 只能照这些说，不许编】
$facts"""
    }
}
