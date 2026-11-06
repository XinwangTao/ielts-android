package com.txw.ielts.data

/**
 * 录音清理判定（纯函数）：只删「没有 session 引用、且早于保留期」的 .wav。
 *
 * 启动时的自动清理与设置页的手动清理共用这一份，避免两处口径漂移：
 * 一处按文件名、一处按绝对路径，最容易出现「刚录完就被删」或「该删的删不掉」。
 * 保留期不同是有意的：启动清理保留 7 天，手动清理更激进（1 天），但仍不会碰刚录的文件。
 */
object RecordingCleanup {

    /** 启动时自动清理：保留 7 天内。 */
    const val KEEP_STARTUP_MS = 7L * 86_400_000L

    /** 用户手动清理：保留 1 天内。 */
    const val KEEP_MANUAL_MS = 24L * 3_600_000L

    /**
     * 查引用时要回看多少条会话。
     * 只查最近 200 条时，练得多的用户会遇到「老会话仍引用、但不在前 200」→ 录音被误删。
     */
    const val REF_LOOKBACK = 5000

    /**
     * @param files 候选文件（绝对路径 to lastModified）
     * @param referenced 仍被任何会话引用的绝对路径
     * @return 可以删除的绝对路径
     */
    fun pickDeletable(
        files: List<Pair<String, Long>>,
        referenced: Set<String>,
        now: Long,
        keepMs: Long,
    ): List<String> = files
        .filter { (path, modified) -> path !in referenced && modified < now - keepMs }
        .map { it.first }
}
