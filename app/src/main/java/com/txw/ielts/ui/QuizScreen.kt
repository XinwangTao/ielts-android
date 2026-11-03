package com.txw.ielts.ui

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txw.ielts.data.BankQuestion
import com.txw.ielts.data.BankRepo
import com.txw.ielts.data.BankSentence
import com.txw.ielts.data.BankSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun QuizScreen(
    set: BankSet,
    repo: BankRepo,
    prefs: com.txw.ielts.data.Prefs,
    answers: Map<Int, String>,
    startAtMs: Long = 0L,
    limitMinutes: Int = 0,          // >0 表示限时模考：倒计时到点自动交卷
    onAnswer: (Int, String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onAddWord: (String, String, String) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val answered = set.questions.count { !answers[it.id].isNullOrBlank() }
    var elapsedSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(startAtMs) {
        while (true) {
            elapsedSec = if (startAtMs <= 0) 0 else ((System.currentTimeMillis() - startAtMs) / 1000).toInt()
            delay(1000)
        }
    }
    val mm = elapsedSec / 60
    val ss = elapsedSec % 60
    val clock = "${mm.toString().padStart(2, '0')}:${ss.toString().padStart(2, '0')}"
    val remainingSec = limitMinutes * 60 - elapsedSec
    var autoSubmitted by remember { mutableStateOf(false) }
    LaunchedEffect(limitMinutes, remainingSec) {
        if (limitMinutes > 0 && remainingSec <= 0 && !autoSubmitted) {
            autoSubmitted = true
            onSubmit()
        }
    }
    // 按套题记住滚动位置：切走再回来停在原处
    val scrollPrefs = remember { context.getSharedPreferences("ielts_scroll", android.content.Context.MODE_PRIVATE) }
    val scrollState = rememberScrollState()
    LaunchedEffect(set.id) {
        val saved = scrollPrefs.getInt("quiz_${set.id}", 0)
        if (saved > 0) scrollState.scrollTo(saved)
    }
    DisposableEffect(set.id) {
        onDispose { scrollPrefs.edit().putInt("quiz_${set.id}", scrollState.value).apply() }
    }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", fontSize = 30.sp, lineHeight = 30.sp) }
            Column(Modifier.weight(1f)) {
                Text(set.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${if (set.module == "listening") "听力" else "阅读"} · 已答 $answered / ${set.questions.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (limitMinutes > 0) {
                    val rem = remainingSec.coerceAtLeast(0)
                    "⏱ 剩余 %02d:%02d".format(rem / 60, rem % 60)
                } else "⏱ $clock",
                style = MaterialTheme.typography.titleMedium,
                color = if (limitMinutes > 0 && remainingSec <= 60) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }

        // 顶部导航与倒计时固定，题目内容独立滚动
        Column(
            Modifier.weight(1f).verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

        if (set.module == "listening") {
            AudioCard(set, repo)
        } else {
            ReadingCard(set.passage, prefs, onAddWord)
        }

        // —— 翻页做题：一页一题，上一题/下一题 ——
        var pageIndex by rememberSaveable(set.id) { mutableIntStateOf(0) }
        val page = set.questions.getOrNull(pageIndex) ?: set.questions.first()
        QuestionCard(pageIndex + 1, page, answers[page.id]) { v -> onAnswer(page.id, v) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = pageIndex > 0,
                onClick = { if (pageIndex > 0) pageIndex-- },
                modifier = Modifier.weight(1f),
            ) { Text("← 上一题") }
            Text(
                "${pageIndex + 1} / ${set.questions.size}",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            OutlinedButton(
                enabled = pageIndex < set.questions.size - 1,
                onClick = { if (pageIndex < set.questions.size - 1) pageIndex++ },
                modifier = Modifier.weight(1f),
            ) { Text("下一题 →") }
        }
        Text(
            "已答 $answered / ${set.questions.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        var submitting by remember { mutableStateOf(false) }
        val submitScope = rememberCoroutineScope()
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = answered > 0 && !submitting,
            onClick = {
                if (!submitting) {
                    submitting = true
                    onSubmit()
                    // 判分是纯本地操作；锁定 1.5 秒防双击生成两条 session，页面跳转后自然失效。
                    submitScope.launch { delay(1500); submitting = false }
                }
            },
        ) { Text(if (answered < set.questions.size) "交卷（还有 ${set.questions.size - answered} 题没答）" else "交卷判分") }

        Text(
            "中途退出会保存已作答内容，下次进入同一套题可以继续。",
            style = MaterialTheme.typography.bodySmall,
        )
        }
    }
}

/** 阅读原文卡：字号与行距可调；长按生词弹出离线词典卡，可一键加入词汇积累。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingCard(
    passage: String,
    prefs: com.txw.ielts.data.Prefs,
    onAddWord: (String, String, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fontSize by remember { mutableIntStateOf(prefs.readerFontSize) }
    var lineHeight by remember { mutableFloatStateOf(prefs.readerLineHeight) }
    val lineOptions = listOf(1.25f, 1.45f, 1.7f)
    val lineIndex = if (lineHeight < 1.35f) 0 else if (lineHeight < 1.6f) 1 else 2
    var layoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    var lookupWord by remember { mutableStateOf<String?>(null) }
    var sheetEntry by remember { mutableStateOf<com.txw.ielts.data.DictEntry?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(false) }

    fun openSheet(word: String) {
        scope.launch {
            lookupWord = word
            sheetEntry = com.txw.ielts.data.DictionaryStore.lookup(context, word)
            added = false
            sheetOpen = true
        }
    }

    BookCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("阅读原文", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(
                    "长按生词可查释义",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(13); prefs.readerFontSize = fontSize }) {
                    Text("A−", fontSize = 14.sp)
                }
                Text(
                    "${fontSize}sp",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(24); prefs.readerFontSize = fontSize }) {
                    Text("A+", fontSize = 19.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("行距", style = MaterialTheme.typography.labelMedium)
                IosSegmented(
                    options = listOf("紧凑", "标准", "宽松"),
                    selected = lineIndex,
                    onSelect = {
                        lineHeight = lineOptions[it]
                        prefs.readerLineHeight = lineHeight
                    },
                )
            }
            HorizontalDivider()
            Text(
                passage,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineHeight).sp,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurface,
                onTextLayout = { layoutResult = it },
                modifier = Modifier.pointerInput(passage) {
                    detectTapGestures(
                        onLongPress = { pos ->
                            layoutResult?.let { lr ->
                                wordAt(lr, pos, passage)?.let { w -> openSheet(w) }
                            }
                        },
                    )
                },
            )
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val entry = sheetEntry
                if (entry == null) {
                    Text(
                        "「${lookupWord ?: ""}」词典未收录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(entry.word, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
                    if (entry.lemma != entry.word) {
                        Text(
                            "≈ 原形 ${entry.lemma}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (entry.phonetic.isNotBlank()) {
                        Text(
                            "/${entry.phonetic}/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    Text(entry.translation, style = MaterialTheme.typography.bodyLarge)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !added,
                        onClick = {
                            onAddWord(entry.lemma, entry.phonetic, entry.translation)
                            added = true
                        },
                    ) { Text(if (added) "已在词汇积累 ✓" else "加入词汇积累") }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** 长按位置 → 所在单词（向两侧扩到字母边界，含撇号）。 */
private fun wordAt(layout: androidx.compose.ui.text.TextLayoutResult, pos: Offset, text: String): String? {
    val offset = layout.getOffsetForPosition(pos)
    if (offset >= text.length) return null
    var start = offset
    var end = start + 1
    if (!text[start].isLetter()) {
        if (start > 0 && text[start - 1].isLetter()) start -= 1 else return null
        end = start + 1
    }
    while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == '\'')) start--
    while (end < text.length && (text[end].isLetter() || text[end] == '\'')) end++
    if (end <= start) return null
    return text.substring(start, end)
}

