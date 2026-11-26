package com.love.soulmate.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import com.love.soulmate.llm.Llm
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 视觉定位：给界面上的可点击元素打编号，让视觉模型说「点第几个」。
 *
 * ── 这个设计的来历 ──
 * 参考腾讯 AppAgent（TencentQQGYLab/AppAgent）的标注法：
 *   1. uiautomator dump 拿到每个可交互元素的 bounds
 *   2. 在截图上给每个元素画编号气泡
 *   3. 把带编号的截图喂给 VLM，让它输出 tap(N)
 *   4. 按编号查回元素真实 bounds，点它的中心
 *
 * ── 为什么这个设计能解决我们的问题 ──
 * 之前踩的坑：`main_tab_msg_text` 只有 50x34 像素，按屏幕比例算坐标会偏 46px，
 * 点不中就表现为「找不到元素 / 流程卡住」。
 *
 * 标注法的关键优势：**坐标永远来自 dump，模型只负责「选哪个」**。
 * 模型选错编号只是选错目标，不会出现「模型给的坐标落在空隙里」这种问题。
 *
 * ── 成本 ──
 * 实测：单次调用约 4 秒，误差 4px 左右，按各家模型定价计费。
 * 只在 rid/text 都匹配不到时才调用，正常流程零成本。
 */
object Vision {

    data class Target(val node: UiNode, val index: Int)

    private const val MAX_ELEMS = 60       // 编号太多模型会看花眼，控制在 60 以内
    private const val BUBBLE_DP = 22       // 气泡半径（dp）
    private const val SCREENSHOT_FILE = "vision_shot.png"

    /**
     * 截图并转 base64（失败返回空串）。
     *
     * ★ 必须压缩：原图 PNG 约 1.3 MB，压到 JPEG 质量 70 后约 200 KB。
     *   实测不压缩时模型调用要 9-15 秒，压缩后 3-5 秒，差 3 倍。
     *
     * ★ 路径选择（踩坑 2026-11-24，平板实测）
     *
     *   模拟器：adb root 后 Shizuku 是 root 身份 → 能直接写 App 私有目录。
     *   真机  ：生产版系统，Shizuku 只有 **shell** 身份 →
     *           · 写 /data/user/0/xxx   → Permission denied
     *           · 写 /data/local/tmp    → 能写，但 SELinux 是 shell_data_file，
     *                                      App(untrusted_app) **读不到**
     *           · 写 /sdcard            → 能写，SELinux 是 fuse:s0，App **能读** ✅
     *
     *   所以统一走 /sdcard，两边都能工作。
     */
    private fun screenshotBase64(): String {
        return try {
            val ctx = Env.context() ?: return ""
            val cacheF = File(ctx.cacheDir, SCREENSHOT_FILE)

            // ① 首选：截到 /sdcard（App 能读，模拟器/真机通用）
            val sdcardPath = "/sdcard/Download/_sm_shot.png"
            Shell.run("screencap -p '$sdcardPath' 2>/dev/null")
            var ok = false

            // 从 sdcard 复制到 cacheDir（App 自己读 sdcard 也是可以的，
            // 直接 File 读 /sdcard 需要 MANAGE_EXTERNAL_STORAGE，
            // 所以还是让 App 自己 copy，走 MediaStore 太麻烦）
            try {
                val src = File(sdcardPath)
                if (src.exists() && src.length() > 1000) {
                    src.copyTo(cacheF, overwrite = true)
                    ok = cacheF.length() > 1000
                }
            } catch (t: Throwable) {
                android.util.Log.w("SoulMate", "Vision: 从 sdcard 复制失败 ${t.message}")
            }

            // ② 退路：直接截到 cacheDir（模拟器 root 身份下可行）
            if (!ok) {
                Shell.run("screencap -p ${cacheF.absolutePath} 2>/dev/null")
                ok = cacheF.exists() && cacheF.length() > 1000
            }

            // ③ 再退：/data/local/tmp（有些设备 App 能读）
            if (!ok) {
                val tmp = "/data/local/tmp/_vs_shot.png"
                Shell.run("screencap -p $tmp 2>/dev/null; chmod 644 $tmp 2>/dev/null")
                try {
                    val src = File(tmp)
                    if (src.exists() && src.length() > 1000) {
                        src.copyTo(cacheF, overwrite = true)
                        ok = cacheF.length() > 1000
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("SoulMate", "Vision: 从 tmp 复制失败 ${t.message}")
                }
            }

            if (!ok || !cacheF.exists()) {
                android.util.Log.e("SoulMate", "Vision: 截图不存在（三种路径都失败）")
                return ""
            }

            val raw = cacheF.readBytes()
            // 解码 → 缩放 → JPEG 压缩
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            if (bmp == null) {
                android.util.Log.w("SoulMate", "Vision: 解码失败，用原图")
                return Base64.encodeToString(raw, Base64.NO_WRAP)
            }

            // 宽度超过 800 就等比缩小（模型不需要高清）
            val maxW = 800
            val scaled = if (bmp.width > maxW) {
                val ratio = maxW.toFloat() / bmp.width
                Bitmap.createScaledBitmap(
                    bmp, maxW, (bmp.height * ratio).toInt(), true
                )
            } else bmp

            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 72, bos)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()

            val outBytes = bos.toByteArray()
            android.util.Log.i(
                "SoulMate",
                "Vision: 截图 ${raw.size / 1024}KB → 压缩 ${outBytes.size / 1024}KB"
            )
            Base64.encodeToString(outBytes, Base64.NO_WRAP)
        } catch (t: Throwable) {
            android.util.Log.e("SoulMate", "Vision: 截图异常 ${t.message}")
            ""
        }
    }

