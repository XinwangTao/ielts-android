
package com.txw.ielts.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 写作/口语句子模板。
 *
 * 只给「骨架 + 何时用」，不鼓励整段背诵：句式参考 ieltsbuddy 的引言两段式、
 * British Council 对 Task 1 overview 的要求与 IELTS Liz 的常用衔接表达，按雅思四项评分
 * （任务回应 / 连贯衔接 / 词汇 / 语法）整理成「槽位 + 中文用法 + 风险提示」。
 *
 * frame 里的【】是填词槽位；whenTo 说明什么情况下套。
 */
data class WritingTemplate(
    val id: String,
    val group: String,
    val frame: String,
    val whenTo: String,
    val example: String = "",
    val pitfall: String = "",
)

/** 模板库：Task 2 议论、Task 1 图表、口语 Part 3 衔接三类。 */
val WRITING_TEMPLATES: List<WritingTemplate> = listOf(
    WritingTemplate(
        "t2-open-both", "Task 2 · 开头",
        "It is often argued that 【一种观点】, while others maintain that 【另一种观点】.",
        "双边讨论题（Discuss both views）第一句：把题干两派装进一句话，别抄题。",
        "It is often argued that university education should be free, while others maintain that students should bear part of the cost.",
        "两个观点各一句带过，别在开头展开。",
    ),
    WritingTemplate(
        "t2-open-trend", "Task 2 · 开头",
        "In recent years, 【现象】 has become a contentious issue.",
        "社会现象类题目（远程办公、短视频）引入背景。",
        "In recent years, remote working has become a contentious issue.",
        "",
    ),
    WritingTemplate(
        "t2-open-whether", "Task 2 · 开头",
        "Whether 【有争议的做法】 is beneficial or harmful has sparked heated debate.",
        "题干是「某做法好不好」时，whether 从句比照抄题干自然。",
        "Whether children should be allowed to use smartphones has sparked heated debate.",
        "",
    ),
    WritingTemplate(
        "t2-stance", "Task 2 · 立场",
        "In my view, 【立场】 for the following reasons.",
        "开头段第二句亮观点；结尾段换词再复述一次。",
        "In my view, fees should be means-tested for the following reasons.",
        "",
    ),
    WritingTemplate(
        "t2-stance-while", "Task 2 · 立场",
        "While I accept that 【让步点】, I would argue that 【主立场】.",
        "同意/不同意题想拿「部分同意」的分：先让一步再站稳。",
        "While I accept that free tuition widens access, I would argue that a small contribution is fairer.",
        "",
    ),
    WritingTemplate(
        "t2-stance-firm", "Task 2 · 立场",
        "I firmly believe that 【立场】, although 【限定条件】.",
        "想表达强立场又不想绝对化，加限定避免被判 overgeneralisation。",
        "I firmly believe that this trend is positive, although it must be regulated.",
        "",
    ),
    WritingTemplate(
        "t2-ts-first", "Task 2 · 主体段",
        "First and foremost, 【第一个理由】.",
        "主体第一段首句；比 first of all 正式，比 firstly 自然。",
        "First and foremost, free access removes the financial barrier for low-income families.",
        "",
    ),
    WritingTemplate(
        "t2-ts-primary", "Task 2 · 主体段",
        "The primary reason for this is that 【原因】.",
        "单边论证第一个主体段，直接把最强理由拎出来。",
        "The primary reason for this is that graduates already benefit from higher lifetime earnings.",
        "",
    ),
    WritingTemplate(
        "t2-ts-another", "Task 2 · 主体段",
        "Another factor worth considering is 【第二个理由】.",
        "第二个主体段开场，避免和第一段同句式。",
        "Another factor worth considering is the strain on public budgets.",
        "",
    ),
    WritingTemplate(
        "t2-explain", "Task 2 · 展开",
        "This is largely because 【解释机制】.",
        "主题句之后必须解释「为什么」，这句是主体段第二层。",
        "This is largely because tuition fees deter applicants from poorer backgrounds.",
        "",
    ),
    WritingTemplate(
        "t2-example-case", "Task 2 · 举例",
        "A case in point is 【具体例子】.",
        "一句话说完的例子，比 for example 书面。",
        "A case in point is Scotland, where tuition is free for Scottish students.",
        "",
    ),
    WritingTemplate(
        "t2-example-take", "Task 2 · 举例",
        "Take 【对象】 as an example: 【一两句描述】.",
        "例子要展开两句时用，细节才拿得到词汇分。",
        "Take Singapore as an example: students pay subsidised fees, and the state recovers the cost through tax revenue.",
        "",
    ),
    WritingTemplate(
        "t2-example-data", "Task 2 · 举例",
        "This is exemplified by 【研究/报告/新闻】, which found that 【结论】.",
        "有可靠来源时用；没有来源别硬编，改用 A case in point。",
        "This is exemplified by an OECD report, which found that graduates earn around 50% more over a career.",
        "",
    ),
    WritingTemplate(
        "t2-cause", "Task 2 · 因果",
        "This can be attributed to 【原因】.",
        "解释现象背后的成因，比 because 更有分量。",
        "This can be attributed to the rising cost of childcare.",
        "",
    ),
    WritingTemplate(
        "t2-result", "Task 2 · 因果",
        "As a consequence, 【结果】.",
        "把论点推到结果，主体段收尾常用。",
        "As a consequence, many young families delay having children.",
        "",
    ),
    WritingTemplate(
        "t2-result-chain", "Task 2 · 因果",
        "Consequently, 【直接结果】, which in turn 【进一步结果】.",
        "写因果链（A→B→C），连贯分吃这套。",
        "Consequently, rents rise, which in turn pushes workers further from city centres.",
        "",
    ),
    WritingTemplate(
        "t2-concession", "Task 2 · 让步反驳",
        "Admittedly, 【对方合理之处】; however, 【反驳或限定】.",
        "让步段开头：先承认对方有理再转折，体现 critical thinking。",
        "Admittedly, fees may discourage some applicants; however, loan schemes can remove this obstacle.",
        "",
    ),
    WritingTemplate(
        "t2-compare", "Task 2 · 对比",
        "In stark contrast to 【A】, 【B】 ...",
        "两边差异很大时用，比 on the other hand 有画面感。",
        "In stark contrast to state schools, private schools spend twice as much per pupil.",
        "",
    ),
    WritingTemplate(
        "t2-hypothesis", "Task 2 · 假设",
        "If 【条件】, then 【结果】, which suggests that 【推论】.",
        "没有真实数据时用假设补一层论证，别整段都在假设。",
        "If fees were removed entirely, demand would outstrip capacity, which suggests that some rationing is inevitable.",
        "",
    ),
    WritingTemplate(
        "t2-emphasise", "Task 2 · 强调",
        "What matters most here is not 【表面因素】 but 【真正因素】.",
        "想让段落有重点时用一次即可，多了显得刻意。",
        "What matters most here is not the fee itself but whether it is means-tested.",
        "",
    ),
    WritingTemplate(
        "t2-concl", "Task 2 · 结尾",
        "In conclusion, 【重申立场】, and 【建议或预测】.",
        "结尾两件事：换词重述立场 + 一句展望。",
        "In conclusion, tuition should be subsidised rather than eliminated, and loans should be income-contingent.",
        "",
    ),
    WritingTemplate(
        "t2-concl-although", "Task 2 · 结尾",
        "To sum up, although 【让步】, I remain convinced that 【立场】.",
        "前面写过让步段时，结尾用 although 收束最顺。",
        "To sum up, although the reform is costly, I remain convinced that it is worthwhile.",
        "",
    ),
    WritingTemplate(
        "t2-concl-value", "Task 2 · 结尾",
        "Ultimately, 【一句话价值判断】.",
        "想让结尾有力一点，用判断句收尾，不要加新论点。",
        "Ultimately, the question is not whether we can afford education, but whether we can afford to ration it.",
        "",
    ),
    WritingTemplate(
        "t1-intro", "Task 1 · 开头",
        "The 【chart type】 illustrates 【主题】 over the period from 【年份】 to 【年份】.",
        "线图/柱图开头：改写题目，不要照抄题干。",
        "The line graph illustrates household energy consumption in two countries from 1990 to 2020.",
        "",
    ),
    WritingTemplate(
        "t1-intro-compare", "Task 1 · 开头",
        "The given 【chart type】 compares 【对象】 in terms of 【指标】.",
        "柱图/表格比较多个对象时用 compares。",
        "The given bar chart compares commuting methods in one city in 2000 and 2020.",
        "",
    ),
    WritingTemplate(
        "t1-overview", "Task 1 · 概览",
        "Overall, 【最显著趋势】, while 【次要趋势】.",
        "Task 1 必须有 overview 段，紧跟开头；没有就上不了 6 分。",
        "Overall, energy use rose in both countries, while the gap between them narrowed.",
        "",
    ),
    WritingTemplate(
        "t1-rise", "Task 1 · 趋势",
        "There was a marked increase in 【指标】, rising from 【数】 to 【数】.",
        "描述上升：名词 increase 比动词稳，数据紧跟其后。",
        "There was a marked increase in internet use, rising from 20% to 75%.",
        "",
    ),
    WritingTemplate(
        "t1-fall", "Task 1 · 趋势",
        "【指标】 declined steadily, from 【数】 in 【年份】 to 【数】 in 【年份】.",
        "描述下降；steadily / sharply / marginally 轮换用。",
        "Coal consumption declined steadily, from 40% in 1990 to 15% in 2020.",
        "",
    ),
    WritingTemplate(
        "t1-plateau", "Task 1 · 趋势",
        "【指标】 plateaued at around 【数】 between 【年份】 and 【年份】.",
        "平台期专用词，比 stayed the same 高级。",
        "Sales plateaued at around 200 units between 2010 and 2015.",
        "",
    ),
    WritingTemplate(
        "t1-share", "Task 1 · 占比",
        "【A】 accounted for 【百分比】 of the total, compared with 【B】 at 【百分比】.",
        "饼图/表格占比：accounted for 是必背搭配。",
        "Transport accounted for 35% of the total, compared with housing at 22%.",
        "",
    ),
    WritingTemplate(
        "t1-multiple", "Task 1 · 比较",
        "【A】 was roughly twice as high as 【B】.",
        "数值差距明显时用倍数，比念数字简洁。",
        "Spending on health was roughly twice as high as spending on defence.",
        "",
    ),
    WritingTemplate(
        "t1-contrast", "Task 1 · 比较",
        "While 【A】 rose sharply, 【B】 remained almost unchanged.",
        "一句话写两条线走势相反，省字数还显功底。",
        "While car use rose sharply, bus use remained almost unchanged.",
        "",
    ),
    WritingTemplate(
        "t1-process", "Task 1 · 流程",
        "The process begins with 【第一步】, after which 【下一步】.",
        "流程图像链子往下写，after which 比 then 书面。",
        "The process begins with the collection of raw materials, after which they are crushed.",
        "",
    ),
    WritingTemplate(
        "t1-map", "Task 1 · 地图",
        "The most noticeable change is that 【原事物】 has been replaced by 【新事物】.",
        "地图题写变化：replaced / extended / converted into 三件套。",
        "The most noticeable change is that the farmland has been replaced by a housing estate.",
        "",
    ),
    WritingTemplate(
        "sp-first", "口语 · 结构",
        "First of all, 【第一点】. Another thing is 【第二点】. So overall, 【总结】.",
        "Part 3 没思路时的救命结构，三层够撑 30 秒。",
        "First of all, it saves time. Another thing is it reduces cost. So overall, I think it is a good idea.",
        "别在 Part 3 只答一句，考官会追到你卡住。",
    ),
    WritingTemplate(
        "sp-depends", "口语 · 缓冲",
        "It really depends on 【条件】. For example, 【情况 A】, but 【情况 B】.",
        "遇到绝对化问题（Is it always good?）先分情况，既缓冲又显思考。",
        "It really depends on the age. For example, children need guidance, but adults can decide for themselves.",
        "",
    ),
    WritingTemplate(
        "sp-opinion", "口语 · 表态",
        "I'd say 【观点】, mainly because 【原因】.",
        "Part 1/3 表态最稳的句式，比 I think 自然。",
        "I'd say it's worth it, mainly because the experience itself teaches you a lot.",
        "",
    ),
    WritingTemplate(
        "sp-unsure", "口语 · 不会答",
        "To be honest, I've never thought about it, but I suppose 【推测】.",
        "真没想法时用，比沉默或 I don't know 好；后面必须接推测。",
        "To be honest, I've never thought about it, but I suppose it would depend on the job.",
        "",
    ),
    WritingTemplate(
        "sp-describe", "口语 · Part 2",
        "What I find most 【形容词】 is 【具体点】, because 【原因】.",
        "Part 2 描述题突出一个细节，避免流水账。",
        "What I find most impressive is the location, because it is right next to the river.",
        "",
    ),
)

