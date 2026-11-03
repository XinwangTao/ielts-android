package com.txw.ielts.data

import android.content.Context

/** 本地设置：模型通道的 base_url / key / 模型名。只存本机，不写入 APK。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ielts_prefs", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE
        set(v) = sp.edit().putString(KEY_BASE, v.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = sp.getString(KEY_API, "") ?: ""
        set(v) = sp.edit().putString(KEY_API, v.trim()).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString(KEY_MODEL, v.trim()).apply()

    var asrModel: String
        get() = sp.getString(KEY_ASR, "base.en") ?: "base.en"
        set(v) = sp.edit().putString(KEY_ASR, v).apply()

    /**
     * 评分通道（图：ielts-local-llm_workflow 的 LlmRouter）：
     * auto = 云端优先、失败自动降级本地；local = 仅本地（离线/零额度）；cloud = 仅云端。
     */
    var scoreChannel: String
        get() = sp.getString(KEY_SCORE_CHANNEL, "auto") ?: "auto"
        set(v) = sp.edit().putString(KEY_SCORE_CHANNEL, v).apply()

    /** 本地评分用的模型目录名（files/models/mnn/<name>）。 */
    var localModel: String
        get() = sp.getString(KEY_LOCAL_MODEL, "qwen3-4b-instruct") ?: "qwen3-4b-instruct"
        set(v) = sp.edit().putString(KEY_LOCAL_MODEL, v).apply()

    /** 评分完成后是否释放模型内存（下次评分需重新加载 1-2 分钟，换内存余量）。 */
    var releaseAfterScore: Boolean
        get() = sp.getBoolean(KEY_RELEASE_AFTER, false)
        set(v) = sp.edit().putBoolean(KEY_RELEASE_AFTER, v).apply()

    /** 目标总分（0.5 步长，4.0–9.0）。首页「离目标还差多少」与临考节奏都依赖它。 */
    var targetBand: Float
        get() = sp.getFloat(KEY_TARGET_BAND, 6.5f)
        set(v) = sp.edit().putFloat(KEY_TARGET_BAND, com.txw.ielts.data.ExamTarget.snapBand(v)).apply()

    /** 考试日期（yyyy-MM-dd，空串表示未设置）。 */
    var examDate: String
        get() = sp.getString(KEY_EXAM_DATE, "") ?: ""
        set(v) = sp.edit().putString(KEY_EXAM_DATE, v.trim()).apply()

    /**
     * 四科目标分。从未设置过时先按总分目标兜底（新装机不必先配四遍），
     * 保存过设置就各科独立：总分调高后四科不会偷偷跟着变，
     * 首页会把「四科换算总分」摆出来提醒，是否齐平由用户自己决定。
     */
    private fun skillTarget(key: String): Float {
        val saved = sp.getFloat(key, UNSET)
        return if (saved == UNSET) targetBand else ExamTarget.snapBand(saved)
    }

    val targetListening: Float
        get() = skillTarget(KEY_TARGET_L)

    val targetReading: Float
        get() = skillTarget(KEY_TARGET_R)

    val targetWriting: Float
        get() = skillTarget(KEY_TARGET_W)

    val targetSpeaking: Float
        get() = skillTarget(KEY_TARGET_S)

    /**
     * 一次性写入四科目标（null 表示这一科没填，保持原值不动）。
     *
     * 逐科 setter 会让「只改总分」也把四科钉在旧值上，所以只在保存时整体写；
     * 也不做「等于总分就删掉」的自动跟随——用户特意把口语压到 6.0 保底时，
     * 那个 6.0 是决策，不是默认值，不该被规则抹掉。
     */
    fun setSkillTargets(listening: Float?, reading: Float?, writing: Float?, speaking: Float?) {
        val editor = sp.edit()
        fun put(key: String, value: Float?) {
            val snapped = value?.takeIf { it.isFinite() }?.let { ExamTarget.snapBand(it) } ?: return
            editor.putFloat(key, snapped)
        }
        put(KEY_TARGET_L, listening)
        put(KEY_TARGET_R, reading)
        put(KEY_TARGET_W, writing)
        put(KEY_TARGET_S, speaking)
        editor.apply()
    }

    // ---- API 用量控制：防止后台或误触频繁调用大模型 ----
    var dailyCallLimit: Int
        get() = sp.getInt(KEY_LIMIT, 20)
        set(v) = sp.edit().putInt(KEY_LIMIT, v.coerceIn(1, 200)).apply()

    /** 每日新卡上限（语料模块用，与 API 额度无关）。 */
    var dailyNewCards: Int
        get() = sp.getInt(KEY_NEW_CARDS, 20)
        set(v) = sp.edit().putInt(KEY_NEW_CARDS, v.coerceIn(1, 100)).apply()

    /** 听读题库的作答进度（JSON：套题 id → {题号: 答案}），用于中途退出后续做。 */
    var bankProgress: String
        get() = sp.getString(KEY_BANK_PROGRESS, "{}") ?: "{}"
        set(v) = sp.edit().putString(KEY_BANK_PROGRESS, v).apply()

    /** 阅读排版：正文走读字号（sp，13–24）与行距倍数（1.2–1.8）。借鉴 Read You 的阅读设置。 */
    var readerFontSize: Int
        get() = sp.getInt(KEY_READER_FONT, 16)
        set(v) = sp.edit().putInt(KEY_READER_FONT, v.coerceIn(13, 24)).apply()

    var readerLineHeight: Float
        get() = sp.getFloat(KEY_READER_LINE, 1.4f)
        set(v) = sp.edit().putFloat(KEY_READER_LINE, v.coerceIn(1.2f, 1.8f)).apply()

    /** 本地评分任务的实际启动时间（JSON：sessionId → 毫秒）。不能复用 startedAt（那是练习开始时间）。 */
    var localScoreStarted: String
        get() = sp.getString(KEY_LOCAL_SCORE_STARTED, "{}") ?: "{}"
        set(v) = sp.edit().putString(KEY_LOCAL_SCORE_STARTED, v).apply()

    /** 本地评分完成时间（JSON：sessionId → 毫秒），用于结果页显示耗时。 */
    var localScoreFinished: String
        get() = sp.getString(KEY_LOCAL_SCORE_FINISHED, "{}") ?: "{}"
        set(v) = sp.edit().putString(KEY_LOCAL_SCORE_FINISHED, v).apply()

    /** 云端额度/限流失败后的冷却截止时间：冷却期内 auto 直接走本地，不再每次白试云端。 */
    var cloudDownUntil: Long
        get() = sp.getLong(KEY_CLOUD_DOWN, 0L)
        set(v) = sp.edit().putLong(KEY_CLOUD_DOWN, v).apply()

    var callsToday: Int
        get() {
            rolloverIfNeeded()
            return sp.getInt(KEY_USED, 0)
        }
        set(v) = sp.edit().putInt(KEY_USED, v).apply()

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun rolloverIfNeeded() {
        if (sp.getString(KEY_DAY, "") != today()) {
            sp.edit().putString(KEY_DAY, today()).putInt(KEY_USED, 0).apply()
        }
    }

    fun remainingCalls(): Int {
        rolloverIfNeeded()
        return (dailyCallLimit - sp.getInt(KEY_USED, 0)).coerceAtLeast(0)
    }

    fun consumeCall() {
        rolloverIfNeeded()
        sp.edit().putInt(KEY_USED, sp.getInt(KEY_USED, 0) + 1).putString(KEY_DAY, today()).apply()
    }

    companion object {
        private const val KEY_BASE = "llm_base_url"
        private const val KEY_API = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
        private const val KEY_ASR = "asr_model"
        private const val KEY_SCORE_CHANNEL = "score_channel"
        private const val KEY_LOCAL_MODEL = "local_model"
        private const val KEY_RELEASE_AFTER = "release_model_after_score"
        private const val KEY_LIMIT = "llm_daily_limit"
        private const val KEY_NEW_CARDS = "daily_new_cards"
        private const val KEY_BANK_PROGRESS = "bank_progress"
        private const val KEY_READER_FONT = "reader_font_size"
        private const val KEY_READER_LINE = "reader_line_height"
        private const val KEY_USED = "llm_calls_today"
        private const val KEY_DAY = "llm_calls_day"
        private const val KEY_LOCAL_SCORE_STARTED = "local_score_started"
        private const val KEY_LOCAL_SCORE_FINISHED = "local_score_finished"
        private const val KEY_CLOUD_DOWN = "cloud_down_until"
        private const val KEY_TARGET_BAND = "target_band"
        private const val KEY_EXAM_DATE = "exam_date"
        private const val KEY_TARGET_L = "target_band_listening"
        private const val KEY_TARGET_R = "target_band_reading"
        private const val KEY_TARGET_W = "target_band_writing"
        private const val KEY_TARGET_S = "target_band_speaking"

        /** 四科目标「未设置」哨兵：比任何合法分数都小，snapBand 不可能产出。 */
        private const val UNSET = -1f
        const val DEFAULT_BASE = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL = "glm-5.3-flash"
    }
}
