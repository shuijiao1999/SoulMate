package com.love.soulmate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.love.soulmate.core.Shell
import com.love.soulmate.engine.Features
import com.love.soulmate.engine.Monitor
import com.love.soulmate.engine.Orchestrator
import com.love.soulmate.llm.Llm
import com.love.soulmate.llm.LlmConfig
import com.love.soulmate.platform.Platform
import com.love.soulmate.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var orch: Orchestrator
    private var shizukuOk by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var cfg by mutableStateOf(LlmConfig())
    private var selectedPlatforms by mutableStateOf(Platform.defaultSelected())
    /**
     * 功能开关（匹配/回复/奇遇铃/同城私聊）。
     *
     * 用户要求（2026-11-24）：每个功能独立勾选，没勾的不跑。
     */
    private var features by mutableStateOf(Features())

    /** 悬浮窗权限是否已开（用于界面提示） */
    private var floatOk by mutableStateOf(false)

    private fun loadPlatforms() {
        val sp = getSharedPreferences("platforms", MODE_PRIVATE)
        val saved = sp.getStringSet("enabled", null)
        // 过滤掉未实现的平台（历史配置里可能残留青藤/牵手等）
        val cleaned = if (saved.isNullOrEmpty()) {
            Platform.defaultSelected()
        } else {
            Platform.sanitize(saved)
        }
        selectedPlatforms = cleaned
        // 如果清理过，把干净的结果写回，避免下次再带着脏数据
        if (cleaned != saved) {
            sp.edit().putStringSet("enabled", cleaned).apply()
        }

        // 恢复功能开关
        features = Features.load(this)
        orch.features = features
    }

    /**
     * 停止当前运行。
     *
     * 用户要求（2026-11-24）：
     * 「soulmate 开始之后就不能再按开始了，加个停止功能，
     *   只有停止后或者主动按停止后，开始功能继续」
     */
    private fun stopRun() {
        if (!orch.isStopRequested()) {
            orch.requestStop()
            toast("正在停止…")
        }
    }

    /**
     * 启动常驻监控。
     *
     * 用户需求（2026-11-24）：「回复功能应该是一直时刻监控后台是否有新消息的」
     *
     * 这里做的事：
     *   1. 起前台服务（防冻结）
     *   2. 起悬浮停止球
     *   3. 进入 Monitor 循环（每 30 秒查一次数据库 mtime）
     */
    private fun startMonitor() {
        if (!features.any) {
            toast("至少勾一个功能")
            return
        }
        if (Monitor.isRunning()) {
            toast("监控已在运行")
            return
        }

        // 前台服务保活
        RunService.start(this)

        // 悬浮停止球
        try {
            if (FloatStop.canShow(this)) FloatStop.show(this) { stopMonitor() }
            else toast("建议开启悬浮窗，否则只能下拉通知栏停止")
        } catch (_: Throwable) {
        }

        orch.moveToBack?.invoke()

        addLog("🔭 启动常驻监控 · 功能：${features.summary()}", "info")

        Monitor.start(
            ctx = applicationContext,
            features = features,
            dryRun = false,
            onRound = { force ->
                // 跑一轮和「立即检查」完全一样，只是由监控触发
                //
                // ★ 踩坑（2026-11-24）：这里原来返回 orch.state.value.sentThisHour，
                //   那是「本小时累计」，不是本轮条数。
                //   结果日志显示每轮都「发出 11 条」，看起来像重复发送，
                //   实际是累计值不变。改成记录前后差值。
                val before = orch.state.value.sentThisHour
                orch.forceScan = force
                orch.runOnce(dryRun = false)
                val after = orch.state.value.sentThisHour
                (after - before).coerceAtLeast(0)
            },
            onLog = { text, level -> addLog(text, level) },
            onState = { running, text ->
                runOnUiThread {
                    if (!running) {
                        try {
                            FloatStop.hide()
                        } catch (_: Throwable) {
                        }
                        RunService.stop(this)
                    }
                }
            },
        )
    }

    private fun stopMonitor() {
        if (!Monitor.isRunning()) {
            toast("监控没在运行")
            return
        }
        toast("正在停止监控…")
        Monitor.stop(this)
        orch.requestStop()

        Thread {
            Thread.sleep(1500)
            runOnUiThread {
                try {
                    FloatStop.hide()
                } catch (_: Throwable) {
                }
                RunService.stop(this)
                toast("监控已停止")
            }
        }.start()
    }

    private fun addLog(text: String, level: String) {
        // 走 Orchestrator 的统一日志通道（界面从 orch.logs 收集）
        try {
            orch.log(text, level)
        } catch (_: Throwable) {
        }
    }

    private fun togglePlatform(id: String) {
        // 未实现的平台禁止勾选
        val p = Platform.byId(id)
        if (p == null || !p.implemented) {
            toast("${p?.label ?: id} 尚未接入，暂不可选")
            return
        }

        val next = selectedPlatforms.toMutableSet()
        if (id in next) next.remove(id) else next.add(id)

        // 至少留一个可用平台
        if (next.none { Platform.byId(it)?.implemented == true }) {
            toast("至少选一个平台")
            return
        }

        selectedPlatforms = next
        getSharedPreferences("platforms", MODE_PRIVATE)
            .edit().putStringSet("enabled", next).apply()
        orch.setPlatforms(next)
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        shizukuOk = grant == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        shizukuOk = Shell.hasPermission()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuOk = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        orch = Orchestrator(applicationContext)
        // 让 RunService（前台服务）能拿到它，实现「通知栏点停止」
        OrchestratorHolder.orchestrator = orch
        // 初始化运行环境探测（jar 路径/端口/用户空间都靠它）
        com.love.soulmate.core.Env.init(this)
        // 初始化本地数据库（聊天记录 / 人物档案 / 发送记录）
        com.love.soulmate.core.Db.init(this)
        orch.moveToBack = { runOnUiThread { moveTaskToBack(true) } }
        orch.bringToFront = {
            runOnUiThread {
                try {
                    startActivity(android.content.Intent(this, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                } catch (_: Throwable) {
                }
            }
        }

        cfg = LlmConfig.load(this)
        Llm.config = cfg
        Llm.visionConfig = Llm.VisionConfig.load(this)
        loadPlatforms()
        orch.setPlatforms(selectedPlatforms)

        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        shizukuOk = Shell.hasPermission()

        // 悬浮窗权限（用户可能随时去系统设置里改，所以每次回来都重查）
        floatOk = FloatStop.canShow(this)

        lifecycleScope.launch {
            repeat(10) {
                if (shizukuOk) return@launch
                kotlinx.coroutines.delay(800)
                if (withContext(Dispatchers.IO) { Shell.hasPermission() }) shizukuOk = true
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Bg0,
                    surface = CardBg,
                    onPrimary = Color.White,
                    onBackground = Color.White,
                    onSurface = Color.White,
                )
            ) {
                val state by orch.state.collectAsState()
                val logs by orch.logs.collectAsState()

                if (showSettings) {
                    SettingsScreen(
                        cfg = cfg,
                        onCfgChange = {
                            cfg = it
                            Llm.config = it
                            LlmConfig.save(this@MainActivity, it)
                        },
                        onBack = { showSettings = false },
                        onTest = { testLlm() },
                        onVisionTest = { testVision() },
                        vcfg = Llm.visionConfig,
                        onVisionChange = {
                            Llm.visionConfig = it
                            Llm.VisionConfig.save(this@MainActivity, it)
                        },
                        pushCfg = com.love.soulmate.core.Pusher.PushConfig.load(this@MainActivity),
                        onPushChange = {
                            com.love.soulmate.core.Pusher.PushConfig.save(this@MainActivity, it)
                        },
                        onPushTest = { spt -> testPush(spt) },
                        personaCfg = com.love.soulmate.llm.Persona.load(this@MainActivity),
                        onPersonaChange = {
                            com.love.soulmate.llm.Persona.save(this@MainActivity, it)
                        },
                    )
                } else {
                    SoulMateApp(
                        state = state,
                        logs = logs,
                        shizukuOk = shizukuOk,
                        onRequestShizuku = { requestShizuku() },
                        onRun = { runCheck(dry = false) },
                        onDryRun = { runCheck(dry = true) },
                        onStop = { stopRun() },
                        onOpenSettings = { showSettings = true },
                        onSwitchAccount = { switchAccount() },
                        selectedPlatforms = selectedPlatforms,
                        onTogglePlatform = { togglePlatform(it) },
                        features = features,
                        onFeaturesChange = {
                            features = it
                            orch.features = it
                            Features.save(this@MainActivity, it)
                        },
                        floatOk = floatOk,
                        onRequestFloat = {
                            FloatStop.requestPermission(this@MainActivity)
                        },
                        monitoring = Monitor.isRunning(),
                        onStartMonitor = { startMonitor() },
                        onStopMonitor = { stopMonitor() },
                    )
                }
            }
        }
    }

    /**
     * 申请 Shizuku 授权。
     *
     * ★ 改进（2026-11-25 用户要求）：服务没在跑时，直接跳到 Shizuku App。
     *
     * ── 原来的问题 ──
     * 只 toast 一句「Shizuku 服务没在运行」就结束了，
     * 用户不知道该干什么（是去装？去开？去哪开？）。
     * 结果就是「App 突然跑不了了，不知道为什么」。
     *
     * ── 现在 ──
     *   ① 服务在跑、有权限   → 直接返回
     *   ② 服务在跑、没权限   → 弹系统授权框
     *   ③ 服务没在跑         → **直接拉起 Shizuku App 的界面**，
     *                          用户看到「启动」按钮点一下就行
     *
     * 注意：这里只做「跳转 + 提示」，**不会替用户点启动**
     * （那需要 shell 权限，正是当前缺的东西，会死循环）。
     */
    private fun requestShizuku() {
        // ① 服务在跑吗
        if (!Shell.isShizukuAvailable()) {
            orch.logExternal(
                "Shizuku 服务没在运行 —— 已跳转到 Shizuku，请点「启动」",
                "warn"
            )
            toast("Shizuku 没运行，正在打开 Shizuku…")
            openShizukuApp()
            return
        }

        // ② 有权限了
        if (Shell.hasPermission()) {
            shizukuOk = true
            toast("已有权限")
            return
        }

        // ③ 服务在跑但没授权 → 弹系统授权框
        Shell.requestPermission()
        toast("已在 Shizuku 里弹出授权请求，点「允许」")
    }

    /**
     * 打开 Shizuku App 主界面（让用户点「启动」）。
     *
     * 踩坑：Shizuku 的启动页在不同版本里类名不同，
     * 直接 am start -n 指定类名可能失败（试过 settings.SettingsActivity 会挂）。
     * 用 package 级的 LAUNCHER intent 最稳。
     */
    private fun openShizukuApp() {
        try {
            val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (i != null) {
                i.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(i)
            } else {
                // 没装 Shizuku，给出安装提示
                toast("没装 Shizuku，请先安装")
                orch.logExternal(
                    "设备上没有 Shizuku（moe.shizuku.privileged.api），" +
                            "需要先安装 Shizuku APK",
                    "error"
                )
            }
        } catch (t: Throwable) {
            toast("打开 Shizuku 失败：${t.message}")
            android.util.Log.w("SoulMate", "openShizukuApp 失败: ${t.message}")
        }
    }

    private fun runCheck(dry: Boolean) {
        val tag = if (dry) "演练" else "检查"
        lifecycleScope.launch {
            val perm = withContext(Dispatchers.IO) { Shell.hasPermission() }
            if (!perm) {
                shizukuOk = false
                // ★ 区分「没授权」和「服务没跑」（2026-11-25）
                //
                // 原来两种情况都只 toast「先授权 Shizuku」，
                // 但「服务没跑」时授权框根本弹不出来，用户会一脸懵。
                // 现在服务没跑就直接跳 Shizuku，让用户点「启动」。
                if (!Shell.isShizukuAvailable()) {
                    toast("Shizuku 服务没运行，正在打开 Shizuku…")
                    orch.logExternal(
                        "Shizuku 服务没在运行 —— 已跳转到 Shizuku，请点「启动」再回来",
                        "error"
                    )
                    openShizukuApp()
                } else {
                    toast("先授权 Shizuku")
                    orch.logExternal("没有 Shizuku 权限", "error")
                }
                return@launch
            }
            if (Llm.config.apiKey.isBlank()) {
                toast("先去设置填 API Key")
                orch.logExternal("API Key 为空，请去设置填写", "error")
                showSettings = true
                return@launch
            }
            orch.logExternal("点击了「$tag」，开始执行")

            // 用户手动点了按钮，就是要跑，跳过「数据没变就不扫」的优化
            // （那个优化只用于自动轮询场景）
            orch.forceScan = true

            // 启动前台服务，防止后台被系统冻结（Android 14 的 do_freezer_trap）
            try {
                RunService.start(this@MainActivity)
            } catch (t: Throwable) {
                orch.logExternal("前台服务启动失败（可能影响后台运行）：${t.message}", "warn")
            }

            // 显示悬浮「停止」按钮 —— 运行时 App 会退到后台，
            // 这是唯一能在任何界面都点到的停止入口。
            val floatOk = try {
                FloatStop.show(this@MainActivity) { stopRun() }
            } catch (t: Throwable) {
                orch.logExternal("悬浮按钮显示失败：${t.message}", "warn")
                false
            }
            if (!floatOk) {
                orch.logExternal(
                    "没有悬浮窗权限，停止请用通知栏的「停止」按钮",
                    "warn"
                )
            }

            try {
                withContext(Dispatchers.IO) { orch.runOnce(dry) }
            } finally {
                // 收尾：隐藏悬浮按钮 + 停前台服务
                try {
                    FloatStop.hide()
                } catch (_: Throwable) {
                }
                try {
                    RunService.stop(this@MainActivity)
                } catch (_: Throwable) {
                }
                orch.forceScan = false
            }
        }
    }

    /**
     * 切换账号。
     *
     * 不自建登录体系 —— 直接打开 目标应用自己的「设置 → 切换账号」，
     * 用户在 目标应用里切完，回来点「立即检查」App 会自动识别新账号，
     * 并按新账号分区加载数据。
     */
    private fun switchAccount() {
        lifecycleScope.launch {
            orch.logExternal("正在打开目标应用的切换账号页面...")
            moveTaskToBack(true)
            kotlinx.coroutines.delay(800)

            val ok = withContext(Dispatchers.IO) {
                com.love.soulmate.soul.Account.openSwitchAccount()
            }

            withContext(Dispatchers.Main) {
                try {
                    startActivity(
                        android.content.Intent(this@MainActivity, MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                } catch (_: Throwable) {
                }
            }

            if (ok) {
                orch.logExternal("已打开切换账号页，切完回来点「立即检查」即可识别", "info")
                toast("在目标应用里切换账号，切完回来点「立即检查」")
            } else {
                orch.logExternal("没自动找到切换入口，请手动：自己 → 设置 → 切换账号", "warn")
                toast("请手动：目标应用 → 设置 → 切换账号")
            }
        }
    }

    private fun testLlm() {
        lifecycleScope.launch {
            orch.logExternal("正在测试模型连通性...")
            val r = withContext(Dispatchers.IO) { Llm.test() }
            if (r.ok) {
                orch.logExternal("模型连通 ✓ 返回：${r.text.take(30)}", "sent")
                toast("模型可用：${r.text.take(20)}")
            } else {
                orch.logExternal("模型不通 ✗ ${r.error}", "error")
                toast("模型不通：${r.error.take(40)}")
            }
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 测试推送：验证 WxPusher 通道。
     */
    private fun testPush(spt: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 临时存一下 SPT，Pusher 从配置里读
            com.love.soulmate.core.Pusher.PushConfig.save(
                this@MainActivity,
                com.love.soulmate.core.Pusher.PushConfig(enabled = true, spt = spt)
            )
            val ok = com.love.soulmate.core.Pusher.alert(
                this@MainActivity,
                "SoulMate 测试推送",
                "收到这条说明推送通道正常。\n出问题时我会用这个渠道通知你。",
                force = true,
            )
            withContext(Dispatchers.Main) {
                toast(if (ok) "推送成功，去微信看" else "推送失败，检查 SPT")
            }
        }
    }

    /**
     * 视觉自测：验证「截图 → 标注编号 → 模型选号 → 精确点击」整条链路。
     *
     * 关键：必须在 目标应用界面上测，因为那才是真实使用场景（元素多、有嵌套）。
     * 之前压到桌面测，可点元素太少，测不出真实难度。
     */
    private fun testVision() {
        lifecycleScope.launch {
            showSettings = false
            kotlinx.coroutines.delay(600)

            val (pass, total) = withContext(Dispatchers.IO) {
                // 把 目标应用 拉到前台，在真实界面上测
                com.love.soulmate.core.Shell.run(
                    "am start -n cn.soulapp.android/" +
                            "cn.soulapp.android.component.startup.main.MainActivity"
                )
                kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(4000) }

                com.love.soulmate.engine.VisionSelfTest.run { text, level ->
                    orch.logExternal(text, level)
                }
            }

            withContext(Dispatchers.Main) {
                bringToFront()
                toast("视觉自测: $pass/$total")
            }
        }
    }

    private fun bringToFront() {
        try {
            startActivity(
                android.content.Intent(this, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统「显示在其他应用上层」设置页返回后，重新检查一次权限
        val now = try {
            FloatStop.canShow(this)
        } catch (_: Throwable) {
            floatOk
        }
        if (now != floatOk) floatOk = now
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        try {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {
        }
        // 保险：Activity 结束时别留下悬浮球
        try {
            FloatStop.hide()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}

@Composable
fun SettingsScreen(
    cfg: LlmConfig,
    onCfgChange: (LlmConfig) -> Unit,
    onBack: () -> Unit,
    onTest: () -> Unit,
    onVisionTest: () -> Unit,
    vcfg: Llm.VisionConfig,
    onVisionChange: (Llm.VisionConfig) -> Unit,
    pushCfg: com.love.soulmate.core.Pusher.PushConfig,
    onPushChange: (com.love.soulmate.core.Pusher.PushConfig) -> Unit,
    onPushTest: (String) -> Unit,
    personaCfg: com.love.soulmate.llm.Persona,
    onPersonaChange: (com.love.soulmate.llm.Persona) -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(cfg.baseUrl) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var model by remember { mutableStateOf(cfg.model) }
    var maxTokens by remember { mutableStateOf(cfg.maxTokens.toString()) }
    var visionOn by remember { mutableStateOf(vcfg.enabled) }
    var visionModel by remember { mutableStateOf(vcfg.model) }
    var pushOn by remember { mutableStateOf(pushCfg.enabled) }
    var pushSpt by remember { mutableStateOf(pushCfg.spt) }
    var pushInvite by remember { mutableStateOf(pushCfg.onInvite) }
    var pushError by remember { mutableStateOf(pushCfg.onError) }
    var quietOn by remember { mutableStateOf(pushCfg.quietStart >= 0) }
    var personaFacts by remember { mutableStateOf(personaCfg.facts) }

    fun push() {
        onCfgChange(
            cfg.copy(
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                model = model.trim(),
                maxTokens = maxTokens.toIntOrNull() ?: 4096,
            )
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Bg0, Bg1)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                }
                Text("设置", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }

            Surface(
                color = CardBg,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("模型接口", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "填 OpenAI 兼容的 base url（如 https://api.openai.com/v1），\n" +
                                "系统会自动补 /chat/completions。填 /messages 结尾则走 Anthropic 协议。",
                        color = Dim, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(14.dp))

                    Field("Base URL", baseUrl, { baseUrl = it; push() },
                        "https://api.openai.com/v1")
                    Spacer(Modifier.height(12.dp))

                    Field("模型名", model, { model = it; push() }, "填服务商给的模型名")
                    Spacer(Modifier.height(12.dp))

                    Field(
                        "API Key", apiKey, { apiKey = it; push() }, "sk-...",
                        secret = true, show = showKey, onToggle = { showKey = !showKey }
                    )
                    Spacer(Modifier.height(12.dp))

                    Field("max_tokens", maxTokens, { maxTokens = it; push() }, "4096")

                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { push(); onTest() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent2)
                        ) {
                            Text("测试连接", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { push(); onVisionTest() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent2)
                        ) {
                            Text("视觉自测", fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---------- 视觉模型（独立配置）----------
            Surface(
                color = CardBg,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "视觉模型（找元素用）",
                            color = Color.White, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = visionOn,
                            onCheckedChange = {
                                visionOn = it
                                onVisionChange(vcfg.copy(enabled = it))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "按 id / 文字都找不到元素时，用截图标注编号让模型选。\n" +
                                "默认与文本同一个模型（该模型需要支持图片输入）。",
                        color = Dim, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    if (visionOn) {
                        Spacer(Modifier.height(12.dp))
                        Field("视觉模型名", visionModel, {
                            visionModel = it
                            onVisionChange(vcfg.copy(model = it.trim()))
                        }, "填支持图片的模型名")
                    }
                }
            }

            // ── 人设配置（2026-11-26 开源版新增）──
            //
            // 为什么要有这块：
            //   原来「我是谁」是硬编码在 Replier.kt 里的（年龄/老家/职业/身高等），
            //   开源后既泄露隐私，别人也无法用 —— 每个人的情况都不一样。
            //
            //   现在拆出来让用户自己填。不填也不会翻车：
            //   模型会被要求「不要提及具体个人信息」，而不是凭空编造。
            Spacer(Modifier.height(16.dp))
            Surface(
                color = CardBg,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "我的人设（让回复像本人）",
                        color = Color.White, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "填了你自己的真实情况，生成的回复才会符合你的身份。\n" +
                                "不填也能用，只是模型不会提及任何具体个人信息。",
                        color = Dim, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "我的真实情况（一行一条）",
                        color = Color.White, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = personaFacts,
                        onValueChange = {
                            personaFacts = it
                            onPersonaChange(personaCfg.copy(facts = it))
                        },
                        placeholder = {
                            Text(
                                "例：\n95年生，杭州人\n产品经理\n喜欢骑行和做饭",
                                color = Dim, fontSize = 11.sp
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White, fontSize = 12.sp
                        ),
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Surface(
                color = CardBg,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("微信通知", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "两种通知，可以分别开关：\n" +
                                "· 邀约 —— 她约见面/要微信，最高价值\n" +
                                "· 出错 —— 发送失败/目标应用卡死/模型挂掉",
                        color = Dim, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    // 总开关
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("开启", color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = pushOn,
                            onCheckedChange = {
                                pushOn = it
                                onPushChange(pushCfg.copy(enabled = it))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                        )
                    }

                    if (pushOn) {
                        Spacer(Modifier.height(10.dp))
                        Field("WxPusher SPT", pushSpt, {
                            pushSpt = it
                            onPushChange(pushCfg.copy(spt = it.trim()))
                        }, "SPT_xxxxx")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在 wxpusher.zjiecode.com 建应用，拿 Simple Push Token（免费）",
                            color = Dim, fontSize = 10.sp, lineHeight = 15.sp
                        )

                        Spacer(Modifier.height(14.dp))

                        // 邀约通知
                        PushKindRow(
                            title = "🔥 邀约通知",
                            desc = "她提到见面/吃饭/要微信时推给你，且不自动回复",
                            checked = pushInvite,
                            onCheckedChange = {
                                pushInvite = it
                                onPushChange(pushCfg.copy(onInvite = it))
                            },
                        )

                        Spacer(Modifier.height(10.dp))

                        // 错误通知
                        PushKindRow(
                            title = "⚠️ 出错通知",
                            desc = "发送失败、目标应用卡死、模型挂掉时推给你",
                            checked = pushError,
                            onCheckedChange = {
                                pushError = it
                                onPushChange(pushCfg.copy(onError = it))
                            },
                        )

                        Spacer(Modifier.height(14.dp))

                        // 免打扰
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "免打扰时段",
                                    color = Color.White, fontSize = 12.sp
                                )
                                Text(
                                    if (quietOn) "这段时间只推邀约，不推错误"
                                    else "全天推送",
                                    color = Dim.copy(alpha = 0.6f), fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = quietOn,
                                onCheckedChange = {
                                    quietOn = it
                                    onPushChange(
                                        pushCfg.copy(
                                            quietStart = if (it) 23 else -1,
                                            quietEnd = if (it) 8 else -1,
                                        )
                                    )
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                            )
                        }
                        if (quietOn) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "23:00 — 08:00",
                                color = Accent2, fontSize = 12.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                onPushChange(pushCfg.copy(spt = pushSpt.trim(), enabled = true))
                                pushOn = true
                                onPushTest(pushSpt.trim())
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent2)
                        ) {
                            Text("测试推送", fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Surface(
                color = CardBg,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("说明", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "· Key 只存在本机，不上传\n" +
                                "· 平板需要能访问这个地址\n" +
                                "· 若地址在内网，需先让电脑做转发",
                        color = Dim, fontSize = 11.sp, lineHeight = 18.sp
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun PushKindRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 12.sp)
            Text(desc, color = Dim.copy(alpha = 0.6f), fontSize = 10.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Accent)
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
    show: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    Column {
        Text(label, color = Dim, fontSize = 11.sp)
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(placeholder, color = Dim.copy(alpha = 0.4f), fontSize = 12.sp) },
            visualTransformation = if (!secret || show) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = if (secret && onToggle != null) {
                {
                    IconButton(onClick = onToggle) {
                        Icon(
                            if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            null, tint = Dim
                        )
                    }
                }
            } else null,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Dim.copy(alpha = 0.3f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp, fontFamily = FontFamily.Monospace
            )
        )
    }
}
