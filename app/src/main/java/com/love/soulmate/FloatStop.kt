package com.love.soulmate

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 悬浮停止按钮。
 *
 * ── 为什么需要（2026-11-24）──
 * App 运行时必须退到后台去操作目标应用，而「停止」必须随手可点。
 * 一开始放在 App 界面上 —— 切到目标应用 后就点不到了。
 * 后来放到通知栏 —— 能点，但要下拉通知栏，还是多一步。
 *
 * 用户提议：「停止这个单独做个小按钮，时刻悬浮在屏幕上是不是好点」
 * 这是最好的方案：常驻悬浮球，任何界面都能点。
 *
 * ── 实现要点 ──
 *   · WindowManager + TYPE_APPLICATION_OVERLAY
 *   · 需要 SYSTEM_ALERT_WINDOW 权限（用户手动开，无法自动授予）
 *   · 可拖动，免得挡住 目标应用的关键按钮
 *   · 结束时立刻移除，不要残留
 *
 * ── 权限 ──
 *   Android 6+ 必须用户手动在「设置 → 应用 → 显示在其他应用上层」里开。
 *   Settings.canDrawOverlays() 检查，没开学就引导用户去开。
 */
object FloatStop {

    private var wm: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** 是否可以显示悬浮窗（权限已授予） */
    fun canShow(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(ctx)
        } else true
    }

    /** 当前是否正在显示 */
    fun isShowing(): Boolean = view != null

    /**
     * 显示悬浮停止按钮。
     *
     * @param onClick 点「停止」时触发
     * @return 是否成功显示（没权限返回 false）
     */
    fun show(ctx: Context, onClick: () -> Unit): Boolean {
        if (view != null) return true
        if (!canShow(ctx)) {
            android.util.Log.w("SoulMate", "FloatStop: 没有悬浮窗权限")
            return false
        }

        return try {
            val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = w

            val tv = TextView(ctx).apply {
                text = "■ 停止"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(28, 16, 28, 16)

                // 圆角红底，带一点透明
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 60f
                    setColor(Color.parseColor("#E6B3261E"))
                    setStroke(2, Color.parseColor("#66FFFFFF"))
                }

                // ★ 长按停止（2026-11-25 用户反馈后重写）
                //
                // ── 用户反馈 ──
                // 「悬浮在屏幕上的停止功能好像没不起作用」
                //
                // ── 原来的设计（已废弃）──
                // 3 秒内连点两次才停。本意是防误触（自动化操作目标应用 时
                // 点击派发到最上层窗口，误点到球会杀掉监控）。
                //
                // ── 为什么不好用 ──
                //   ① 3 秒窗口太短：用户犹豫一下、或者第一下没点准，
                //      就变回「再点一次停止」，感觉「点了没反应」
                //   ② 自动化运行时屏幕在动、点在跳，很难在 3 秒内精确点两次
                //   ③ 视觉上「再点一次停止」和「■ 停止」差别不明显，
                //      用户以为已经生效了
                //
                // ── 现在的设计 ──
                //   · **长按 700ms = 立即停止**（一步到位，不会误触）
                //   · 短按 = 只提示「长按停止」，不停止
                //
                // 防误触效果更好：自动化误点只会有一次 80ms 的短按，
                // 根本到不了 700ms，永远不会误停。
                //
                // ★★ 踩坑（2026-11-25，第一次改完长按没生效）★★
                // 把长按/拖动的 setOnTouchListener 分成了两处写：
                // 一个管长按，一个管拖动。
                // 而 View.setOnTouchListener 是**覆盖式**的，
                // 后设置的会把前面那个整个顶掉 —— 结果长按永远收不到事件。
                // 现在合并到下面**同一个** setOnTouchListener 里。
                // （downAt / moved 等状态变量也挪到 apply 外面统一声明）
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // ★ 踩坑（2026-11-24，导致监控被误停）
                //
                // 原来放在 (40, 260) —— 屏幕左上角。
                // 但 目标应用的返回按钮在 (50, 135)，聊天列表首行在 y=170 附近。
                //
                // 自动化操作目标应用 时用 `input tap` / u2 click，
                // 系统会把点击派发给**最上层窗口** —— 也就是我们的悬浮球。
                // 结果：点目标应用的返回键 → 实际点到了「停止」→ 监控被杀。
                //
                // 实测：18:53:36 正常发消息，18:54:03 主线程收到停止请求。
                //
                // 修法：挪到**右侧竖直中间**，那里 那里没有高频交互元素
                // （右侧只有头像/点赞/更多，且都在顶部或底部）。
                // 同时把大小做小一点，减少遮挡面积。
                x = -1   // -1 表示初始化时按屏幕宽度算
                y = -1   // -1 表示按屏幕高度算
            }

            // 计算安全位置：屏幕右侧、竖直 25% 处
            //
            // 为什么是右侧：
            //   聊天页右侧只有头像/更多（顶部）和点赞（底部），
            //   竖直中间区域基本没有可点元素 ← 最安全
            // 为什么不用左上角：
            //   左上角是返回按钮(≈50,135)和会话列表首行，高频误触
            try {
                val dm = ctx.resources.displayMetrics
                p.x = (dm.widthPixels * 0.90f).toInt()
                p.y = (dm.heightPixels * 0.25f).toInt()
            } catch (_: Throwable) {
                p.x = 1400
                p.y = 640
            }

            // ★ 唯一的触摸监听器：同时管「拖动」和「长按 → 二次确认 → 停止」
            //
            // 踩坑见上：之前分成两处 setOnTouchListener，后者把前者覆盖，
            // 导致长按完全收不到事件（表现为「点了没反应」）。
            //
            // ★★ 踩坑 2（2026-11-25 22:18，长按版上线后立刻误停）★★
            //
            // 实测日志：
            //   22:18:09  FloatStop: 长按 4884ms → 停止
            //   22:18:26  FloatStop: 长按 1784ms → 停止
            //   22:18:30  FloatStop: 长按 824ms → 停止
            //   22:18:34  已按你的要求停止
            // 一轮正式运行 3 分钟就被停掉，一条都没发完。
            //
            // ── 原因 ──
            // 自动化流程里有 Ui.longPress()（长按消息气泡删除会话、
            // 长按列表项等），底层是 `input swipe x y x y <ms>`。
            // 这个手势如果**在悬浮球覆盖区域内**（右边缘 x>1098），
            // 系统会把触摸派发给最上层的悬浮窗 —— 也就是停止球。
            // 于是「长按气泡」变成了「长按停止」。
            //
            // ── 修法 ──
            // 长按**不再直接停止**，只把按钮变成「确认」状态，
            // 必须在 3 秒内**再点一次**才真的停。
            // 这样自动化误触的最坏结果只是按钮闪一下变文字，
            // 不会中断正在跑的任务。
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            var downAt = 0L          // 按下时刻（算长按时长）

            // 是否已进入「等待确认」状态
            var armed = false
            var armedAt = 0L

            fun disarm() {
                armed = false
                armedAt = 0L
                tv.text = "■ 停止"
            }

            tv.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = p.x
                        startY = p.y
                        moved = false
                        downAt = System.currentTimeMillis()
                        v.alpha = 0.6f          // 按下反馈
                        false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) {
                            moved = true
                            p.x = startX + dx.toInt()
                            p.y = startY + dy.toInt()
                            try {
                                wm?.updateViewLayout(tv, p)
                            } catch (_: Throwable) {
                            }
                            true
                        } else {
                            false
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        val held = System.currentTimeMillis() - downAt

                        if (moved) {
                            // 拖动结束，什么都不做
                            false
                        } else if (held >= 700 && !armed) {
                            // ★ 第一次长按 → 只进入「确认」状态，不停
                            armed = true
                            armedAt = System.currentTimeMillis()
                            tv.text = "点这确认"
                            android.util.Log.i(
                                "SoulMate",
                                "FloatStop: 长按 ${held}ms → 等待二次确认（不停止）"
                            )
                            // ★ 8 秒没确认才自动撤销（2026-11-25 第二次修）
                            //
                            // 原来设 3 秒，实测太短：人手要反应一下、
                            // 或者从长按结束到移过来点第二次，很容易超时，
                            // 结果又变成「点了没反应」。
                            // 8 秒够从容，而且自动化误触也不会等这么久。
                            tv.postDelayed({
                                if (armed && System.currentTimeMillis() - armedAt >= 7900) {
                                    disarm()
                                }
                            }, 8000)
                            true
                        } else if (held < 700 && armed) {
                            // ★ 已 armed 后的一次点击 → 真的停止
                            android.util.Log.w(
                                "SoulMate", "FloatStop: 二次确认 → 停止"
                            )
                            disarm()
                            try {
                                onClick()
                            } catch (t: Throwable) {
                                android.util.Log.w(
                                    "SoulMate", "FloatStop onClick 失败: ${t.message}"
                                )
                            }
                            true
                        } else if (held >= 700 && armed) {
                            // 已经在确认状态又长按了一次，也当作确认
                            android.util.Log.w(
                                "SoulMate", "FloatStop: 确认状态下再长按 → 停止"
                            )
                            disarm()
                            try {
                                onClick()
                            } catch (t: Throwable) {
                                android.util.Log.w(
                                    "SoulMate", "FloatStop onClick 失败: ${t.message}"
                                )
                            }
                            true
                        } else {
                            // 短按且未 armed → 只提示怎么用
                            tv.text = "长按停止"
                            tv.postDelayed({
                                if (!armed) tv.text = "■ 停止"
                            }, 1200)
                            false
                        }
                    }

                    else -> false
                }
            }

            w.addView(tv, p)
            view = tv
            params = p
            android.util.Log.i("SoulMate", "FloatStop: 悬浮停止按钮已显示")
            true
        } catch (t: Throwable) {
            android.util.Log.w("SoulMate", "FloatStop.show 失败: ${t.message}")
            hide()
            false
        }
    }

    /** 隐藏悬浮按钮（运行结束时必须调，避免残留） */
    fun hide() {
        try {
            view?.let { v -> wm?.removeView(v) }
        } catch (t: Throwable) {
            android.util.Log.w("SoulMate", "FloatStop.hide 失败: ${t.message}")
        } finally {
            view = null
            params = null
            wm = null
        }
    }

    /** 引导用户去开悬浮窗权限 */
    fun requestPermission(ctx: Context) {
        try {
            val i = android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (t: Throwable) {
            android.util.Log.w("SoulMate", "FloatStop 引导权限失败: ${t.message}")
        }
    }
}
