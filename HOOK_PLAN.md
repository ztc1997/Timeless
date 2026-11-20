# 无时 · ColorOS 17 时钟 hook 点校准与方案

> 基于对四个真机提取 APK 的静态反编译（jadx 1.5.6 / aapt / dexdump），非推测。
> 目标设备：OPPO / 一加 ColorOS 17（Android 17，API 37）。
> 补充样本：`灵感组件_17.00.030.apk` 已单独分析，结论见 §2.5。

## 1. 样本事实

| 提取文件 | 包名 | 版本 | 签名/UID | DEX | 角色 |
|---|---|---|---|---|---|
| 系统桌面_17.3.12.apk | `com.android.launcher` | 17.3.12 (170030012) | `oppo.uid.launcher`（平台签名） | 7 | 桌面 + 手势 + 桌面小组件时钟 |
| 息屏_17.001.0021.apk | `com.oplus.aod` | 17.001.0021 (170010021) | 普通应用 | 1（强混淆） | 仅 AOD 设置/编辑/商店页，**不渲染时钟** |
| 系统界面_17.99.02.apk | `com.android.systemui` | 17.99.02 (179902) | `android.uid.systemui`（平台签名） | 9 | 状态栏、锁屏、AOD 时钟的真正渲染进程 |
| 灵感组件_17.00.030.apk | `com.oplus.keyguard.style.widgets` | 17.00.030 (1700030) | 普通应用 | 2 | 锁屏样式**小组件容器 / 卡片中心**，SystemUI 以插件方式同进程加载；**不渲染时间文本** |

关键结论：

- 三者都是标准多重 DEX，**无壳、无加固、无 dex 动态加载**，Java 层 hook 可行。
- **息屏时钟不在 `com.oplus.aod`，而在 `com.android.systemui`**（`com.oplus.systemui.aod.aodclock.*`）。因此 AOD 屏蔽不需要把 `com.oplus.aod` 加进作用域。
- README 里" luminous clock 可能是自绘 View"的担心属实：AOD 时钟是**图片数字 / Canvas 表盘**，没有 TextView 可改文本。
- **"灵感组件"与"桌面灵感时钟"是两回事**：前者是锁屏小组件容器（`com.oplus.keyguard.style.widgets`），后者在 Launcher 的 `com.android.launcher3.inspirationclock` 包内。前者不格式化、不渲染当前时间，无需加入 hook 方案。

## 2. 时钟渲染面（按屏蔽价值排序）

### 2.1 状态栏时钟 —— 首选，最干净

类：`com.android.systemui.statusbar.policy.Clock`，`extends TextView`（位于 `classes.dex`）。

```
public final void updateClock()            // line 687
  → private CharSequence getSmallTime()    // line 269，时间字符串的唯一来源
  → setText(smallTime)                     // line 698
  → mEx.setTextWithRedOneStyle(this, smallTime, mShowSeconds)  // line 699
```

`mEx` 的实现是 `com.oplus.systemui.common.clock.OplusClockExImpl`，其
`setTextWithRedOneStyle(TextView, CharSequence, boolean)` **内部用 `textView.post(Runnable)` 异步 setText**。

> ⚠️ 这正是不少模块"hook 了 updateClock 却不生效"的原因：after 阶段写进去的 `--:--` 会被那个 post 出去的 Runnable 用真实时间覆盖。

**推荐 hook 点**：`Clock.getSmallTime()`（private，返回 `CharSequence`），在 `intercept` 里命中屏蔽条件时**先调用 `chain.proceed()` 让原方法跑完，再用占位符覆盖返回值**。这样 `setText` 与 `setTextWithRedOneStyle` 拿到的都是占位符，异步覆盖也变成覆盖成占位符，完全一致。

> ⚠️ **真机实测教训（2026-11-17）**：绝不能"命中就直接返回占位符、跳过 proceed"。`getSmallTime()` 内部懒初始化了 `mClockFormat` 与 `mContentDescriptionFormat` 两个 `SimpleDateFormat` 字段，跳过原方法后 `updateClock()` 紧接着调用 `mContentDescriptionFormat.format(...)` 会抛 NPE，SystemUI 反复崩溃 → LSPosed 进入安全模式。所以必须 proceed 后再覆盖返回值（丢弃原返回值即可，副作用已保留）。

- 现有 `ClockMaskFeature` 需要新增一种"返回值替换"型 target（`CharSequence` 提供者），与现有"写控件文本"型并存。
- 备用点：`updateClock()` after + 同步屏蔽 `OplusClockExImpl.setTextWithRedOneStyle`（before 里替换为占位符），但复杂度更高，不建议首选。

