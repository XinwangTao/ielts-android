package com.txw.ielts.data

/**
 * 备份文件保留策略（纯函数）。
 *
 * 备份导出只增不减；设置页的「清理旧备份」按修改时间保留最近 N 个。
 * 把判定抽出来，既能单测边界，也避免 UI 里再抄一遍排序逻辑。
 */
object BackupFiles {

    /** 默认保留最近 5 个。 */
    const val KEEP = 5

    /**
     * 撞名时生成唯一文件名：`a.json` 已存在 → `a_2.json`，再撞 → `a_3.json`。
     *
     * 导出文件名精确到分钟，同一分钟内连点两次不能静默覆盖上一份备份。
     */
    fun uniqueName(existing: Set<String>, name: String): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "${base}_$i$ext"
            if (candidate !in existing) return candidate
            i++
        }
    }

    /**
     * @param files 候选文件（绝对路径 to lastModified）
     * @return 应删除的路径：按修改时间倒序后，第 keep 个之后的
     */
    fun selectDeletionCandidates(files: List<Pair<String, Long>>, keep: Int = KEEP): List<String> {
        if (keep <= 0) return files.map { it.first }
        return files.sortedByDescending { it.second }.drop(keep).map { it.first }
    }
}
