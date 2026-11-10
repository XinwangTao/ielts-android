package com.txw.ielts

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.txw.ielts.data.ChartSpec
import com.txw.ielts.data.Prefs
import com.txw.ielts.llm.AiReview
import com.txw.ielts.llm.AiReviewCache
import com.txw.ielts.llm.BankExplain
import com.txw.ielts.llm.BankVariant
import com.txw.ielts.llm.SpeakingSample
import com.txw.ielts.llm.StudyOutline
import com.txw.ielts.llm.VocabResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * AI 生成任务的会话级状态与协程作用域。
 *
 * 这些状态原来都是 MainActivity 组合里的 remember，任务跑在 rememberCoroutineScope 上：
 * 旋转屏或其它配置变更会让组合销毁，进行中的本地生成随之取消，几分钟推理白跑；
 * 云端请求还可能因为状态归零被用户重复点击、重复扣额度。
 *
 * 放进 ViewModel 后，状态与任务都活到 Activity 真正结束（进程死亡仍会丢，见计划的长尾说明）。
 * 状态暴露为 MutableState，界面侧按原变量名 `by ai.xxx` 委托，函数体不需要改。
 */
class AiTaskViewModel(app: Application) : AndroidViewModel(app) {

    // ---- 写作提纲 ----
    val writingOutline = mutableStateOf<StudyOutline?>(null)
    val writingOutlineTopic = mutableStateOf<String?>(null)
    val writingOutlineBusy = mutableStateOf(false)
    val writingOutlineError = mutableStateOf<String?>(null)

    // ---- 口语范例 ----
    val speakingSample = mutableStateOf<SpeakingSample?>(null)
    val speakingSampleQuestion = mutableStateOf<String?>(null)
    val speakingSampleBusy = mutableStateOf(false)
    val speakingSampleError = mutableStateOf<String?>(null)

    // ---- 生词卡 ----
    val vocabResult = mutableStateOf<VocabResult?>(null)
    val vocabSessionId = mutableStateOf<Long?>(null)
    val vocabBusy = mutableStateOf(false)
    val vocabError = mutableStateOf<String?>(null)

    // ---- Part 3 追问 ----
    val followUpQuestion = mutableStateOf<String?>(null)
    val followUpSessionId = mutableStateOf<Long?>(null)
    val followUpBusy = mutableStateOf(false)
    val followUpError = mutableStateOf<String?>(null)

    // ---- 写作换题 ----
    val newTopicBusy = mutableStateOf(false)
    val newTopicError = mutableStateOf<String?>(null)
    val newTopicSessionId = mutableStateOf<Long?>(null)

    // 换题结果 → 写作页预填。原来也在组合里，旋转后生成完就看不到题目了。
    val writingPresetTopic = mutableStateOf<String?>(null)
    val writingPresetTask = mutableStateOf<String?>(null)
    val writingPresetChart = mutableStateOf<ChartSpec?>(null)

    // ---- 口语预设（追问结果 → 去练习）----
    val speakingPresetQuestion = mutableStateOf<String?>(null)
    val speakingPresetPart = mutableStateOf<String?>(null)

    // ---- AI 复盘 ----
    val aiReviewCache = mutableStateOf(AiReviewCache.fromJson(Prefs(app).aiReviewCache))
    val aiReview = mutableStateOf<AiReview?>(aiReviewCache.value?.review)
    val aiReviewBusy = mutableStateOf(false)
    val aiReviewError = mutableStateOf<String?>(null)

    // ---- AI 出题 ----
    val aiGenBusy = mutableStateOf(false)
    val aiGenError = mutableStateOf<String?>(null)
    val aiGenStage = mutableStateOf<String?>(null)
    val aiGenReady = mutableStateOf<String?>(null)
    val pendingQuizNotices = mutableStateOf<Map<String, String>>(emptyMap())

    // ---- 错题精讲 ----
    val aiExplains = mutableStateOf<Map<String, BankExplain>>(emptyMap())
    val explainingIds = mutableStateOf<Set<String>>(emptySet())
    val explainErrors = mutableStateOf<Map<String, String?>>(emptyMap())

    // ---- 错题变式 ----
    val bankVariants = mutableStateOf<Map<String, BankVariant>>(emptyMap())
    val variantBusyIds = mutableStateOf<Set<String>>(emptySet())
    val variantErrors = mutableStateOf<Map<String, String?>>(emptyMap())

    /** 用 ViewModel 生命周期执行生成：配置变更不会取消，Activity 真正结束才取消。 */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        android.util.Log.i("AiTaskVM", "launch id=${System.identityHashCode(this)}")
        return viewModelScope.launch(block = block)
    }

    override fun onCleared() {
        android.util.Log.i("AiTaskVM", "onCleared id=${System.identityHashCode(this)}")
        super.onCleared()
    }
}
