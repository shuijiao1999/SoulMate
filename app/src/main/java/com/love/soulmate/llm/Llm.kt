package com.love.soulmate.llm

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GlmResult(val ok: Boolean, val text: String, val error: String = "")

/**
 * 模型接口。完全可配置：baseUrl / apiKey / model 都由用户在设置里填，
 * 不写死任何一家。
 *
 * 默认兼容两种协议：
 *   OpenAI 格式  /v1/chat/completions
 *   Anthropic 格式 /v1/messages
 * 自动判断：url 里含 messages 走 Anthropic，否则走 OpenAI。
 */
data class LlmConfig(
    val baseUrl: String = DEFAULT_BASE,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.9,
) {
    companion object {
        // ★ 默认值故意留空：开源版本不内置任何网关地址或密钥。
        //
        // 用户必须在 App 设置里填自己的：
        //   · API 地址（如 https://api.openai.com/v1）
        //   · API Key
        //   · 模型名
        //
        // ── 为什么留空 ──
        // 之前的版本把某商用网关的地址和 key 写死在代码里，
        // 一旦公开仓库就等于把密钥送出去了。
        // 现在改成空字符串，App 启动时会提示「请先填 API Key」。
        const val DEFAULT_BASE = ""
        const val DEFAULT_MODEL = ""
        const val DEFAULT_KEY = ""

        fun load(ctx: Context): LlmConfig {
            val sp = ctx.getSharedPreferences("llm", Context.MODE_PRIVATE)
            return LlmConfig(
                baseUrl = sp.getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE,
                apiKey = sp.getString("apiKey", DEFAULT_KEY) ?: DEFAULT_KEY,
                model = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
                maxTokens = sp.getInt("maxTokens", 4096),
                temperature = sp.getFloat("temperature", 0.9f).toDouble(),
            )
        }

        fun save(ctx: Context, c: LlmConfig) {
            ctx.getSharedPreferences("llm", Context.MODE_PRIVATE).edit()
                .putString("baseUrl", c.baseUrl)
                .putString("apiKey", c.apiKey)
                .putString("model", c.model)
                .putInt("maxTokens", c.maxTokens)
                .putFloat("temperature", c.temperature.toFloat())
                .apply()
        }
    }
}

object Llm {

    var config: LlmConfig = LlmConfig()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun ask(system: String, user: String): GlmResult {
        val c = config
        if (c.apiKey.isBlank()) return GlmResult(false, "", "未配置 API Key")
        if (c.baseUrl.isBlank()) return GlmResult(false, "", "未配置 Base URL")

        val anthropic = c.baseUrl.contains("/messages") ||
                c.baseUrl.contains("anthropic") ||
                c.model.startsWith("claude") ||
                c.model.startsWith("glm")

        return if (anthropic) askAnthropic(c, system, user) else askOpenAi(c, system, user)
    }

