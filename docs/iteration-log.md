# 迭代日志（AI 学习闭环）

| 时间 | 改动 | 验证命令 | 结果 |
|---|---|---|---|
| 2026-11-05 22:35 | 任务 1 收尾：学习页删除卡片 + `CardRepo.delete`（已在 `acfe1c2` 提交，工作树无残留） | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL（25 tasks，全绿） |
| 2026-11-05 22:42 | 生成卡正面统一清洗/去重：新增 `CardText`，AI 批注、听读错题、查词、跟读、生词卡统一压平空白、截断与大小写无关判重；`countByFront` 改 `COLLATE NOCASE` | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`CardTextTest` 7/7 通过 |
| 2026-11-05 22:43 | 复习队列「Again 当场重排」：新增 `ReviewQueue.afterGrade`，答错不消失到明天，隔 3 张再问（队短则排尾/立即重问）；学习页评分接上 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`ReviewQueueTest` 8/8 通过 |
| 2026-11-05 22:44 | AI 出题解析去重：`GeneratedParser.questions` 按归一化题干+答案去重（大小写/空白无关），只保留第一次；题干相同答案不同仍保留 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`GeneratedParserTest` 8/8 通过 |