    /**
     * 在截图上画编号气泡。
     *
     * @param shotB64 原始截图（PNG/JPEG base64）
     * @param targets 要标注的元素（按顺序编号 1..N）
     * @return 标注后的 base64（JPEG）；失败返回原始图
     */
    private fun annotate(shotB64: String, targets: List<Target>): String {
        return try {
            val raw = Base64.decode(shotB64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return shotB64
            val out = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return shotB64
            val canvas = Canvas(out)

            // ★ 截图已按比例缩放过，标注坐标必须同比例换算
            val scale = out.width.toFloat() / Screen.width()
            val density = Screen.density() * scale
            val r = BUBBLE_DP * density

            val fill = Paint().apply {
                isAntiAlias = true
                color = Color.argb(210, 16, 16, 16)
                style = Paint.Style.FILL
            }
            val stroke = Paint().apply {
                isAntiAlias = true
                color = Color.argb(255, 255, 200, 40)
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
            }
            val text = Paint().apply {
                isAntiAlias = true
                color = Color.WHITE
                textSize = 13f * density
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }

            for (t in targets) {
                val n = t.node
                val x1 = n.x1 * scale
                val y1 = n.y1 * scale
                val x2 = n.x2 * scale
                val y2 = n.y2 * scale

                canvas.drawRect(RectF(x1, y1, x2, y2), stroke)

                val cx = (x1 + x2) / 2f
                val cy = (y1 + y2) / 2f
                canvas.drawCircle(cx, cy, r, fill)
                canvas.drawCircle(cx, cy, r, stroke)
                val ty = cy - (text.descent() + text.ascent()) / 2
                canvas.drawText(t.index.toString(), cx, ty, text)
            }

            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 72, bos)
            out.recycle()
            if (out !== bmp) bmp.recycle()
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            android.util.Log.e("SoulMate", "Vision: 标注失败 ${t.message}")
            shotB64
        }
    }

    /**
     * 用视觉找元素并点击。
     *
     * @param hint 要点的东西，用自然语言描述（如「聊天 tab」「发送按钮」）
     * @param nodes 当前界面节点（为空则自己 snap）
     * @return 点击成功返回被点的节点，失败返回 null
     */
    fun tapByVision(hint: String, nodes: List<UiNode>? = null): UiNode? {
        if (!Llm.visionConfig.enabled) return null

        val all = nodes ?: Ui.snap()
        if (all.isEmpty()) return null

        // 候选：clickable 的节点，按面积从大到小（大目标更可能是导航类元素）
        val clickable = all.filter { it.clickable && it.w > 0 && it.h > 0 }
            .sortedByDescending { it.w.toLong() * it.h }
            .take(MAX_ELEMS)

        if (clickable.isEmpty()) return null

        val targets = clickable.mapIndexed { i, n -> Target(n, i + 1) }

        val shot = screenshotBase64()
        if (shot.isBlank()) return null

        val labeled = annotate(shot, targets)

        val prompt = buildString {
            appendLine("这是一张手机截图，图中的可交互元素已经用带数字的圆点标注，")
            appendLine("数字就在元素中心，元素外有细边框。")
            appendLine()
            appendLine("请找出「$hint」。")
            appendLine()
            appendLine("只输出一个 JSON，不要任何其他文字：")
            appendLine("{\"index\": 数字}")
            appendLine()
            appendLine("如果图上找不到这个元素，输出 {\"index\": 0}")
        }

        val r = Llm.askVision(prompt, labeled, 128)
        if (!r.ok) return null

        val idx = parseIndex(r.text)
        if (idx <= 0 || idx > targets.size) return null

        val picked = targets[idx - 1].node
        Ui.tap(picked.cx, picked.cy)
        return picked
    }

