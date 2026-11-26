package com.love.soulmate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.love.soulmate.engine.EngineState
import com.love.soulmate.engine.Features
import com.love.soulmate.engine.LogItem
import com.love.soulmate.platform.Platform

// ── 配色：深色紫蓝，科技感，和 目标应用的调性接近但不撞
val Bg0 = Color(0xFF0B0E1A)
val Bg1 = Color(0xFF141A2E)
val CardBg = Color(0xFF1B2238)
val CardHi = Color(0xFF232C47)
val Accent = Color(0xFF7C5CFF)
val Accent2 = Color(0xFF4AC8E8)
val Sent = Color(0xFF4ADE80)
val Warn = Color(0xFFFBBF24)
val Err = Color(0xFFF87171)
val Dim = Color(0xFF8891A8)

@Composable
fun SoulMateApp(
    state: EngineState,
    logs: List<LogItem>,
    shizukuOk: Boolean,
    onRequestShizuku: () -> Unit,
    onRun: () -> Unit,
    onDryRun: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchAccount: () -> Unit,
    selectedPlatforms: Set<String>,
    onTogglePlatform: (String) -> Unit,
    features: Features,
    onFeaturesChange: (Features) -> Unit,
    floatOk: Boolean,
    onRequestFloat: () -> Unit,
    monitoring: Boolean = false,
    onStartMonitor: () -> Unit = {},
    onStopMonitor: () -> Unit = {},
) {
    // 长按 目标应用 选项时弹出的功能配置框
    var showSoulSheet by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Bg0, Bg1)))
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {

            // ── 顶部固定区（不滚动）──
            TopBar(state, shizukuOk, onRequestShizuku, onOpenSettings, onSwitchAccount)
            if (!shizukuOk) {
                ShizukuBanner(onRequestShizuku)
            }

            // ── 中间可滚动区 ──
            //
            // ★ 踩坑（2026-11-24，用户实测反馈）：
            // 原来整个页面是 Column 没有 verticalScroll，
            // 手机上（1080x2340）高度不够时，下面的「立即检查」按钮被挤出屏幕外，
            // 用户根本点不到 —— 选了同城私聊（展开档位后更高）就更点不到了。
            //
            // 修法：
            //   1. 中间部分包一层 verticalScroll
            //   2. 底部「立即检查/演练」固定住不滚（用户明确要求「别让我找不到按钮」）
            //   3. 日志区改成可折叠，默认收起，减少高度占用
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                StatusCards(state)
                Spacer(Modifier.height(8.dp))

                // 平台选择（长按 目标应用 弹出功能配置）
                PlatformPicker(
                    selected = selectedPlatforms,
                    onToggle = onTogglePlatform,
                    onLongPressSoul = { showSoulSheet = true },
                    soulFeatureSummary = features.summary(),
                    running = state.running,
                )
                Spacer(Modifier.height(8.dp))

                // 悬浮窗权限提示
                if (!floatOk) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clickable { onRequestFloat() },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0x33FFA726)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚠", color = Color(0xFFFFA726), fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "开启「悬浮停止按钮」",
                                    color = Color(0xFFFFCC80),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "点了才能随时停止 · 点这里去开",
                                    color = Dim,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 运行日志（可折叠）
                LogSection(
                    logs = logs,
                    running = state.running,
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── 底部固定区：运行按钮永远可见 ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Bg0)))
            ) {
                // ── 常驻监控开关 ──
                //
                // 用户需求（2026-11-24）：
                // 「回复功能应该是一直时刻监控后台是否有新消息的」
                //
                // 开了它就不用再点「立即检查」：
                //   每 30 秒查一次 目标应用 数据库 mtime，
                //   有新消息才进入完整扫描 + 回复流程。
                MonitorBar(
                    monitoring = monitoring,
                    running = state.running,
                    onStart = onStartMonitor,
                    onStop = onStopMonitor,
                )

                if (state.running) {
                    Text(
                        "运行中，配置已锁定。要改配置请先点「停止」。",
                        color = Dim.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
                ActionRow(state, onRun, onDryRun, onStop)
                Spacer(Modifier.height(8.dp))
            }
        }

        // ── 长按 目标应用 弹出的功能配置框 ──
        if (showSoulSheet) {
            SoulFeatureSheet(
                visible = true,
                running = state.running,
                features = features,
                onChange = onFeaturesChange,
                onDismiss = { showSoulSheet = false },
            )
        }
    }
}

