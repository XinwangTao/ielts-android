package com.txw.ielts.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.txw.ielts.WavRecorder
import kotlinx.coroutines.delay
import java.io.File

internal val TASK2_TOPICS = listOf(
    "Some people believe university students should study whatever they like. Others think they should only study subjects that will be useful. Discuss both views and give your own opinion.",
    "Some people think that the best way to stay healthy is to do regular exercise. Others believe diet is more important. Discuss both views and give your own opinion.",
    "In many countries, people are moving from rural areas to cities. What problems does this cause, and what measures could solve them?",
    "Some people believe individuals should take responsibility for protecting the environment, while others think governments should take the lead. Discuss both views and give your own opinion.",
    "In many countries, people spend more time on their phones than with friends and family. What problems does this cause, and how can they be solved?",
    "More and more companies allow employees to work from home. Do the advantages of remote work outweigh the disadvantages?",
    "Some people think university education should be free for everyone. To what extent do you agree or disagree?",
    "In many countries, the population is ageing rapidly. What problems does this cause, and what measures could governments take?",
    "Some people think the media should report only facts, while others believe opinion and analysis are also valuable. Discuss both views and give your own opinion.",
    "Artificial intelligence is increasingly used in the workplace. Do the benefits of this development outweigh the drawbacks?",
    "Some people believe advertising of unhealthy food should be banned. To what extent do you agree or disagree?",
    "Some people think public transport should be free in cities. Do the advantages of this policy outweigh the disadvantages?",
)

/** Task 1 题目：文字数据题，或剑桥真题图片题（assets/bank/task1/）。 */
internal data class Task1Item(
    val label: String,              // 题号标签：文1 / 14-1
    val prompt: String,             // 题干（含主题）
    val textData: String? = null,   // 文字题的数据
    val image: String? = null,      // 图片题资源路径
)

/** 图片题统一题干。 */
private fun chartPrompt(topic: String): String =
    "The chart(s) below show $topic. Summarise the information by selecting and reporting the main features, and make comparisons where relevant."

