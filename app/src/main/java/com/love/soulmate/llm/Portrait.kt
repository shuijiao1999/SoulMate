package com.love.soulmate.llm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.love.soulmate.core.Screen
import com.love.soulmate.core.Shell

/**
 * 人像审美筛选。
 *
 * ── 用户需求（2026-11-24）──
 * 「如果空间有人像图的话，我希望你能识别下人像，
 *   我喜欢的女孩子是比较瘦，然后图片的美颜不要太严重，长相比较可爱的」
 *
 * ── 怎么实现 ──
 * 三步：
 *   1. 从帖子配图区域截出图片（用 u2 的 bounds）
 *   2. 把图片发给视觉模型，让它打分
 *   3. 分数不够就跳过这个人
 *
 * ── 评分设计（关键）──
 * 用户给了三个维度，权重不同：
 *
 *   slim    瘦        —— 最重要，用户明确说「比较瘦」
 *   natural 美颜轻    —— 重要，「不要太严重」
 *   cute    可爱      —— 加分项，「长相可爱」
 *
 * 模型输出 JSON，这里转成 0-100 分：
 *   基础 50
 *   slim:    +25（瘦）/+10（匀称）/-15（偏胖）
 *   natural: +15（自然）/+5（轻微）/ -20（重度美颜）
 *   cute:    +10（可爱）/0（一般）
 *   不是人像：直接 0 分（跳过）
 *
 * ── 为什么要量化 ──
 * 让模型直接说「我喜欢/不喜欢」不稳定（每次判断飘）。
 * 拆成三个可观测维度打分，再按权重算，结果稳定得多，
 * 而且用户如果觉得「瘦」更重要，可以调权重。
 */
object Portrait {

    /** 一张图的人像评估 */
    data class Score(
        /** 是不是人像（有人脸/人物） */
        val isPortrait: Boolean = false,
        /** 瘦：0-10，10 = 很瘦 */
        val slim: Int = 0,
        /** 美颜轻：0-10，10 = 完全自然 */
        val natural: Int = 0,
        /** 可爱：0-10 */
        val cute: Int = 0,
        /** 综合分 0-100 */
        val total: Int = 0,
        /** 模型给的一句话理由 */
        val reason: String = "",
    ) {
        companion object {
            val EMPTY = Score()
        }
    }

    /** 通过分数线（默认 60） */
    private const val PASS_LINE = 60

    /**
     * 评估当前屏幕上某个区域的图片。
     *
     * @param bounds 图片所在区域 [x1, y1, x2, y2]
     * @return 评分；截图或识别失败返回 null
     */
    fun evaluateRegion(bounds: IntArray): Score? {
        if (bounds.size < 4) return null
        val x1 = bounds[0]
        val y1 = bounds[1]
        val x2 = bounds[2]
        val y2 = bounds[3]
        if (x2 - x1 < 80 || y2 - y1 < 80) return null   // 太小，不是人像图

        // ① 截屏
        val full = screenshotBase64() ?: return null

        // ② 问模型
        val q = buildString {
            appendLine("这是一张社交软件里的照片。请评估里面的人像。")
            appendLine()
            appendLine("严格按以下标准打分（1-10 分）：")
            appendLine("1. slim（身材瘦）：10=明显偏瘦/骨架细，5=匀称，1=偏胖")
            appendLine("2. natural（美颜轻）：10=几乎无美颜/素颜感，5=轻微滤镜，1=重度磨皮/脸型失真")
            appendLine("3. cute（长相可爱）：10=非常可爱，5=一般，1=不符合")
            appendLine()
            appendLine("如果图里没有人脸或人物（纯风景/食物/宠物/文字截图），")
            appendLine("isPortrait 填 false，其余填 0。")
            appendLine()
            appendLine("只输出 JSON，不要解释：")
            appendLine("{\"isPortrait\":true,\"slim\":8,\"natural\":5,\"cute\":7,\"reason\":\"一句话\"}")
        }

        val raw = try {
            com.love.soulmate.core.Vision.describe(q)
        } catch (_: Throwable) {
            return null
        }
        if (raw.isBlank()) return null

        return parseScore(raw)
    }

    /**
     * 评估一张本地图片文件（用于已经截好的图）。
     */
    fun evaluateImageFile(path: String): Score? {
        if (!java.io.File(path).exists()) return null
        return evaluateRegion(intArrayOf(0, 0, Screen.width(), Screen.height()))
    }

    /** 解析模型返回的 JSON */
    fun parseScore(text: String): Score? {
        // 提取 JSON（模型可能包在 ```json 里）
        val json = Regex("""\{[\s\S]*?\}""").find(text)?.value ?: return null
        return try {
            val o = org.json.JSONObject(json)
            val isP = o.optBoolean("isPortrait", false)
            val slim = o.optInt("slim", 0).coerceIn(0, 10)
            val natural = o.optInt("natural", 0).coerceIn(0, 10)
            val cute = o.optInt("cute", 0).coerceIn(0, 10)
            val reason = o.optString("reason", "")

            if (!isP) {
                Score(false, 0, 0, 0, 0, reason.ifBlank { "不是人像" })
            } else {
                Score(true, slim, natural, cute, computeTotal(slim, natural, cute), reason)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 算综合分。
     *
     * 权重按用户优先级：
     *   瘦 40%、自然 35%、可爱 25%
     * （用户先说瘦，再说美颜不严重，最后才是可爱）
     */
    fun computeTotal(slim: Int, natural: Int, cute: Int): Int {
        // 先归一化到 0-100
        val s = slim.coerceIn(0, 10) * 10
        val n = natural.coerceIn(0, 10) * 10
        val c = cute.coerceIn(0, 10) * 10
        return (s * 0.40 + n * 0.35 + c * 0.25).toInt()
    }

    /** 是否达标 */
    fun passes(score: Score): Boolean = score.isPortrait && score.total >= PASS_LINE

    /** 截屏并转 base64 */
    private fun screenshotBase64(): String? {
        val paths = listOf(
            "/sdcard/Download/_sm_portrait.png",
            "/sdcard/_sm_portrait.png",
        )
        for (p in paths) {
            try {
                Shell.run("screencap -p '$p' 2>/dev/null")
                val bytes = readDeviceFile(p) ?: continue
                if (bytes.size > 10_000) {
                    return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** 用 shell cat 读设备上的文件 */
    private fun readDeviceFile(path: String): ByteArray? {
        return try {
            val out = com.love.soulmate.core.Shell.exec("base64 '$path' 2>/dev/null")
            val clean = out.replace(Regex("""\s"""), "")
            if (clean.length < 100) return null
            android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
        } catch (_: Throwable) {
            null
        }
    }

    /** 给用户看的说明 */
    fun describeScore(s: Score): String {
        if (!s.isPortrait) return "不是人像"
        return "瘦${s.slim}/自然${s.natural}/可爱${s.cute} = ${s.total}分"
    }
}