### 2.2 锁屏"流体时钟" —— 多套样式，需覆盖主流那套

都在 `com.android.systemui` 内，包 `com.oplus.systemui.shared.clocks`：

| 样式 | View 类 | 刷新入口 | 文本载体 |
|---|---|---|---|
| 红色横排单时钟（主流） | `RedHorizontalSingleClockView` (`extends LinearLayout`) | `public final void refresh()` | 内部 `tvHour`=`RedTextClock`、`tvMinute`/`tvWeek`=`android.widget.TextClock` |
| 单时钟 | `SingleClockView` | 对应 `SingleClockController` | 待真机确认 |
| 双时钟 | `DualClockView` | `updateLocatedTime$1` | 待真机确认 |

`RedTextClock`（`extends AppCompatTextView`）的 `public final void onTimeChanged()`（line 243）里：
```
setText(KeyguardUtils.getSpannedHourString(getContext(), string))
```
`RedHorizontalSingleClockView.refresh()` 会依次调用 `tvHour.onTimeChanged()`、`tvMinute.refreshTime()`、`tvWeek.refreshTime()`。

**推荐 hook 点（按优先级）**：

1. `com.oplus.systemui.shared.clocks.RedHorizontalSingleClockView#refresh` —— after 阶段反射取 `tvHour`/`tvMinute`/`tvWeek`，逐个 `setText(占位符)`。一处覆盖三个控件。
2. `com.oplus.systemui.shared.clocks.RedTextClock#onTimeChanged` —— 它本身是 TextView，after 里对 `chain.thisObject` 直接 `setText(占位符)`，适配现有 `applyMask()` 逻辑，改动最小。
3. 上层统一点：`com.android.keyguard.ClockEventController` 负责分发时钟事件，但它不直接持有文本控件，适合做"命中即预处理"，不适合单独承担替换。

> 注：分钟/星期是 framework 的 `android.widget.TextClock`，不要全局 hook 它的 `refreshTime()`，会误伤 SystemUI 里其它 TextClock。通过宿主 View 的字段定位最稳。

### 2.3 AOD 息屏时钟 —— 无文本，只能"藏"

同样在 `com.android.systemui` 内，包 `com.oplus.systemui.aod.aodclock.off.clock`：

- `DefaultClockView extends RelativeLayout`：`public final void updateTime(boolean, boolean)`（line 94）通过 `setImageResource(mHorizontalClock[digit])` / `mVerticalClock[digit]` 把 0–9 的数字**图片**贴到 4 个 ImageView 上；时间来源 `private void getTime()`（line 69，直接读 `Calendar`）。
- `DialClockView extends View`：`onDraw(Canvas)`（line 79）画模拟表盘指针。
- `ClockLayout extends FrameLayout`：持有 `mDefaultClockView` / `mDialClockView`，在两者间切换。
- `AodClockLayout` line 593 调 `updateTime(...)`，line 597 调 `dial.invalidate()`。

**推荐方案**：屏蔽时让整个时钟容器透明（`setAlpha(0f)`），而不是改文本或改 visibility——

1. hook `com.oplus.systemui.aod.aodclock.off.clock.DefaultClockView#updateTime`，after 里对 `chain.thisObject` 设 `alpha=0f`（不屏蔽时恢复 `1f`）。
2. hook `com.oplus.systemui.aod.aodclock.off.clock.DialClockView#onDraw`，before 里若屏蔽则跳过绘制并清屏；或同样对实例设 `alpha=0f`。
3. 更集中的替代点：hook `com.oplus.systemui.aod.aodclock.off.clock.ClockLayout`，在其切换/绘制相关方法里对 `mDefaultClockView`、`mDialClockView` 同时设 alpha。

> 因为 AOD 恰恰是"夜间醒来看到时间"的核心场景，这块优先级其实很高。但要接受：这里做不到显示 `--:--`，只能让时间区域消失。