    /** 从模型回复里抠出 index 数字 */
    fun parseIndex(text: String): Int {
        // 优先找 "index": N
        Regex("\"index\"\\s*:\\s*(\\d+)").find(text)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        // 退而求其次：找 "tap(5)" 这种
        Regex("[Tt]ap\\s*\\(\\s*(\\d+)\\s*\\)").find(text)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        // 再退：单独一个数字
        val nums = Regex("\\d+").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        return nums.firstOrNull { it in 1..MAX_ELEMS } ?: 0
    }

    /**
     * 用视觉读聊天列表里的「红心」标记。
     *
     * ── 为什么需要 ──
     * 实测：目标应用列表里的红心 ❤️ **在无障碍树里完全不存在**，
     * dump 只能拿到 name/message/time，红心是纯图形。
     * 但它恰恰是很有用的信号（我点过「喜欢」的人，或互相喜欢）。
     *
     * 一次调用返回所有带红心的会话名，成本约 0.06 credit。
     *
     * @param names 当前列表里的会话名（按屏幕顺序）
     * @return 带红心的会话名集合
     */
    fun readHearted(names: List<String>): Set<String> {
        if (!Llm.visionConfig.enabled || names.isEmpty()) return emptySet()

        val shot = screenshotBase64()
        if (shot.isBlank()) return emptySet()

        val list = names.take(12).mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n")
        val prompt = buildString {
            appendLine("这是社交 App 的聊天列表截图。")
            appendLine("每一行是一个会话，左边头像是对方，右边是名字和时间。")
            appendLine()
            appendLine("有些会话的**名字右边有一个红色的小爱心图标**（❤️），有些没有。")
            appendLine()
            appendLine("下面是我按屏幕从上到下列出的会话名：")
            appendLine(list)
            appendLine()
            appendLine("请判断**哪些会话的名字右边有红色爱心**。")
            appendLine()
            appendLine("只输出 JSON 数组，不要解释：")
            appendLine("{\"hearted\": [1, 3, 5]}")
            appendLine()
            appendLine("数组元素是我列的序号。如果一个都没有，输出 {\"hearted\": []}")
        }

        val r = Llm.askVision(prompt, shot, 128)
        if (!r.ok) {
            android.util.Log.w("SoulMate", "Vision.readHearted 失败: ${r.error}")
            return emptySet()
        }

        android.util.Log.i("SoulMate", "Vision.readHearted: ${r.text.take(100)}")

        // 解析序号
        val idxs = Regex("\\d+").findAll(r.text)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..names.size }
            .toList()

        return idxs.map { names[it - 1] }.toSet()
    }

    /**
     * 用视觉判断当前在哪个页面/状态（不需要点击，纯理解）。
     * 用于「额度是否用完」「是否弹窗」这类判断。
     */
    fun describe(question: String): String {
        if (!Llm.visionConfig.enabled) {
            android.util.Log.w("SoulMate", "Vision.describe: 未启用")
            return ""
        }
        val shot = screenshotBase64()
        if (shot.isBlank()) return ""
        android.util.Log.i("SoulMate", "Vision.describe: 调模型 ${Llm.visionConfig.model}")
        val t0 = System.currentTimeMillis()
        val r = Llm.askVision(question, shot, 256)
        val dt = System.currentTimeMillis() - t0
        android.util.Log.i(
            "SoulMate",
            "Vision.describe: ${dt}ms ok=${r.ok} err=${r.error.take(60)} " +
                    "text=${r.text.take(60)}"
        )
        return if (r.ok) r.text else ""
    }
}
