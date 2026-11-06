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
| 2026-11-05 22:58 | 逐句改法卡也走归一化 key 判重；移除已无调用的 `CardDao.countByFront`，所有入库入口统一用 `allFronts` + `CardText.key` | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL |
| 2026-11-05 23:00 | 收尾验证：全量单测（含并发提交的复盘功能）+ 完整 AI 探针 | `./gradlew.bat :app:testDebugUnitTest --offline`；`python tools/ai_probe.py` | 170 tests / 0 failures；AI 18/18 PASS，EXIT=0 |
| 2026-11-05 23:03 | 判分归一化新增 `AnswerKey`：全角数字/字母/空格折回半角后再清洗，中文输入法打出的「２５」「ＡＢＣ」不再被判错 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；`AnswerKeyTest` 4/4 通过 |
| 2026-11-06 00:47 | AI 出题 choice 答案归一化：模型把答案写成 `B` / `(B)` / `B. 选项原文` 时折回选项原文，越界字母原样保留，避免「选对判错」 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；181 tests / 0 failures，`GeneratedParserTest` 13/13 通过 |
| 2026-11-06 00:50 | AI 出题题型别名归一化：`Multiple Choice` / `True/False/Not Given` / `fill-in-the-blank` / `multiple_choice` 等折回 choice/tfng/gap；题型缺失或认不出时按选项与答案推断（答案能对上选项→choice，答案像 T/F/NG→tfng），避免有选项的题在 UI 里退化成文本框 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；183 tests / 0 failures，`GeneratedParserTest` 15/15 通过 |
| 2026-11-06 00:53 | AI 出题判断题支持 YES/NO/NOT GIVEN 另一套标准选项：type 别名新增 ynng/yesno；答案 Y/N/NG 折回 YES/NO/NOT GIVEN；小写或乱序选项统一文案并补齐缺失项；无题型但答案为 YES 时不再退化成填空题 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；186 tests / 0 failures，`GeneratedParserTest` 18/18 通过 |
| 2026-11-06 00:55 | AI 出题答案 JSON 卫生：`strings()` 兼容裸数字/布尔（`"answers":[35]`、`20.0`、`true`），整数值还原成 `20` 不补 `.0`，数组内 null 跳过，避免听力价格/时间/数量题因「缺答案」被整题丢弃 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；188 tests / 0 failures，`GeneratedParserTest` 20/20 通过 |
| 2026-11-06 00:58 | 复习卡质量：逐句改法过滤空原句/空改写、改写与原句仅差大小写/空白/句末标点的无效条目，以及同一原句的重复条目；云端解析统一走 `ScoreSanitizer.parseSentenceFixes`，入库与旧会话展示层同步拦截，避免正面=背面的改错卡挤占反馈卡上限 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；190 tests / 0 failures |
| 2026-11-06 01:00 | AI 批注/复盘 JSON 卫生：`ScoreSanitizer.strings` 不再对数组项直接 `toString()`（JSON null 曾变字面量 "null"、`3` 变 "3.0"），只收 String/Number/Boolean 标量、整数不补 `.0`、按 `CardText.key` 去重，空别名回退下一个 key（`top_fixes` 空时用 `fixes`）；逐句改法 `sentence/issue/better` 过滤 null 字面量；云端评分与 `ReviewSummary` 共用同一套清洗，避免假改进点污染结果页、高频问题统计和复习卡 | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；195 tests / 0 failures（新增 `ScoreSanitizerTest` 5 例、`ReviewSummaryTest` 1 例） |
| 2026-11-06 09:21 | 错题本趋势分析：新增 `WrongTrendCalc`，用已有练习记录（听读 correct/total、跟读 accuracy）汇总近 7 天 vs 上一个 7 天正确率，错题卡按 tags/source 拆听力/阅读/跟读并统计反复遗忘（lapses>0）与未重刷（reps=0）；语料页新增「错题趋势」卡片展示分科 delta、存量构成与可执行诊断，不新增表/不改 schema | `./gradlew.bat :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL；205 tests / 0 failures（新增 `WrongTrendTest` 9 例） |
