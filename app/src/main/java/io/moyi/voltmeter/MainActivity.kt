package io.moyi.voltmeter

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlinx.coroutines.delay

/** 卡片统一内边距：MIUIX 的 Card 默认是 0，不加文字就会贴着边 */
private val CardPad = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
private val HeroPad = PaddingValues(horizontal = 16.dp, vertical = 20.dp)

/** 小节标题，缩进和卡片内容对齐 */
@Composable
private fun SecTitle(text: String) {
    SmallTitle(text = text, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp))
}

/** 分段选择器：等宽按钮组，永远正好填满卡片内容区，不会像 TabRow 那样溢出 */
@Composable
private fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Card(
                modifier = Modifier.weight(1f),
                cornerRadius = 12.dp,
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 11.dp),
                colors = if (isSelected) {
                    CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary,
                    )
                } else {
                    CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                    )
                },
                onClick = { onSelect(index) },
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(Prefs.get(context)) }
    var snap by remember { mutableStateOf(BatteryReader.read(context)) }
    // 实时监听电池广播（零权限）
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                snap = Snapshot.from(intent, BatteryReader.currentUa(c))
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 任何参数改动都会在 250ms 内自动保存并同步到小部件，避免「应用内和小部件不一致」
    LaunchedEffect(settings) {
        delay(250)
        Prefs.set(context, settings)
        if (settings.refresh == RefreshMode.Realtime) {
            if (Build.VERSION.SDK_INT >= 33) {
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            VoltService.start(context)
        } else {
            VoltService.stop(context)
        }
        Ticker.apply(context, settings.refresh)
        updateAllWidgets(context)
    }

    Scaffold(topBar = { SmallTopAppBar(title = "电池电压") }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            HeroCard(snap, settings)

            SecTitle("实时数据")
            Card(insideMargin = CardPad) {
                InfoRow("原始电压", snap?.let { WidgetPainter.formatMv(it.voltageMv) } ?: "无数据")
                HorizontalDivider()
                InfoRow("实测电流", currentText(snap, settings))
                HorizontalDivider()
                InfoRow("电压补偿", compText(snap, settings))
                HorizontalDivider()
                InfoRow(
                    "单芯电压（补偿后）",
                    snap?.let {
                        val c = Calc.cellsOf(it.voltageMv, settings.cells)
                        String.format("%.3f V（按 %d 芯折算）", Calc.ocvPerCellMv(it, settings) / 1000f, c)
                    } ?: "-"
                )
                HorizontalDivider()
                InfoRow("系统电量", snap?.systemLevel?.takeIf { it >= 0 }?.let { "$it%" } ?: "未知")
                HorizontalDivider()
                InfoRow(
                    "电池温度",
                    snap?.let { String.format("%.1f ℃", it.temperatureC) } ?: "-"
                )
                HorizontalDivider()
                InfoRow("充电状态", if (snap?.charging == true) "充电中" else "未充电")
            }

            SecTitle("电量算法")
            Card(insideMargin = CardPad) {
                Segmented(
                    options = listOf("分段曲线", "线性映射"),
                    selected = if (settings.curve == Curve.Piecewise) 0 else 1,
                    onSelect = {
                        settings = settings.copy(
                            curve = if (it == 0) Curve.Piecewise else Curve.Linear
                        )
                    },
                )
                Text(
                    text = when (settings.curve) {
                        Curve.Piecewise -> "按锂电池放电曲线插值，两端更贴近真实电量。"
                        Curve.Linear -> "电压线性映射到 0~100%，简单但满电段掉得很快。"
                    },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            SecTitle("电池参数")
            Card(insideMargin = CardPad) {
                Text(
                    "电芯数量",
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Segmented(
                    options = listOf("自动", "单芯", "双芯"),
                    selected = when (settings.cells) {
                        CellMode.Auto -> 0
                        CellMode.One -> 1
                        CellMode.Two -> 2
                    },
                    onSelect = {
                        settings = settings.copy(
                            cells = when (it) {
                                0 -> CellMode.Auto
                                1 -> CellMode.One
                                else -> CellMode.Two
                            }
                        )
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "自动：整包电压高于 4.6V 时按双芯串联折算。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            SecTitle("电流补偿")
            Card(insideMargin = CardPad) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("按 I×R 补偿带载压降", fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
                        Text(
                            "大电流放电时电压被内阻拉低，会让电量偏低",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Switch(
                        checked = settings.compEnabled,
                        onCheckedChange = { settings = settings.copy(compEnabled = it == true) },
                    )
                }
                if (settings.compEnabled) {
                    Spacer(Modifier.height(10.dp))
                    NumberPicker(
                        value = settings.resistanceMilliohm / 5,
                        onValueChange = { settings = settings.copy(resistanceMilliohm = it * 5) },
                        range = 0..60,
                        label = { "${it * 5} mΩ" },
                        visibleItemCount = 3,
                        wrapAround = false,
                    )
                    Text(
                        "内阻按单芯填，一般 30~120 mΩ。补偿量上限 ±400 mV，防止电流读数异常。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 20.dp),
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("电流单位", fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(10.dp))
                    Segmented(
                        options = listOf("自动", "µA", "mA"),
                        selected = when (settings.curUnit) {
                            CurUnit.Auto -> 0
                            CurUnit.Micro -> 1
                            CurUnit.Milli -> 2
                        },
                        onSelect = {
                            settings = settings.copy(
                                curUnit = when (it) {
                                    0 -> CurUnit.Auto
                                    1 -> CurUnit.Micro
                                    else -> CurUnit.Milli
                                }
                            )
                        },
                    )
                    Text(
                        "自动：读数绝对值小于 100000 就按 mA 解读（一加/OPPO 等机型 HAL 常把 mA 当 µA 透传），否则按 µA。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            SecTitle("关机电压（单芯）")
            Card(insideMargin = CardPad) {
                NumberPicker(
                    value = settings.cutOffMv / 10,
                    onValueChange = { settings = settings.copy(cutOffMv = it * 10) },
                    range = 200..450,
                    label = { "${it * 10} mV" },
                    visibleItemCount = 3,
                    wrapAround = false,
                )
                Text(
                    "关机电压是电量 0% 的基准，整机自动关机点一般 3.3~3.4V。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }

            SecTitle("满电电压（单芯）")
            Card(insideMargin = CardPad) {
                NumberPicker(
                    value = settings.fullMv / 10,
                    onValueChange = { settings = settings.copy(fullMv = it * 10) },
                    range = 380..450,
                    label = { "${it * 10} mV" },
                    visibleItemCount = 3,
                    wrapAround = false,
                )
                Text(
                    "普通锂电 4.20V，高压电芯 4.40~4.50V，整机充满后显示的静置电压为准。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 20.dp),
                )
        }

            SecTitle("小部件刷新方式")
            Card(insideMargin = CardPad) {
                Segmented(
                    options = listOf("15 分钟", "1 分钟", "实时", "手动"),
                    selected = when (settings.refresh) {
                        RefreshMode.M15 -> 0
                        RefreshMode.M1 -> 1
                        RefreshMode.Realtime -> 2
                        RefreshMode.Manual -> 3
                    },
                    onSelect = {
                        settings = settings.copy(
                            refresh = when (it) {
                                0 -> RefreshMode.M15
                                1 -> RefreshMode.M1
                                2 -> RefreshMode.Realtime
                                else -> RefreshMode.Manual
                            }
                        )
                    },
                )
                Text(
                    text = when (settings.refresh) {
                        RefreshMode.M15 -> "后台最多 15 分钟刷新一次，最省电。"
                        RefreshMode.M1 -> "每 1 分钟刷新一次，耗电略增。"
                        RefreshMode.Realtime -> "常驻前台服务，秒级刷新，通知栏会有一条常驻通知。"
                        RefreshMode.Manual -> "不自动刷新，点一下小部件才更新。"
                    },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    updateAllWidgets(context)
                    Toast.makeText(context, "小部件已刷新", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("立即刷新小部件", fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { pinWidget(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("添加小部件到桌面", fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "提示：所有参数改动都会自动保存并同步到小部件（约 0.3 秒后生效）；点一下小部件也能立即刷新。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

/** 实测电流的可读文本 */
private fun currentText(snap: Snapshot?, settings: VoltSettings): String {
    if (snap == null) return "-"
    if (snap.currentUa == Int.MIN_VALUE) return "该机型不支持读取"
    val ua = Calc.currentUa(snap, settings)
    val a = ua / 1_000_000f
    val state = when {
        a > 0.005f -> "充电"
        a < -0.005f -> "放电"
        else -> "静置"
    }
    val assumedMilli = settings.curUnit == CurUnit.Auto &&
        snap.currentUa != 0 && snap.currentUa in -100_000..100_000
    return String.format(
        "%.3f A（%s）%s",
        a,
        state,
        if (assumedMilli) " · 按 mA 解读" else "",
    )
}

/** 补偿量的可读文本 */
private fun compText(snap: Snapshot?, settings: VoltSettings): String {
    if (!settings.compEnabled) return "未启用"
    if (snap == null) return "-"
    return String.format("%+d mV", Calc.compensationMv(snap, settings))
}

/** 请求把 2x1 小部件钉到桌面（Android 8+） */
private fun pinWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    if (Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(
            ComponentName(context, VoltWidgetProvider::class.java),
            null,
            null,
        )
    } else {
        Toast.makeText(
            context,
            "请长按桌面空白处，在小部件列表里选择「电池电压」",
            Toast.LENGTH_LONG,
        ).show()
    }
}

@Composable
private fun HeroCard(snap: Snapshot?, settings: VoltSettings) {
    Card(insideMargin = HeroPad) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (snap == null) {
                Text("无数据", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            } else {
                val pct = Calc.percent(snap, settings)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = pct.toString(),
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Text(
                        text = "%",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp),
                    )
                }
                Text(
                    text = WidgetPainter.formatMv(snap.voltageMv),
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (snap.charging) {
                    Text(
                        text = "充电中",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
    }
}