    // OpenAI 风格：POST {base}/chat/completions
    private fun askOpenAi(c: LlmConfig, system: String, user: String): GlmResult {
        val url = c.baseUrl.trimEnd('/') + "/chat/completions"
        return try {
            val body = JSONObject().apply {
                put("model", c.model)
                put("max_tokens", c.maxTokens)
                put("temperature", c.temperature)
                put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
                )
            }.toString()

            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${c.apiKey}")
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return GlmResult(false, "", "HTTP ${resp.code} ${raw.take(120)}")
                }
                val j = JSONObject(raw)
                val choices = j.optJSONArray("choices") ?: return GlmResult(false, "", "无 choices")
                if (choices.length() == 0) return GlmResult(false, "", "choices 为空")
                val msg = choices.getJSONObject(0).optJSONObject("message")
                val text = msg?.optString("content", "")?.trim()
                    ?: choices.getJSONObject(0).optString("text", "").trim()
                if (text.isBlank()) GlmResult(false, "", "返回空文本")
                else GlmResult(true, text)
            }
        } catch (t: Throwable) {
            GlmResult(false, "", t.javaClass.simpleName + ": " + (t.message ?: "").take(80))
        }
    }

    // Anthropic 风格：POST {base}/messages
    private fun askAnthropic(c: LlmConfig, system: String, user: String): GlmResult {
        var url = c.baseUrl.trimEnd('/')
        if (!url.endsWith("/messages")) url += "/messages"

        return try {
            val body = JSONObject().apply {
                put("model", c.model)
                put("max_tokens", maxOf(c.maxTokens, 6400)) // GLM 的 thinking 会吃掉额度
                put("temperature", c.temperature)
                put("system", system)
                put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", user)
                ))
            }.toString()

            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("x-api-key", c.apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return GlmResult(false, "", "HTTP ${resp.code} ${raw.take(120)}")
                }
                val j = JSONObject(raw)
                val arr = j.optJSONArray("content") ?: return GlmResult(false, "", "无 content")
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    if (p.optString("type") == "text") sb.append(p.optString("text"))
                }
                val text = sb.toString().trim()
                if (text.isBlank()) GlmResult(false, "", "返回空文本")
                else GlmResult(true, text)
            }
        } catch (t: Throwable) {
            GlmResult(false, "", t.javaClass.simpleName + ": " + (t.message ?: "").take(80))
        }
    }

    /** 连通性测试 */
    fun test(): GlmResult = ask("你是助手，只输出回复内容本身。", "回复两个字：收到")

    // ---------- 视觉调用（带图片）----------

    /**
     * 视觉模型独立配置。
     *
     * 可以填和文本模型同一个（只要它支持图片输入），也可以单独指定一个
     * 更便宜的视觉模型。两者互不影响。
     *
     * 实测参考：支持看图的模型用来「在截图里找坐标」是可行的，
     * 偏差通常在 10px 以内，单次调用耗时 3 秒左右。
     */
    data class VisionConfig(
        val enabled: Boolean = true,
        val baseUrl: String = DEFAULT_BASE,
        val apiKey: String = LlmConfig.DEFAULT_KEY,
        val model: String = DEFAULT_MODEL,
        val maxTokens: Int = 512,
    ) {
        companion object {
            // 同样不内置网关地址和模型名，由用户在自己设置里填
            const val DEFAULT_BASE = ""
            const val DEFAULT_MODEL = ""

            fun load(ctx: Context): VisionConfig {
                val sp = ctx.getSharedPreferences("llm", Context.MODE_PRIVATE)
                return VisionConfig(
                    enabled = sp.getBoolean("visionEnabled", true),
                    baseUrl = sp.getString("visionBase", DEFAULT_BASE) ?: DEFAULT_BASE,
                    apiKey = sp.getString("visionKey", LlmConfig.DEFAULT_KEY)
                        ?: LlmConfig.DEFAULT_KEY,
                    model = sp.getString("visionModel", DEFAULT_MODEL) ?: DEFAULT_MODEL,
                    maxTokens = sp.getInt("visionMaxTokens", 512),
                )
            }

            fun save(ctx: Context, c: VisionConfig) {
                ctx.getSharedPreferences("llm", Context.MODE_PRIVATE).edit()
                    .putBoolean("visionEnabled", c.enabled)
                    .putString("visionBase", c.baseUrl)
                    .putString("visionKey", c.apiKey)
                    .putString("visionModel", c.model)
                    .putInt("visionMaxTokens", c.maxTokens)
                    .apply()
            }
        }
    }

    var visionConfig: VisionConfig = VisionConfig()

    /**
     * 带图片提问。图片以 base64 传入（OpenAI 兼容的 image_url 格式）。
     *
     * 只用 OpenAI 协议 —— 实测网关的视觉模型都走这个格式，
     * Anthropic 的 image source 格式在这台网关上没验证过，不做猜测。
     */
    fun askVision(prompt: String, pngBase64: String, maxTokens: Int = -1): GlmResult {
        val c = visionConfig
        if (!c.enabled) return GlmResult(false, "", "视觉未启用")
        if (c.apiKey.isBlank()) return GlmResult(false, "", "视觉未配 Key")

        val url = c.baseUrl.trimEnd('/') + "/chat/completions"
        return try {
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url", JSONObject()
                            .put("url", "data:image/jpeg;base64,$pngBase64")
                    )
                )

            val body = JSONObject().apply {
                put("model", c.model)
                put("max_tokens", if (maxTokens > 0) maxTokens else c.maxTokens)
                put("temperature", 0.1)
                put(
                    "messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", content)
                    )
                )
            }.toString()

            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${c.apiKey}")
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return GlmResult(false, "", "HTTP ${resp.code} ${raw.take(100)}")
                }
                val j = JSONObject(raw)
                val choices = j.optJSONArray("choices") ?: return GlmResult(false, "", "无 choices")
                if (choices.length() == 0) return GlmResult(false, "", "choices 为空")
                val msg = choices.getJSONObject(0).optJSONObject("message")
                val txt = msg?.optString("content", "")?.trim() ?: ""
                if (txt.isBlank()) GlmResult(false, "", "空回复") else GlmResult(true, txt)
            }
        } catch (t: Throwable) {
            GlmResult(false, "", t.javaClass.simpleName + ": " + (t.message ?: "").take(80))
        }
    }

    /** 从可能被 JSON 包了一层的返回里抠出真正文本 */
    fun unwrap(text: String): String {
        var cur = text.trim()
        repeat(3) {
            if (!cur.startsWith("{")) return cur
            val m = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(cur) ?: return cur
            cur = try {
                org.json.JSONTokener("\"${m.groupValues[1]}\"").nextValue() as String
            } catch (_: Throwable) {
                return cur
            }
        }
        return cur.trim()
    }
}