@Composable
private fun TopBar(
    state: EngineState,
    shizukuOk: Boolean,
    onRequestShizuku: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchAccount: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Accent, Accent2))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("SoulMate", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (state.running) "正在运行 · ${state.phase}" else "待命",
                color = if (state.running) Sent else Dim,
                fontSize = 12.sp
            )
            if (state.account.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "账号：${state.account}",
                        color = Accent2.copy(alpha = 0.85f),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "切换",
                        color = Accent.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Accent.copy(alpha = 0.12f))
                            .clickable { onSwitchAccount() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Surface(
            shape = CircleShape,
            color = if (shizukuOk) Sent.copy(alpha = 0.15f) else Err.copy(alpha = 0.15f),
            modifier = Modifier.clickable(enabled = !shizukuOk) { onRequestShizuku() }
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (shizukuOk) Sent else Err)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (shizukuOk) "Shizuku 已授权" else "点此授权",
                    color = if (shizukuOk) Sent else Err,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, null, tint = Dim)
        }
    }
}

@Composable
private fun ShizukuBanner(onRequest: () -> Unit) {
    Surface(
        color = Warn.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clickable { onRequest() }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = Warn, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "需要 Shizuku 授权才能操作目标应用，点这里授权。",
                color = Warn, fontSize = 12.sp, modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ChevronRight, null, tint = Warn, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatusCards(state: EngineState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatCard("待处理", state.pending.toString(), Modifier.weight(1f))
        StatCard("本小时发出", state.sentThisHour.toString(), Modifier.weight(1f))
        StatCard("上次检查", state.lastRun.ifBlank { "—" }, Modifier.weight(1f))
    }
}

/**
 * 平台多选。
 *
 * 规则：
 *   · 只勾默认平台（目标应用），未实现的平台一律置灰、不可点
 *   · 未实现的平台即使被历史配置勾上，也会在逻辑里被过滤掉
 *   · 新增平台只需在 Platform 枚举里把 implemented 改成 true，这里自动可点
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlatformPicker(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onLongPressSoul: () -> Unit = {},
    soulFeatureSummary: String = "",
    running: Boolean = false,
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "平台",
                color = Dim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            val usable = selected.count { id -> Platform.byId(id)?.implemented == true }
            Text(
                "可用 ${usable} 个",
                color = Dim.copy(alpha = 0.55f),
                fontSize = 11.sp
            )
            Spacer(Modifier.weight(1f))
            // ★ 用户要求（2026-11-24）：
            // 「点 soul 的选择框时不会弹出来」→ 给一个明确的入口提示
            Text(
                "长按卡片配置功能",
                color = Accent2.copy(alpha = 0.8f),
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(8.dp))

        // 两列流式布局
        val items = Platform.entries.toList()
        items.chunked(2).forEach { rowItems ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { p ->
                    val enabled = p.implemented
                    val checked = enabled && p.id in selected
                    val isSoul = p.id == "soul"

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (!enabled) Modifier
                                else if (isSoul) {
                                    // 目标应用 卡片：单击=勾选，长按=打开功能配置
                                    Modifier
                                        .combinedClickable(
                                            onClick = { onToggle(p.id) },
                                            onLongClick = { onLongPressSoul() },
                                        )
                                } else {
                                    Modifier.clickable { onToggle(p.id) }
                                }
                            ),
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            !enabled -> CardBg.copy(alpha = 0.4f)
                            checked -> Accent.copy(alpha = 0.18f)
                            else -> CardBg
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (checked) Accent else Color.Transparent
                        ),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = if (enabled) {
                                    { onToggle(p.id) }
                                } else null,
                                enabled = enabled,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Accent,
                                    uncheckedColor = Dim.copy(alpha = 0.5f),
                                    disabledCheckedColor = Dim.copy(alpha = 0.25f),
                                    disabledUncheckedColor = Dim.copy(alpha = 0.2f),
                                ),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.label,
                                    color = when {
                                        !enabled -> Dim.copy(alpha = 0.4f)
                                        checked -> Color.White
                                        else -> Color(0xFFB8C0D4)
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (!enabled) {
                                    Text(
                                        "待开发",
                                        color = Dim.copy(alpha = 0.35f),
                                        fontSize = 9.sp
                                    )
                                } else if (isSoul && soulFeatureSummary.isNotBlank()) {
                                    // 显示当前勾了哪些功能，让用户不点开也知道
                                    Text(
                                        soulFeatureSummary,
                                        color = Accent2.copy(alpha = 0.75f),
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            // 目标应用 卡片右侧加个设置图标，提示可点
                            if (isSoul) {
                                Text(
                                    "⚙",
                                    color = if (running) Dim.copy(alpha = 0.3f)
                                    else Accent2.copy(alpha = 0.7f),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = CardBg
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 12.dp)) {
            Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, color = Dim, fontSize = 11.sp)
        }
    }
}

/**
 * 目标应用 功能配置弹窗（长按平台卡片里的 目标应用 时弹出）。
 *
 * ── 用户要求（2026-11-24 实测反馈）──
 * 「我觉得灵魂匹配这些功能按钮应该是我长按 soul 选项的时候弹出来的一个选项框」
 * 「然后点 soul 的选择框时不会弹出来」
 *
 * 所以：
 *   · 单击 目标应用 卡片 = 勾选/取消勾选平台（和别的平台一致）
 *   · 长按 目标应用 卡片 = 弹出这个框配置功能
 *   · 卡片右侧有个 ⚙ 图标，提示可以长按
 *
 * 四个功能，独立勾选：
 *   1. 灵魂匹配   —— 消耗额度，主动找新人         默认 关
 *   2. 回复会话   —— 维护已有对话（价值最高）      默认 开
 *   3. 奇遇铃     —— 她主动靠近，点进去聊         默认 开
 *   4. 同城私聊   —— 去广场主动搭话             默认 关（带人数档位）
 */
@Composable
private fun SoulFeatureSheet(
    visible: Boolean,
    running: Boolean,
    features: Features,
    onChange: (Features) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                // 弹窗内部也允许滚动，档位展开变高也不怕
                .heightIn(max = 620.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF1B2233),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                // 标题行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "功能配置",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (running) "运行中不可改" else "勾了才跑",
                        color = if (running) Color(0xFFFFA726) else Dim.copy(alpha = 0.55f),
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "关闭",
                        color = Accent2,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    if (running) "要改配置请先停止运行"
                    else "长按平台卡片可再次打开这个框",
                    color = Dim.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )

                Spacer(Modifier.height(14.dp))

                // 功能列表（可滚动）
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    FeatureRow(
                        checked = features.match,
                        locked = running,
                        title = "灵魂匹配",
                        desc = "消耗匹配额度，主动找新人",
                        onChange = { onChange(features.copy(match = it)) },
                    )
                    Spacer(Modifier.height(12.dp))

                    FeatureRow(
                        checked = features.reply,
                        locked = running,
                        title = "回复会话",
                        desc = "扫聊天列表，回她最后说的话",
                        onChange = { onChange(features.copy(reply = it)) },
                    )
                    Spacer(Modifier.height(12.dp))

                    FeatureRow(
                        checked = features.miracleBell,
                        locked = running,
                        title = "奇遇铃",
                        desc = "弹窗出现时点「立即私聊」搭话，聊完回原任务",
                        onChange = { onChange(features.copy(miracleBell = it)) },
                    )
                    Spacer(Modifier.height(12.dp))

                    FeatureRow(
                        checked = features.greetings,
                        locked = running,
                        title = "新招呼",
                        desc = "处理「收到的新招呼」里主动来找你的人，发一条即开启私聊",
                        onChange = { onChange(features.copy(greetings = it)) },
                    )
                    Spacer(Modifier.height(12.dp))

                    FeatureRow(
                        checked = features.deleteDeadChats,
                        locked = running,
                        title = "删除废弃会话",
                        desc = "匹配跑完后，删掉我发完、她没回的会话",
                        onChange = { onChange(features.copy(deleteDeadChats = it)) },
                    )
                    Spacer(Modifier.height(12.dp))

                    FeatureRow(
                        checked = features.squareProactive,
                        locked = running,
                        title = "同城私聊",
                        desc = "去广场同城主动搭话",
                        onChange = { onChange(features.copy(squareProactive = it)) },
                    )

                    // 人像筛选（只在同城私聊开启时可用）
                    if (features.squareProactive) {
                        Spacer(Modifier.height(6.dp))
                        FeatureRow(
                            checked = features.portraitFilter,
                            locked = running,
                            title = "看脸筛选",
                            desc = "有配图的帖子先识别长相：偏瘦优先、美颜不重、长相可爱",
                            onChange = { onChange(features.copy(portraitFilter = it)) },
                        )
                    }

                    // 人数档位
                    if (features.squareProactive) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.padding(start = 28.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("每轮", color = Dim.copy(alpha = 0.6f), fontSize = 11.sp)
                            Spacer(Modifier.width(4.dp))
                            listOf(5, 10, 15).forEach { n ->
                                val picked = n == features.proactiveCount
                                Surface(
                                    modifier = if (running) Modifier
                                    else Modifier.clickable {
                                        onChange(features.copy(proactiveCount = n))
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = when {
                                        running -> CardBg.copy(alpha = 0.3f)
                                        picked -> Accent.copy(alpha = 0.28f)
                                        else -> CardBg
                                    },
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (picked && !running) Accent else Color.Transparent
                                    ),
                                ) {
                                    Text(
                                        "$n",
                                        color = when {
                                            running -> Dim.copy(alpha = 0.4f)
                                            picked -> Accent2
                                            else -> Dim
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = if (picked) FontWeight.SemiBold
                                        else FontWeight.Normal,
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp, vertical = 6.dp
                                        )
                                    )
                                }
                            }
                            Spacer(Modifier.width(2.dp))
                            Text("个", color = Dim.copy(alpha = 0.5f), fontSize = 11.sp)
                        }
                    }

                    // ── 同城帖子评论（2026-11-25 新增）──
                    //
                    // 和「同城私聊」并列但独立开关：
                    // 私聊有送花墙，评论没有。两者都跑的时候串行（先私聊再评论），
                    // 因为它们共用同一个广场页面。
                    Spacer(Modifier.height(12.dp))
                    FeatureRow(
                        checked = features.squareComment,
                        locked = running,
                        title = "同城评论",
                        desc = "在广场帖子下评论（无送花墙，公开曝光）",
                        onChange = { onChange(features.copy(squareComment = it)) },
                    )

                    // 条数档位（只在勾了「同城评论」时显示）
                    if (features.squareComment) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.padding(start = 28.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("每轮", color = Dim.copy(alpha = 0.6f), fontSize = 11.sp)
                            Spacer(Modifier.width(4.dp))
                            listOf(3, 5, 10).forEach { n ->
                                val picked = n == features.squareCommentCount
                                Surface(
                                    modifier = if (running) Modifier
                                    else Modifier.clickable {
                                        onChange(features.copy(squareCommentCount = n))
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = when {
                                        running -> CardBg.copy(alpha = 0.3f)
                                        picked -> Accent.copy(alpha = 0.28f)
                                        else -> CardBg
                                    },
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (picked && !running) Accent else Color.Transparent
                                    ),
                                ) {
                                    Text(
                                        "$n",
                                        color = when {
                                            running -> Dim.copy(alpha = 0.4f)
                                            picked -> Accent2
                                            else -> Dim
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = if (picked) FontWeight.SemiBold
                                        else FontWeight.Normal,
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp, vertical = 6.dp
                                        )
                                    )
                                }
                            }
                            Spacer(Modifier.width(2.dp))
                            Text("条", color = Dim.copy(alpha = 0.5f), fontSize = 11.sp)
                        }
                    }

                // ★ 地区多选（只在勾了「删除废弃会话」时显示）
                //
                // 用户要求（2026-11-25）：
                // 「删除的规则是超过3天没有新的聊天，然后不在我选择的地区」
                // 「这个也可以做一个多选框配置，默认是江浙沪」
                if (features.deleteDeadChats) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "可接受地区（不在这些地方的会被删）",
                        color = Dim.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(8.dp))

                    // 分两行排，避免一行挤太多
                    val all = Features.ALL_REGIONS
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        all.chunked(6).forEach { rowItems ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                rowItems.forEach { region ->
                                    val picked = region in features.regions
                                    Surface(
                                        modifier = Modifier.clickable(enabled = !running) {
                                            if (running) return@clickable
                                            val next = features.regions.toMutableSet()
                                            if (picked) next.remove(region) else next.add(region)
                                            if (next.isEmpty()) return@clickable  // 至少留一个
                                            onChange(features.copy(regions = next))
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = when {
                                            running -> Color(0xFF232B3D)
                                            picked -> Accent.copy(alpha = 0.22f)
                                            else -> Color(0xFF232B3D)
                                        },
                                    ) {
                                        Text(
                                            region,
                                            color = when {
                                                running -> Dim.copy(alpha = 0.4f)
                                                picked -> Accent2
                                                else -> Dim.copy(alpha = 0.7f)
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = if (picked) FontWeight.SemiBold
                                            else FontWeight.Normal,
                                            modifier = Modifier.padding(
                                                horizontal = 10.dp, vertical = 5.dp
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }

                Spacer(Modifier.height(16.dp))

                // 完成按钮
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss() },
                    shape = RoundedCornerShape(12.dp),
                    color = Accent.copy(alpha = 0.9f),
                ) {
                    Text(
                        "完成",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 11.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 一行功能开关 */
@Composable
private fun FeatureRow(
    checked: Boolean,
    locked: Boolean,
    title: String,
    desc: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onChange(it) },
            enabled = !locked,
            colors = CheckboxDefaults.colors(
                checkedColor = Accent,
                uncheckedColor = Dim.copy(alpha = if (locked) 0.3f else 0.5f),
            ),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (locked) Dim.copy(alpha = 0.5f) else Color.White,
                fontSize = 12.sp
            )
            Text(
                desc,
                color = Dim.copy(alpha = 0.5f),
                fontSize = 10.sp
            )
        }
    }
}


@Composable
private fun ActionRow(
    state: EngineState,
    onRun: () -> Unit,
    onDryRun: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.running) {
            // ── 运行中：只有一个「停止」按钮 ──
            // 用户要求：「开始之后就不能再按开始了，加个停止功能，
            //          只有停止后或者主动按停止后，开始功能继续」
            Button(
                onClick = onStop,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB3261E),   // 红色，明显区别
                )
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text("停止", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { },
                enabled = false,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Dim.copy(alpha = 0.4f)
                )
            ) {
                Text("演练", fontSize = 14.sp)
            }
        } else {
            Button(
                onClick = onRun,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("立即检查", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onDryRun,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent2)
            ) {
                Icon(Icons.Default.Visibility, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("演练", fontSize = 14.sp)
            }
        }
    }
}

