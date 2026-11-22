package com.ztc1997.timeless.hook

import android.os.Process
import com.ztc1997.timeless.BuildConfig
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.GLog
import com.ztc1997.timeless.core.MaskReason
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 无时 —— 现代 Xposed API（libxposed API 102）入口。
 *
 * 入口注册：META-INF/xposed/java_init.list 里的一行类名。
 *
 * 生命周期（框架按顺序回调）：
 *   onModuleLoaded   模块被载入某进程，一次
 *   onPackageReady   某包的 classLoader 就绪，在此挂钩子
 *   onHotReloading   旧代码收到热重载请求
 *   onHotReloaded    新代码就绪，自己重装钩子
 *
 * 注意：不要在构造函数里做任何初始化 —— 框架要先注入 framework 接口，
 * 那发生在 onModuleLoaded 之前、构造之后。
 */
class TimelessModule : XposedModule() {

    private var configSource: ConfigSource? = null
    private var hookBag: HookBag? = null
    private var hostClockHider: HostClockHider? = null

    /**
     * 只有 SystemUI 进程有发流体云（promoted ongoing）的权限。
     * 实时读进程名：热重载不会重放 onModuleLoaded，实例字段会停在 false。
     */
    private val isSystemUiProcess: Boolean
        get() = runCatching { Process.myProcessName() == "com.android.systemui" }
            .getOrDefault(false)

    /** 进程级安装门闩：同一进程可能对多个 scope 包回调 onPackageReady，钩子只装一次。 */
    private val installGate = InstallGate()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        installLogSink()
        GLog.level = ConfigSnapshot.DEFAULT.logLevel

        val process = param.processName
        // system_server 里永远没有目标宿主，误勾作用域时在这里挡掉，
        // 避免给整个系统进程挂钩。
        if (param.isSystemServer) {
            GLog.i("system_server 不在作用域，detach")
            detach()
            return
        }

        GLog.i(
            "=== 无时 v${BuildConfig.VERSION_NAME} 载入 ===" +
                " process=$process pid=${Process.myPid()}" +
                " framework=${frameworkName} ${frameworkVersion}($frameworkVersionCode)" +
                " api=$apiVersion"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // 作用域由 LSPosed 依 scope.list / 用户勾选过滤，框架不会回调作用域外的包。
        // 但同一进程可能对多个 scope 包各回调一次（真机日志：systemui 内装了 4 遍），
        // 必须用进程级门闩挡住重复 install，否则会重复挂钩、重复注册配置监听，
        // 并留下无法卸载的旧 HookBag 句柄。
        if (!installGate.tryInstall()) {
            GLog.i("进程内钩子已安装，跳过本次 onPackageReady")
            return
        }
        install(param.classLoader)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        GLog.i("收到热重载请求，清理自检节拍并允许")
        // Handler.postDelayed 不在 HookHandle 管理范围内，热重载只卸载钩子不会
        // 取消旧 generation 的 ticker；必须显式停掉，否则旧代码继续改视图。
        hostClockHider?.close()
        hostClockHider = null
        // 流体云同理：钩子卸掉后不会再有回调来撤销它，先自己收掉。
        FluidCloudNotifier.dismiss()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        // 热重载不会重放 onModuleLoaded：新 generation 的 GLog 静态状态是全新的，
        // 不重新注入日志出口就只剩 android.util.Log 兜底，LSPosed 日志里什么都看不到
        // （2026-11-19 实测踩到，误以为热重载失败）。
        installLogSink()
        GLog.i("热重载完成，重装钩子（旧句柄 ${param.oldHookHandles.size} 个）")
        hookBag?.unhookAll()
        param.oldHookHandles.forEach { runCatching { it.unhook() } }
        hookBag = null
        configSource?.close()
        configSource = null

        installGate.reset()
        val cl = currentAppClassLoader()
        if (cl == null) {
            GLog.e("热重载后拿不到宿主 classLoader，重启宿主即可恢复")
            return
        }
        install(cl)
    }

