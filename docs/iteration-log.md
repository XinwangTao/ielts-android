# 迭代日志（AI 学习闭环）

| 时间 | 改动 | 验证命令 | 结果 |
|---|---|---|---|
| 2026-11-05 22:35 | 任务 1 收尾：学习页删除卡片 + `CardRepo.delete`（已在 `acfe1c2` 提交，工作树无残留） | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL（25 tasks，全绿） |
| 2026-11-05 22:42 | 生成卡正面统一清洗/去重：新增 `CardText`，AI 批注、听读错题、查词、跟读、生词卡统一压平空白、截断与大小写无关判重；`countByFront` 改 `COLLATE NOCASE` | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`CardTextTest` 7/7 通过 |
| 2026-11-05 22:43 | 复习队列「Again 当场重排」：新增 `ReviewQueue.afterGrade`，答错不消失到明天，隔 3 张再问（队短则排尾/立即重问）；学习页评分接上 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`ReviewQueueTest` 8/8 通过 |
| 2026-11-05 22:44 | AI 出题解析去重：`GeneratedParser.questions` 按归一化题干+答案去重（大小写/空白无关），只保留第一次；题干相同答案不同仍保留 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`GeneratedParserTest` 8/8 通过 |
| 2026-11-05 22:45 | AI 反馈卡上限：新增 `FeedbackCap`（300 张），练习批注/逐句改法入库后只淘汰已复习过的卡（少复习、早创建先走），未复习的反馈一张不删 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`FeedbackCapTest` 4/4 通过 |
| 2026-11-05 22:46 | 每日队列名额：新增 `QueuePolicy.freshSlots`，新卡只补到期卡剩余空位并受每日额度约束，修复到期卡多时队列超过 limit=60 的问题 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`QueuePolicyTest` 4/4 通过 |
| 2026-11-05 22:46 | SM-2 调度回归：新增 10 条单测锁定 Again/Good/Hard/Easy 的间隔、ease 上下限（1.3~2.8）与 180 天封顶 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`Sm2Test` 10/10 通过 |
| 2026-11-05 22:49 | 端到端复验：完整跑 AI 探针（含评分/提纲/范例/生词/出题/精讲/复盘 18 项） | `python tools/ai_probe.py` | 18/18 PASS，EXIT=0（本地 CC Switch 正常；含并发 WIP 的 ai_review） |
| 2026-11-05 22:50 | AI 出题类型归一化：type 统一小写；choice 选项<2 降级填空题；tfng 缺选项补 TRUE/FALSE/NOT GIVEN 并归一化 T/F/NG 答案；并为并发 WIP 生成缺失的 ai_review 夹具 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL，19 个测试类全绿（GeneratedParserTest 10/10） |
| 2026-11-05 22:54 | AI 批注/错题卡跨会话判重：入库前用 `CardText.key` 归一化库中全部正面，标点/空白/大小写不同的旧卡不再重复建卡（新增 `CardDao.allFronts`） | `./gradlew.bat :app:testDebugUnitTest --offline --rerun-tasks` | BUILD SUCCESSFUL（强制重跑，19 个测试类全绿） |
| 2026-11-05 22:55 | 删除 AI 套题时清理私有目录音频：新增 `BankAudio.ownedWavName`（只认 wav 文件名，防路径穿越），`deleteAiSet` 同步删除 filesDir/bank 下的 TTS 音频 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`BankAudioTest` 3/3 通过 |
| 2026-11-05 22:57 | 断网/降级提示：新增 `ChannelPick.localReason`（断网 > 额度冷却 > 额度用完）与 `NetworkStatus`，设置页评分通道下方显示「为何自动模式走本地」 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`ChannelPickTest` 8/8 通过 |
| 2026-11-05 22:57 | 查词/跟读/AI 生词卡也改用归一化 key 跨会话判重，与批注/错题一致（大小写、首尾标点不再重复建卡） | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL |
