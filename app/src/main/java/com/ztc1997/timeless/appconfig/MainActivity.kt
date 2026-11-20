package com.ztc1997.timeless.appconfig

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ztc1997.timeless.R
import com.ztc1997.timeless.core.ConfigKeys
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.SleepWindow
import com.ztc1997.timeless.core.SystemSleepTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 设置页入口：原 View 构建实现已迁移到 Compose Material 3。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TimelessTheme {
                SettingsScreen()
            }
        }
    }
}

private data class OverrideOption(val labelRes: Int, val value: Int)

/** 跟随系统但还没拿到值时展示的占位。 */
private const val NO_TIME = "--:--"

/**
 * 读 hook 侧写进 Settings.Secure 的系统作息缓存。
 *
 * hook 进程（SystemUI / launcher）持有 `oppo.permission.OPPO_COMPONENT_SAFE`，
 * 能读场景服务的 provider；模块 App 没有这个权限，所以只能读这份镜像。
 * 读不到说明目标进程还没跑过（设备重启后、或模块未启用），界面给占位提示。
 */
private fun readSystemSleep(context: Context): Pair<String, String>? =
    runCatching {
        Settings.Secure.getString(context.contentResolver, SystemSleepTime.SECURE_KEY)
    }.getOrNull().let(SystemSleepTime::decode)

/** hook 侧回执：最后一次刷新请求的序号（0 = 还没有过）。 */
private fun readSleepAck(context: Context): Int =
    runCatching {
        Settings.Secure.getInt(context.contentResolver, SystemSleepTime.ACK_KEY, 0)
    }.getOrDefault(0)

/**
 * 请 hook 侧立刻去查一次系统作息，返回本次请求序号。
 *
 * 模块 App 没有场景服务 provider 的读权限，只能让持有权限的 hook 进程代查；
 * 请求序号走配置通道，hook 查完把同一序号写进 [SystemSleepTime.ACK_KEY]。
 * 框架还没连上（写不进远端）时返回 null —— 这时只能读旧缓存或等 hook 自己刷新。
 */
private fun requestSystemSleepRefresh(): Int? {
    if (ModulePreferences.backend() != ModulePreferences.Backend.REMOTE) return null
    val token = (System.currentTimeMillis() and 0x7FFF_FFFF).toInt().coerceAtLeast(1)
    val ok = ModulePreferences.edit { putInt(ConfigKeys.SYSTEM_SLEEP_REQUEST, token) }
    return if (ok) token else null
}

/**
 * 本次刷新请求是否已了结：hook 回执对上了、请求发不出去但已有缓存、或等超时。
 *
 * 没请求可发（token 为 null）且缓存为空时返回 false，保持"读取中"，靠慢轮询等回写。
 */
private fun settled(
    context: Context,
    token: Int?,
    window: Pair<String, String>?,
    deadline: Long,
): Boolean = when {
    // 回执对上了也要确认值真的读到了：镜像键可能被外部清掉
    token != null && window != null && readSleepAck(context) == token -> true
    token == null && window != null -> true
    SystemClock.elapsedRealtime() > deadline -> true
    else -> false
}

private val OVERRIDE_OPTIONS = listOf(
    OverrideOption(R.string.override_auto, ConfigKeys.OVERRIDE_AUTO),
    OverrideOption(R.string.override_on, ConfigKeys.OVERRIDE_ON),
    OverrideOption(R.string.override_off, ConfigKeys.OVERRIDE_OFF),
)

/**
 * 设置页的表单状态：始终是存储中生效值的镜像，没有"未保存的编辑"中间态。
 *
 * 界面值不能在组合期"读一次就记住"：框架的 binder 是异步注入的，
 * 首帧往往早于连接，那时读到的是本地镜像（首次安装就是空文件 → 默认值）。
 * 连接/推送/本地写入到来时必须重新读，否则界面停在旧值上。
 *
 * 每个开关直接写入存储后即生效。控件是受控的（checked 取自 [values]），
 * 只有写入成功后 [values] 才更新；写失败时保持原值，视觉上自动回滚。
 */
private class SettingsForm(initial: ConfigSnapshot) {
    var values by mutableStateOf(initial)
        private set
    var backend by mutableStateOf(ModulePreferences.backend())
        private set

