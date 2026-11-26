# SoulMate

> 一个 Android UI 自动化的开源示例项目 —— 演示如何用 Shizuku 获取 shell 级权限、
> 通过 uiautomator2 做界面自动化、并用视觉模型在控件定位失败时兜底。

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-blue)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://www.android.com/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

---

## ⚠️ 重要声明（请先读）

### 这是什么

这是一个 **Android UI 自动化技术的教学示例项目**。它的价值在于演示：

- 如何用 **Shizuku** 从普通 App 拿到 shell 级权限
- 如何用 **uiautomator2** 通过 HTTP JSON-RPC 做界面自动化（而非 Binder）
- 如何用 **视觉模型** 在找不到控件时兜底定位
- 如何设计一个能长时间运行的**状态机**（限流、去重、卡死自救）

这些技术在任何安卓自动化场景都能复用 —— 无障碍测试、批量操作、
数据采集、RPA、自动化回归等。

### 本项目不提供

- ❌ 任何 API Key、服务器、或开箱即用的服务
- ❌ 针对任何特定平台的开箱配置
- ❌ 绕过风控、批量骚扰、刷量的方案

### 使用前请想清楚

1. 自动化操作可能违反目标应用的服务条款
2. 未经对方同意批量发送消息可能构成骚扰
3. 作者不对任何滥用行为负责

**如果你只是想学技术 —— 读代码就好，不必真的跑起来。**

---

## ✨ 演示的技术点

| 模块 | 解决的问题 |
|---|---|
| **Shizuku 提权** | 普通 App 如何获得 shell（uid 2000）身份执行命令 |
| **u2 HTTP 架构** | 为什么不走 Binder 而走 HTTP JSON-RPC |
| **三层控件定位** | rid → text → 视觉模型，逐层降级 |
| **视觉兜底** | 截图 + 元素编号 + 让模型选编号 |
| **卡住自救** | 用「多久没进展」触发，而非重试次数 |
| **状态机设计** | 限流、去重、断点续跑、随时可停 |
| **本地存储** | SQLite 管理跨轮次的上下文 |

---

## 🔧 技术方案

### 为什么用 Shizuku 而不是无障碍

| 能力 | 无障碍 | Shizuku |
|---|---|---|
| 读界面树 | ✅ | ✅ |
| 点击滑动 | ✅ | ✅ |
| 截图 | ✅ (API30+) | ✅ |
| **强行重启目标 App** | ❌ | ✅ |
| **读目标 App 私有数据** | ❌ | ✅ |
| **跨用户空间操作** | ❌ | ✅ |

Shizuku 通过 `adb`/`root` 拿到 `shell` 身份，能力完整。

### 核心链路

```
宿主 App
    │
    ├── Shizuku（拿 shell 权限）
    │       └── 启动 u2 server（app_process）
    │
    └── HTTP → 127.0.0.1:9008/jsonrpc/0
            └── uiautomator2 服务
                    └── 操作目标 App
```

**关键设计**：App 与 u2 之间走 **HTTP**，不走 Binder。
好处是 u2 一旦启动，App 直连 127.0.0.1 即可，不依赖 shell 每次转发。

### 三层控件定位

```
第 1 层：按 resource-id 找        ← 最准
第 2 层：按 text / content-desc   ← 次准
第 3 层：截图 + 元素编号 + 视觉模型 ← 兜底
```

前两层免费且快；第三层有成本，只在都失败时才走。

### 视觉兜底是怎么做的

不是让模型「看图给坐标」（那会很飘），而是：

1. dump 界面树，拿到所有可点击元素的**真实 bounds**
2. 在截图上给每个元素画编号气泡
3. 问模型「为了实现 <目标>，该点哪个编号」
4. 模型只回一个数字，实际坐标仍来自 dump

**好处**：模型选错编号最多是选错目标，不会出现「坐标落在空隙里」。

---

## 📦 构建

