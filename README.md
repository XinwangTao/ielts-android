# 雅思随身练（ielts-android）

安卓端雅思备考 App：单词/语料复习、听力与阅读、口语跟读、作文批改，AI 功能可走本机或云端模型。

- 技术栈：Kotlin + Jetpack Compose，Room 本地库，协程；无第三方 UI 框架
- AI：OpenAI 兼容接口（自建网关 / CC Switch / 本地模型皆可），提示词与 JSON 清洗在 `llm/` 下有单测
- 语音：系统 TTS 朗读 + 本机录音转写；TTS 保留引擎当前音色（装了 Kokoro 这类好引擎时听感最佳）

## 构建

```bash
./gradlew :app:assembleDebug          # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 单元测试（约 200 项）
```

`local.properties` 里配好 `sdk.dir`；AI 网关地址、密钥在 App 设置页填。

## 主要模块

| 模块 | 说明 |
|---|---|
| 词汇/语料 | SRS 复习队列、AI 反馈卡沉淀、卡片正反面清洗去重 |
| 听力/阅读 | 题库 + AI 现场出题，听力音频由 TTS 分段合成后拼接 |
| 口语 | 跟读打分、逐句改法、复述练习 |
| 写作 | 大小作文批改，给出分项评分与逐句改进 |
| AI | 批注/评分/出题的提示词与 JSON 容错（`ScoreSanitizer`、`GeneratedParser`） |

## 测试与验收

- 单测：`./gradlew :app:testDebugUnitTest`（AI 解析、复习队列、TTS 音色等都有覆盖）
- 真机验收清单见 `docs/acceptance-checklist.md`
- AI 端到端探针：`tools/ai_probe.py`（PC 侧跑，需要可达的 OpenAI 兼容网关）

## 许可

MIT，见 `LICENSE`。
