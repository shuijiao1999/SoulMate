package com.love.soulmate.core

import android.content.pm.PackageManager
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 外壳：拿到 shell 之后跑 adb 命令。
 *
 * 为什么不用无障碍服务：
 *   平板上有两个 目标应用 空间（u0 普通 / u900 分身），无障碍服务只能看前台那个，
 *   而 shell 可以 am start --user 900 精确切换，这一点无障碍做不到。
 *
 * 注意：Shizuku 类本身没有公开的 newProcess，
 * 必须走 IShizukuService.newProcess(...) 拿 IRemoteProcess，
 * 再包成 ShizukuRemoteProcess 才能读输出流。
 */
object Shell {

    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        if (!isShizukuAvailable()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (isShizukuAvailable() && !hasPermission()) {
                Shizuku.requestPermission(1001)
            }
        } catch (_: Throwable) {
        }
    }

    private fun service(): IShizukuService? = try {
        IShizukuService.Stub.asInterface(Shizuku.getBinder())
    } catch (t: Throwable) {
        null
    }

    private fun newProc(cmd: String): ShizukuRemoteProcess? {
        return try {
            val svc = service() ?: return null
            val rp = svc.newProcess(
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) ?: return null
            ShizukuRemoteProcess::class.java
                .getDeclaredConstructor(moe.shizuku.server.IRemoteProcess::class.java)
                .apply { isAccessible = true }
                .newInstance(rp)
        } catch (t: Throwable) {
            null
        }
    }

    /** 执行命令，返回 stdout + stderr */
    fun exec(cmd: String, timeoutMs: Long = 40_000): String {
        val p = newProc(cmd) ?: return ""
        val sb = StringBuilder()
        try {
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    sb.append(line).append('\n')
                    line = r.readLine()
                }
            }
        } catch (_: Throwable) {
        }
        try {
            BufferedReader(InputStreamReader(p.errorStream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    sb.append(line).append('\n')
                    line = r.readLine()
                }
            }
        } catch (_: Throwable) {
        }
        try {
            p.waitForTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
        return sb.toString()
    }

    /** 只执行，不等输出 */
    fun run(cmd: String) {
        val p = newProc(cmd) ?: return
        try {
            p.waitForTimeout(15_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
    }
}
