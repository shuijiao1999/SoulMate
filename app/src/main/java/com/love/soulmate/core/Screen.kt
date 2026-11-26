package com.love.soulmate.core

/**
 * 屏幕尺寸感知 + 坐标换算。
 *
 * 为什么要有这一层（换机型就废过很多次）：
 *   原来所有坐标都写死成 1600x2560 下的实测值（比如发送按钮 1514,2461），
 *   换一台手机全废：点偏、滑不动、左右判断反掉。
 *
 * ★ 坐标换算的四层策略（从准到糊）：
 *   1) **节点定位**：按 resource-id / text 找到节点直接点它的中心 —— 最准，跨机型零误差
 *   2) **dp 换算**：固定高度的 UI 元素（底部 tab 栏、导航栏）用 dp 算，不随屏幕高度变形
 *   3) **比例兜底**：纯比例只在没节点、不知道 dp 时用
 *   4) 绝不写死像素
 *
 * 为什么比例不如 dp：
 *   同一个「底部 tab 栏」在 1600x2560（高 2560）里占 1.8%，
 *   在 1080x2340（高 2340）里占 2.0% —— 按比例算就会差几十像素。
 *   但它实际是固定的 56dp，用 dp 换算两台机器都对。
 */
object Screen {

    @Volatile
    private var w = 0

    @Volatile
    private var h = 0

    @Volatile
    private var density = 0f

    fun width(): Int {
        if (w > 0) return w
        measure()
        return w
    }

    fun height(): Int {
        if (h > 0) return h
        measure()
        return h
    }

    /** 每 dp 对应多少像素（density=3.0 表示 1dp=3px） */
    fun density(): Float {
        if (density > 0f) return density
        measure()
        return if (density > 0f) density else 3f
    }

    fun reset() {
        w = 0
        h = 0
        density = 0f
    }

    private fun measure() {
        // wm size 输出：Physical size: 1600x2560
        try {
            val out = Shell.exec("wm size")
            val m = Regex("(\\d+)x(\\d+)").find(out)
            if (m != null) {
                w = m.groupValues[1].toInt()
                h = m.groupValues[2].toInt()
            }
        } catch (_: Throwable) {
        }

        // wm density 输出：Physical density: 320
        try {
            val out = Shell.exec("wm density")
            val m = Regex("(\\d+)").findAll(out).lastOrNull()
            if (m != null) {
                val dpi = m.groupValues[1].toIntOrNull() ?: 0
                if (dpi > 0) density = dpi / 160f
            }
        } catch (_: Throwable) {
        }

        if (w > 0 && h > 0) return

        // 兜底：从界面树推（取最大的 bounds）
        try {
            val nodes = Ui.snap()
            var mw = 0
            var mh = 0
            for (n in nodes) {
                if (n.x2 > mw) mw = n.x2
                if (n.y2 > mh) mh = n.y2
            }
            if (mw > 0 && mh > 0) {
                w = mw
                h = mh
            }
        } catch (_: Throwable) {
        }

        if (w <= 0) {
            w = 1080
            h = 2340
        }
    }

    /** 屏幕是否横屏（宽 > 高） */
    fun isLandscape(): Boolean = width() > height()

    fun px(rx: Double): Int = (width() * rx).toInt()

    fun py(ry: Double): Int = (height() * ry).toInt()

    /** dp → 像素 */
    fun dp(v: Int): Int = (v * density()).toInt()

    /** dp → 像素（小数） */
    fun dp(v: Float): Int = (v * density()).toInt()

    /** 从屏幕底部往上量 dp 的 y 坐标（底部固定栏用这个最准） */
    fun fromBottomDp(dpFromBottom: Int): Int = height() - dp(dpFromBottom)

    /** 从屏幕顶部往下量 dp 的 y 坐标（顶部固定栏用这个最准） */
    fun fromTopDp(dpFromTop: Int): Int = dp(dpFromTop)

    /** 从屏幕右侧往左量 dp 的 x 坐标 */
    fun fromRightDp(dpFromRight: Int): Int = width() - dp(dpFromRight)

    /**
     * 判断消息是谁发的（她 or 我）。
     *
     * ── 实测数据（1080x2340 模拟器，2026-11-23）──
     *   我发的：x1 = 510~525（长句），x2 = 859，距右 = 221
     *   她发的：x1 = 221（固定，贴左侧头像），x2 = 260~533，距右 ≥ 547
     *
     * ── 踩过的坑 ──
     *   原来用 x1 > 屏宽/2（540）判断，结果我的一条长消息 x1=510，
     *   刚好小于阈值，被判成「她发的」，导致整条会话判断反了
     *   （表现为：明明是我最后说的，却认为她需要回复，或者反过来）。
     *
     * ── 为什么用 x1 而不是 x2 ──
     *   x2 受文字长度影响（长句 x2 大，短句 x2 小），不稳定。
     *   x1 取决于气泡对齐方式：
     *     她 → 气泡左边固定贴着头像，x1 几乎是常量 221
     *     我 → 气泡右对齐，x1 随宽度变化但总是远大于 221
     *   所以用「x1 是否明显大于左侧边距」判断最稳。
     *
     * 阈值取屏宽的 0.35（1080 下 = 378）：远大于她消息的 221，
     * 又明显小于我的 510，中间留足容错。
     */
    fun sideOf(x1: Int): String {
        val threshold = (width() * 0.35).toInt()
        return if (x1 > threshold) "me" else "her"
    }

    /** 日志用：当前识别到的屏幕尺寸 + 密度 */
    fun desc(): String = "${width()}x${height()} @${density()}x"
}
