package com.love.soulmate.platform

/**
 * 平台注册表。
 *
 * 设计目的：App 不能只绑死 目标应用。以后接青藤/牵手/小红书时，
 * 只要实现 PlatformDriver 并在这里注册，UI 的勾选项会自动出现，
 * 不需要改界面代码。
 *
 * 目前状态：
 *   目标应用      —— 已实现（读列表/读对话/发送/匹配）
 *   其他      —— 占位，勾选后会在日志里提示「未实现」
 */
enum class Platform(
    val id: String,
    val label: String,
    val pkg: String,
    val implemented: Boolean,
    val note: String = "",
) {
    SOUL(
        id = "soul",
        label = "Soul",
        pkg = "cn.soulapp.android",
        implemented = true,
        note = "已支持：匹配 / 读消息 / 自动回复",
    ),
    QINGTENG(
        id = "qingteng",
        label = "青藤之恋",
        pkg = "com.qingteng.app",
        implemented = false,
        note = "预留：只评论不私聊",
    ),
    QIANSHOU(
        id = "qianshou",
        label = "牵手",
        pkg = "com.qianshou.app",
        implemented = false,
        note = "预留",
    ),
    XIAOHONGSHU(
        id = "xiaohongshu",
        label = "小红书",
        pkg = "com.xingin.xhs",
        implemented = false,
        note = "预留：未互关私信受限",
    ),
    TANTAN(
        id = "tantan",
        label = "探探",
        pkg = "com.p1.mobile.putong",
        implemented = false,
        note = "预留",
    );

    companion object {
        fun byId(id: String): Platform? = entries.firstOrNull { it.id == id }

        /** 默认只勾「已实现」的平台，未实现的一律不勾 */
        fun defaultSelected(): Set<String> =
            entries.filter { it.implemented }.map { it.id }.toSet()

        /** 过滤掉未实现的（历史配置里可能残留），保证跑起来不会卡 */
        fun sanitize(ids: Set<String>): Set<String> {
            val usable = ids.mapNotNull { byId(it) }.filter { it.implemented }
            return if (usable.isEmpty()) defaultSelected() else usable.map { it.id }.toSet()
        }

        /**
         * 拿这个平台的驱动实现。
         *
         * ── 2026-11-24 说明 ──
         * 原来这里返回 PlatformDriver（分叉分支的多平台抽象层），
         * 但那条分支的方案没被采用 —— Orchestrator 直接调 目标应用Bot，
         * 没有走 driver 抽象。所以 PlatformDriver.kt / 目标应用Driver.kt 已删除。
         *
         * 现在这个函数保留但返回 null，等真正接第二个平台时再按需实现。
         */
        fun driverOf(id: String): Any? = null

        /** 所有「已实现」的平台（不再检查 driver，因为已弃用该抽象） */
        fun runnable(): List<Platform> = entries.filter { it.implemented }
    }
}
