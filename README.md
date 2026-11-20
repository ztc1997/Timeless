# 无时 Timeless

睡眠时段屏蔽系统时间显示。夜间醒来不看时间，避免"还有多久天亮"的焦虑计算，改善睡眠质量。

> 名字取自「无时」——夜里没有时间。英文 Timeless 既是「超越时间」，也读作 time-less（没有时间）。

## 技术路线

基于 **libxposed 现代 API（API 102）** 的 LSPosed 模块。睡眠时段不再自绘星月，而是把"现在几点"这个**答案**换成 `88:88` 这类四位全同的不可能钟点，让宿主用自己的字形、位图、颜色重新排版。

假数字按分钟确定性生成（6~9），相邻分钟必变、无法反推真实时间，逐位写入与整串替换天然一致。详见 [FAKE_CLOCK_PLAN.md](FAKE_CLOCK_PLAN.md)。

仅支持 **ColorOS 17 / Android 17（API 37）**（OPPO / 一加 / realme 同底座）；`minSdk` 与 `targetSdk` 均为 37。

### 触发策略（优先级从高到低）

1. 总开关关闭 → 不屏蔽
2. 手动覆盖：强制显示 / 强制屏蔽 → 直接决定
3. 与勿扰 / 睡眠模式联动开启且勿扰生效 → 屏蔽
4. 固定时段（支持跨午夜，如 23:00–07:00）或系统自动学习作息 → 屏蔽
5. 其余 → 不屏蔽

配置项通过 `XposedService.getRemotePreferences()` 跨进程下发，改动即时生效，无需重启宿主进程。

## 工程结构

```
app/src/main/
├── resources/META-INF/xposed/     # 模块声明（API 102，非 legacy）
│   ├── module.prop                # min/target=102 / exceptionMode=protective / autoHotReload
│   ├── java_init.list             # 入口：TimelessModule
│   └── scope.list                 # 作用域候选（SystemUI / 启动器 / ColorfulEngine）
├── java/com/ztc1997/timeless/
│   ├── core/                      # 两进程共用，不引用 Xposed API
│   │   ├── ConfigKeys.kt / ConfigSnapshot.kt
│   │   ├── SleepWindow.kt         # 时段判定（纯逻辑，可单测）
│   │   ├── FakeTime.kt            # 假时间生成（纯函数，可单测）
│   │   ├── GLog.kt                # 日志出口（函数式注入）
│   │   └── Placeholder.kt         # [LEGACY] 旧星月占位，仅回退保留
│   ├── hook/                      # 仅目标进程，可引用 API
│   │   ├── TimelessModule.kt      # 入口与生命周期、进程级安装门闩
│   │   ├── ConfigSource.kt        # 目标进程读取 remote preferences
│   │   ├── MaskDecision.kt        # 屏蔽判定唯一装配点
│   │   ├── ClockMaskFeature.kt    # 状态栏 / 锁屏时钟
│   │   ├── ClockTargets.kt        # 时钟 hook 点候选表
│   │   ├── PersonalityClocksFeature.kt  # 插件文本 / 主题图片数字取图源头
│   │   ├── KeyguardTextWriteMask.kt     # TextView#setText 最终写入点兜底
│   │   ├── EngineClockInjector.kt       # VariUIEngine / EGView 时间变量与 getter
│   │   ├── ColorfulClockFrameMask.kt    # 壁纸景深时钟
│   │   ├── AodClockHeightGuard.kt       # AOD 电池重叠修复
│   │   ├── HostClockHider.kt            # 引擎钩子补装与自检
│   │   └── StarMoon*.kt / EngineClockPainter.kt  # [LEGACY] 星月自绘回退
│   └── appconfig/                 # 模块 App 进程，不引用 API
│       ├── TimelessApp.kt / FrameworkBridge.kt
│       ├── ModulePreferences.kt   # 配置写入（远端优先，本地兜底）
│       └── MainActivity.kt        # Compose 设置页
└── res/                           # 模块名与设置页资源
```

## 构建与安装

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

1. 手机需 Root + LSPosed（框架需支持 Xposed API 102）。
2. 安装 APK，在 LSPosed 中启用模块。
3. **勾选作用域**：`com.android.systemui`、设备上实际的启动器包（本机为 `com.android.launcher`），使用景深/引擎壁纸时钟的主题还需勾选 `com.heytap.colorfulengine`。
4. 调试期覆盖安装（`install -r`）即可触发热重载，无需改 `versionCode`。
5. 打开"无时"App 配置时段与联动开关。

> 未连接框架时，App 会把配置暂存在本地；首次在 LSPosed 勾选后自动迁移到远端。

## 当前覆盖

| 时钟 | 目标类 / 方法 | 方式 |
|---|---|---|
| 状态栏 | `Clock#getSmallTime()` | 先 proceed 再覆盖返回值为假时间 |
| 锁屏横排 / 单 / 双时钟 | `RedHorizontalSingleClockView` 等 | 写字段或异步 Runnable 出口 |
| AOD 系统自带数字 / 表盘 | `DefaultClockView#updateTime()` / `DialClockView#onDraw()` | 按判定设 alpha |
| 插件时钟文本 | `MyCustomizedTextView#tryUpdateText()` | 源头换参数 |
| 主题图片数字 | `Utils.getHostClockNum()` / `ThemeClockHelper#setTextImg()` | 源头换数字，沿用主题位图 |
| 锁屏文本兜底 | `TextView#setText()` | 仅 `com.oplus.keyguard.*` 且形似时间 |
| VariUIEngine / EGView | `TimeView` getter、`calcTimeNumber`、时间变量 `k(String)`、逐位 PNG | 换假数字，宿主自排版 |
| 壁纸景深时钟 | `ClockFrameRender#K(Context,long)` | 返回假时间（需勾选 ColorfulEngine） |

日期、星期、农历、天气、电量均不处理。

## 已知限制

- 桌面外部 AppWidget 时钟（天气 / 世界时钟等）暂未覆盖。
- 依赖 ColorOS 17 具体类名/混淆名；换版后需按 [HOOK_PLAN.md](HOOK_PLAN.md) 重新校准。
- 连续多次强杀 SystemUI 后 LSPosed 可能停止注入，需重启设备；优先用热重载。

## 验证

```bash
./gradlew testDebugUnitTest :app:assembleDebug
./gradlew assembleRelease   # R8 full 冒烟：设置页打开、开关保存
```

先查单测，再看 LSPosed 日志（tag `Timeless`）确认每个进程"首帧配置 / 挂钩成功"只出现一次；热重载后不应再出现旧的周期性自检日志。