@Composable
private fun AudioCard(set: BankSet, repo: BankRepo) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var failure by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    var currentSentence by remember { mutableStateOf<BankSentence?>(null) }
    var syncSentence by remember { mutableStateOf<BankSentence?>(null) }
    var posSec by remember { mutableIntStateOf(0) }
    var durSec by remember { mutableIntStateOf(0) }

    val file = remember(set.id) { repo.audioFile(set) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        player.setOnCompletionListener { playing = false }
        onDispose { runCatching { player.release() } }
    }

    fun prepareAndPlay(fromBeginning: Boolean) {
        val f = file ?: run { failure = "音频文件缺失"; return }
        runCatching {
            player.reset()
            player.setDataSource(f.absolutePath)
            player.prepare()
            player.setPlaybackSpeedCompat(speed)
            if (fromBeginning) player.seekTo(0)
            player.start()
            started = true
            playing = true
            failure = null
            currentSentence = null
            posSec = 0
            durSec = runCatching { player.duration / 1000 }.getOrDefault(0)
        }.onFailure {
            playing = false
            failure = "音频播放失败：${it.message}"
        }
    }

    fun togglePlay() {
        if (playing) {
            runCatching { player.pause() }
            playing = false
        } else if (started) {
            runCatching { player.start() }
            playing = true
        } else {
            prepareAndPlay(fromBeginning = true)
        }
    }

    fun playSentence(s: BankSentence) {
        currentSentence = s
        prepareAndPlay(fromBeginning = false).also {
            runCatching { player.seekTo((s.start * 1000).toInt()) }
        }
    }

    // 循环当前句：到达句尾 seek 回句首
    LaunchedEffect(currentSentence) {
        val s = currentSentence ?: return@LaunchedEffect
        while (currentSentence == s) {
            runCatching {
                if (player.currentPosition >= (s.end * 1000).toInt()) {
                    player.seekTo((s.start * 1000).toInt())
                }
            }
            delay(50)
        }
    }

    // 字幕同步：整段播放时随进度自动高亮当前句
    LaunchedEffect(playing, started) {
        while (playing && started && currentSentence == null && set.sentences.isNotEmpty()) {
            runCatching {
                val pos = player.currentPosition
                syncSentence = set.sentences.firstOrNull {
                    pos >= (it.start * 1000).toInt() && pos < (it.end * 1000).toInt()
                }
            }
            delay(300)
        }
        syncSentence = null
    }

    // 播放进度：轮询当前位置，显示进度条与已播/总时长
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (playing) {
            runCatching {
                posSec = player.currentPosition / 1000
                durSec = (player.duration / 1000).coerceAtLeast(0)
            }
            delay(500)
        }
    }

    BookCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("听力音频", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { togglePlay() }) { Text(if (playing) "暂停" else "播放") }
                OutlinedButton(onClick = { prepareAndPlay(fromBeginning = true) }) { Text("重播") }
            }
            if (durSec > 0) {
                // 可拖动进度条：用户想跳着听某一段时不必等播放。
                androidx.compose.material3.Slider(
                    value = posSec.toFloat().coerceIn(0f, durSec.toFloat()),
                    onValueChange = { v ->
                        posSec = v.toInt()
                        runCatching { player.seekTo((v * 1000).toInt()) }
                    },
                    valueRange = 0f..durSec.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "%d:%02d / %d:%02d".format(posSec / 60, posSec % 60, durSec / 60, durSec % 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("倍速", style = MaterialTheme.typography.labelMedium)
                IosSegmented(
                    options = listOf("0.75x", "1x", "1.25x", "1.5x"),
                    selected = listOf(0.75f, 1f, 1.25f, 1.5f).indexOf(speed).coerceAtLeast(0),
                    onSelect = { i ->
                        speed = listOf(0.75f, 1f, 1.25f, 1.5f)[i]
                        if (playing) runCatching { player.setPlaybackSpeedCompat(speed) }
                    },
                )
            }

            if (set.sentences.isNotEmpty()) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "精听逐句：点句子循环听该句，播放时同步高亮",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    set.sentences.forEachIndexed { i, s ->
                        val active = currentSentence == s || (currentSentence == null && syncSentence == s)
                        Text(
                            "${i + 1}. ${s.text}",
                            modifier = Modifier.fillMaxWidth().clickable { playSentence(s) },
                            style = if (active) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            failure?.let {
                HorizontalDivider()
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("已降级为显示原文，仍可作答：", style = MaterialTheme.typography.labelMedium)
                Text(set.transcript, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** PlaybackParams 需要 API 23+；本项目 minSdk 26，但仍包一层避免个别 ROM 抛异常。 */
private fun MediaPlayer.setPlaybackSpeedCompat(speed: Float) {
    runCatching { playbackParams = playbackParams.setSpeed(speed) }
}

@Composable
private fun QuestionCard(index: Int, q: BankQuestion, answer: String?, onChange: (String) -> Unit) {
    BookCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$index. ${q.prompt}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Serif,
            )

            when (q.type) {
                "choice" -> q.options.forEachIndexed { i, opt ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onChange(opt) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = answer == opt, onClick = { onChange(opt) })
                        Text("${('A' + i)}. $opt", style = MaterialTheme.typography.bodySmall)
                    }
                }

                "tfng" -> IosSegmented(
                    options = q.options,
                    selected = q.options.indexOf(answer).coerceAtLeast(0),
                    onSelect = { onChange(q.options[it]) },
                )

                else -> IosTextField(
                    value = answer ?: "",
                    onValueChange = onChange,
                    singleLine = true,
                    label = "答案（1–2 个词）",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