/** Task 1 题库：6 道文字数据题 + 剑桥 14-21 共 32 张真题图表。 */
internal val TASK1_ITEMS = listOf(
    Task1Item(
        "文1",
        "The table below shows the percentage of household spending in a European country in 1995 and 2015.",
        textData = "Food: 24% (1995) → 18% (2015)\nHousing: 18% → 26%\nTransport: 15% → 16%\nEntertainment: 9% → 11%\nHealthcare: 6% → 8%\nOther: 28% → 21%",
    ),
    Task1Item(
        "文2",
        "The line graph below shows the number of people (in millions) using three forms of transport in a city between 2000 and 2020.",
        textData = "Car: 2000=2.0, 2005=2.4, 2010=2.8, 2015=2.9, 2020=3.0\nBus: 2000=1.5, 2005=1.6, 2010=1.7, 2015=1.6, 2020=1.4\nBicycle: 2000=0.3, 2005=0.5, 2010=0.9, 2015=1.4, 2020=2.0",
    ),
    Task1Item(
        "文3",
        "The pie charts below show the reasons people gave for learning a foreign language in 2000 and 2020.",
        textData = "2000: Work 40%, Travel 25%, Study 20%, Family 15%\n2020: Work 30%, Travel 30%, Study 15%, Family 10%, Culture 15%",
    ),
    Task1Item(
        "文4",
        "The bar chart below shows the electricity generated from four sources in a country in 2010 and 2020.",
        textData = "Coal: 45% (2010) → 30% (2020)\nNatural gas: 25% → 28%\nWind: 10% → 24%\nSolar: 5% → 12%\nHydro: 15% → 6%",
    ),
    Task1Item(
        "文5",
        "The table below shows the population and GDP per capita of four cities in 2020.",
        textData = "City A: population 8.2 million, GDP per capita $52,000\nCity B: population 3.6 million, GDP per capita $68,000\nCity C: population 12.4 million, GDP per capita $31,000\nCity D: population 5.1 million, GDP per capita $45,000",
    ),
    Task1Item(
        "文6",
        "The line graph below shows internet usage (percentage of population) in three countries from 2000 to 2020.",
        textData = "Country X: 2000=5%, 2005=18%, 2010=45%, 2015=72%, 2020=88%\nCountry Y: 2000=2%, 2005=8%, 2010=22%, 2015=40%, 2020=55%\nCountry Z: 2000=30%, 2005=48%, 2010=65%, 2015=78%, 2020=85%",
    ),
    // 剑桥 14
    Task1Item("14-1", chartPrompt("the nutritional composition of three meals in the USA"), image = "bank/task1/cam14-t1-pie.webp"),
    Task1Item("14-2", chartPrompt("the destinations of UK graduates"), image = "bank/task1/cam14-t2-bar.webp"),
    Task1Item("14-3", chartPrompt("fuel sources for electricity production in five countries"), image = "bank/task1/cam14-t3-table.webp"),
    Task1Item("14-4", chartPrompt("Grange Park before and after redevelopment"), image = "bank/task1/cam14-t4-map.webp"),
    // 剑桥 15
    Task1Item("15-1", chartPrompt("coffee and banana sales in four countries"), image = "bank/task1/cam15-t1-bar.webp"),
    Task1Item("15-2", chartPrompt("the recycling process of plastic bottles"), image = "bank/task1/cam15-t2-flow.webp"),
    Task1Item("15-3", chartPrompt("consumption of three types of spread"), image = "bank/task1/cam15-t3-line.webp"),
    Task1Item("15-4", chartPrompt("visitor satisfaction at Ashdown Museum"), image = "bank/task1/cam15-t4-pie-table.webp"),
    // 剑桥 16
    Task1Item("16-1", chartPrompt("ownership of household appliances and time spent on housework"), image = "bank/task1/cam16-t1-line.webp"),
    Task1Item("16-2", chartPrompt("an airport site and its redevelopment plan"), image = "bank/task1/cam16-t2-map.webp"),
    Task1Item("16-3", chartPrompt("cinema attendance by age group"), image = "bank/task1/cam16-t3-bar.webp"),
    Task1Item("16-4", chartPrompt("employment in three economic sectors"), image = "bank/task1/cam16-t4-table.webp"),
    // 剑桥 17
    Task1Item("17-1", chartPrompt("the Norbiton industrial area development plan"), image = "bank/task1/cam17-t1-map.webp"),
    Task1Item("17-2", chartPrompt("police budget sources and spending"), image = "bank/task1/cam17-t2-bar-table.webp"),
    Task1Item("17-3", chartPrompt("weekly household spending by category"), image = "bank/task1/cam17-t3-bar.webp"),
    Task1Item("17-4", chartPrompt("the number of shops opening and closing from 2011 to 2018"), image = "bank/task1/cam17-t4-line.webp"),
    // 剑桥 18
    Task1Item("18-1", chartPrompt("population growth in four Asian cities"), image = "bank/task1/cam18-t1-line.webp"),
    Task1Item("18-2", chartPrompt("the proportion of women with higher education in four countries"), image = "bank/task1/cam18-t2-bar.webp"),
    Task1Item("18-3", chartPrompt("changes to the central library floor plan"), image = "bank/task1/cam18-t3-map.webp"),
    Task1Item("18-4", chartPrompt("consumer spending by category in five countries"), image = "bank/task1/cam18-t4-table.webp"),
    // 剑桥 19
    Task1Item("19-1", chartPrompt("internet use in three countries"), image = "bank/task1/cam19-t1-line.webp"),
    Task1Item("19-2", chartPrompt("adults' motivations for continuing education"), image = "bank/task1/cam19-t2-bar.webp"),
    Task1Item("19-3", chartPrompt("the glass bottle recycling process"), image = "bank/task1/cam19-t3-flow.webp"),
    Task1Item("19-4", chartPrompt("energy sources in a European country"), image = "bank/task1/cam19-t4-pie.webp"),
    // 剑桥 20
    Task1Item("20-1", chartPrompt("a museum building before and after renovation"), image = "bank/task1/cam20-t1-map.webp"),
    Task1Item("20-2", chartPrompt("mobile and landline phone use in five countries"), image = "bank/task1/cam20-t2-bar.webp"),
    Task1Item("20-3", chartPrompt("hotel customer satisfaction ratings"), image = "bank/task1/cam20-t3-table.webp"),
    Task1Item("20-4", chartPrompt("sales of three products"), image = "bank/task1/cam20-t4-line.webp"),
    // 剑桥 21
    Task1Item("21-1", chartPrompt("employment in four US industries"), image = "bank/task1/cam21-t1-line.webp"),
    Task1Item("21-2", chartPrompt("a university cafe before and after renovation"), image = "bank/task1/cam21-t2-map.webp"),
    Task1Item("21-3", chartPrompt("the formation of a rain shadow desert"), image = "bank/task1/cam21-t3-flow.webp"),
    Task1Item("21-4", chartPrompt("library user satisfaction survey"), image = "bank/task1/cam21-t4-pie-table.webp"),
    // 自建补充图表（数据自拟，生成图）
    Task1Item("折1", chartPrompt("population of four countries from 1960 to 2020"), image = "bank/task1/gen-line-1.webp"),
    Task1Item("折2", chartPrompt("metro passengers in three cities from 2000 to 2020"), image = "bank/task1/gen-line-2.webp"),
    Task1Item("折3", chartPrompt("household energy consumption in two countries from 1990 to 2020"), image = "bank/task1/gen-line-3.webp"),
    Task1Item("折4", chartPrompt("share of employment by sector in one country from 1980 to 2020"), image = "bank/task1/gen-line-4.webp"),
    Task1Item("折5", chartPrompt("sales of three products from 2015 to 2023"), image = "bank/task1/gen-line-5.webp"),
    Task1Item("折6", chartPrompt("internet use in three countries from 2000 to 2020"), image = "bank/task1/gen-line-6.webp"),
    Task1Item("柱1", chartPrompt("the proportion of women with higher education in four countries in 2000 and 2020"), image = "bank/task1/gen-bar-1.webp"),
    Task1Item("柱2", chartPrompt("commuting methods in one city in 2000 and 2020"), image = "bank/task1/gen-bar-2.webp"),
    Task1Item("柱3", chartPrompt("weekly exercise time by age group"), image = "bank/task1/gen-bar-3.webp"),
    Task1Item("柱4", chartPrompt("international tourist arrivals in five countries in 2019 and 2023"), image = "bank/task1/gen-bar-4.webp"),
    Task1Item("柱5", chartPrompt("healthcare spending as a percentage of GDP in four regions"), image = "bank/task1/gen-bar-5.webp"),
    Task1Item("柱6", chartPrompt("household spending by category in 2010 and 2023"), image = "bank/task1/gen-bar-6.webp"),
    Task1Item("饼1", chartPrompt("household energy sources in 2020"), image = "bank/task1/gen-pie-1.webp"),
    Task1Item("饼2", chartPrompt("daily time allocation of an average adult"), image = "bank/task1/gen-pie-2.webp"),
    Task1Item("饼3", chartPrompt("undergraduate majors at one university"), image = "bank/task1/gen-pie-3.webp"),
    Task1Item("饼4", chartPrompt("urban water use by sector"), image = "bank/task1/gen-pie-4.webp"),
    Task1Item("表1", chartPrompt("population and GDP per capita of four cities in 2020"), image = "bank/task1/gen-table-1.webp"),
    Task1Item("表2", chartPrompt("cost of living index in five countries in 2023"), image = "bank/task1/gen-table-2.webp"),
    Task1Item("表3", chartPrompt("average annual salary by industry in 2023"), image = "bank/task1/gen-table-3.webp"),
    Task1Item("表4", chartPrompt("student satisfaction survey results"), image = "bank/task1/gen-table-4.webp"),
)