    /**
     * 写入单个键并立即生效；返回是否成功（失败时调用方给提示，界面保持原值）。
     *
     * 写远端时 SharedPreferences 的变更监听也会触发 [refresh]，这里显式再刷一次
     * 是为了覆盖写本地的情况（本地 editor 没注册监听，不会自己回调）。两次读到的
     * 都是刚提交的新值，幂等。
     */
    fun write(block: ModulePreferences.EditorScope.() -> Unit): Boolean {
        val ok = ModulePreferences.edit(block)
        refresh()
        return ok
    }

    /** 以存储中的值为准刷新界面（写入后、后端连接/断开、远端推送）。 */
    fun refresh() {
        backend = ModulePreferences.backend()
        values = ModulePreferences.load()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val form = remember { SettingsForm(ModulePreferences.load()) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    // 观察者必须用同一个引用，保证 observe / unobserve 成对。
    // 回调可能来自 binder 线程（框架推送），统一切主线程再改 Compose 状态。
    DisposableEffect(Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        val observer: () -> Unit = {
            if (Looper.myLooper() === mainHandler.looper) {
                form.refresh()
            } else {
                mainHandler.post { form.refresh() }
            }
        }
        ModulePreferences.observe(observer)
        onDispose { ModulePreferences.unobserve(observer) }
    }

    val values = form.values
    val backendRes = backendStatusRes(form.backend)

    // 系统自动学习的作息由目标进程（hook 侧）读取后写进 Settings.Secure，
    // 模块 App 没有场景服务 provider 的读权限，只能读这份缓存。
    // 打开开关时主动请求 hook 侧立刻刷新一次，别让用户等 5 分钟。
    val context = LocalContext.current
    var systemWindow by remember { mutableStateOf(readSystemSleep(context)) }
    var fetchPending by remember { mutableStateOf(false) }
    LaunchedEffect(values.followSystem) {
        if (!values.followSystem) {
            fetchPending = false
            return@LaunchedEffect
        }
        // 已有缓存先展示，避免一开开关就闪 "--:--"
        systemWindow = readSystemSleep(context)
        val token = requestSystemSleepRefresh()
        // 请求没能发出去（框架未连接）时不要显示"读取中"——什么都不会来
        fetchPending = token != null
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (true) {
            val window = readSystemSleep(context)
            if (window != null) systemWindow = window
            if (fetchPending) {
                fetchPending = !settled(context, token, window, deadline)
            }
            delay(if (fetchPending) 250 else 5_000)
        }
    }
    val followSystem = values.followSystem
    val manualStart = values.windowStart
    val manualEnd = values.windowEnd
    val shownStart = if (followSystem) systemWindow?.first ?: NO_TIME else manualStart
    val shownEnd = if (followSystem) systemWindow?.second ?: NO_TIME else manualEnd

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 点击回调不是 Composable 上下文，提前把文案解析好。
    val applyFailedMessage = stringResource(R.string.apply_failed)

    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    /** 写入单个设置项；只有写失败（后端未就绪）才提示，成功时静默生效。 */
    fun applySetting(block: ModulePreferences.EditorScope.() -> Unit) {
        if (!form.write(block)) showSnackbar(applyFailedMessage)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HeroCard(backendRes = backendRes)
            }
            item { SectionTitle(stringResource(R.string.section_trigger)) }
            item {
                SettingsCard {
                    SettingSwitchRow(
                        headline = stringResource(R.string.setting_enabled),
                        checked = values.enabled,
                        onCheckedChange = { on -> applySetting { putBoolean(ConfigKeys.ENABLED, on) } },
                    )
                    SettingSwitchRow(
                        headline = stringResource(R.string.setting_schedule),
                        checked = values.scheduleEnabled,
                        onCheckedChange = { on -> applySetting { putBoolean(ConfigKeys.SCHEDULE_ENABLED, on) } },
                    )
                    SettingSwitchRow(
                        headline = stringResource(R.string.setting_follow_system),
                        supporting = stringResource(R.string.setting_follow_system_desc),
                        checked = values.followSystem,
                        onCheckedChange = { on -> applySetting { putBoolean(ConfigKeys.FOLLOW_SYSTEM, on) } },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TimeField(
                            label = stringResource(R.string.time_start),
                            value = shownStart,
                            onClick = { showStartPicker = true },
                            modifier = Modifier.weight(1f),
                            enabled = !followSystem,
                            hint = if (followSystem) stringResource(R.string.time_from_system) else null,
                        )
                        TimeField(
                            label = stringResource(R.string.time_end),
                            value = shownEnd,
                            onClick = { showEndPicker = true },
                            modifier = Modifier.weight(1f),
                            enabled = !followSystem,
                            hint = if (followSystem) stringResource(R.string.time_from_system) else null,
                        )
                    }
                    if (followSystem && systemWindow == null) {
                        InfoText(
                            if (fetchPending) {
                                stringResource(R.string.follow_system_fetching)
                            } else {
                                stringResource(R.string.follow_system_waiting)
                            },
                        )
                    }
                    SettingSwitchRow(
                        headline = stringResource(R.string.setting_link_dnd),
                        checked = values.linkDnd,
                        onCheckedChange = { on -> applySetting { putBoolean(ConfigKeys.LINK_DND, on) } },
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.section_manual_override)) }
            item {
                SettingsCard {
                    OVERRIDE_OPTIONS.forEach { option ->
                        ListItem(
                            headlineContent = { Text(stringResource(option.labelRes)) },
                            leadingContent = {
                                RadioButton(
                                    selected = values.manualOverride == option.value,
                                    onClick = null,
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            modifier = Modifier.clickable {
                                applySetting { putInt(ConfigKeys.MANUAL_OVERRIDE, option.value) }
                            },
                        )
                    }
                }
            }
            item {
                InfoText(stringResource(R.string.info_placeholder))
            }
        }
    }

    if (showStartPicker) {
        TimeSelectionDialog(
            initialValue = values.windowStart,
            fallbackValue = ConfigKeys.DEFAULT_WINDOW_START,
            onConfirm = { picked -> applySetting { putString(ConfigKeys.WINDOW_START, picked) } },
            onDismiss = { showStartPicker = false },
        )
    }
    if (showEndPicker) {
        TimeSelectionDialog(
            initialValue = values.windowEnd,
            fallbackValue = ConfigKeys.DEFAULT_WINDOW_END,
            onConfirm = { picked -> applySetting { putString(ConfigKeys.WINDOW_END, picked) } },
            onDismiss = { showEndPicker = false },
        )
    }
}

private fun String.toHourMinute(fallback: String): Pair<Int, Int> {
    val total = SleepWindow.parseHhMm(this)
        ?: SleepWindow.parseHhMm(fallback)
        ?: return 0 to 0
    return total / 60 to total % 60
}

private fun formatHhMm(hour: Int, minute: Int): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')

@Composable
private fun TimeField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hint: String? = null,
) {
    OutlinedCard(
        modifier = if (enabled) modifier.clickable(onClick = onClick) else modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSelectionDialog(
    initialValue: String,
    fallbackValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (initialHour, initialMinute) = initialValue.toHourMinute(fallbackValue)
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.time_picker_title)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(formatHhMm(timePickerState.hour, timePickerState.minute))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        TimePicker(state = timePickerState)
    }
}

private fun backendStatusRes(backend: ModulePreferences.Backend): Int = when (backend) {
    ModulePreferences.Backend.REMOTE -> R.string.backend_connected
    ModulePreferences.Backend.LOCAL -> R.string.backend_local
    ModulePreferences.Backend.NONE -> R.string.backend_uninitialized
}

@Composable
private fun HeroCard(backendRes: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 横幅图标：消融 8。40dp 才看得出右缘的碎散；24dp 时缺口会糊掉。
            Icon(
                painter = painterResource(R.drawable.ic_placeholder_fake_time),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .padding(end = 14.dp)
                    .size(40.dp),
            )
            Column {
                Text(
                    text = stringResource(R.string.hero_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(backendRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingSwitchRow(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = if (supporting != null) {
            { Text(supporting, style = MaterialTheme.typography.bodySmall) }
        } else {
            null
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
