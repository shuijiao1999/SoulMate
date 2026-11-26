package com.love.soulmate.platform

/**
 * 平台间共享的数据模型。
 *
 * 从 目标应用Bot 里提出来，让所有平台的 driver 共用同一套结构，
 * 这样 Orchestrator 处理「她说」和「目标应用」的代码完全一样。
 */

/** 会话列表里的一行 */
data class ChatRow(
    val name: String,
    val preview: String,
    val time: String = "",
    /** 未读条数，0 表示没有未读（没这个概念的平台传 0） */
    val unread: Int = 0,
    /** 系统标签，如「奇遇铃」「匹配」「新注册」 */
    val tag: String = "",
    /** 是否有「喜欢」标记（目标应用的红心） */
    val hearted: Boolean = false,
)

/** 一条消息 */
data class Msg(val side: String, val text: String, val y: Int = 0)

/** 一次对话的完整内容 */
data class ChatDetail(
    val with: String = "",
    val messages: List<Msg> = emptyList(),
) {
    val lastSide: String get() = messages.lastOrNull()?.side ?: ""
    val lastText: String get() = messages.lastOrNull()?.text ?: ""
}

/**
 * 对方的资料。
 * 各平台能拿到的字段不同，拿不到的留空字符串/空列表。
 */
data class Profile(
    /** IP 属地，如「上海」 */
    val ip: String = "",
    /** 原始标签列表（引力签、认证、兴趣等） */
    val raw: List<String> = emptyList(),
    /** 匹配度，如「95%」 */
    val match: String = "",
)

/** 发送结果 */
data class SendResult(val ok: Boolean, val error: String = "")
