package com.txw.ielts.data

/**
 * 本地评分「等待中」的判定与文案。
 *
 * 为什么单独抽出来：结果页的 `staleLocal` 由调用方在组合期算一次，
 * 而 `session` 来自 Room Flow —— 只有数据库变化才触发父级重组。
 * 每秒跳动的 `waitedSec` 是结果页自己的 state，改不动父级，
 * 于是过了 10 分钟这个标记仍然是 false，「重新评分」的出口永远不激活。
 * 判定必须由「会每秒刷新的 waitedSec」驱动，而不是由父级传下来的快照。
 */
object LocalWait {

    /** 超过这个秒数仍停在 pending_local，视为评分已中断。 */
    const val STALE_AFTER_SEC = 600L

    /**
     * 是否已经该给「重新评分」入口。
     * @param waitedSec 已经等待的秒数（每秒刷新，是判定的真实驱动源）
     * @param staleFromSnapshot 调用方在组合期算好的快照（可能长期不更新）
     */
    fun isStale(waitedSec: Long, staleFromSnapshot: Boolean): Boolean =
        staleFromSnapshot || waitedSec >= STALE_AFTER_SEC

    /** 按钮文案：运行中 > 已中断 > 还在等。 */
    fun retryLabel(busy: String?, stale: Boolean): String = when {
        busy != null -> busy
        stale -> "评分已中断，重新评分"
        else -> "评分中…（${STALE_AFTER_SEC / 60} 分钟后可重试）"
    }
}
