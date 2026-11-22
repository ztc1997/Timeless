# AGENTS.md — 无时 Timeless

给 AI 助手 / 新协作者的项目速览。改代码前先读完这一页。

## 项目是什么

ColorOS 17（Android 17）睡眠时段把系统各处时钟替换为占位符 `-` 的
LSPosed 模块。包名 `com.ztc1997.timeless`，libxposed API 102，Kotlin。
真机：一加 PLQ110（早期）与 PJD110（2026-11-19 起，ColorOS 17.0.0.100）；
两台机器的时钟链路**不完全相同**，见下面第 4 条。

## 目录结构

```
app/src/main/java/com/ztc1997/timeless/
  core/      纯逻辑层（不依赖 Xposed API，双进程共用）
    ConfigKeys.kt       remote preferences 键表 + 默认值
    ConfigSnapshot.kt   配置不可变快照
    SleepWindow.kt      睡眠时段判定（含跨午夜）
    GLog.kt             统一日志出口（Sink 由 hook 入口注入）
  hook/      hook 层
    TimelessModule.kt           入口（META-INF/xposed/java_init.list 注册）
    ClockMaskFeature.kt         状态栏/锁屏(shared.clocks)/AOD 的替换分派
    ClockTargets.kt             hook 候选表（HookMode: SET_TEXT/RETURN_VALUE/HIDE_VIEWS）
    PersonalityClocksFeature.kt 插件时钟源头拦截（核心，见下）
    HostClockHider.kt           第三方主题锁屏/AOD 引擎时钟的钩子安装与状态自检
    EngineClockPainter.kt       引擎时钟的星月直绘（锁屏位图 / AOD drawText）
    StarMoonGlyph.kt            星月字形的唯一实现（Span 与引擎直绘共用）
    StarMoonSpan.kt             文本链路的星月 ReplacementSpan
    ConfigSource.kt             远端偏好配置通道
    DndState.kt / HookBag.kt / Reflect.methods.kt
  appconfig/ 模块 App 侧 UI 与配置写入
reverse/     真机逆向归档（见下节）
HOOK_PLAN.md hook 方案推导全文
```

## 构建 / 部署 / 调试（真机 USB）

```bash
./gradlew :app:assembleDebug
MSYS_NO_PATHCONV=1 adb install -r app/build/outputs/apk/debug/app-debug.apk
# 安装会自动重启作用域内进程；保险起见手动强杀：
PIDS=$(adb shell pidof com.android.launcher com.android.systemui | tr -d '\r')
adb shell "su -c 'kill -9 $PIDS'"
```

- 作用域：`com.android.systemui` + `com.android.launcher`（模块 App 内可改）
- **签名**：口令放在项目根 `keystore.properties`（已 gitignore），
  `app/build.gradle.kts` 读它给 **debug 与 release 都绑同一套 release 签名**。
  设备上装的就是 release 签名版，debug 用默认 Android Debug 签名会直接
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，而卸载重装会丢模块配置。文件缺失或
  没填全时静默退回 Gradle 默认行为，不影响他人克隆后构建。
  该文件用 UTF-8 读（storeFile 含中文路径），口令四项：`storeFile` /
  `storePassword` / `keyAlias` / `keyPassword`。
- **模块日志在 LSPosed 日志里，logcat 看不到**：
  `adb shell "su -c 'grep timeless /data/adb/lspd/log/modules_*.log'"`
- 设备 root：KernelSU，shell 有 su 权限
- 诊断期 hook 进程日志强制 debug 级（`TimelessModule.install()` 里
  `GLog.level = 3`，配置变更回调同样强制）；问题收口后改回跟随配置

## 核心 hook 设计（真机验证结论）

ColorOS 17 时钟渲染分两套，都要拦：

1. **SystemUI 自带**（状态栏 + 部分 AOD）：
   - 状态栏 `com.android.systemui.statusbar.policy.Clock#getSmallTime`
     （RETURN_VALUE 模式，**必须先 proceed 再覆盖返回值**——内部懒初始化
     SimpleDateFormat，跳过 proceed 会 NPE 崩 SystemUI、触发 LSPosed 安全模式）
   - AOD `com.oplus.systemui.aod.aodclock.off.clock.DefaultClockView#updateTime` /
     `DialClockView#onDraw`（HIDE_VIEWS 模式，alpha 隐藏）
