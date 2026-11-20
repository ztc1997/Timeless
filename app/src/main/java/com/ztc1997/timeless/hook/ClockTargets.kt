package com.ztc1997.timeless.hook

/**
 * 时钟控件的 hook 点候选表。
 *
 * 全部来自 ColorOS 17 真机 APK 的静态反编译（见项目根目录 HOOK_PLAN.md），
 * 而非 AOSP 猜测。目标进程只有两个：com.android.systemui 与 com.android.launcher。
 *
 * 每种 target 的替换方式由 [mode] 决定：
 *  - [HookMode.SET_TEXT]   ：proceed 之后把控件内指定字段的 TextView 写成占位符；
 *  - [HookMode.RETURN_VALUE]：先 proceed 执行原方法，命中时用占位符覆盖返回值（用于返回值即时间文本的方法）；
 *  - [HookMode.HIDE_VIEWS] ：每次刷新按判定设置 View alpha（命中 0f / 解除 1f），用于无文本的
 *                             AOD 图片数字与 Canvas 表盘，状态切换天然恢复。
 *
 * 每条候选即使 hook 失败也会被逐条 runCatching 隔离，互不影响。
 */
object ClockTargets {

    enum class HookMode {
        /** after 阶段写文本：把 [textFields] 指向的 TextView（或自身）替换为占位符。 */
        SET_TEXT,

        /** intercept 阶段先执行原方法，命中时返回占位符覆盖其返回值。用于方法返回值就是时间文本的情况。 */
        RETURN_VALUE,

        /**
         * 每次方法执行后按当前判定设置 View alpha：命中睡眠则 0f（透明），否则 1f（恢复）。
         * 用于 AOD 这类以图片数字 / Canvas 表盘绘制、没有 TextView 文本可替换的时钟。
         * 因为刷新入口每分钟都会调用，解除睡眠后下一次刷新即自动恢复，无需记忆原状态。
         * [textFields] 在这里表示要设 alpha 的 View 字段；为空则作用于 clockObject 自身。
         */
        HIDE_VIEWS,
    }

    data class Target(
        val id: String,
        val className: String,
        val methodName: String,
        val mode: HookMode = HookMode.SET_TEXT,
        /**
         * 要写占位符的 TextView 字段名。为空表示 clockObject 本身就是 TextView，
         * 直接改它的 text；非空则反射取这些字段。
         */
        val textFields: Array<String> = emptyArray(),
        /** 当 clockObject 自身是 TextView 时，用该提示决定写占位符的哪一段（hour/minute/colon）。 */
        val maskHint: String? = null,
    )

