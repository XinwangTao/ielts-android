package com.txw.ielts.data

/**
 * 首启引导的一步。
 *
 * @param ready 这一步是否已经满足（例如本地模型已就绪）。满足就打勾，用户不必再动手；
 *   不满足时必须说清「现在该做什么」，而不是只念一遍现状。
 * @param action 按钮文案；null 表示这一步只需阅读，没有可点的动作。
 */
data class GuideStep(
    val id: String,
    val title: String,
    val detail: String,
    val ready: Boolean,
    val action: String? = null,
)

/** 首启引导的整体状态：步骤 + 还差几步 + 顶部结论。 */
data class FirstRunPlan(
    val steps: List<GuideStep>,
    val pending: Int,
    val headline: String,
    val detail: String,
) {
    val allReady: Boolean get() = pending == 0

    fun step(id: String): GuideStep? = steps.firstOrNull { it.id == id }
}

/** 本地模型的三种状态：内部就绪 / 只投递到外部目录 / 完全没有。 */
enum class LocalModelState {
    READY,
    DELIVERED,
    MISSING,
}

/**
 * 首启引导的判定（纯逻辑，UI 只负责渲染）。
 *
 * 为什么要有：新装机默认是「自动（云端优先）」，而云端口令可能已死、本地模型也可能没投递。
 * 用户第一次打开 App 看到的是首页一堆入口，却不知道「现在能不能出分」——
 * 引导的作用就是把「目标 / AI 通道 / 第一道题」这三件事一次讲清，缺哪一步直接给按钮。
 *
 * 抽成纯函数是为了能在 PC 上单测：这几条判断一旦写反，
 * 用户会看到「本地模型已就绪」但点评分就报错，或者在全都配好之后还被引导拦着。
 */
object FirstRun {

    const val STEP_TARGET = "target"
    const val STEP_AI = "ai"
    const val STEP_PRACTICE = "practice"

    /** 引导只在没看完时自动出现；设置页的「重新查看」由调用方直接切页面，不看这个标记。 */
    fun shouldAutoShow(completed: Boolean): Boolean = !completed

    fun plan(
        targetBand: Float,
        examDate: String,
        localModel: String,
        localModelState: LocalModelState,
        cloudKeyReady: Boolean,
        online: Boolean,
        practiceCount: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): FirstRunPlan {
        val steps = listOf(
            targetStep(targetBand, examDate, nowMs),
            aiStep(localModel, localModelState, cloudKeyReady, online),
            practiceStep(practiceCount),
        )
        val pending = steps.count { !it.ready }
        val headline = when (pending) {
            0 -> "都准备好了：开始第一轮练习"
            1 -> "还差 1 步就能开始练题"
            else -> "还差 $pending 步就能开始练题"
        }
        return FirstRunPlan(
            steps = steps,
            pending = pending,
            headline = headline,
            detail = "引导只在首次启动出现；之后可在设置页底部「重新查看新手引导」。",
        )
    }

    private fun targetStep(targetBand: Float, examDate: String, nowMs: Long): GuideStep {
        val days = ExamTarget.daysUntil(examDate, nowMs)
        val band = String.format(java.util.Locale.US, "%.1f", targetBand)
        return when {
            examDate.isBlank() -> GuideStep(
                id = STEP_TARGET,
                title = "设好目标与考期",
                detail = "目标 Band $band，但还没设考试日期：首页倒计时、临考查漏和复练节奏都靠它。",
                ready = false,
                action = "去设置",
            )
            days == null -> GuideStep(
                id = STEP_TARGET,
                title = "设好目标与考期",
                detail = "考试日期「$examDate」不是合法的 yyyy-MM-dd，改对它才会算倒计时。",
                ready = false,
                action = "去设置",
            )
            days < 0 -> GuideStep(
                id = STEP_TARGET,
                title = "设好目标与考期",
                detail = "考试日期 $examDate 已经过去 ${-days} 天，改到新的考期才能继续算倒计时。",
                ready = false,
                action = "去设置",
            )
            else -> GuideStep(
                id = STEP_TARGET,
                title = "设好目标与考期",
                detail = "目标 Band $band，$examDate 考试（还剩 $days 天）。",
                ready = true,
            )
        }
    }

    private fun aiStep(
        localModel: String,
        state: LocalModelState,
        cloudKeyReady: Boolean,
        online: Boolean,
    ): GuideStep {
        val title = "确认 AI 评分通道"
        return when {
            state == LocalModelState.READY -> GuideStep(
                id = STEP_AI, title = title,
                detail = "本地模型 $localModel 已就绪：断网也能评分、出题和错题精讲。",
                ready = true,
            )
            state == LocalModelState.DELIVERED -> GuideStep(
                id = STEP_AI, title = title,
                detail = "本地模型 $localModel 已投递，首次评分会自动拷贝到内部存储（约 1–2 分钟），之后可离线。",
                ready = true,
            )
            cloudKeyReady && online -> GuideStep(
                id = STEP_AI, title = title,
                detail = "本地模型还没投递，但云端 API 已配置：联网时走云端评分；断网时需要本地模型。",
                ready = true,
            )
            cloudKeyReady -> GuideStep(
                id = STEP_AI, title = title,
                detail = "云端 key 有了但现在没网，本地模型也没投递：此刻评分一定失败。连上网或先投递模型。",
                ready = false,
                action = "去设置",
            )
            online -> GuideStep(
                id = STEP_AI, title = title,
                detail = "本地模型没投递、云端 API key 也是空的：两条通道都没准备，评分会直接报错。",
                ready = false,
                action = "去设置",
            )
            else -> GuideStep(
                id = STEP_AI, title = title,
                detail = "既没有网络，也没有投递本地模型：现在完全没法评分，先连网或投递模型。",
                ready = false,
                action = "去设置",
            )
        }
    }

    private fun practiceStep(count: Int): GuideStep =
        if (count > 0) GuideStep(
            id = STEP_PRACTICE,
            title = "做第一道题",
            detail = "已完成 $count 次练习，保持节奏：首页「今日任务」会按错题和最弱科排优先级。",
            ready = true,
        ) else GuideStep(
            id = STEP_PRACTICE,
            title = "做第一道题",
            detail = "还没做过题：听力题库一套 5 分钟，答完就有分数、逐句改法和错题卡。",
            ready = false,
            action = "去做题",
        )
}
