package com.love.soulmate.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 通过 uiautomator2 的 android-server 读界面树 + 输入文字。
 *
 * 为什么不用系统的 `uiautomator dump`：
 *   目标应用的聊天列表有持续刷新（在线绿点、时间跳动），
 *   系统的 dump 会一直等 "idle state"，实测 6 次全部失败：
 *       ERROR: could not get idle state.
 *   而 u2 server 走 AccessibilityService 直连，不等待 idle，100% 能拿到。
 *
 * 关键：涉及中文的请求一律走 OkHttp 直连 HTTP，
 *       绝不经过 shell（shell 会破坏 UTF-8，实测中文变 ?????）。
 */
object U2 {

    // 全部从 Env 探测，不写死任何设备相关常量
    @Volatile
    private var port: Int = 9008

    @Volatile
    private var ready = false

    private val URL get() = "http://127.0.0.1:$port/jsonrpc/0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 发 JSON-RPC。
     *
     * ★ 一律走 OkHttp 直连 127.0.0.1，**不依赖设备上的 curl**。
     *   踩坑：模拟器（API 34 google_apis）系统镜像里没有 curl，
     *   原来用 shell curl 的写法在那类设备上 100% 失效。
     *   直连还有一个好处：中文参数不经过 shell 转义，不会再乱码。
     */
    private fun rpc(json: String, timeoutSec: Int = 30): String {
        return try {
            val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(URL).post(body).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                resp.body?.string() ?: ""
            }
        } catch (t: Throwable) {
            ""
        }
    }

    private fun pingOn(p: Int): Boolean {
        val saved = port
        port = p
        val ok = rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", 6).contains("pong")
        if (!ok) port = saved
        return ok
    }

    private fun ping(): Boolean =
        rpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", 8).contains("pong")

    private fun startServer(): Boolean {
        // ★ findJar() 找不到时会自动从 APK 的 assets 里解压
        val jar = Env.findJar()
        if (jar.isBlank()) {
            android.util.Log.e(
                "SoulMate",
                "U2: u2.jar 找不到，且从 assets 解压也失败 —— " +
                        "检查 APK 是否打包了 assets/u2.jar"
            )
            return false
        }

        android.util.Log.i("SoulMate", "U2: 用 jar=$jar 启动 u2 server")

        for (p in Env.portCandidates()) {
            Shell.run(
                "CLASSPATH='$jar' nohup app_process / com.wetest.uia2.Main -p $p " +
                        ">/dev/null 2>&1 &"
            )
            repeat(10) {
                Thread.sleep(900)
                if (pingOn(p)) {
                    port = p
                    android.util.Log.i("SoulMate", "U2: server 已启动，端口 $p")
                    return true
                }
            }
        }

        android.util.Log.e("SoulMate", "U2: 所有端口都试过，server 起不来")
        return false
    }

    /** 确保 server 可用（第一次调用会拉起） */
    fun ensure(): Boolean {
        if (ready && ping()) return true
        if (ping()) {
            ready = true
            return true
        }
        ready = startServer()
        return ready
    }

    /** 拿界面 XML。失败返回空串 */
    fun dump(): String {
        if (!ensure()) return ""
        val r = rpc(
            """{"jsonrpc":"2.0","id":2,"method":"dumpWindowHierarchy","params":[false,50]}""",
            35
        )
        if (r.isBlank()) return ""
        return try {
            val o = JSONObject(r)
            o.optString("result", "")
        } catch (t: Throwable) {
            // 有些版本直接返回 xml 不包 json
            if (r.contains("<hierarchy")) r else ""
        }
    }

    /** 直接点屏幕（u2 的 click，比 input tap 稳，不需要额外权限） */
    fun click(x: Int, y: Int): Boolean {
        if (!ensure()) return false
        val r = rpc(
            """{"jsonrpc":"2.0","id":3,"method":"click","params":[$x,$y]}""", 15
        )
        return r.contains("\"result\"")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, steps: Int = 10): Boolean {
        if (!ensure()) return false
        val r = rpc(
            """{"jsonrpc":"2.0","id":4,"method":"swipe","params":[$x1,$y1,$x2,$y2,$steps]}""",
            20
        )
        return r.contains("\"result\"")
    }

    fun pressBack(): Boolean {
        if (!ensure()) return false
        val r = rpc("""{"jsonrpc":"2.0","id":5,"method":"pressKey","params":["back"]}""", 12)
        return r.contains("\"result\"")
    }

    /**
     * 往当前焦点输入框输入文字（含中文）。
     *
     * 踩过的巨坑（多轮排查才定位）：
     *   1) ADBKeyboard 广播会让 目标应用的输入框节点直接消失 → 弃用
     *   2) 用 shell + curl 传中文 JSON，经过 sh 转义后变成乱码 → 弃用
     *   3) 【正解】直接用 OkHttp POST 到 u2 的 127.0.0.1 端口，
     *      不经过 shell，UTF-8 原样传输。Python 版就是这样才成功的。
     */
    fun inputText(text: String): Boolean {
        if (!ensure()) return false

        // 清空旧内容
        post("""{"jsonrpc":"2.0","id":20,"method":"clearInputText"}""")

        val setJs = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 21)
            put("method", "setClipboard")
            put("params", JSONArray().put("lbl").put(text))
        }.toString()

        val r1 = post(setJs)
        Thread.sleep(500)
        val r2 = post("""{"jsonrpc":"2.0","id":22,"method":"pasteClipboard"}""")
        return r1 != null && r2 != null
    }

    /** 直接用 HTTP POST，不经过 shell（中文安全的唯一方式） */
    private fun post(json: String, timeoutSec: Int = 20): String? {
        return try {
            val req = okhttp3.Request.Builder()
                .url(URL)
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                resp.body?.string()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 清空输入框 */
    fun clearInput(): Boolean {
        if (!ensure()) return false
        val r = post("""{"jsonrpc":"2.0","id":23,"method":"clearInputText"}""")
        return r != null
    }
}