/** AI 填词状态：按模板 id 区分，切换模板时不会串内容。 */
data class TemplateFillState(
    val templateId: String,
    val topic: String,
    val busy: Boolean = false,
    val text: String? = null,
    val why: String? = null,
    val error: String? = null,
)

/** 模板抽屉：分类筛选 + 何时用 / 示范 / 让 AI 按当前题目填。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WritingTemplateSheet(
    task: String,
    topic: String,
    fill: TemplateFillState?,
    onRequestFill: (WritingTemplate, String) -> Unit,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = remember(task) {
        if (task == "task1") {
            listOf("全部", "Task 1 · 开头", "Task 1 · 概览", "Task 1 · 趋势", "Task 1 · 占比", "Task 1 · 比较", "Task 1 · 流程", "Task 1 · 地图")
        } else {
            listOf("全部", "Task 2 · 开头", "Task 2 · 立场", "Task 2 · 主体段", "Task 2 · 展开", "Task 2 · 举例", "Task 2 · 因果", "Task 2 · 让步反驳", "Task 2 · 对比", "Task 2 · 假设", "Task 2 · 强调", "Task 2 · 结尾", "口语 · 结构", "口语 · 缓冲", "口语 · 表态", "口语 · 不会答", "口语 · Part 2")
        }
    }
    var selected by remember(task) { mutableStateOf("全部") }
    val visible = WRITING_TEMPLATES.filter { t ->
        val inTask = if (task == "task1") t.group.startsWith("Task 1") else !t.group.startsWith("Task 1")
        inTask && (selected == "全部" || t.group == selected)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("句子模板", style = MaterialTheme.typography.titleLarge)
            Text(
                "先看「什么时候用」，再让 AI 按当前题目填一次；填完的句子进草稿后要自己改写，才是你的。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { g ->
                    FilterChip(
                        selected = selected == g,
                        onClick = { selected = g },
                        label = { Text(g.removePrefix("Task 2 · ").removePrefix("Task 1 · ")) },
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { t ->
                    val current = fill?.takeIf { it.templateId == t.id }
                    val busy = current?.busy == true
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(t.frame, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodyLarge)
                            Text(t.whenTo, style = MaterialTheme.typography.bodySmall)
                            if (t.example.isNotBlank()) {
                                Text("示范：" + t.example, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (t.pitfall.isNotBlank()) {
                                Text("注意：" + t.pitfall, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (current?.error != null) {
                                Text(current.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (current?.text != null) {
                                if (!current.why.isNullOrBlank()) {
                                    Text("AI 说明：" + current.why, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(current.text, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { onInsert(current.text) }) { Text("插入到草稿") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { onRequestFill(t, topic) },
                                    enabled = !busy && topic.isNotBlank(),
                                ) { Text(if (busy) "生成中…" else "让 AI 按这道题填") }
                                if (busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** AI 作文框架抽屉：整篇骨架（每段带【中文槽位】），可整篇放进草稿再逐句填。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EssaySkeletonSheet(
    topic: String,
    busy: Boolean,
    skeleton: com.txw.ielts.llm.EssaySkeleton?,
    error: String?,
    onGenerate: () -> Unit,
    onInsert: (String) -> Unit,
    inserted: Boolean = false,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("AI 作文框架", style = MaterialTheme.typography.titleLarge)
            Text(
                "按这道题把句子模板拼成四段骨架，【】里是你要填的内容；填完直接提交评分，再看改进点重写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (topic.isBlank()) {
                Text("先在上面选一道题或粘贴题目，再生成框架。", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onGenerate, enabled = !busy) {
                    Text(if (busy) "生成中…" else if (skeleton?.isEmpty == false) "重新生成" else "生成框架")
                }
                if (busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                if (skeleton?.isEmpty == false) {
                    TextButton(onClick = { onInsert(skeleton.toDraftText()) }) {
                        Text(if (inserted) "已在草稿（再点替换）" else "整篇放进草稿")
                    }
                }
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            LazyColumn(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(skeleton?.paragraphs.orEmpty()) { para ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(para.label, style = MaterialTheme.typography.titleSmall)
                            para.lines.forEach { line -> Text(line, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodyMedium) }
                            if (para.hint.isNotBlank()) {
                                Text(para.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