    val ALL = listOf(
        // ═══════════════════════════════════════════════════════════
        // 状态栏时钟（com.android.systemui）— P0
        // getSmallTime() 的返回值是两条写文本路径的共同数据源：
        //   Clock.updateClock() -> setText(getSmallTime())
        //   OplusClockExImpl.setTextWithRedOneStyle(..., getSmallTime(), ...)
        // 后者内部用 textView.post {} 异步 setText，所以 after 型覆盖会被冲掉。
        // 采用 RETURN_VALUE：命中时直接返回占位符，两条路径都拿到它。
        // ═══════════════════════════════════════════════════════════
        Target(
            "statusbar_smallTime",
            "com.android.systemui.statusbar.policy.Clock",
            "getSmallTime",
            HookMode.RETURN_VALUE,
        ),

        // ═══════════════════════════════════════════════════════════
        // 锁屏时钟（com.android.systemui）— P1，三种样式全做
        // 三种样式由 com.oplus.systemui.shared.clocks 下的 View 渲染，
        // 均由对应 Controller 的 events 回调触发刷新。
        // ═══════════════════════════════════════════════════════════
        // 样式一：横排流体时钟。refresh() 同步调用 tvHour.onTimeChanged() /
        // tvMinute.refreshTime()，after 覆盖有效。tvWeek 是星期，不在占位范围。
        Target(
            "keyguard_red_horizontal",
            "com.oplus.systemui.shared.clocks.RedHorizontalSingleClockView",
            "refresh",
            HookMode.SET_TEXT,
            arrayOf("tvHour", "tvMinute", "tvColon"),
        ),
        // 横排时钟的小时本身是 RedTextClock(AppCompatTextView)，有自己的 mTicker
        // 每分钟直接调 onTimeChanged()（不经过父 View 的 refresh），必须单独挂。
        Target(
            "keyguard_red_textclock",
            "com.oplus.systemui.shared.clocks.RedTextClock",
            "onTimeChanged",
            HookMode.SET_TEXT,
            maskHint = "hour",
        ),
        // 样式二：普通单时钟。refresh(boolean) -> updateTime()，after 覆盖有效。
        // 小时/分钟是独立 TextView；amPm 在非 24 小时制下显示，占位时清空。
        Target(
            "keyguard_single",
            "com.oplus.systemui.shared.clocks.SingleClockView",
            "refresh",
            HookMode.SET_TEXT,
            arrayOf("mTimeHour", "mTimeMinute"),
        ),
        // 样式三：双时钟（本地+常驻）。refresh() 用 postOnBackgroundThread 异步写文本，
        // after 覆盖 refresh 本身无效。真正的写入在匿名 Runnable 里，且分 located/resident
        // 两路 —— 都由这个内部类的 run() 执行（$r8$classId 区分），hook 一次覆盖两种。
        // 该 Runnable 的 this$0 字段直接指向 DualClockView，从其字段写占位符。
        Target(
            "keyguard_dual",
            "com.oplus.systemui.shared.clocks.DualClockView\$updateLocatedTime\$1\$1",
            "run",
            HookMode.SET_TEXT,
            arrayOf("locatedTimeHour", "locatedTimeMinute", "residentTimeHour", "residentTimeMinute"),
        ),

        // ═══════════════════════════════════════════════════════════
        // AOD 息屏时钟（com.android.systemui）— P1，无文本，用 alpha 隐藏
        // 渲染由 AodClockLayout.performLayoutUpdate 每分钟驱动一次：
        //   mGlobalClockType == 1/2 → DefaultClockView#updateTime（图片数字）
        //   mGlobalClockType == 3    → DialClockView#invalidate → onDraw（Canvas 表盘）
        // 二者都没有 TextView：数字是 setImageResource 贴的图片，表盘是 Drawable.draw。
        // 采用 HIDE_VIEWS：每次刷新按判定设 alpha，命中透明、解除恢复，天然跟随状态切换。
        // 注意：AOD 在 SystemUI 进程，无需额外作用域；RAM-less 新渲染走独立插件 APK，
        //       本机 com.oplus.aod 只是设置页，那种样式不在本 hook 覆盖范围内。
        // ═══════════════════════════════════════════════════════════
        // 数字图片时钟：淡出整个横向/纵向容器（含冒号），比逐张 ImageView 更彻底。
        Target(
            "aod_digital",
            "com.oplus.systemui.aod.aodclock.off.clock.DefaultClockView",
            "updateTime",
            HookMode.HIDE_VIEWS,
            arrayOf("mGlobalHorizontal", "mGlobalVertical"),
        ),
        // Canvas 表盘：作用于 View 自身（textFields 为空）。onDraw 每帧都会调用，
        // after 阶段设 alpha 对本帧不生效、但下一帧生效；睡眠是分钟级场景，可接受。
        Target(
            "aod_dial",
            "com.oplus.systemui.aod.aodclock.off.clock.DialClockView",
            "onDraw",
            HookMode.HIDE_VIEWS,
        ),
    )

    /**
     * 需要「从宿主对象再取一层」的 target：runnable 本身不持有 TextView，
     * 真正的 View 在其 this$0 字段里。这里声明取一次外层。
     */
    val OUTER_FIELD_TARGETS = setOf("keyguard_dual")
}
