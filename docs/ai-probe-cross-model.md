# AI 功能跨模型验证（2026-11-06）

## 结论

| 模型 | token 预算 | 结果 | 夹具 |
|---|---|---|---|
| DeepSeek V4.1 Flash（`ccs-claude-openai--deepseek-v4.1-flash[1m]`） | 4000 | **18/18** | `app/src/test/resources/ai_probe/` |
| GLM-5.3-Flash（`ccs-claude-openai--glm-5.3-flash`） | 3500 | **18/18** | `app/src/test/resources/ai_probe_glm/` |

覆盖 18 项：写作 Task2/Task1 评分、弱作文区分度、口语评分、提纲 Task2/Task1、口语范例 Part1/2/3、生词卡、AI 复盘、阅读出题 6/8 题、听力出题 6/8 题、错题精讲、口语追问、图表题干润色。

强/弱作文区分度：DeepSeek 7.0 vs 4.5-5.0；GLM 7.0 vs 5.5。

## 关键坑与加固

1. **必须带 `thinking: disabled`**（与 `LlmClient.chat` 一致）。否则推理 token 吃满 `max_tokens`，正文返回空或半截 JSON；探针最初就踩了这个坑。
2. **JSON 卫生**：字符串内不能有裸双引号（转义 `\"` 或改单引号）；解析层镜像 `ScoreSanitizer.extractJson`，把字符串内裸换行换成空格，并按括号配对截取对象（不会被 JSON 后面的说明文字带偏）。
3. **答案格式**：每个可接受答案单独一个数组元素，禁止 `20 / twenty` 斜杠拼接；数字同时给数字与英文写法（`44` / `forty-four`），否则 transcript 与答案对不上。
4. **条数容忍**：提纲论点、`top_fixes` 允许 3-5 条；模型偶发多给一条不应当成失败。
5. **本地预算**：本地 MNN 的 `max_new_tokens=3500`；全量 18 项在 3500 下也通过，阅读/听力 8 题不截断。

## 复跑

```bash
# 主力模型（同时刷新报告与夹具）
python tools/ai_probe.py --report docs/ai-probe-report.md --fixtures app/src/test/resources/ai_probe

# 跨模型（GLM，只刷新 GLM 夹具）
python tools/ai_probe.py --model ccs-claude-openai--glm-5.3-flash --max-tokens 3500 \
  --fixtures app/src/test/resources/ai_probe_glm

# 只跑某一项
python tools/ai_probe.py --only listening_generation_8
```

夹具是模型输出的快照；改提示词后重跑上面的命令刷新，再跑：

```bash
./gradlew testDebugUnitTest
```

`AiProbeFixtureTest`（DeepSeek）与 `AiProbeGlmFixtureTest`（GLM）会用 App 自己的 `ScoreSanitizer / StudyAi / GeneratedParser` 再解析一遍，防止「模型能输出但 App 吃不下」。

## 真机状态（OPPO PKJ110）

- 本地 4B **AI 复盘**：加载后 30.9 秒生成 375 tokens，页面显示总结 + 3 条行动 + 缓存提示，通过。
- `e2e_ai` 无点击自测：`vocab` 6 条通过；`outline` / `sample` 需要 App 在前台且手机空闲，用户切到其他 App 时会被系统冻结，留待下次补跑。
- 测试副产物 `card 170` / `session 22` 已按授权清理，现有 169 张卡 / 15 条记录。
