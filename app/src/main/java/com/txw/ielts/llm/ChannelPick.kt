package com.txw.ielts.llm

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

    /** 云端失败文案里出现这些，说明不是瞬时故障而是账号侧额度/权限问题。 */
    private val QUOTA_MARKERS = listOf("403", "429", "11200", "quota", "额度")

    /**
     * 是否属于「额度类失败」。这类失败短期内不会自愈，
     * 命中后应把云端冷却一小时，避免每次评分都先白试一次。
     */
    fun isQuotaFailure(message: String?): Boolean {
        val m = message ?: return false
        return QUOTA_MARKERS.any { m.contains(it, ignoreCase = true) }
    }
}
