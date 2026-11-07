package com.txw.ielts.data

/**
 * 模考总分合成（纯函数）。
 *
 * 写作 Task1:Task2 = 1:2；口语取各段 average。两段缺一段时不合成——
 * 把单篇当「模考总分」会误导（Task1 失分往往更严重）。
 */
object MockScore {

    fun roundHalf(v: Double): Double = Math.round(v * 2.0) / 2.0

    fun writing(task1: Double?, task2: Double?): Double? {
        if (task1 == null || task2 == null) return null
        return roundHalf((task1 + 2 * task2) / 3.0)
    }

    fun average(bands: List<Double>): Double? =
        if (bands.isEmpty()) null else roundHalf(bands.average())
}
