package com.love.soulmate.core

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 运行环境探测与适配。**任何设备相关的东西都必须经过这里，不许在业务代码里写死。**
 *
 * 为什么需要这一层（开源要求：所有 Android 设备都能用）：
 *   · u2.jar 的路径不能假设是 /data/local/tmp（不同 ROM 权限不同）
 *   · 端口不能假设 9008（可能被占用）
 *   · 用户空间不能假设只有 0 和 900（ZUI 分身是 900，小米/华为的分身 ID 不同）
 *   · 屏幕尺寸必须运行时读，不能写 1600x2560
 */
object Env {

    private var appContext: Context? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun context(): Context? = appContext

    // ---------- 设备信息 ----------

    val brand: String get() = Build.BRAND ?: ""
    val model: String get() = Build.MODEL ?: ""
    val sdkInt: Int get() = Build.VERSION.SDK_INT

    fun desc(): String = "$brand $model (Android $sdkInt)"

    // ---------- u2 server ----------

    /**
     * 把打包在 assets 里的 u2.jar 部署到 **shell 能读到**的位置。
     *
     * ── 踩坑 1（2026-11-24，用户手机实测）──
     * 「平板和手机的布局是不一样的，我放到手机上就卡住不动了」
     *
     * 真实原因不是布局 —— 是**工程里压根没打包 u2.jar**。
     * 平板上能用是因为我手工推了 jar，手机上没人做这步。
     *
     * ── 踩坑 2（同一小时，连踩两次）──
     * 第一次修：解压到 `filesDir`（App 私有）
     *   → `cp: bad '.../files/u2.jar': Permission denied`
     *   `/data/user/0/<pkg>/files` 是 700，只有那个 uid 能进，shell 进不去。
     *
     * ── 踩坑 3 ──
     * 第二次修：以为 `/data/local/tmp` 最好
     *   → App **自己写不了**那里（SELinux 限制 App 直接写 shell_data_file）
     *
     * ── 最终方案：`/sdcard/Android/data/<pkg>/files` ──
     * 实测这个位置两边都能访问：
     *   ```
     *   drwxrwx--x u0_a58 sdcard_rw  .../Android/data/<pkg>/files
     *   ^ App 有写权限            ^ shell 在 sdcard_rw 组里，也能读
     *   ```
     *
     * 流程：
     *   1. assets → Android/data/<pkg>/files/u2.jar（App 直接写，不用 shell）
     *   2. Shizuku shell `cp` 到 /data/local/tmp + chmod 644（老位置兜底）
     *   3. 两个位置哪个成功用哪个
     *
     * @return shell 能读到的绝对路径；失败返回空串
     */
    fun deployJar(): String {
        val ctx = appContext ?: return ""

        // ① 先看有没有现成的（/data/local/tmp 是历史位置，优先复用）
        for (p in listOf("/data/local/tmp/u2.jar", extJarPath())) {
            if (validJar(p)) return p
        }

        // ② 解压到 Android/data（App 有写权限，shell 也能读）
        val extPath = extJarPath()
        if (extPath.isBlank()) {
            android.util.Log.e("SoulMate", "Env: 拿不到外部存储目录")
            return ""
        }

        val ok = try {
            val f = File(extPath)
            f.parentFile?.mkdirs()
            ctx.assets.open("u2.jar").use { input ->
                f.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            android.util.Log.i("SoulMate", "Env: 解压到 $extPath 大小=${f.length()}")
            f.length() > 1_000_000
        } catch (t: Throwable) {
            android.util.Log.e(
                "SoulMate",
                "Env: 解压失败 ${t.javaClass.simpleName} ${t.message}"
            )
            false
        }

        if (!ok) return ""

        // ③ 顺手也拷一份到 /data/local/tmp（有些设备那里更稳）
        //    失败不影响，因为 extPath 已经可用了
        Shell.run("cp '$extPath' /data/local/tmp/u2.jar 2>/dev/null")
        Shell.run("chmod 644 /data/local/tmp/u2.jar 2>/dev/null")

        // ④ 校验 shell 真能读到（用 cat 而不是 ls —— ls 能看到不代表能读）
        return if (validJar(extPath)) {
            extPath
        } else if (validJar("/data/local/tmp/u2.jar")) {
            "/data/local/tmp/u2.jar"
        } else {
            android.util.Log.e("SoulMate", "Env: 部署后 shell 仍读不到")
            ""
        }
    }

    /** /sdcard/Android/data/<pkg>/files/u2.jar —— App 写，shell 也能读 */
    private fun extJarPath(): String {
        val ctx = appContext ?: return ""
        val dir = ctx.getExternalFilesDir(null)
            ?: File("/sdcard/Android/data/${ctx.packageName}/files")
        return File(dir, "u2.jar").absolutePath
    }

    /**
     * 校验某个路径的 jar 是否 **shell 真能读到**。
     *
     * ★ 用 `cat | head -c 4` 而不是 `ls`：
     *   ls 只能证明文件存在（元数据可读），不能证明内容可读。
     *   踩过这个坑 —— ls 成功但 app_process 一读就 Permission denied。
     */
    private fun validJar(path: String): Boolean {
        val out = Shell.exec("cat '$path' 2>/dev/null | head -c 4 | od -An -tx1 2>/dev/null")
        // ZIP 文件头是 50 4b 03 04（"PK\003\004"）
        return out.contains("50 4b")
    }

    /** u2.jar 候选路径（只保留 shell 能读到的位置） */
    fun jarCandidates(): List<String> = listOf(
        "/data/local/tmp/u2.jar",
        extJarPath(),
    ).filter { it.isNotBlank() }

    /**
     * 找到实际存在的、且 **shell 能读到** 的 u2.jar。
     * 找不到会自动从 assets 部署一次。
     */
    fun findJar(): String {
        for (p in jarCandidates()) {
            if (validJar(p)) return p
        }
        return deployJar()
    }

    /** JSON 临时文件路径（不能假设 /data/local/tmp 可写） */
    fun tmpFile(name: String): String {
        val base = appContext?.cacheDir?.absolutePath ?: "/data/local/tmp"
        return "$base/$name"
    }

    /**
     * u2 server 端口候选。
     * 默认 9008，被占则往后找。
     */
    fun portCandidates(): List<Int> {
        val list = ArrayList<Int>()
        list.add(9008)
        for (p in 9009..9020) list.add(p)
        return list
    }

    // ---------- 用户空间 ----------

    /**
     * 枚举装了这个包的所有 Android 用户空间。
     *
     * 不能用「0 和 900」硬编码：
     *   小米分身 = 999，华为隐私空间 = 888，三星安全文件夹 = 150…
     * 通用做法是查 pm list users，再逐个确认装了目标包。
     */
    fun findUserSpaces(pkg: String): List<Int> {
        val result = ArrayList<Int>()
        val out = Shell.exec("pm list users")
        // 格式：UserInfo{0:机主:13} running
        val re = Regex("""UserInfo\{(\d+):""")
        val ids = re.findAll(out).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()

        if (ids.isEmpty()) {
            // 读不到就退回当前用户
            val cur = Ui.currentUser()
            return if (cur >= 0) listOf(cur) else listOf(0)
        }

        for (uid in ids) {
            val has = Shell.exec("pm list packages --user $uid $pkg 2>/dev/null")
            if (has.contains(pkg)) result.add(uid)
        }
        return if (result.isEmpty()) ids else result
    }

    // ---------- 能力检查 ----------

    /** 该设备是否具备运行条件，返回缺失项列表（空=全齐） */
    fun missingRequirements(): List<String> {
        val miss = ArrayList<String>()
        if (Build.VERSION.SDK_INT < 26) miss.add("Android 8.0+（当前 ${Build.VERSION.SDK_INT}）")
        if (!Shell.isShizukuAvailable()) miss.add("Shizuku 未运行")
        if (!Shell.hasPermission()) miss.add("Shizuku 未授权")
        if (findJar().isBlank()) miss.add("u2.jar 未就位")
        return miss
    }
}