    /** 把框架日志接口接到 [GLog]（onModuleLoaded 与热重载后各注入一次）。 */
    private fun installLogSink() {
        GLog.install { priority, tag, msg, tr ->
            if (tr != null) log(priority, tag, msg, tr) else log(priority, tag, msg)
        }
    }

    private fun install(classLoader: ClassLoader) {
        val xposed: XposedInterface = this

        // 防御：即便门闩失效，也先注销旧配置监听再换新，避免旧 generation 回调残留。
        configSource?.close()
        configSource = null
        val configSource = ConfigSource(xposed)
        val bag = HookBag()

        // 插件时钟（personality.clocks）真实渲染桌面卡片与锁屏数字时钟。
        // 类不在宿主 classLoader 里，需要宿主 Context：走"延迟安装"路径
        // （Instrumentation.callApplicationOnCreate / 任一时钟钩子首次回调兜底）。
        // 注意：不要用 hook ClassLoader.loadClass 的"看门狗"方案 —— 在 loadClass
        // 回调里再挂钩会触发框架类加载重入，真机实测直接把 LSPosed 打进安全模式。
        val pluginFeature = PersonalityClocksFeature(xposed, { configSource.snapshot }, bag)
        val pluginOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val triggerPlugin: () -> Unit = {
            if (pluginOnce.compareAndSet(false, true)) {
                val ctx = DndState.currentAppContext()
                if (ctx == null) {
                    pluginOnce.set(false) // 下次回调再试
                } else {
                    pluginFeature.install(ctx)
                }
            }
        }

        // 流体云只由 SystemUI 进程发：那里有发流体云（Seedling 胶囊）的权限；
        // launcher / colorfulengine 里同样的一次判定不需要（也不该）弹通知。
        val fluidCloudSink: ((MaskReason?) -> Unit)? = if (isSystemUiProcess) {
            // 先让系统把自家通知识别为流体云（否则只会是普通通知），
            // 再在屏蔽状态跳变时发帖。
            FluidCloudFilter(xposed, bag).install(classLoader)
            val sink: (MaskReason?) -> Unit = { reason ->
                FluidCloudNotifier.onDecision(reason) { configSource.snapshot }
            }
            sink
        } else {
            null
        }
        val feature = ClockMaskFeature(
            xposed, classLoader, { configSource.snapshot }, bag, triggerPlugin, fluidCloudSink,
        )

        // 配置一变就更新日志等级与时钟判定；时钟判定每次回调都读最新快照，无需重装钩子。
        // 顺带：跟随系统被打开始、或设置页发来刷新请求时，立刻查一次系统作息。
        configSource.onChanged = { fresh ->
            GLog.level = fresh.logLevel
            SystemSleepSource.onConfigChanged(fresh)
        }
        configSource.open()
        // 首帧配置读完后同步一次日志等级（设置页的日志开关才真正生效）。
        GLog.level = configSource.snapshot.logLevel
        // 进程刚起来时先把系统作息抄一份到 Settings.Secure，
        // 设置页打开就有值可展示，不用等第一次时钟刷新。
        SystemSleepSource.onConfigChanged(configSource.snapshot)

        // 诊断：把首帧配置打进日志。排查"为什么没生效"时第一眼要确认的是
        // manualOverride / enabled 到底是什么值 —— remote preferences 与模块 App
        // 的本地镜像可能不一致，只看本地文件会误判。
        GLog.i("首帧配置：${configSource.snapshot}")

        feature.install()

        // 时钟文本的最终写入点兜底：按实例包名筛 com.oplus.keyguard.*，
        // 一次覆盖 digital / text / gallery 全部族与全部子类（含主题换版新增的
        // 子类覆写）。详见 KeyguardTextWriteMask 的类注释。
        KeyguardTextWriteMask(xposed, { configSource.snapshot }, bag).install()

        // 壁纸引擎（ColorfulEngine）的景深时钟：锁屏/AOD 上那串占满屏幕的巨大
        // 数字是壁纸进程用 GL 画的，不在 View 树里，只能在壁纸进程内拦时间来源。
        // 非壁纸进程里类不存在，install 直接返回 0。
        ColorfulClockFrameMask(xposed, { configSource.snapshot }, bag).install(classLoader)

        // AOD 电池重叠：屏蔽态下拦截主题把 oplus_keyguardstyle_aod_clock_height
        // 写成异常小值（会把电池上移压到星月上）。详见 AodClockHeightGuard。
        AodClockHeightGuard(xposed, { configSource.snapshot }, bag).install(classLoader)

        installPluginClocks(xposed, bag, pluginFeature, classLoader, pluginOnce)

        // 第三方主题：锁屏 / AOD 时钟由主题控件自绘（无文本可替换），
        // 只能整块跳绘。详见 HostClockHider。
        val hider = HostClockHider(xposed, classLoader, { configSource.snapshot }, bag)
        hider.install()
        hostClockHider = hider

        this.configSource = configSource
        this.hookBag = bag
    }