2. **插件 `KeyguardPersonalityClocks.apk`**（`/system_ext/app/`，桌面灵感时钟卡片
   + 锁屏个性时钟的实际渲染方）：
   - 宿主通过 `createPackageContext("com.oplus.keyguard.personality.clocks")`
     加载，拿到的 classLoader 与宿主实际持有的是**同一实例**（已验证，无分身问题）
   - 时间数字统一写入入口 `tryUpdateText(CharSequence)`，有 **3 层实现**，
     虚分派终点是 `digital.widget.MyCustomizedTextView`（覆写后直接 setText）：
     ```
     com.oplus.keyguard.clock.digital.widget.MyCustomizedTextView  ← 必须挂
     com.oplus.keyguard.clock.common.widget.CustomizedTextView     ← 公共基类
     com.oplus.keyguard.clock.base.widget.CustomizedTextView      ← 中间层(final)
     ```
     只挂基类会被覆写完全绕过（教训：桌面短暂替换后恢复真实时间、锁屏不生效）
   - 拦截规则：写入内容为 1~2 位纯数字才替换为 `-`（冒号/AM/PM/日期/天气放行）；
     命中时 `chain.proceed(arrayOf("-"))` 保留原方法副作用
   - 安装时机：宿主 Application 创建后（Instrumentation.callApplicationOnCreate
     钩子）+ 状态栏钩子首次回调兜底；`onPackageReady` 时 Application 往往未 attach
3. **主题模板引擎自绘**（第三方主题的锁屏 + AOD，2026-11-19 真机取证）：
   - 锁屏 `keyguard_plugin_container` 的实例就是 `KeyguardEngineContainer`
     → VariUIEngine 的 `com.zplus.engine.lk_view.TimeView`：数字是 0-9 Bitmap，
     `draw(Canvas)` 里按 `getHourText()`/`getMinuteText()` 取位图逐位 `drawBitmap`
   - AOD 走 EGView 的 `com.oplus.egview.widget.TimeView`：垂直两行「时 / 分」，
     `onDraw` + `canvas.drawText`
   - **这套引擎自己遍历子元素直接调 `view.draw(canvas)`，不经
     `ViewGroup.dispatchDraw`，所以 `visibility=INVISIBLE` 和 `alpha=0` 一律无效**
     （真机两次实测：两个属性都设了，画面照旧）。唯一入口是绘制方法本身：
     睡眠时不 proceed，改由 `EngineClockPainter` 在**原数字的格子**上画星月
     （锁屏按 `e[0..9]` 位图宽高逐位排布、冒号 `e[10]` 照画；AOD 按
     `mTextLines` 两行 + `fontSpacing + mExtraLineGap` 复刻基线）。字形来自
     `StarMoonGlyph`——与桌面 `StarMoonSpan` 是同一份实现，所以三处观感一致
   - 引擎类在 VariUIEngine（`com.oplus.uiengine`）自己的 ClassLoader 里，宿主 CL
     找不到；且 `install()` 执行时 `currentAppContext()` 仍为 null。所以由 20s
     自检节拍补装，`ActivityThread.getSystemContext()` 兜底
   - **只在 SystemUI 进程挂**：launcher 的时钟走 personality 的星月占位，
     引擎钩子在那里既不需要也会互相打架
- 日期类（`DateTimeView` / `DateView` / `LunarView`）放行——用户要的是
  「不显示时间」，日期与电量保持原样
4. **桌面/锁屏的「图片数字」**（2026-11-19 换机到 PJD110 / ColorOS 17.0.0.100 后补全）：
   这台机器上桌面与锁屏的时间都不是文本，而是主题的**数字位图**，两条不同链路：
   - 桌面 `ClockTimeView#refreshTimeText` 走 imageMode →
     `Utils.getHostClockNum(ctx, digit)` 取 0-9 位图 → `applyDigitDrawable` 贴成
     compound drawable。**在取图源头**包成 `StarMoonDrawable`（`hookClockNum`），
     intrinsic 沿用原图，宿主字宽/行高不变；星/月按「分钟种子 + 数字值」选，
     与状态栏/文本链路图案一致。上一版在这里设 `alpha=0`，真机表现是**桌面时钟
     整块消失**（用户报障），已废弃。
   - 桌面分隔符：`Utils.getHostClockSymbol` 在 launcher 侧**给不出可见的图**
     （hook 拿到非 null 但屏幕上空白），所以睡眠时段统一换成 `ColonDrawable`
     自己补两个圆点，颜色/高度沿用刚量到的数字。
   - 锁屏走 VariUIEngine 的**逐位 PNG**：每个数字是一个 `ImageElementView`，
     `e` 字段是当前图片名（`time1/time_2.png`），模板表达式按分钟换图。
     `hookEngineClocks` 里的 `drawImageElement` 只拦名字带 `time` 的数字图片
     （日期/装饰放行），在原格子里画星月。**注意**：`TimeView` 与 `ImageNumber`
     的钩子在这台机器上挂得上但一次不回调，别据此判断「锁屏没接管」。
   - 两位数字图都有「格子比墨迹大/尖端顶格会被裁」的问题：`DigitInk` +
     `StarMoonGlyph.inkFraction` 先量出原图墨迹包围盒，再按 `BITMAP_SHRINK` 收
     一点余量，月牙宽度用 `MOON_WIDTH_BITMAP`（文本链路仍用 `MOON_WIDTH_TEXT`）。

