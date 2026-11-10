# 真机验收结果 · 2026-11-10（PKJ110 / SDK 37）

> 按 `docs/device-acceptance-runbook.md` 批次 1 执行；结果回填
> `docs/acceptance-checklist.md` 第八节。失败项按 runbook 的「结果记录格式」留证。

## 1.1 预热期间点「AI 提纲」——通过

- 步骤：进写作页 → 5 秒后预热开始（`LocalLlm: loading local model qwen3-4b-instruct`，09:31:59）
  → 加载期间点「AI 提纲」。
- 实际：按钮变为「生成提纲中…」，页面显示「本地模型生成提纲中（请留在 App 内） 已等待 16 秒」，
  **未出现**「本地模型正在忙」；约 40 秒后出提纲（立场/论点，按钮变为「换一份提纲」）。
- 日志：`LocalLlm` loading 记录 + 无 `本地模型正在忙` 文案。

## 1.2 关闭「提前加载模型」后进练习页——通过

- 步骤：`run-as` 把 `preload_on_practice` 写为 false（测前备份原 XML）→ 冷启动进写作页，
  App 全程前台保持 15 秒。
- 实际：`logcat -s LocalLlm` 无加载记录；`dumpsys notification` 无 `channel=guard` 通知。
- 测后已恢复原设置（`preload_on_practice` 键移除 = 默认开启，`score_channel=local` 等原值保留）。

## 1.3 AI 出题（8 题档）——波动：一次失败、一次成功

### 失败（首次，09:38:52）

```text
场景编号：1.3
复现步骤：AI 出题 → 阅读题 → Band 7.0 → 单选+判断 → 8 题 → 生成
预期 / 实际：预期生成 8 题完整套题；实际提示
  「生成失败：Unterminated array at character 4030 of {...}」
  JSON 在第 8 题 answers 前截断（第 1–7 题完整，第 8 题只到 "FAL"）
日志：11-10 09:38:52 E/E2E  ai gen fail: Unterminated array at character 4030 ...
截图：未留存（UI 与日志原文一致，错误文本已整段回显）
```

- 界面把整段原始 JSON 作为错误文案展示，占满整屏；用户无法据此操作。
- 本地出题路径 `LocalLlmEngine.generateReading/generateListening` 只发一次、解析失败直接抛异常，
  **没有** `LlmClient`（云端）那样的「不完整/格式失败重发一次」兜底。
- `MAX_NEW_TOKENS = 3500`（`LocalLlmEngine.kt`）是加载时写入的全局上限；8 题档输出（长文 +
  8 题 + 双语解析 + keys）在本次于 4030 字符处断开，与 token 预算不足/模型提前收尾均吻合，
  需要用 `local gen done ... chars` 日志进一步区分（本次日志缓冲被系统日志滚动冲掉）。

### 成功（第二次，约 09:44）

```text
场景编号：1.3（重跑）
复现步骤：AI 出题 → 阅读题 → Band 7.0 → 单选+判断 → 8 题 → 生成（冷启动）
预期 / 实际：生成成功，自动进入答题页（「阅读 · 已答 0 / 8」），原文与题目可做
日志：进入答题页，题头显示 0/8；无崩溃
```

- 结论：8 题档本地生成**可成功但存在波动**；失败时缺少兜底与可读提示。

## 待跟进

1. 本地出题失败兜底：解析失败/不完整时重发一次（或提示改用 6 题档），错误文案截断，
   不把整段原始 JSON 回显给用户。
2. 批次 1 剩余人工项：1.4/1.5/1.6/1.7/1.8/1.9/1.10。
3. 8 题档听力出题（本地 + TTS）未单独验证。