### 环境要求

- Android Studio（或仅命令行 SDK）
- JDK 17
- Android SDK 34
- Gradle 8.x（用项目自带 wrapper）

### 命令

```bash
cd android
./gradlew assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

---

## 📱 安装与配置

### 1. 装 Shizuku

从 [Shizuku 官网](https://shizuku.rikka.app/) 或 GitHub Releases 下载安装。

**启动 Shizuku 服务**（二选一）：

- **无线调试**（Android 11+，不需要电脑）：
  1. 开发者选项 → 无线调试 → 打开
  2. Shizuku → 点「配对」→ 按提示输入 6 位配对码
  3. 之后每次重启手机，打开 Shizuku 点「启动」即可

- **连电脑 adb**（每次重启都要做）：
  ```bash
  adb shell /data/app/~~xxx/moe.shizuku.privileged.api-xxx/lib/arm64/libshizuku.so
  ```

### 2. 装本 App

```bash
adb install app-debug.apk
```

或直接传到手机点击安装。

### 3. 授权

1. 打开 App
2. 点右上角「Shizuku 未授权」→ 弹窗里选「始终允许」
3. 授予「悬浮窗」权限（用于运行时随时停止）

### 4. 配置模型

设置里填 LLM 的 API 地址、Key、模型名。需要**支持视觉**的模型
（用于第三层兜底定位）。

**这三项都没有默认值，源码里是空的。** 未填时 App 会提示
「未配置 API Key」。

任意 OpenAI 兼容的网关都能用：

| 服务商 | Base URL 示例 |
|---|---|
| OpenAI | `https://api.openai.com/v1` |
| DeepSeek | `https://api.deepseek.com/v1` |
| 硅基流动 | `https://api.siliconflow.cn/v1` |
| 自建网关 | `http://你的地址:端口/v1` |

填 `/messages` 结尾则自动走 Anthropic 协议（Claude）。

### 5. 配置微信推送（可选）

App 能在关键时刻推微信给你（任务出错、需要人工介入等）。