/**
 * 运行日志区（可折叠）。
 *
 * ── 为什么折叠（2026-11-24 用户实测反馈）──
 * 原来日志区用 weight(1f) 占满剩余空间，手机上把按钮挤到屏幕外了。
 * 改成：默认只显示最后 3 条，点标题展开看全部。
 *
 * 运行中自动展开（这时候日志最重要）。
 */
@Composable
private fun LogSection(
    logs: List<LogItem>,
    running: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    // 运行中自动展开
    LaunchedEffect(running) {
        if (running) expanded = true
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "运行日志",
                color = Dim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            if (logs.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "${logs.size} 条",
                    color = Dim.copy(alpha = 0.45f),
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "收起 ▲" else "展开 ▼",
                color = Accent2.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }

        if (logs.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Inbox, null, tint = Dim.copy(alpha = 0.3f),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "还没有日志\n点下方按钮开始",
                    color = Dim.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        } else {
            // 收起时只显示最后 3 条，展开时给固定的高度（避免无限拉长）
            val shown = if (expanded) logs else logs.takeLast(3)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (expanded) 260.dp else (shown.size * 46).dp.coerceAtMost(150.dp))
                    .padding(horizontal = 20.dp)
            ) {
                LogList(shown, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun LogList(logs: List<LogItem>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        reverseLayout = true
    ) {
        items(logs.asReversed()) { item ->
            LogRow(item)
        }
    }
}

@Composable
private fun LogRow(item: LogItem) {
    val color = when (item.level) {
        "sent" -> Sent
        "warn" -> Warn
        "error" -> Err
        else -> Color(0xFFB8C0D4)
    }
    val icon = when (item.level) {
        "sent" -> Icons.Default.Send
        "warn" -> Icons.Default.Warning
        "error" -> Icons.Default.Error
        else -> Icons.Default.Circle
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            item.time,
            color = Dim.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            icon, null, tint = color.copy(alpha = 0.7f),
            modifier = Modifier.size(12.dp).padding(top = 3.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            item.text,
            color = color,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 常驻监控开关条。
 *
 * 用户需求（2026-11-24）：
 * 「回复功能应该是一直时刻监控后台是否有新消息的」
 *
 * 开：每 30 秒查一次数据库，有新消息就跑一轮回复，全程无人值守
 * 关：只在你点「立即检查」时跑一轮
 */
@Composable
private fun MonitorBar(
    monitoring: Boolean,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (monitoring) Color(0x331B5E20) else CardBg.copy(alpha = 0.8f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (monitoring) Color(0xFF4CAF50) else Color.Transparent
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (monitoring) "🔭" else "💤",
                fontSize = 16.sp
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (monitoring) "常驻监控中" else "常驻监控",
                    color = if (monitoring) Color(0xFF81C784) else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (monitoring) "每 30 秒检查一次，有新消息自动回复"
                    else "开启后不用再点「立即检查」，新消息自动处理",
                    color = Dim.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            if (monitoring) {
                Surface(
                    modifier = Modifier.clickable { onStop() },
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFB3261E).copy(alpha = 0.9f),
                ) {
                    Text(
                        "停止",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            } else {
                Surface(
                    modifier = if (running) Modifier
                    else Modifier.clickable { onStart() },
                    shape = RoundedCornerShape(8.dp),
                    color = if (running) CardBg.copy(alpha = 0.4f)
                    else Color(0xFF1B5E20).copy(alpha = 0.9f),
                ) {
                    Text(
                        "开启",
                        color = if (running) Dim.copy(alpha = 0.4f) else Color(0xFFA5D6A7),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}
