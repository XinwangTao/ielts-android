package com.txw.ielts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.txw.ielts.data.Prefs

@Composable
fun SettingsScreen(
    prefs: Prefs,
    onBack: () -> Unit,
    /** 重新打开首启引导：目标/通道/第一题的检查清单，配错时最容易靠它自查。 */
    onReplayGuide: () -> Unit = {},
) {
    var base by remember { mutableStateOf(prefs.baseUrl) }
    var key by remember { mutableStateOf(prefs.apiKey) }
    var model by remember { mutableStateOf(prefs.model) }
    var asr by remember { mutableStateOf(prefs.asrModel) }
    var channel by remember { mutableStateOf(prefs.scoreChannel) }
    var localModel by remember { mutableStateOf(prefs.localModel) }
    var limit by remember { mutableStateOf(prefs.dailyCallLimit.toString()) }
    var targetBand by remember { mutableStateOf("%.1f".format(prefs.targetBand)) }
    // 四科目标：留空/不动就跟随总分目标，改过才单独生效（见 Prefs.skillTarget）。
    var targetL by remember { mutableStateOf("%.1f".format(prefs.targetListening)) }
    var targetR by remember { mutableStateOf("%.1f".format(prefs.targetReading)) }
    var targetW by remember { mutableStateOf("%.1f".format(prefs.targetWriting)) }
    var targetS by remember { mutableStateOf("%.1f".format(prefs.targetSpeaking)) }
    var examDate by remember { mutableStateOf(prefs.examDate) }
    var saved by remember { mutableStateOf(false) }
    // 任一字段再编辑就撤销「已保存」标记，避免用户以为改动已生效。
    LaunchedEffect(base, key, model, asr, channel, localModel, limit, targetBand, examDate, targetL, targetR, targetW, targetS) {
        if (saved) saved = false
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IosNavBar("设置", onBack = onBack)
        Text("评分用的 OpenAI 兼容接口（默认智谱）。key 只存在本机。",
            style = MaterialTheme.typography.bodySmall)

        // —— 备考目标：目标分决定首页差距提示，考试日期决定临考时今日任务的收紧节奏 ——
        Text("备考目标", style = MaterialTheme.typography.labelLarge)
        IosTextField(
            targetBand, { targetBand = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
            label = "目标 Band",
            supportingText = { Text("0.5 步长，范围 4.0–9.0；保存时自动吸附") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        IosTextField(
            examDate, { examDate = it.trim().take(10) },
            label = "考试日期",
            supportingText = {
                val days = com.txw.ielts.data.ExamTarget.daysUntil(examDate, System.currentTimeMillis())
                Text(
                    when {
                        examDate.isBlank() -> "形如 2026-03-14；留空则不显示倒计时"
                        days == null -> "日期格式不对，应形如 2026-03-14"
                        days < 0 -> "考试日期已过（${-days} 天前），倒计时不再显示"
                        days == 0 -> "就是今天"
                        else -> "距考试 $days 天"
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )

        // —— 四科目标：总分不是四科随便平均，官方是平均后向上取到 0.5，
        // 所以给某一科留保底（比如口语 6.0）时，总分目标要跟着重算，不能只看总分输入框 ——
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            IosTextField(
                targetL, { targetL = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
                label = "听力 L", modifier = Modifier.weight(1f), singleLine = true,
            )
            IosTextField(
                targetR, { targetR = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
                label = "阅读 R", modifier = Modifier.weight(1f), singleLine = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            IosTextField(
                targetW, { targetW = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
                label = "写作 W", modifier = Modifier.weight(1f), singleLine = true,
            )
            IosTextField(
                targetS, { targetS = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
                label = "口语 S", modifier = Modifier.weight(1f), singleLine = true,
            )
        }
        val overallPreview = com.txw.ielts.data.ExamTarget.overallBand(
            targetL.toDoubleOrNull(), targetR.toDoubleOrNull(),
            targetW.toDoubleOrNull(), targetS.toDoubleOrNull(),
        )
        Text(
            if (overallPreview != null) "四科换算总分 %.1f（四科平均后向上取 0.5）".format(overallPreview)
            else "四科都填才换算；调总分不会自动改这四科，不一致时自己对齐",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        IosTextField(base, { base = it }, label = "Base URL", modifier = Modifier.fillMaxWidth(), singleLine = true)
        IosTextField(
            key, { key = it }, label = "API Key", modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        IosTextField(model, { model = it }, label = "模型", modifier = Modifier.fillMaxWidth(), singleLine = true)

        IosTextField(
            limit, { limit = it.filter(Char::isDigit) },
            label = "每日 API 调用上限",
            supportingText = {
                Text("今天已用 ${prefs.callsToday} / ${prefs.dailyCallLimit} 次，超限会直接拒绝且不扣费")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("端侧转写模型", style = MaterialTheme.typography.labelLarge)
        IosSegmented(
            options = listOf("base.en（准）", "tiny.en（快）"),
            selected = if (asr == "tiny.en") 1 else 0,
            onSelect = { asr = if (it == 1) "tiny.en" else "base.en" },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("评分通道", style = MaterialTheme.typography.labelLarge)
        IosSegmented(
            options = listOf("自动（云端优先）", "仅本地", "仅云端"),
            selected = when (channel) {
                "local" -> 1
                "cloud" -> 2
                else -> 0
            },
            onSelect = {
                channel = when (it) {
                    1 -> "local"
                    2 -> "cloud"
                    else -> "auto"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        val localReady = com.txw.ielts.llm.LocalLlmEngine.isReady(context, localModel) ||
            com.txw.ielts.llm.LocalLlmEngine.hasExternalCopy(context, localModel)
        // 显式选「仅云端」又断网：不提示的话，用户提交后只能等一次必然失败的超时。
        com.txw.ielts.llm.ChannelPick.cloudOfflineError(
            channel,
            hasNetwork = com.txw.ielts.llm.NetworkStatus.hasNetwork(context),
            localAvailable = localReady,
        )?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // 自动模式降级原因：断网/额度冷却时提前说明，否则用户会以为评分变慢是模型坏了。
        // 本地模型没投递时不能只说「将使用本地模型评分」——那会变成一句空头承诺。
        com.txw.ielts.llm.ChannelPick.localReason(
            channel,
            cloudDown = System.currentTimeMillis() < prefs.cloudDownUntil,
            hasNetwork = com.txw.ielts.llm.NetworkStatus.hasNetwork(context),
            remainingCalls = prefs.remainingCalls(),
        )?.let { reason ->
            Text(
                if (localReady) "$reason，自动模式将使用本地模型评分。"
                else "$reason，但本地模型未投递：自动模式此刻会评分失败，请先按下方提示推送模型。",
                style = MaterialTheme.typography.bodySmall,
                color = if (localReady) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error,
            )
        }
        Text(
            if (localReady) "当前模型：$localModel；云端额度耗尽或断网时自动离线评分（后台出分并通知）。"
            else "本地模型未部署：选「仅本地」或额度耗尽前，需先推送模型：\n" +
                "adb push <模型目录>/. ${com.txw.ielts.llm.LocalLlmEngine.externalDir(context, localModel).absolutePath}/",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("本地评分模型", style = MaterialTheme.typography.labelLarge)
        val models = remember {
            com.txw.ielts.llm.LocalLlmEngine.availableModels(context).ifEmpty {
                listOf(localModel)
            }
        }
        IosSegmented(
            options = models,
            selected = models.indexOf(localModel).coerceAtLeast(0),
            onSelect = { localModel = models[it] },
            modifier = Modifier.fillMaxWidth(),
        )
        val chosen = models.getOrElse(models.indexOf(localModel).coerceAtLeast(0)) { localModel }
        val chosenReady = com.txw.ielts.llm.LocalLlmEngine.isReady(context, chosen)
        val chosenExternal = com.txw.ielts.llm.LocalLlmEngine.hasExternalCopy(context, chosen)
        Text(
            when {
                chosenReady -> "$chosen 已就位（内部存储），可直接离线评分"
                chosenExternal -> "$chosen 已投递（外部存储），首次评分会自动拷贝到内部，约 1-2 分钟"
                else -> "$chosen 未投递"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "提示：平板内存有限，本地评分期间避免同时使用语音助手等其他本地大模型 App（会内存竞争导致极慢或被系统回收）。内存紧张时建议选 qwen35-2b（约 1.4GB）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var releasedMsg by remember { mutableStateOf<String?>(null) }
        var releaseAfter by remember { mutableStateOf(prefs.releaseAfterScore) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = releaseAfter,
                onCheckedChange = {
                    releaseAfter = it
                    prefs.releaseAfterScore = it
                },
            )
            Text(
                "评分完成后释放模型内存（更省内存，下次评分慢 1-2 分钟）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        var preloadOnPractice by remember { mutableStateOf(prefs.preloadOnPractice) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = preloadOnPractice,
                onCheckedChange = {
                    preloadOnPractice = it
                    prefs.preloadOnPractice = it
                },
            )
            Text(
                "进入写作 / 口语 / AI 出题页时提前加载模型" +
                    if (releaseAfter) "（与上一项冲突：每次评分后仍会释放）" else "（首次操作少等约 1 分钟，代价是常驻约 2.7GB）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = {
            val ok = com.txw.ielts.llm.LocalLlmEngine.release()
            releasedMsg = if (ok) "已释放模型内存，下次评分会自动重新加载" else "评分进行中，结束后再释放"
        }) { Text("卸载本地模型（释放内存）") }
        releasedMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // —— 后台运行（ColorOS 自家冻结层） ——
        Text("后台评分", style = MaterialTheme.typography.titleMedium)
        val power = remember {
            context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        }
        var ignoringBattery by remember {
            mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName))
        }
        Text(
            if (ignoringBattery) "当前：已允许后台运行"
            else "当前：未允许后台运行（长评分在熄屏/切后台时可能被系统冻结）",
            style = MaterialTheme.typography.bodySmall,
            color = if (ignoringBattery) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val direct = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${context.packageName}"),
                )
                runCatching { context.startActivity(direct) }.onFailure {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    }
                }
                ignoringBattery = power.isIgnoringBatteryOptimizations(context.packageName)
            }) {
                Text(if (ignoringBattery) "已允许（可再检查）" else "允许后台运行（电池优化白名单）")
            }
            TextButton(onClick = {
                ignoringBattery = power.isIgnoringBatteryOptimizations(context.packageName)
            }) { Text("刷新状态") }
        }
        TextButton(onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}"),
                    )
                )
            }
        }) { Text("打开应用信息（电池 / 后台设置）") }

        // —— 悬浮窗保活：ColorOS 的 Hans 冻结器只看 FGS 不够（2026-11-06 实测），
        // 需要一个「仍然有可见窗口」的信号。1x1 透明悬浮窗由 Guard 在评分/AI 生成期间临时添加。 ——
        var canOverlay by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
        // 用户从系统设置页返回时本页不会重建：轮询两个权限状态，避免显示过期的「未允许」。
        LaunchedEffect(Unit) {
            while (true) {
                ignoringBattery = power.isIgnoringBatteryOptimizations(context.packageName)
                canOverlay = android.provider.Settings.canDrawOverlays(context)
                kotlinx.coroutines.delay(1500)
            }
        }
        Text(
            if (canOverlay) "悬浮窗保活：已允许"
            else "悬浮窗保活：未允许（长评分切后台仍可能被冻结）",
            style = MaterialTheme.typography.bodySmall,
            color = if (canOverlay) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val uri = android.net.Uri.parse("package:${context.packageName}")
                val direct = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri,
                )
                runCatching { context.startActivity(direct) }.onFailure {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        )
                    }
                }
                canOverlay = android.provider.Settings.canDrawOverlays(context)
            }) { Text(if (canOverlay) "已允许（可再检查）" else "允许悬浮窗（后台保活）") }
            TextButton(onClick = {
                canOverlay = android.provider.Settings.canDrawOverlays(context)
            }) { Text("刷新状态") }
        }
        Text(
            "开启后，评分 / AI 生成期间会临时显示一个 1×1 透明悬浮窗（结束后立刻移除），并让屏幕保持暗亮，用来绕开系统对后台进程的冻结。" +
                "不授权时自动跳过，不影响其他功能；不需要可在系统设置里随时关闭。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "ColorOS 还有一层自己的冻结：设置 → 电池 → 应用耗电管理 → 雅思随身练 → 允许后台运行 / 允许自启动 / 允许关联启动。" +
                "不开也能后台跑（实测 74 秒完成一次口语评分），但冷启动首次加载模型更慢；" +
                "若评分被系统打断，结果页会在 3 分钟后给出「重新评分」入口，不会永久卡住。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TextButton(onClick = onReplayGuide) { Text("重新查看新手引导") }

        var exportMsg by remember { mutableStateOf<String?>(null) }
        val exportScope = rememberCoroutineScope()
        // 备份文件列表：确认「上次导出成功没有」，也能按需删单个。
        var backupFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
        var backupRefresh by remember { mutableStateOf(0) }
        var pendingDeleteBackup by remember { mutableStateOf<java.io.File?>(null) }
        // 导入预览（只读）：选文件 → 解析 → 只看摘要，不写库（写库仍属 L1 待确认）。
        var backupPreview by remember { mutableStateOf<String?>(null) }
        val pickBackup = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                exportScope.launch {
                    val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }.getOrNull()
                    }
                    backupPreview = if (text == null) {
                        "读取文件失败"
                    } else {
                        runCatching {
                            com.txw.ielts.data.BackupRestore.summary(com.txw.ielts.data.BackupRestore.parse(text))
                        }.getOrElse { it.message ?: "解析失败" }
                    }
                }
            }
        }
        LaunchedEffect(backupRefresh) {
            backupFiles = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                java.io.File(context.getExternalFilesDir(null), "backup").listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
                    ?.sortedByDescending { it.lastModified() }
                    .orEmpty()
            }
        }
        TextButton(onClick = {
            exportScope.launch {
                val r = com.txw.ielts.Backup.export(context)
                exportMsg = if (r != null) {
                    "已导出 ${r.sessions} 条练习 / ${r.cards} 张卡片：${r.file.absolutePath}"
                } else "导出失败（存储不可写）"
                backupRefresh++
            }
        }) { Text("导出数据备份（JSON）") }
        exportMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = {
            pickBackup.launch(arrayOf("application/json", "text/plain", "*/*"))
        }) { Text("预览备份文件（只读）") }
        backupPreview?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // 备份写在外部私有目录，手机上不方便翻：直接交给系统分享（存到网盘/发给自己）。
        TextButton(onClick = {
            val dir = java.io.File(context.getExternalFilesDir(null), "backup")
            val latest = dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
                ?.maxByOrNull { it.lastModified() }
            if (latest == null) {
                exportMsg = "还没有备份文件，先点上面的「导出数据备份」"
            } else {
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", latest,
                    )
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "分享备份"))
                }.onFailure { exportMsg = "分享失败：${it.message}" }
            }
        }) { Text("分享最近备份") }

        // 备份只增不减：保留最近 5 个，其余删掉。删除属不可逆，先确认。
        var confirmCleanBackups by remember { mutableStateOf(false) }
        TextButton(onClick = { confirmCleanBackups = true }) { Text("清理旧备份（保留最近 5 个）") }
        if (confirmCleanBackups) {
            AlertDialog(
                onDismissRequest = { confirmCleanBackups = false },
                title = { Text("清理旧备份？") },
                text = { Text("将删除 backup 目录中除最近 5 个之外的所有 JSON 备份，无法恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmCleanBackups = false
                        exportScope.launch {
                            exportMsg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    val dir = java.io.File(context.getExternalFilesDir(null), "backup")
                                    val files = dir.listFiles()
                                        ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
                                        ?.map { it.absolutePath to it.lastModified() }
                                        .orEmpty()
                                    // 保留策略是纯函数（BackupFiles），这里只负责删。
                                    val targets = com.txw.ielts.data.BackupFiles.selectDeletionCandidates(files)
                                    var freed = 0L
                                    var count = 0
                                    targets.forEach { path ->
                                        val f = java.io.File(path)
                                        val len = f.length()
                                        if (f.delete()) {
                                            freed += len
                                            count++
                                        }
                                    }
                                    "已删除 $count 个旧备份，释放 ${freed / 1024} KB"
                                }.getOrElse { "清理失败：${it.message}" }
                            }
                            backupRefresh++
                        }
                    }) { Text("确认清理") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmCleanBackups = false }) { Text("取消") }
                },
            )
        }

        // 备份列表：最多显示 10 个，逐个可删（删除前确认）。
        if (backupFiles.isNotEmpty()) {
            Text("备份文件（${backupFiles.size}）", style = MaterialTheme.typography.labelLarge)
            backupFiles.take(10).forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${f.name} · ${f.length() / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pendingDeleteBackup = f }) { Text("删除") }
                }
            }
        }
        pendingDeleteBackup?.let { f ->
            AlertDialog(
                onDismissRequest = { pendingDeleteBackup = null },
                title = { Text("删除这个备份？") },
                text = { Text("${f.name}（${f.length() / 1024} KB），删除后无法恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDeleteBackup = null
                        exportScope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { f.delete() }
                            }
                            exportMsg = if (!f.exists()) "已删除 ${f.name}" else "删除失败：${f.name}"
                            backupRefresh++
                        }
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteBackup = null }) { Text("取消") }
                },
            )
        }

        var confirmClear by remember { mutableStateOf(false) }
        var clearMsg by remember { mutableStateOf<String?>(null) }
        // 只清 AI 派生缓存：练习记录、词汇卡、课表都保留，适合「想换一份提纲/复盘」但不想丢数据。
        TextButton(onClick = {
            prefs.clearDerivedCaches()
            clearMsg = "已清除本地 AI 缓存（复盘 / 提纲 / 范例 / 生词 / 忽略批注）"
        }) {
            Text("清除本地 AI 缓存")
        }
        // 录音只增不减：删掉没有任何评分记录引用的 .wav，安全且能回收空间。
        TextButton(onClick = {
            exportScope.launch {
                clearMsg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val refs = com.txw.ielts.data.AppDb.get(context).sessions().audioPaths().toSet()
                        val files = context.filesDir.listFiles()
                            ?.filter { it.isFile && it.name.endsWith(".wav", ignoreCase = true) }
                            ?.map { it.absolutePath to it.lastModified() }
                            .orEmpty()
                        // 与启动清理共用同一判定（RecordingCleanup），只是保留期更短。
                        // 对练逐轮录音只在 ielts_partner 里，也要算引用。
                        val partner = com.txw.ielts.data.RecordingCleanup.partnerRefs(
                            context.getSharedPreferences(
                                com.txw.ielts.data.RecordingCleanup.PARTNER_PREFS,
                                android.content.Context.MODE_PRIVATE,
                            ).getString(com.txw.ielts.data.RecordingCleanup.PARTNER_TURNS_KEY, "[]")
                        )
                        val targets = com.txw.ielts.data.RecordingCleanup.pickDeletable(
                            files, refs + partner, System.currentTimeMillis(),
                            com.txw.ielts.data.RecordingCleanup.KEEP_MANUAL_MS,
                        )
                        var freed = 0L
                        var count = 0
                        targets.forEach { p ->
                            val f = java.io.File(p)
                            val len = f.length()
                            if (f.delete()) {
                                freed += len
                                count++
                            }
                        }
                        "已清理 $count 个无主录音，释放 ${freed / 1024 / 1024} MB"
                    }.getOrElse { "清理失败：${it.message}" }
                }
            }
        }) {
            Text("清理无主录音")
        }
        // 存储占用：用户能分清「空间被录音、模型还是数据库占了」，再决定清什么。
        var storageText by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(clearMsg) {
            storageText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                fun size(f: java.io.File?): Long = f?.let {
                    if (it.isDirectory) it.listFiles()?.sumOf { c -> size(c) } ?: 0L else it.length()
                } ?: 0L
                val rec = context.filesDir.listFiles()?.filter { it.name.endsWith(".wav") }?.sumOf { it.length() } ?: 0L
                val model = size(java.io.File(context.filesDir, "models"))
                val db = size(context.getDatabasePath("ielts.db"))
                fun mb(v: Long) = "%.1f MB".format(v / 1024.0 / 1024.0)
                // AI 缓存条数：清缓存前后能看出有没有真的清掉。
                val cacheCount = runCatching { org.json.JSONObject(prefs.studyAiCache).length() }.getOrDefault(0)
                "存储：录音 ${mb(rec)} · 模型 ${mb(model)} · 数据库 ${mb(db)} · AI 缓存 $cacheCount 条"
            }
        }
        storageText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { confirmClear = true }) {
            Text("清空所有数据", color = MaterialTheme.colorScheme.error)
        }
        clearMsg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("清空所有数据？") },
                text = {
                    Text("将删除全部练习记录、评分结果、词汇卡片和范文收藏，无法恢复。建议先点上面的「导出数据备份」。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        exportScope.launch {
                            runCatching {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.txw.ielts.data.AppDb.get(context).clearAllTables()
                                }
                                java.io.File(context.filesDir, "model_essays.json").delete()
                                context.filesDir.listFiles()?.filter { it.name.endsWith(".wav") }?.forEach { it.delete() }
                                // 派生缓存必须一起清：否则清空后 AI 复盘仍显示旧练习生成的建议。
                                prefs.clearDerivedCaches()
                            }
                            clearMsg = "已清空所有数据（本地 AI 复盘/生词/提纲缓存也已重置，建议重启 App 让界面完全刷新）"
                        }
                    }) { Text("确认清空") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text("取消") }
                },
            )
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                prefs.baseUrl = base
                prefs.apiKey = key
                prefs.model = model
                prefs.asrModel = asr
                prefs.scoreChannel = channel
                prefs.localModel = localModel
                limit.toIntOrNull()?.let { prefs.dailyCallLimit = it }
                prefs.targetBand = com.txw.ielts.data.ExamTarget.snapBand(targetBand.toFloatOrNull(), prefs.targetBand)
                // 四科目标独立写入：调总分不会自动改四科，上面的换算提示会暴露两者不一致。
                prefs.setSkillTargets(
                    targetL.toFloatOrNull(), targetR.toFloatOrNull(),
                    targetW.toFloatOrNull(), targetS.toFloatOrNull(),
                )
                prefs.examDate = examDate
                targetBand = "%.1f".format(prefs.targetBand)
                targetL = "%.1f".format(prefs.targetListening)
                targetR = "%.1f".format(prefs.targetReading)
                targetW = "%.1f".format(prefs.targetWriting)
                targetS = "%.1f".format(prefs.targetSpeaking)
                saved = true
            },
        ) { Text(if (saved) "已保存" else "保存") }

        Text(
            "模型文件需要先推到设备：\n" +
                "adb push <base.en 目录>/. /sdcard/Android/data/com.txw.ielts/files/models/base.en/",
            style = MaterialTheme.typography.bodySmall,
        )

        BookCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("关于", style = MaterialTheme.typography.labelLarge)
                listOf(
                    "应用" to "雅思随身练",
                    "版本" to "1.0",
                    "数据" to "全部本地存储，不上云",
                    "转写" to "端侧 whisper，音频不出设备",
                ).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(k, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
