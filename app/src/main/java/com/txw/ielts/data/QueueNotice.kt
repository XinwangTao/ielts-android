package com.txw.ielts.data

/**
 * 点「开始复习 / 错题专项」却拿到空队列时给用户的一句交代。
 *
 * 为什么要有：首页「今日任务」和语料页的按钮原来在队列为空时只改一个看不见的
 * `error`（首页根本不显示这个字段），点击看起来完全没有反应。更糟的是它还会顺手
 * 调 `beginRound(empty)`，把刚结束那一轮的小结清掉 —— 用户刚看完小结，点一下空入口
 * 反而把小结弄没了。现在空队列不改轮次、不静默，而是说清「为什么没有」和「可以去哪」。
 *
 * 抽成纯数据是为了能在 JVM 上锁定两条底线：
 * 1) 这是正常状态，不是报错，文案不能吓人；
 * 2) 有错题可练时必须给出真实张数并指向错题专项，没有时不能编出一个「0 张」。
 */
data class QueueNotice(val title: String, val detail: String) {

    companion object {

        /**
         * 每日复习队列为空。
         * @param wrongAvailable 错题专项此刻真正可以刷的张数（未学或已到期）
         */
        fun emptyReview(wrongAvailable: Int): QueueNotice {
            val count = wrongAvailable.coerceAtLeast(0)
            val next = if (count > 0) {
                "错题专项里还有 $count 张可以重刷，比空等一轮更有用。"
            } else {
                "新卡明天会再补进来；也可以去题库自由做一套，保持手感。"
            }
            return QueueNotice(
                title = "今天没有到期的复习卡",
                detail = next,
            )
        }

        /** 错题专项的队列为空：不是错题丢了，只是都还没到下次到期时间。 */
        fun emptyWrong(): QueueNotice = QueueNotice(
            title = "暂无到期的错题",
            detail = "错题都复习过了，还没到下次到期时间；到期后会自动重新出现在这里。",
        )
    }
}