/** 题目在评分时使用的完整文本（图片题只有题干 + 主题提示）。 */
internal fun Task1Item.fullText(): String =
    if (textData != null) "$prompt\n\n$textData" else "$prompt\n\n(Chart image shown in the app. Focus your score on language, structure and coherence; data coverage should be checked against the chart by the student.)"

@Composable
fun WritingScreen(
    stage: String?,           // null 表示未提交；非空表示处理中的阶段文案
    error: String?,
    onSubmit: (task: String, topic: String, audioPath: String?, typedText: String, seconds: Int) -> Unit,
    onBack: () -> Unit,
    focusFixes: List<String> = emptyList(),   // 上次评分的改进点：带着这几点重写
    presetTopic: String? = null,              // 从结果页带回的原题
    presetTask: String = "task2",
    presetChart: com.txw.ielts.data.ChartSpec? = null,  // AI 出图题：直接渲染规格，不用静态图
    onOpenArguments: () -> Unit = {},
    onOpenMock: () -> Unit = {},
    onOpenChartGen: () -> Unit = {},
) {
    val context = LocalContext.current
    val draftPrefs = remember { context.getSharedPreferences("ielts_draft", android.content.Context.MODE_PRIVATE) }
    var task by remember { mutableStateOf(if (presetChart != null) "task1" else presetTask) }
    var selectedT1 by remember { mutableIntStateOf(0) }
    var topicImage by remember { mutableStateOf<String?>(null) }
    var topic by remember {
        mutableStateOf(
            presetTopic ?: if (presetChart != null) {
                presetChart.prompt() + "\n\n" + presetChart.dataText()
            } else if (presetTask == "task1") TASK1_ITEMS[0].fullText() else TASK2_TOPICS[0]
        )
    }
    // 从结果页/论点库带回的 Task 1 题：匹配到图库则显示图片（AI 出图题不走静态图库）
    LaunchedEffect(Unit) {
        if (presetChart == null && task == "task1" && presetTopic != null) {
            val idx = TASK1_ITEMS.indexOfFirst { presetTopic.startsWith(it.prompt.take(40)) }
            if (idx >= 0) {
                selectedT1 = idx
                topicImage = TASK1_ITEMS[idx].image
            }
        }
    }
    var voiceMode by remember { mutableStateOf(draftPrefs.getBoolean("voice_mode", true)) }
    var typing by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var typedText by remember { mutableStateOf(draftPrefs.getString("writing", "") ?: "") }
    var recorder by remember { mutableStateOf<WavRecorder?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    // 提交后禁止草稿回写：此前 onDispose 会把刚提交的正文重新存回草稿，重进页面像没提交过。
    var submitted by remember { mutableStateOf(false) }

    // 草稿自动保存（800ms 防抖）：切走或旋转屏幕不丢已输入内容
    LaunchedEffect(typedText) {
        if (submitted) return@LaunchedEffect
        delay(800)
        if (!submitted) draftPrefs.edit().putString("writing", typedText).apply()
    }
    // 记住上次的输入方式：键盘用户重进可以直接看到草稿
    LaunchedEffect(voiceMode) {
        draftPrefs.edit().putBoolean("voice_mode", voiceMode).apply()
    }
    // 提交成功后（route 离开本页）清空草稿
    DisposableEffect(Unit) {
        onDispose {
            if (!submitted && typedText.length > 30) draftPrefs.edit().putString("writing", typedText).apply()
        }
    }

    fun startRecording() {
        val f = File(context.filesDir, "writing_${System.currentTimeMillis()}.wav")
        runCatching {
            WavRecorder(f).also { it.start() }
        }.onSuccess {
            recorder = it; audioFile = f; recording = true; elapsed = 0; localError = null
        }.onFailure { localError = "录音启动失败：${it.message}" }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 授权成功后直接开始录音，省掉「再点一次开始」的困惑。
        if (granted) startRecording() else localError = "没有麦克风权限，无法口述"
    }

    LaunchedEffect(Unit) {
        // 键盘和口述都计写作时长；此前只在录音时计时，键盘用户永远显示 00:00。
        while (true) {
            delay(1_000)
            elapsed += 1
        }
    }
    DisposableEffect(Unit) {
        onDispose { recorder?.stop() }
    }

    val busy = stage != null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IosNavBar("写作练习室", onBack = onBack) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenArguments, enabled = !busy && !recording) {
                    Text("论点")
                }
                TextButton(onClick = onOpenChartGen, enabled = !busy && !recording) {
                    Text("出图题")
                }
                Text(
                    "%02d:%02d".format(elapsed / 60, elapsed % 60),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Serif,
                    color = when {
                        elapsed >= 45 * 60 -> MaterialTheme.colorScheme.error      // 超时 5 分钟
                        elapsed >= 40 * 60 -> MaterialTheme.colorScheme.tertiary   // Task 2 建议 40 分钟
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        IosSegmented(
            options = listOf("Task 2 议论文", "Task 1 图表"),
            selected = if (task == "task1") 1 else 0,
            onSelect = { i ->
                task = if (i == 1) "task1" else "task2"
                if (i == 1) {
                    selectedT1 = 0
                    topic = TASK1_ITEMS[0].fullText()
                    topicImage = TASK1_ITEMS[0].image
                } else {
                    topic = TASK2_TOPICS[0]
                    topicImage = null
                }
                // 换题就作废旧录音，避免把上一题的录音按新题提交。
                audioFile = null
                elapsed = 0
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !recording,
        )

        if (focusFixes.isNotEmpty()) {
            BookCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("上次评分的改进点（写完对照检查）", style = MaterialTheme.typography.labelLarge)
                    focusFixes.take(3).forEachIndexed { i, f ->
                        Text("${i + 1}. $f", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text("题目", style = MaterialTheme.typography.labelLarge)
        TextButton(
            enabled = !busy && !recording,
            onClick = onOpenMock,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("写作模考：Task 1 + Task 2 连续 60 分钟") }
        if (task == "task1") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                TASK1_ITEMS.forEachIndexed { i, item ->
                    FilterChip(
                        selected = selectedT1 == i,
                        enabled = !busy && !recording,
                        onClick = {
                            selectedT1 = i
                            topic = item.fullText()
                            topicImage = item.image
                            audioFile = null
                            elapsed = 0
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        } else {
            val topics = if (presetTopic != null && presetTopic !in TASK2_TOPICS) {
                listOf(presetTopic) + TASK2_TOPICS
            } else TASK2_TOPICS
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                topics.forEachIndexed { i, t ->
                    FilterChip(
                        selected = topic == t,
                        enabled = !busy && !recording,
                        onClick = { topic = t; audioFile = null; elapsed = 0 },
                        label = { Text("题 ${i + 1}") },
                    )
                }
            }
        }
        BookCard(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (task == "task1") "Task 1 · 图表描述（150 词以上）" else "Task 2 · 议论文（250 词以上）",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (presetChart != null && task == "task1") {
                    // AI 出图题：直接渲染同一份数据规格，图表与题干数字必然一致。
                    ChartView(presetChart)
                    Text(presetChart.prompt(), style = MaterialTheme.typography.bodyLarge)
                } else {
                    topicImage?.let { path ->
                        val bmp = remember(path) {
                            runCatching {
                                context.assets.open(path).use { BitmapFactory.decodeStream(it).asImageBitmap() }
                            }.getOrNull()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "Task 1 图表",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                            )
                        }
                    }
                    Text(topic, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        IosSegmented(
            options = listOf("口述（端侧转写）", "键盘输入"),
            selected = if (voiceMode) 0 else 1,
            onSelect = { voiceMode = it == 0 },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !recording,
        )

        if (voiceMode) {
            BookCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (recording) {
                            val pulse = rememberInfiniteTransition(label = "rec")
                            val scale by pulse.animateFloat(
                                initialValue = 0.8f,
                                targetValue = 1.4f,
                                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                                label = "pulse",
                            )
                            Box(
                                Modifier.size(12.dp)
                                    .scale(scale)
                                    .background(MaterialTheme.colorScheme.error, CircleShape),
                            )
                        }
                        Text(
                            when {
                                recording -> "录音中……说你的作文，说完点停止"
                                audioFile != null -> "已录 ${"%.1f".format((audioFile?.length() ?: 0L) / 32000.0)} 秒，可以提交"
                                else -> "点开始录音，计时自动开始"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy && !recording,
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    startRecording()
                                }
                            }
                        ) { Text("开始录音") }

                        OutlinedButton(
                            enabled = recording,
                            onClick = {
                                recorder?.stop(); recorder = null; recording = false
                            }
                        ) { Text("停止") }

                        OutlinedButton(
                            enabled = !busy && !recording && audioFile != null,
                            onClick = { audioFile = null; elapsed = 0 }
                        ) { Text("重录") }
                    }
                }
            }
        } else {
            IosTextField(
                value = typedText,
                onValueChange = { typedText = it },
                enabled = !busy,
                label = "键入或粘贴你的作文",
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            )
            // 雅思硬性字数要求：Task 2 250 词、Task 1 150 词，实时显示进度。
            val words = typedText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            val target = if (task == "task1") 150 else 250
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    "已写 $words 词 / 目标 $target 词",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (words >= target) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (words >= target) {
                    Text("已达到字数", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (words.toFloat() / target).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!typing && busy) {
            Text(stage ?: "", style = MaterialTheme.typography.bodyMedium)
        }
        (error ?: localError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (!busy && !recording && ((voiceMode && audioFile == null) || (!voiceMode && typedText.trim().length <= 30))) {
            // 说明按钮为什么点不了，避免用户以为界面坏了。
            Text(
                if (voiceMode) "先录一段再提交" else "至少写 30 个字符再提交",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !recording &&
                if (voiceMode) audioFile != null else typedText.trim().length > 30,
            onClick = {
                submitted = true
                draftPrefs.edit().putString("writing", "").apply()   // 已提交，清空草稿
                onSubmit(
                    task,
                    topic,
                    if (voiceMode) audioFile?.absolutePath else null,
                    if (voiceMode) "" else typedText.trim(),
                    elapsed,
                )
            }
        ) { Text(if (busy) "处理中…" else "提交评分") }
    }
}