**已实施（HIDE_VIEWS 模式）**：刷新链路实证为 `AodClockLayout#performLayoutUpdate()` 每分钟调度一次——`mGlobalClockType==1/2` 走 `DefaultClockView#updateTime()`，`==3` 走 `DialClockView#invalidate()→onDraw()`（AodClockLayout.java line 588–598）。据此：
- `aod_digital`：hook `DefaultClockView#updateTime`，after 对 `mGlobalHorizontal` / `mGlobalVertical` 两容器设 alpha（命中 0f、解除 1f）；比逐张 ImageView 更彻底，覆盖横/竖排与冒号。
- `aod_dial`：hook `DialClockView#onDraw`，after 对 View 自身设 alpha（textFields 为空即作用于 thisObject）。after 设 alpha 对本帧不生效、下一帧生效，睡眠是分钟级场景可接受。
- 恢复不靠记忆原值：每次刷新都按当前 `shouldMask()` 重写 alpha，睡眠结束后的下一次刷新（≤1 分钟）自动恢复。
- 限制：`AodData#shouldUseNewRenderMethod()` 为真且 AOD 插件已安装时（RAM-less 新渲染），本地 `ClockLayout` 被 `setVisibility(8)` 隐藏，时间由独立 AOD 插件 APK 渲染——本机 `com.oplus.aod` 只是设置页，该样式不在覆盖内，需实机按 `mGlobalClockType` / AOD 开关判定是否要追加。

### 2.4 桌面"灵感时钟" —— 混淆重，需真机校准

在 `com.android.launcher` 内：

- `com.android.launcher3.inspirationclock.InspirationClock extends FrameLayout`：桌面时钟容器，运行时按简单类名 `"MyCustomizedTextView"` 找子控件；有 `dispatchDraw(Canvas)`。
- `com.android.launcher3.inspirationclock.InspirationClocksManager#onTimeTicked()`（line 479）：由 `TimeTickedObserver` 收 `android.intent.action.TIME_TICK` 后分发。
- `com.android.launcher3.inspirationclock.InspirationClockController`：持有并刷新 `InspirationClock`。

**推荐点**：hook `InspirationClocksManager#onTimeTicked()`，after 遍历 `controllerList` 里的 `InspirationClock`，对其 descendant TextViews 写占位符。次选：hook `InspirationClock` 的子控件刷新方法——但子控件类被混淆成 `a2`/`b2`/…（`Function1` lambda），需真机用 LSPosed 日志打印视图层级确认，列为 P2。

#### 2.4.1 桌面"卡片时钟"五种样式的归属（重要修正）

用户提到的五种样式并非都在 Launcher 内。"自行添加的卡片"本质是 **AppWidget / 卡片**，宿主是 Launcher，但**渲染方分散在多个 APK**：

| 样式 | 实际渲染方 | 是否在已分析 APK 内 | hook 可行性 |
|---|---|---|---|
| **灵感时钟** | `com.android.launcher` → `com.android.launcher3.inspirationclock.InspirationClock`（自定义 View + `MyCustomizedTextView` 子控件） | ✅ 在 Launcher | 可 hook（见 §2.4），但子控件混淆需真机确认 |
| **天气时钟** | `com.coloros.widget` 的 `...smallweather.*`（`OppoWeather*`、`OxygenWeatherSingle` 等），或 `com.coloros.weather2`；Launcher 资源里有 `Constants_Package_OPWIDGET_WEATHER_CLOCK_WIDGET_CLASS_NAME` 专门指向它 | ❌ 需另取 APK | 需取到 APK 后分析 |
| **世界时钟** | `com.coloros.alarmclock`（`OplusUIEngine.CLOCK_ALARM_PACKAGE`，`LauncherCardUtil.DISABLE_CARD_INFO_MAP` 明确映射 `"世界时钟"` → 此包），Provider 为 `com.coloros.alarmclock.widget.DigitalAppWidgetProvider` | ❌ 需另取 APK | 需取到 APK 后分析 |
| **本地时钟 / 城市时钟** | 大概率同属 `com.coloros.alarmclock` / `com.oneplus.deskclock`（同一时钟应用的数字/模拟 Widget 变体）；Launcher 侧只保存包名 `CLOCK_PACKAGE_NAME`，具体样式由 Provider 的 `AppWidgetProvider` + RemoteViews 决定 | ❓ 需 APK 确认 | 需取到 APK 后分析 |

