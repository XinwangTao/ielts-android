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
        )?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // 自动模式降级原因：断网/额度冷却时提前说明，否则用户会以为评分变慢是模型坏了。
        com.txw.ielts.llm.ChannelPick.localReason(
            channel,
            cloudDown = System.currentTimeMillis() < prefs.cloudDownUntil,
            hasNetwork = com.txw.ielts.llm.NetworkStatus.hasNetwork(context),
            remainingCalls = prefs.remainingCalls(),
        )?.let { reason ->
            Text(
                "$reason，自动模式将使用本地模型评分。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
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

        TextButton(onClick = onReplayGuide) { Text("重新查看新手引导") }

        var exportMsg by remember { mutableStateOf<String?>(null) }
        val exportScope = rememberCoroutineScope()
        TextButton(onClick = {
            exportScope.launch {
                val f = com.txw.ielts.Backup.export(context)
                exportMsg = if (f != null) "已导出：${f.absolutePath}" else "导出失败（存储不可写）"
            }
        }) { Text("导出数据备份（JSON）") }
        exportMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        var confirmClear by remember { mutableStateOf(false) }
        var clearMsg by remember { mutableStateOf<String?>(null) }
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
                            }
                            clearMsg = "已清空所有数据"
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
