# ielts-android

雅思练习 App：Kotlin + Jetpack Compose + Room，本地 4B（MNN）评分/生成，sherpa-onnx Whisper 离线转写，云端 OpenAI 兼容通道可切换。

## 构建与验证

```bash
bash tools/verify_all.sh              # 一键回归：单测 + 打包 + lint + AI 探针
./gradlew :app:testDebugUnitTest      # JVM 单测（纯逻辑，不依赖设备）
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:lintDebug              # lint（基线 0 error）
```

- 单测开了 `unitTests.isReturnDefaultValues`（`app/build.gradle.kts`），所以带 `android.util.Log` 的纯逻辑也能测。
- CI：`.github/workflows/android-ci.yml` 跑单测 + lint（命令与 `tools/verify_all.sh` 一致）；Dependabot 周更 Gradle。
- 跑 `bash tools/verify_all.sh` 时看脚本退出码：`bash tools/verify_all.sh; echo EXIT=$?`。
  用管道（`| tail`）会把脚本失败吞成 0（本项目已踩过）。
- 构建偶发 “KSP/增量编译不一致”（`AppDb_Impl` 找不到符号、`lateinit cleanFilenames`）：重试一次，仍失败则 `--rerun-tasks` 或 `:app:clean`。

## AI 链路

- 提示词真源在 `llm/LlmClient.kt`（评分/出题/精讲）与 `llm/StudyAi.kt`（提纲/范例/生词/复盘/追问/变式/写作换题）。
- 云端通道：`llm/Routing.kt` 的 `routeLlm`（local / cloud / auto）。auto 遇到**非 HTTP 层**失败（模型把 JSON 写坏）会先重试一次，再回退本地；`retryOnceOnFormat` 给不走 routeLlm 的评分链路用。
- 本地引擎：`llm/LocalLlmEngine.kt`。`isGenerating`（只看 `state=="generating"`）用于“模型忙”提示；加载中会排队而不是直接报错。
- 结果缓存：`llm/AiReviewCache.kt`（复盘，24h）、`llm/StudyAiCache.kt`（提纲/范例/生词/变式，7 天 + LRU 30）。界面上已有同一结果时再点按钮 = 想换一份，会跳过缓存重新生成。

## PC 端验证（不用装机）

```bash
python tools/ai_probe.py                                   # 全部用例
python tools/ai_probe.py --only variant                    # 子集
python tools/ai_probe.py --model "ccs-claude-openai--glm-5.3-flash"
```

- 提示词从 Kotlin 源码抽取（`from_kotlin`），测试与线上不漂移。
- 出题类用例自带 6000 token 预算（App 同口径）；8 题长输出在 4000 下会被截断。
- 换模型后同步 `docs/ai-probe-cross-model.md` 的结果表。

## 真机自测（有设备时）

```bash
bash tools/e2e_local_ai.sh <设备> outline|sample|vocab|review|followup|variant|newtopic|score_writing|score_speaking
```

脚本会先预检前台（不是桌面/雅思会快速失败；通知栏挡住会自动收起），结果从 `E2E: RESULT ...` 日志判定。

## 后台执行

- `Guard.kt`：mediaPlayback 前台服务 + 静音音轨把进程钉在前台/后台；可选 1×1 悬浮窗、屏幕常亮。
- 评分走 `ScoreService`（START_STICKY + 看门狗 + 失败原因存盘 `ScoreFailures`）。
- 实测边界与证据：`docs/oppo-background-scoring-findings.md`（ColorOS 会冻结窗口不可见的进程；冷加载可能被限速但会完成）。

## 目录地图

- `app/src/main/java/com/txw/ielts/MainActivity.kt`：路由、AI 请求编排、结果页调用点（很大，改动前先 grep 定位）。
- `ui/`：各页面；`ui/ResultScreen.kt` 底部有复用的 `RecordingPlayer`（播放/倍速/进度/拖动）。
- `data/`：Room（`Session.kt` 含 DAO/AppDb）、`Prefs.kt`（所有持久化开关）。
- `llm/`：引擎、提示词、解析器、选路、缓存。
- `tools/`：`ai_probe.py`（PC 探针）、`e2e_local_ai.sh`（真机无点击自测）。

## 文档

- `docs/acceptance-checklist.md`：功能 × 验证方式 × 状态，含「待真机验证 TODO」。
- `docs/ai-probe-report.md` / `docs/ai-probe-cross-model.md`：探针结果与跨模型结论。
- `docs/ai-e2e-playbook.md`：真机验证操作手册。
- `docs/oppo-*.md`：OPPO 部署与后台限制实测。

## 约定

- 改动保持最小、跟随现有风格；新增纯逻辑优先抽成可单测的对象。
- AI 动作回调（`onRequestXxx` / `onAiExplain` 等）**不给默认空实现**：漏接线必须在编译期失败，
  否则界面上就是「按钮点了没反应」（本仓库已踩两次）。
- 多行 Kotlin 字符串的改动走编辑工具，不要用脚本批量替换：转义换行常被写成真换行，导致编译错误。
- 模型「解析成功」不等于「内容完整」：出题要检查题数、答案、双语解析是否齐全，
  缺项应在业务层重发一次并取更完整的一份（同一 `LlmClient` 实例，额度只扣一次）。
- 涉及 UI 的改动，若当前无法真机验证，写进验收清单 TODO 并说明命令/判据。
- `prefs.<json 字段> = fn(prefs.<同一字段>)` 这种读改写必须包在 `Prefs.editJson {}` 里（companion 级锁）：
  后台评分/生成并发完成时，裸读改写会互相覆盖。新增 JSON 设置时同时给受锁写入路径。
- 用脚本改多行 Kotlin/文档时注意仓库部分是 CRLF：断言/替换要按 `\r?\n` 匹配，
  脚本跑完用 grep 复核落盘，不要让断言失败被后台任务日志吞掉。
- **Bash heredoc 会折叠反斜杠**：脚本里写 `\n`、`\p{L}`、`’` 可能被提前解释成真换行/真字符，
  产出不可编译的 Kotlin。含转义的文件用 Write/Edit 工具或 `chr(92)` 构造，写完 grep 复核。
- 可选能力（TTS/播放器）失败可以静默，但不能改变界面状态：恢复播放失败必须复位按钮。
- 聚合统计必须与单条展示共用有效性判定：Band 用 `ScoreGate.isValidBand`（0.5–9.0），
  正确率要求 `total>0` 且 `correct` 夹在 0..total；新增聚合先补 0/越界/坏 JSON 的边界测试。
- 不要提交 `.tmp-shots/`、`.secret/`、录音与模型文件。