Launcher 侧证据链（已反编译验证）：
- `com.android.launcher.theme.OplusUIEngine.CLOCK_ALARM_PACKAGE = "com.coloros.alarmclock"`，`SPECIAL_PACKAGE_SET` 还含 `com.oneplus.deskclock` / `com.coloros.calendar`。
- `LauncherCardUtil.DISABLE_CARD_INFO_MAP`：`Pair(OplusUIEngine.CLOCK_ALARM_PACKAGE, "世界时钟")`。
- `R.string` 资源名：`Constants_Package_OPWIDGET_CLOCK_WIDGET_CLASS_NAME` / `_WEATHER_CLOCK_WIDGET_CLASS_NAME` / `_OPWIDGET_PACKAGE_NAME`（= `com.coloros.widget`）、`CLOCK_PACKAGE_NAME`、`WEATHER_PACKAGE_NAME`、`WidgetListRowEntry_PACKAGE_ARLARM_CLOCK`。
- aapt 从 `resources.arsc` 提取到：`com.coloros.alarmclock`、`com.coloros.alarmclock.widget.DigitalAppWidgetProvider`、`com.coloros.weather2`、`com.coloros.widget.smallweather.*`（7 种天气 Widget）、`com.oneplus.deskclock`、`com.oplus.alarmclock`。
- `InspirationClockStyleBridge#handleUpdateStyle()` 只处理"灵感时钟"的 style_json / 字体 / `style_pkg`（keyguard 字体跟随），**与桌面 AppWidget 时钟卡片是两条独立链路**，不要混为一谈。

**结论**：现有四个 APK 只覆盖了"灵感时钟"这一种（而且在 Launcher 内）。要覆盖天气/世界/本地/城市时钟，需要再提取以下 APK：`com.coloros.alarmclock`（或 `com.oneplus.deskclock`）、`com.coloros.widget`（小组件公共 Provider）、`com.coloros.weather2`（或 `com.coloros.weather`）。拿到后我会按同样方法定位它们的 AppWidget Provider / RemoteViews 时间文本刷新点。

### 2.5 补充样本：灵感组件 APK —— 与本方案无关（已排除）

`灵感组件_17.00.030.apk` = `com.oplus.keyguard.style.widgets`，2 个 DEX，普通应用签名，无壳。反编译证据：

- `KeyguardStyleWidgetsImpl#onClockStateChanged(Bundle)` 只读 `uiState` / `clockSize` 两个整数，用于切换容器布局状态，**Bundle 里没有、也不解析时间字符串**；全包没有 `SimpleDateFormat` / `Calendar.getInstance` / `HH:mm` 格式化逻辑。
- 包内 `setText()` 仅出现在 `CardCenterCardContainer` / `CardCenterCategoryContainer`，设置的是**卡片名称和分组标题**，不是时间。
- `TextClockTranslationDelegate` 只是 `translationY` 位移动画接口（锁屏展开/折叠时移动时钟），不写文本。
- 加载方式：`SystemUI` 的 `CommonConstants.WIDGETS_PLUGIN_PKG_NAME` 指向该包，`JsonUtils` 用 `createPackageContextCompat(...)` + `getClassLoader().loadClass("com.oplus.keyguard.style.widgets.comm.WidgetCommImpl")` 把它加载进 **SystemUI 进程**；`KeyguardStyleWidgetsPlugin(hostContext, pluginContext)` 只是包装 `KeyguardStyleWidgetsImpl` 的插件入口。
- 它承担的是锁屏时钟**周围**的小组件/卡片中心（`ClockAndWidgetProtocol` 里是 `setWidgetsVisibility`、`setWidgetsContainerAlpha`、`getWidgetRow` 等），不是时钟本体。

结论：**不需要为「无时」hook 这个 APK，也不需要勾选为 LSPosed 作用域**。即使将来要控制锁屏小组件显隐，也可以直接在 SystemUI 进程内 hook `KeyguardPlugin` 的 `widgetPlugin` 调用链，不必单独加载这个包。

## 3. 与现有工程的对接建议

1. `ClockTargets.Target` 增加 `mode` 字段：`SET_TEXT` / `RETURN_VALUE` / `HIDE_VIEW`，`applyMask()` 按 mode 分派。
2. 新增返回值型 target：`Target("statusbar_getSmallTime", "com.android.systemui.statusbar.policy.Clock", "getSmallTime", RETURN_VALUE)`。
3. hook `getSmallTime()` 时，`intercept` 里先 `chain.proceed()`（保留其初始化副作用），命中再 `return@intercept PLACEHOLDER`（CharSequence）覆盖返回值。绝不跳过 proceed。
4. 隐藏型（AOD）必须提供**恢复**分支：非屏蔽时把 alpha 设回 1f，否则解除屏蔽后时间永久消失。
5. `scope.list` / README 校准：本机型真实启动器包名是 `com.android.launcher`（不是 `com.oppo.launcher` 等）；`com.oplus.aod` 与 `com.oplus.keyguard.style.widgets` 都无需勾选。
6. 进程名：`com.android.systemui` 主进程名需在真机日志确认（一般为 `com.android.systemui`）；现有 `isSystemServer` detach 逻辑保留。

## 4. 实施与验证顺序