5. **假时间占位 `88:88`**（2026-11-20 定稿，取代星月自绘；方案推导见
   `FAKE_CLOCK_PLAN.md`）：不再自绘，而是把「现在几点」这个**答案**换掉，让宿主
   用**主题自己的字形/位图/颜色**重新排版。四条链路各有一个源头：
   - SystemUI 状态栏：`Clock#getSmallTime` 返回值
   - personality 插件文本：`TextView#setText` 最终写入点（`KeyguardTextWriteMask`）
   - ColorfulEngine 壁纸时钟（景深时钟）：`ClockFrameRender#K(Context,long)`
     （`ColorfulClockFrameMask`，需作用域勾选 `com.heytap.colorfulengine`）
   - VariUIEngine 模板时钟：`EngineClockInjector` —— `TimeView#getHourText/
     getMinuteText`、AOD `calcTimeNumber`（改 `mTextLines`）、逐位 PNG
     `ImageElementView#draw`（换图片名），**外加引擎时间变量层**
6. **引擎时间变量层（`variUIEngineProguard.c7.j#k(String)`）不能省**
   （2026-11-20 用户报「锁屏有一位时间没被替换」）：`TimeView.draw()` 里分钟的
   **十位**并不走 `getMinuteText()`，而是直接 `mEngineUtil.c.k("minute")` 取原始
   变量（反编译 `TimeView.java` 第 172 行），只有个位用 `getMinuteText()`。
   只挂 getter 时屏上是「假时 : 真十位 + 假个位」，真实个位恰好等于假数字的那
   一分钟（如假数字 8、真实 18:58）整串看起来就像没替换。
   变量层拦 `minute` / `hour` / `hour12` / `hour24` / `system.time.min1|min2` /
   `system.time.hour1|hour2` / `*_resident` 共 11 个名字：`TimeView#b()` 量宽与
   `draw()` 取值就读到同一个假值，宽度与观感天然一致。类名是混淆的，换版对不上
   时还有一条兜底：从活的 `TimeView` 反查 `mEngineUtil.c` 再挂（只尝试一次）。

7. **启动流体云提醒**（2026-11-22）：屏蔽从无到有时，SystemUI 进程发一条通知，
   标题「无时已启动」，副标题按原因显示「强制屏蔽 / 免打扰模式 / 睡眠时段」，
   附「临时禁用」动作。实现见 `hook/FluidCloudNotifier.kt` +
   `hook/FluidCloudFilter.kt` + `appconfig/TempDisableReceiver.kt`：
   - **必须从 SystemUI 进程发**：真正的 ColorOS 流体云（挖孔旁黑色胶囊、锁屏
     黑色卡片）由 SystemUI 内的 Seedling 插件渲染，模块 App 是普通三方应用，
     发出来只会是普通通知。
   - **AOD（熄屏）不显示胶囊，这是系统限制，不是我们的通知有问题**：真机 PLQ110
     确认 AOD 上只有假时间（及一个小通知图标），系统原生流体云在该模式下同样不渲染胶囊。
     根因是胶囊容器 flow 要求 `SmoothTransitionController.isPanoramicAodEnable()` 为真
     （`Settings.Secure.panoramic_aod_enable=1`），普通 AOD 下为 0。实测临时改 setting、
     hook 胶囊开关 `setAllowToShowNotificationCapsule`（AOD 下根本没被调用）、
     hook 全景判断（flow 放行但 AOD 仍无胶囊 View 节点）三条路都无效，勿重复尝试。
   - **关键：光发通知不够，必须过 filter**。SystemUI 的
     `OplusLiveAlertFilters.filterEntry()` AND 多个子 filter，插件的
     `onLiveNotificationPost` 只认白名单 app/服务，我们的通知拿 flag=0 会被
     降级成普通通知。手动往 extras 塞 `oplusLiveAlertOptions` 没用 ——
     `OplusLiveAlertNotificationsRepository.entryToLiveAlert()` 会覆盖它。
     `FluidCloudFilter` hook 总 filter，只把自家通知（pkg=com.android.systemui
     && id=0x54494D45）的返回值改成 7（允许流体云渲染），
     其余通知原样放行；options 交给系统自己生成。flag 只管识别，不改变 AOD 渲染限制。
   - 曾误以为 Live Updates（promoted ongoing）就是流体云：实测那只是普通通知，
     真正差异全在上面这个 filter 上。渠道用 `live_alert`（与系统
     `LiveAlertChipModel` 一致），`setShortCriticalText` 是胶囊上的短文案
     （「无时·时段」，超过 7 字会被截断）。
   - 触发点是 `ClockMaskFeature` 每次判定后的 `MaskDecision.evaluate()`：
     null → 原因时发帖，原因 → null 时撤销，原因变化才更新同一条通知。
     非 SystemUI 进程不传 sink，不重复发。热重载时显式 dismiss。
   - **临时禁用 = 到本次睡眠窗口结束**：hook 算好到期 Unix 秒放进按钮的
     PendingIntent；模块 App 的 receiver 只写 `ConfigKeys.TEMP_DISABLE_UNTIL_SEC`
     （write 0 表示提前恢复），`SleepWindow.decide` 到点自动恢复原判定，不需要清理。
   - 真机踩坑：模块 App 被 ColorOS 冻结时，显式广播会「Enqueued」但不执行；
     冷启动或前台时正常。PendingIntent 从 SystemUI 点按钮一般能拉起进程，
     但自动化验证广播前最好先 `am force-stop`。

