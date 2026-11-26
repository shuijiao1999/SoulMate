package com.love.soulmate.core

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

data class UiNode(
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
    val text: String,
    val rid: String,
    val cls: String,
    val clickable: Boolean,
    val desc: String,
) {
    val w get() = x2 - x1
    val h get() = y2 - y1
    val cx get() = (x1 + x2) / 2
    val cy get() = (y1 + y2) / 2
}

/**
 * 界面树读取 + 基础操作。
 *
 * 全机型通用性依赖：
 *   · 读取走 u2 server（不依赖分辨率）
 *   · 操作优先按 rid / text 定位节点
 *   · 必须用坐标时一律走相对比例
 */
object Ui {

    private val BOUNDS = Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")

    fun dumpHierarchy(): String {
        // 优先 u2：目标应用列表页持续刷新，系统 dump 会卡在 idle 等待
        val viaU2 = U2.dump()
        if (viaU2.contains("<hierarchy")) return viaU2
        Shell.exec("uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1")
        return Shell.exec("cat /sdcard/_ui.xml")
    }

    fun parse(xml: String): List<UiNode> {
        if (xml.isBlank() || !xml.contains("<hierarchy")) return emptyList()
        val out = ArrayList<UiNode>(256)
        try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            val db = f.newDocumentBuilder()
            val doc = db.parse(InputSource(StringReader(xml)))
            val nodes = doc.getElementsByTagName("node")
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val b = e.getAttribute("bounds") ?: ""
                val m = BOUNDS.matcher(b)
                if (!m.find()) continue
                val x1 = m.group(1).toInt()
                val y1 = m.group(2).toInt()
                val x2 = m.group(3).toInt()
                val y2 = m.group(4).toInt()
                out.add(
                    UiNode(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        text = e.getAttribute("text") ?: "",
                        rid = (e.getAttribute("resource-id") ?: "").substringAfterLast('/'),
                        cls = (e.getAttribute("class") ?: "").substringAfterLast('.'),
                        clickable = e.getAttribute("clickable") == "true",
                        desc = e.getAttribute("content-desc") ?: "",
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }

    fun snap(): List<UiNode> = parse(dumpHierarchy())

    // ---------- 操作 ----------

    fun tap(x: Int, y: Int) {
        if (!U2.click(x, y)) Shell.run("input tap $x $y")
    }

    /** 按屏幕比例点（0.0-1.0），换机型也能用 */
    fun tapRel(rx: Double, ry: Double) {
        tap(Screen.px(rx), Screen.py(ry))
    }

    fun longPress(x: Int, y: Int, ms: Int = 1200) {
        Shell.run("input swipe $x $y $x $y $ms")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 400) {
        Shell.run("input swipe $x1 $y1 $x2 $y2 $ms")
    }

    /** 按屏幕比例滑（0.0-1.0） */
    fun swipeRel(x1: Double, y1: Double, x2: Double, y2: Double, ms: Int = 400) {
        swipe(Screen.px(x1), Screen.py(y1), Screen.px(x2), Screen.py(y2), ms)
    }

    fun back() {
        Shell.run("input keyevent 4")
    }

    // ---------- 输入法 ----------

    private const val IME_ADB = "com.android.adbkeyboard/.AdbIME"

    @Volatile
    private var imeSwitched = false

    private var savedIme: String = ""

    /**
     * 切到 ADBKeyboard。
     *
     * 踩坑（真实反馈）：用户平时用搜狗，代码却往 ADBKeyboard 发广播，
     * 结果 ADBKeyboard 没启用 → 广播没人接 → 文字根本没进输入框，
     * 表现为「找到了要回的人，但就是没发出去」。
     *
     * 所以发消息前必须确认输入法，发完再切回去。
     */
    fun ensureAdbIme(): Boolean {
        val cur = Shell.exec("settings get secure default_input_method").trim()
        if (cur.contains("adbkeyboard")) {
            imeSwitched = false
            return true
        }
        savedIme = cur
        Shell.run("ime enable $IME_ADB")
        Shell.run("ime set $IME_ADB")
        Thread.sleep(1200)
        val now = Shell.exec("settings get secure default_input_method").trim()
        imeSwitched = now.contains("adbkeyboard")
        return imeSwitched
    }

    /** 切回用户原来的输入法，别把人家的键盘弄没了 */
    fun restoreIme() {
        if (!imeSwitched) return
        try {
            if (savedIme.isNotBlank() && !savedIme.contains("adbkeyboard")) {
                Shell.run("ime set $savedIme")
            } else {
                // 找不到原输入法就还原成搜狗（国内机型最常见）
                Shell.run("ime set com.sohu.inputmethod.sogou.oem/" +
                        "com.sohu.inputmethod.sogou.SogouIME")
            }
            Thread.sleep(600)
        } catch (_: Throwable) {
        }
        imeSwitched = false
    }

    /**
     * 输入文本（含中文）。
     *
     * 不再用 ADBKeyboard —— 实测它会让 目标应用的输入框节点直接消失。
     * 改走 u2 剪贴板（setClipboard + pasteClipboard，一次 shell 内完成）。
     */
    fun inputText(text: String): Boolean = U2.inputText(text)

    fun clearInput(): Boolean = U2.clearInput()

    // ---------- 前台信息 ----------

    private val FOCUS_RE = Regex("""u(\d+)\s+([\w.]+)/([\w.$]+)""")

    /**
     * 解析当前焦点窗口。
     *
     * ★ 踩坑（模拟器上暴露的严重问题）：
     *   `dumpsys window | grep mCurrentFocus` 在某些设备上会输出**多行**
     *   （模拟器就是两行：App 和 目标应用 各一行）。
     *   原来用 findFirst 取第一行，读到的是 App，于是永远判定「不在 目标应用」，
     *   整轮流程被跳过。
     *
     *   正确做法：**取最后一行**（dumpsys 里越靠后越靠上层），
     *   并且如果最后一个不是目标包、但列表里确实有目标包，就认为目标包可见。
     */
    private fun focusLines(): List<Triple<Int, String, String>> {
        val out = Shell.exec("dumpsys window | grep mCurrentFocus")
        return FOCUS_RE.findAll(out).map {
            Triple(
                it.groupValues[1].toIntOrNull() ?: -1,
                it.groupValues[2],
                it.groupValues[3].substringAfterLast('.')
            )
        }.toList()
    }

    /** 当前最上层包名（多行时取最后一行） */
    fun currentPackage(): String = focusLines().lastOrNull()?.second ?: ""

    /** 当前最上层 Activity（多行时取最后一行） */
    fun currentActivity(): String = focusLines().lastOrNull()?.third ?: ""

    /** 当前用户空间（多行时取最后一行） */
    fun currentUser(): Int = focusLines().lastOrNull()?.first ?: -1

    /**
     * 指定包是否在前台可见。
     * 多行输出时，只要列表里有它就算可见（模拟器同时挂两个窗口的情况）。
     */
    fun isForeground(pkg: String): Boolean = focusLines().any { it.second == pkg }

    /**
     * 等焦点稳定后再读，避免读到「切换过程中」的空值。
     *
     * ★ 踩坑：App 调 moveTaskToBack 后约 1.5-2 秒内，mCurrentFocus 会短暂为空。
     *   原来直接把「空」当成「App 已退出」，结果紧接着去读当前包名拿到空串，
     *   整轮跑挂（日志表现为「读不到当前用户空间」）。
     *
     * @param timeoutMs 最长等待
     * @return 是否等到了有效焦点
     */
    fun waitFocusReady(timeoutMs: Long = 6000): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (focusLines().isNotEmpty()) return true
            Thread.sleep(300)
        }
        return focusLines().isNotEmpty()
    }

    /**
     * 等指定包退出前台（切后台/被覆盖）。
     * 焦点为空时**继续等**，不算退出。
     */
    fun waitUntilNotForeground(pkg: String, timeoutMs: Long = 8000): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val lines = focusLines()
            if (lines.isNotEmpty() && lines.none { it.second == pkg }) return true
            Thread.sleep(400)
        }
        return focusLines().none { it.second == pkg }
    }
}