    /**
     * 安装 KeyguardPersonalityClocks 插件时钟拦截。
     *
     * onPackageReady 触发时宿主 Application 往往尚未 attach（实测 launcher 上
     * currentApplication() 为 null），而 createPackageContext 需要 Context，
     * 所以拿不到就先挂一个"Application 创建后"的触发钩子再补装。
     */
    private fun installPluginClocks(
        xposed: XposedInterface,
        bag: HookBag,
        pluginFeature: PersonalityClocksFeature,
        classLoader: ClassLoader,
        pluginOnce: java.util.concurrent.atomic.AtomicBoolean,
    ) {
        // 立即路径：Application 已 attach，直接装。pluginFeature 是 install() 里
        // 创建的唯一实例，triggerPlugin 与下面的延迟路径共用它 —— 三者共享同一套
        // hookedClasses 幂等集合，避免多路触发时对同一 Class 重复挂钩。
        val immediate = DndState.currentAppContext()
        if (immediate != null) {
            if (pluginOnce.compareAndSet(false, true)) {
                pluginFeature.install(immediate)
            }
            return
        }
        GLog.i("宿主 Application 未 attach，插件时钟拦截改为延迟安装")
        val m = runCatching {
            Class.forName("android.app.Instrumentation", false, classLoader)
                .getDeclaredMethod("callApplicationOnCreate", android.app.Application::class.java)
        }.getOrNull() ?: run {
            GLog.w("找不到 Instrumentation.callApplicationOnCreate，插件时钟拦截跳过")
            null
        } ?: return
        // 门闩独立于 pluginOnce：即便立即路径/triggerPlugin 已装过，延迟路径仍保留
        // 兜底重试的机会（应对当时 Context 未就绪、install 内部失败）。多次调用同一
        // 实例的 install() 是安全的——其内部 hookedClasses 幂等集合会对同一 Class 去重。
        val deferredOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        runCatching {
            bag.add(
                xposed.hook(m)
                    .setId("timeless:personality:deferred")
                    .intercept { chain ->
                        val r = chain.proceed()
                        if (deferredOnce.compareAndSet(false, true)) {
                            runCatching {
                                DndState.currentAppContext()?.let {
                                    pluginFeature.install(it)
                                    GLog.i("插件时钟拦截已在 Application 创建后完成")
                                }
                            }.onFailure { GLog.w("延迟安装插件时钟失败", it) }
                        }
                        r
                    },
            )
        }.onFailure { GLog.w("挂延迟安装钩子失败", it) }
    }

    private fun currentAppClassLoader(): ClassLoader? = runCatching {
        DndState.currentAppContext()?.classLoader
    }.getOrNull()
}