### 血泪教训（勿重蹈）

- **别只信宿主的「取时间方法」**：`TimeView` 有 `getHourText()`/`getMinuteText()`，
  但 `draw()` 里分钟十位是另走 `k("minute")` 的（2026-11-20「有一位没被替换」）。
  换成假数字这类改造，要顺着**每一个取值点**看反编译，不能只看同名 getter；
  变量层（`VariableManager`）比各个 getter 更靠源头，量宽与绘制共用同一份值。
- **首选热重载，不要习惯性杀进程**：`module.prop` 里 `autoHotReload=true`，
  模块已实现 `onHotReloading()→true` / `onHotReloaded()`（卸载旧句柄 + 重装）。
  真机实测**同版本 `install -r` 覆盖安装即触发热重载**，不用 bump `versionCode`
  （「必须改版本号才触发」是早期误记，2026-11-20 已按实测纠正）。
  `onHotReloaded()` **必须重新 `GLog.install`**：新 generation 的静态状态是全新的，
  不注入日志出口就只剩 `android.util.Log` 兜底（LSPosed 日志里什么都看不到，
  曾误判成「热重载失败」——排查时记得 `adb logcat -s Timeless`）。
- **连续多次 `kill -9` SystemUI 后，LSPosed 会停止注入新进程**
  （日志停在被杀那一刻）。`killall` 无效，只能 `adb reboot`。热重载能省掉这一步。
- **热重载只卸载钩子，不会取消 `Handler.postDelayed` 的节拍**：旧 generation 的
  ticker 仍在主线程队列里循环，会按**旧代码**继续改视图（2026-11-19 实测：删掉
  「整块 alpha/visibility 隐藏」后重载，日志里 `AOD 容器 visibility → 4` 照旧每
  几十秒出现一次，锁屏/AOD 依然空白）。所以**验证「删除某类 hook」必须杀一次
  宿主进程或重启设备**，否则测的是旧逻辑；新代码也拿不到旧实例去 stop。
- **「整块隐藏」只能用在确认时钟就是该视图的场合**：`CustomOplusKeyguardStyleClock`
  的 `setAlpha/setInnerAlpha → 0`、`AodBlackLayout#setVisibility → INVISIBLE`、
  `style.widgets` 的 `widgetsViewRoot.alpha = 0` 在第三方主题上无效（时钟不是它们
  画的），但在**官方主题**上就是屏幕上那块容器的真身——时间**连同日期**整块消失，
  只剩电量与通知图标。三组已于 2026-11-19 删除，`HostClockHider` 现在只做引擎
  时钟的绘制接管。要隐藏就画星月，不要留一块空白。
- **引擎时钟绘制接管失败会静默空白**：`EngineClockPainter.draw` 取不到数字时返回
  false，`hookEngineClocks` 直接 `return null` 不画（口径是「绝不回落真实时间」）。
  现在失败会打一次「取不到数字，本块将空白」告警，看到这条说明该类布局与预期的
  字段不符，要先补字段而不是继续调星月形状。