1. **P0** 状态栏 `Clock.getSmallTime()` 返回值替换 —— 一处生效即证明整条链路（配置下发 + 时段判定 + hook）打通。
2. **P1** 锁屏 `RedTextClock.onTimeChanged` + `RedHorizontalSingleClockView.refresh`。
3. **P1** AOD `DefaultClockView.updateTime` / `DialClockView` 透明化（含恢复）。
4. **P2** 桌面 `InspirationClocksManager.onTimeTicked` + 子控件遍历。
5. 每步用 LSPosed 日志 tag `Timeless` 看"命中"行；真机若某样式未命中，打印时钟 View 的类名与字段再补候选。

## 5. 尚未静态解决、需真机确认的点

- 锁屏当前生效的具体样式（`RedHorizontalSingleClockView` vs `SingleClockView` vs `DualClockView`）取决于用户选的锁屏样式——建议三种都挂候选。
- `TextView` 自绘字体（`NumberFontVariationTextView` 等）是否参与时钟显示，需看真机视图层级。
- `param.processName` 在 ColorOS 17 上的确切值（用于日志与排错）。
- 桌面五种时钟卡片中，"天气/世界/本地/城市"四种由外部 AppWidget Provider APK 渲染（见 §2.4.1），需拿到 `com.coloros.alarmclock` / `com.coloros.widget` / `com.coloros.weather2` 后静态分析其 `AppWidgetProvider` 与 RemoteViews 时间文本（常见为 `TextView.setText` 或 `setTextViewText(R.id.clock, ...)`）。这几种是 RemoteViews 跨进程更新，hook 点将在这些 Provider 的 `onUpdate` / 时间广播接收器里，而非 Launcher。
- 是否存在独立的 AOD 进程：静态证据显示 AOD 渲染在 SystemUI 内，但真机仍建议 `adb shell pidof com.android.systemui` 与灭屏后 `dumpsys activity processes` 复核。

## 6. 2026-11-20 换第三方主题（ColorfulEngine 引擎壁纸）后的修正

三条"写入源头"钩子在新主题上全部落空，真机取证后追加了两条链路。**结论：不要再按
类名逐个找写入源，主题换版就会多出子类覆写，永远追不上。**

### 6.1 最终写入点兜底（systemui / launcher）

`KeyguardTextWriteMask`：挂 `android.widget.TextView.setText(CharSequence[,BufferType])`，
按实例类名前缀 `com.oplus.keyguard.*` 过滤，只改 `^\d{1,2}:\d{2}$` 与（资源 id 名含
time/clock 的）1~2 位纯数字。取代了 `hookTextContent`（text 族）与 `hookGalleryClock`
（gallery 族）。

失败原因存档：`DefaultSingleClockView` **覆写**了 `refreshClockText`；基类实现只写离屏
控件 `time_text`，子类紧接着写屏幕上的 `time_text_ext`。日志能看到
`18:34 → 66:66`，但改的不是屏幕上那个。

### 6.2 壁纸引擎时钟（com.heytap.colorfulengine，独立进程）

这类主题锁屏/AOD 上那串占满屏幕的巨大数字**不在 View 树里**（uiautomator 数字区域
完全空白，`KeyguardEngineContainer` 子节点恒 0），是壁纸进程用 GL 把数字画进壁纸纹理
（景深效果，数字内部透出另一层壁纸）。

- 反编译产物：`reverse/out-colorfulengine/`（只保留 `wallpaper/clockframe` 包）
- 钩点：`ClockFrameRender#K(Context, long)`（全 APK 唯一的时间格式化点，格式 `"HHmm"`）
- 调用链：`a(boolean,long)` = updateClockTime → `K()` → `I(串)` 算变了几个数字 →
  `H(I,串)` 里 `x0([各位数字])` 上传 digit uniform + 返回动画名 →
  `i0/h0/X` 上传并播放翻转动画
- 做法：让 `K` 返回 `FakeTime.mask("HHmm")`（四位全同 6~9）。四个数字位取同一张假数字
  纹理（主题自己的字形+景深），与上一分钟必不同 → 走 `fourNumberChange` 整串翻转，
  每分钟必变。**不碰 `x0`／纹理名／GL 状态**，没有取不到纹理导致空白的风险。
- 前置条件：LSPosed 作用域必须勾选 `com.heytap.colorfulengine`（已在 `scope.list`
  推荐列表里，但已安装模块要用户手动勾选，勾完需重启壁纸进程）。

真机结果（v0.1.9）：锁屏小字 `88:88`、壁纸巨大数字 `9999`；跨分钟 `9999 → 6666`
两侧同步；AOD 同样一致。
