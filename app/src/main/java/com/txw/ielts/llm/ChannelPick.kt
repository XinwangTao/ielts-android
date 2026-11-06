package com.txw.ielts.llm

/** 首页/设置页的通道提示：说明为什么 AI 会走本地，以及用户需不需要动手。 */
data class ChannelNotice(
    val title: String,
    val detail: String,
    /** 显式选了云端又断网：光提示不够，需要引导去设置页改通道。 */
    val needsAction: Boolean,
)

/**
 * 「评分通道」的选路与降级判定。
 *
 * 为什么抽出来：同一条规则原来在写作/口语两条评分链路上各抄了一遍，
 * 而 AI 出题（阅读/听力/错题讲解）三处**完全没写** —— 云端口令已死时，
 * 那三条路仍然每次都先打一次必然失败的请求再回退，白等一个往返。
 */
object ChannelPick {

    /**
     * 实际该走哪条通道。
     * @param mode 设置页选的通道：local / cloud / auto
     * @param cloudDown 云端是否处于冷却期（上次失败后 1 小时内）
     * @param hasNetwork 当前是否有可用网络
     * @param remainingCalls 今日云端剩余额度
     * @return local / cloud / auto，可直接交给 routeLlm
     */
    fun effective(mode: String, cloudDown: Boolean, hasNetwork: Boolean, remainingCalls: Int): String = when {
        mode == "local" -> "local"
        // cloud 是用户显式选择：即使判定「云端不可用」也要照打，失败就报错，不偷偷降级。
        mode != "auto" -> mode
        cloudDown || !hasNetwork || remainingCalls <= 0 -> "local"
        else -> "auto"
    }

    /**
     * 显式选「仅云端」又断网时，不要等 HTTP 超时才报错：这一路必然失败，
     * 提前给出「改哪一项设置」的提示。auto/local 不走云端，不返回错误。
     *
     * @param localAvailable 本地模型是否可用（内部已就绪，或已投递到外部目录）。
     *   不可用时不能再让用户「改成仅本地」——那同样是必失败，得先投递模型。
     */
    fun cloudOfflineError(
        mode: String,
        hasNetwork: Boolean,
        localAvailable: Boolean = true,
    ): String? = if (mode == "cloud" && !hasNetwork) {
        if (localAvailable) {
            "当前无网络，云端评分不可用。请到设置页把评分通道改为「仅本地」或「自动（云端优先）」后重试。"
        } else {
            "当前无网络，云端评分不可用，本地模型也还没投递：此刻评分一定失败。" +
                "请先连上网，或到设置页按提示投递本地模型后再重试。"
        }
    } else null

    /**
     * 首页的降级提示：自动模式下断网/冷却/额度用完时，AI 仍能用本地模型，
     * 但用户需要知道「为什么变慢」；显式选云端又断网时则要说清怎么改。
     *
     * @param localAvailable 本地模型是否可用（内部已就绪，或已投递到外部目录）。
     *   这条提示原来只按通道模式判断，于是本地模型没投递时也会说「已改用本地模型」——
     *   用户照着等了几分钟，实际得到的是一句「缺少本地模型」。现在不可用时如实说明并给出口。
     */
    fun notice(
        mode: String,
        cloudDown: Boolean,
        hasNetwork: Boolean,
        remainingCalls: Int,
        localAvailable: Boolean = true,
    ): ChannelNotice? = when {
        // 仅本地是主动选择，但本地模型没投递时这条通道根本不通，必须提醒。
        mode == "local" && !localAvailable -> ChannelNotice(
            title = "仅本地：本地模型还没就绪",
            detail = "当前选择「仅本地」，但本地模型没有投递或拷贝完成，评分与 AI 出题会直接失败。" +
                "到设置页按提示推送模型，或先把通道改成「自动（云端优先）」。",
            needsAction = true,
        )
        mode == "local" -> null
        mode == "cloud" && !hasNetwork -> ChannelNotice(
            title = "无网络：云端通道不可用",
            detail = "当前选择「仅云端」，评分与 AI 出题会直接失败。" +
                if (localAvailable) {
                    "到设置页改为「仅本地」或「自动」即可离线继续。"
                } else {
                    "本地模型也还没投递，切换通道前需先到设置页按提示推送模型。"
                },
            needsAction = true,
        )
        mode == "cloud" -> null
        !hasNetwork && !localAvailable -> ChannelNotice(
            title = "无网络：本地模型还没就绪",
            detail = "自动模式断网时会走本地，但本地模型没有投递或拷贝完成，此刻评分与出题会直接失败。" +
                "连上网，或到设置页按提示推送模型后再试。",
            needsAction = true,
        )
        !hasNetwork -> ChannelNotice(
            title = "无网络：已改用本地模型",
            detail = "评分、出题与错题精讲会自动走端侧模型；首次加载约 1–2 分钟，结果不受影响。",
            needsAction = false,
        )
        cloudDown && !localAvailable -> ChannelNotice(
            title = "云端额度冷却中：本地模型还没就绪",
            detail = "冷却期间自动模式会走本地，但本地模型没有投递，此刻评分与出题会直接失败。" +
                "到设置页按提示推送模型，或等冷却结束后用云端。",
            needsAction = true,
        )
        cloudDown -> ChannelNotice(
            title = "云端额度冷却中：已改用本地模型",
            detail = "上次云端额度或限流失败，1 小时内直接走本地，避免每次评分都先白等一轮。",
            needsAction = false,
        )
        remainingCalls <= 0 && !localAvailable -> ChannelNotice(
            title = "今日云端额度已用完：本地模型还没就绪",
            detail = "额度用完后自动模式会走本地，但本地模型没有投递，此刻评分与出题会直接失败。" +
                "到设置页按提示推送模型，或明天再用云端。",
            needsAction = true,
        )
        remainingCalls <= 0 -> ChannelNotice(
            title = "今日云端额度已用完：已改用本地模型",
            detail = "可在设置页调整每日调用上限；明天恢复云端优先，本地评分仍然可用。",
            needsAction = false,
        )
        else -> null
    }

    /** 云端失败文案里出现这些，说明不是瞬时故障而是账号侧额度/权限问题。 */
    private val QUOTA_MARKERS = listOf("403", "429", "11200", "quota", "额度")

    /**
     * 自动模式落到本地时的原因文案；没降级（或用户显式选了通道）返回 null。
     * 设置页用它提示「为什么现在评分会走本地」，断网时不至于让用户以为模型坏了。
     */
    fun localReason(mode: String, cloudDown: Boolean, hasNetwork: Boolean, remainingCalls: Int): String? = when {
        mode != "auto" -> null
        !hasNetwork -> "当前无网络"
        cloudDown -> "云端额度或限流冷却中"
        remainingCalls <= 0 -> "今日云端额度已用完"
        else -> null
    }

    /**
     * 是否属于「额度类失败」。这类失败短期内不会自愈，
     * 命中后应把云端冷却一小时，避免每次评分都先白试一次。
     */
    fun isQuotaFailure(message: String?): Boolean {
        val m = message ?: return false
        return QUOTA_MARKERS.any { m.contains(it, ignoreCase = true) }
    }
}