- **混淆符号缺失必须留 warn，不能裸 `return 0`**：`ImageElementView` 的字段 `e`、
  `variUIEngineProguard.c7.j#k(String)`、`ClockFrameRender#K` 都是混淆名，宿主换版
  改名后钩子就**静默不装** —— 画面照旧真实时间、日志干净，只能靠肉眼发现。四处失败
  分支已补「缺少 xxx（混淆改名？）」告警（2026-11-20）；排查「某个时钟没替换」先
  grep 这几条。另：`HostClockHider.install()` 已按进程名收口到 SystemUI，
  launcher / colorfulengine 不再重复挂引擎钩子（只留各自的 personality / 壁纸链路）。

- **切勿 hook `ClassLoader.loadClass`** 做"类加载看门狗"：在 loadClass 回调里再
  `xposed.hook()` 会触发框架类加载重入，真机实测直接把 LSPosed 打进安全模式
- 桌面时钟不要用"遍历 View 树写文本"的钩子（已删除 installDesktop）——与源头
  参数替换交替覆盖，会导致桌面在两种占位样式间跳动
- **`KeyguardEngineContainer` 整层 `alpha=0` 副作用极大**：它是整个主题锁屏
  壁纸层，连清晰壁纸底图和手电筒/相机快捷按钮一起抹掉，屏上只剩模糊壁纸
- **改完 APK 连杀几次 SystemUI 后，LSPosed 可能不再注入新进程**（日志停在被杀
  那一刻）。此时 `killall` 无效，必须 `adb reboot` 恢复——不要误判成代码问题
- RETURN_VALUE 模式永不跳过 `chain.proceed()`
- **模块 App 侧配置不要在组合期"读一次就记住"**：框架 binder 由 daemon 反向
  调用 `XposedProvider` 异步注入，release 包启动快，首帧常常赶在连接之前，
  那时只能读到本地文件（首次安装是空的）→ 设置页永远显示默认参数（2026-11-18
  修复）。现在 `ModulePreferences.load()` + 监听刷新，并且：
  - 写远端成功的键镜像一份到本地，冷启动先用它顶，不闪默认值
  - 框架没连上时的写入记进 `__timeless_pending`，连接后覆盖式补写远端
    （否则那次保存会被永久丢在本地，远端旧值继续生效）
  - 界面有未保存改动（dirty）时不跟随后台推送，避免冲掉用户刚拨的开关

## reverse/ — 逆向归档

从真机系统 APK 用 jadx 1.5.6 反编译（2026-11-17），原始 APK 在 `D:/`：

| 目录 | 来源 APK（D:/） | 说明 |
|---|---|---|
| `out-personality/` | 锁屏相关 KeyguardPersonalityClocks | **最重要**：桌面/锁屏时钟全部实现 |
| `out-clockbase/` | clock.base | CustomizedTextView 基类族 |
| `out-aod/` | 息屏_17.001.0021.apk（com.oplus.aod） | AOD 设置页（渲染在 systemui） |
| `out-inspiration/` | 灵感组件_17.00.030.apk | 灵感组件（未 hook，时钟走 personality） |
| `out-systemui/` | 系统界面_17.99.02.apk | **仅节选**：shared/clocks、aod、statusbar/policy/Clock.java |
| `*.manifest.txt` / `*.log` | — | aapt manifest、jadx 日志 |

`out-launcher`（180M）与 systemui 其余部分未归档，原始工作目录
`C:/Users/ztc20/AppData/Local/Temp/rea_work/`（临时目录，可能已被清理）。
查锁屏时钟资源 id：`reverse/out-personality/resources/` 下搜
`visible_digital_time_text_view`。

## 约定

- 配置跨进程一律走 remote preferences（组 `timeless_config`），键表只认
  `ConfigKeys`；hook 回调每帧读 `ConfigSource.snapshot`，不缓存
- core/ 层禁止 import 任何 `io.github.libxposed.api` 类型
- 新 hook 目标先进 `ClockTargets` 候选表再实现
- 占位符 = 假时间 `88:88`（`core/FakeTime.kt`）：四位同值，取值 6..9，按分钟
  确定性生成且相邻分钟必变；`digit(seed, original)` 刻意忽略原数字，所以整串
  替换与逐位写入天然对齐，也无法反推真实时间。宿主用自己的字形/位图/颜色
  重新排版，不再自绘。`core/Placeholder.kt`、`hook/StarMoonSpan.kt`、
  `hook/StarMoonDrawable.kt`、`hook/EngineClockPainter.kt` 均为 [LEGACY]
  回退代码，勿在新链路引用