用的服务是 **[WxPusher](https://wxpusher.zjiecode.com/)**（免费的消息推送，
不需要公众号、不需要服务器）。

#### 怎么配

1. 打开 <https://wxpusher.zjiecode.com/> 用微信扫码登录
2. 左侧「**应用管理**」→「**创建应用**」
   - 应用名随便填
   - 其他项不用改
3. 左侧「**用户管理**」→ 扫二维码关注你的应用
4. 回应用详情页，找到「**Simple Push**」那栏，复制 **`SPT`**（形如 `SPT_xxxxx`）

**只需要 SPT 一个值**，appToken 和 UID 都不用填。

#### 接口细节

```
POST https://wxpusher.zjiecode.com/api/send/message/simple-push
Content-Type: application/json

{
  "spt": "SPT_xxxxx",
  "summary": "标题（最多 98 字）",
  "content": "正文",
  "content_type": 1
}
```

返回 `{"code":1000}` 表示成功。

---

## ⚙️ 配置项

所有功能都可在界面勾选开关：

| 配置 | 默认 | 说明 |
|---|---|---|
| 自动匹配 | 关 | 消耗平台额度，用完自动跳过 |
| 自动回复 | 开 | 扫全部会话（不只顶部 N 个） |
| 弹窗处理 | 开 | 处理打断流程的浮层 |
| 主动打招呼 | 开 | 处理「有人主动来打招呼」 |
| 清理废弃会话 | 开 | 超时 + 地区不符则删 |
| 同城评论 | 关 | 帖子下评论（需目标平台支持） |
| 人像筛选 | 关 | 需视觉模型 |
| 微信推送 | 关 | 需填 WxPusher SPT |
| 可接受地区 | 江浙沪 | 多选 |

### 风控参数（硬编码，可按需调整）

```kotlin
SAME_PERSON_GAP = 45_000L   // 同一人最少隔 45 秒
GLOBAL_GAP      = 20_000L   // 任意两条最少隔 20 秒
HOURLY_CAP      = 25        // 每小时最多 25 条
DAILY_CAP       = 120       // 每天最多 120 条
```

**这些参数是为了减少打扰，不是为了绕过风控。不建议调小。**

---

## 🗂️ 项目结构

```
android/
├── app/src/main/
│   ├── assets/u2.jar              # uiautomator2 服务端
│   ├── java/com/love/soulmate/
│   │   ├── core/                  # 基础设施
│   │   │   ├── Db.kt              # SQLite（上下文/档案/记录）
│   │   │   ├── Env.kt             # 环境探测、jar 部署
│   │   │   ├── Shell.kt           # Shizuku shell
│   │   │   ├── U2.kt              # u2 JSON-RPC 客户端
│   │   │   ├── Ui.kt              # 界面操作封装
│   │   │   ├── Screen.kt          # 屏幕适配
│   │   │   ├── StateReader.kt     # 页面状态识别
│   │   │   ├── Vision.kt          # 视觉兜底定位
│   │   │   └── Pusher.kt          # 消息推送
│   │   │
│   │   ├── engine/                # 流程编排
│   │   │   ├── Orchestrator.kt    # 主流程
│   │   │   ├── Monitor.kt         # 常驻监控循环
│   │   │   ├── Features.kt        # 功能开关
│   │   │   └── StuckRescue.kt     # 卡住自救
│   │   │
│   │   ├── llm/                   # 模型调用
│   │   │   ├── Llm.kt             # 通用 LLM 客户端
│   │   │   └── ...
│   │   │
│   │   ├── platform/              # 平台适配层
│   │   │
│   │   ├── ui/SoulMateApp.kt      # Compose 界面
│   │   ├── MainActivity.kt
│   │   ├── FloatStop.kt           # 悬浮停止按钮
│   │   └── RunService.kt          # 前台服务保活
│   └── res/
└── build.gradle.kts
```

---

## ⚠️ 已知限制

### 1. 屏幕必须亮着

uiautomator 依赖亮屏才能读界面。屏幕一黑，所有操作都会卡住。

建议开启：
```bash
adb shell settings put global stay_on_while_plugged_in 7
adb shell settings put system screen_off_timeout 1800000
```

### 2. Shizuku 重启后要重新启动

手机重启后 Shizuku 服务会失效。打开 Shizuku App 点「启动」即可
（无线调试配对过就不需要电脑）。

### 3. 目标 App 更新会失效

控件 id 和布局会变。这是所有 UI 自动化的通病，本项目用三层定位
+ 视觉兜底尽量缓解，但无法完全避免。

---

## 📖 代码阅读建议

代码里的注释记录了大量踩坑经验 —— 每个关键决策点都写了「为什么这样做」
和「之前踩了什么坑」。建议重点看：

| 文件 | 说明 |
|---|---|
| `engine/StuckRescue.kt` | 卡住自救的设计（用时间戳触发，而非重试次数） |
| `core/Vision.kt` | 为什么用「标注编号」而不是「让模型给坐标」 |
| `core/Db.kt` | 为什么用 SQLite 而不用 Room |
| `FloatStop.kt` | 悬浮球防误触的三次迭代 |
| `core/U2.kt` | 为什么走 HTTP 而不走 Binder |

---

## 📜 License

MIT

---

## 🙏 致谢

- [Shizuku](https://github.com/RikkaApps/Shizuku) —— Apache 2.0
- [uiautomator2](https://github.com/openatx/uiautomator2)
- [uiautomator-server](https://github.com/openatx/uiautomator-server)

---

**本项目仅供学习 Android 自动化技术使用。**

使用者应自行确保其行为符合所在地区法律法规及目标应用的服务条款。
作者不对任何因使用本项目导致的账号封禁、法律纠纷或第三方损失承担责任。
