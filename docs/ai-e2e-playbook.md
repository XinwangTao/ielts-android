# 本地 AI 端侧 e2e Playbook

> 2026-11-06。设备 OPPO PKJ110（Android 17 / arm64），本地模型 `qwen3-4b-instruct`，
> 入口 `tools/e2e_local_ai.sh`。目的是**不点手机**就能验证端侧 AI 链路，
> 每次改 prompt / 引擎 / 解析后先跑这里，再做 UI 验收。

## 一键命令

```bash
# 手机需亮屏、无线调试开着，且前台是桌面或雅思 App
bash tools/e2e_local_ai.sh <设备> <模式> [--force]

# 设备优先用 mDNS 名（IP 会随 DHCP 变）：
# adb-afa99023-q6q7Cb._adb-tls-connect._tcp
# 模式：
#   outline | sample | vocab | review | followup | variant | newtopic
#   gen_reading | gen_reading8 | gen_listening | gen_listening8
#   score_writing | score_speaking
```

脚本会等旧进程真正退出 → 带 `--es e2e_ai <模式>` 启动 → **只看本轮启动后的日志**，
用 `E2E: RESULT ai <模式>` 精确匹配（最长 15 分钟），命中即打印该行并退出 0。

## 已验证结果（PKJ110 / qwen3-4b-instruct）

| 模式 | 结果 | 耗时（含冷加载） | 备注 |
|---|---|---|---|
| `outline` | stance 222 字 / 3 论点 / 5 短语 / JSON 直出 | ~60s | |
| `sample` | 195 词 / 5 关键词 / 3 结构 | ~90s | 首版 146 词，自动重试后达标 |
| `vocab` | 6 条搭配，中文释义 | ~40s | |
| `score_writing` | overall 7.5（TR8/CC7/LR8/GRA7.5），fixes 3 / 逐句 2 | ~100s | 299 词强作文 |
| `score_speaking` | overall 7.5（FC8/LR7.5/GRA7/P7.5），fixes 3 / 逐句 2 | ~90s | 149 词 Part 2；2026-11-09 复跑 overall 7.0（FC8/LR7/GRA6.5/P7），414 tokens/30.8s |
| `gen_reading` | 4 题 / 文章 180 词 / JSON 可解析 | ~55s | 4 题档 |
| `gen_listening` | 4 题 / 听力稿 214 词 / JSON 可解析；同日另一次 3 题 / 187 词 | ~60s | 2026-11-09 真机；3 题那次触发重试兜底 |
| `gen_listening8` | 8 题 / complete=8 / 听力稿 197 词 / 808 tokens | ~70s（含冷加载） | 2026-11-09 真机，未触发重试 |
| `gen_reading8` | 8 题 / complete=8 / 文章 224 词 / 869 tokens | ~90s（含冷加载） | 2026-11-09 真机，未触发重试 |
| `review` | tasks=3 / summary / encouragement 均非空 | ~30s | 2026-11-06 真机 |
| `followup` | 83 字符单句追问 | ~15s | 2026-11-06 真机 |
| `variant` | 4 选项 / answerOk=true / 题干非空 | ~20s（模型已热） | 2026-11-09 真机 |
| `newtopic` | 题干 32 词 / `new_prompt` JSON 可解析 | ~20s（模型已热） | 2026-11-09 真机 |

## 前置条件

1. 设置页评分通道 = `local`，`localModel` 已投递（外部或内部完整）。
2. 屏幕亮着；本地生成期间 App 必须在前台，ColorOS 在窗口不可见时会冻结推理
   （见 `docs/oppo-background-scoring-findings.md` §九）。
3. 手机前台是桌面或雅思 App。脚本预检会拒绝「明确是别的 App」的情况；
   `mCurrentFocus=null`（转场/刚亮屏）不算占用。确实要强跑加 `--force`。

## 已知坑

- **前台被抢**：并行会话/其他 App（TickFlow、飞书、语音助手）会把雅思切走；
  进程被冻结后日志永远不出现。脚本会告警/快速失败，先回桌面再跑。
- **两个会话同时用一台手机（2026-11-09 实测）**：旧脚本每轮 `logcat -c` 清全局缓冲、
  再 grep 任意 `E2E: RESULT ai`，并发时会把对方模式的结果当成自己的（`score_speaking`
  拿到 `gen_listening` 的假 PASS）。现在不清缓冲，改按设备时间戳 `logcat -T` 过滤，
  并且只认本轮模式；另一条同样重要：`am force-stop` 是异步的，紧接着 `am start`
  会把新 Intent 交给旧 Activity，Compose 仍读创建时的旧 intent → 上一轮模式再跑一遍。
  脚本会等 `pidof` 为空再启动。
- **安装授权弹窗**：ColorOS 的 `InstallGrantActivity` 会盖住 App；安装后如出现先返回。
- **主线程 ANR**：e2e 钩子必须在 `withContext(Dispatchers.IO)` 里调引擎；
  主线程直接 `submit` 会 5 秒 ANR（已修，`14c7352`）。
- **`e2e_wav` 的音频路径必须 App 可读**（2026-11-11 实测）：Android 13+ 分区存储下，
  传 `/sdcard/xxx.wav` 会在转写时 `open failed: EACCES`，并留下一条永远 `pending_eval`
  的口语会话（结果页只能反复「重新评分」）。改用
  `/sdcard/Android/data/com.txw.ielts/files/<name>.wav`，或 `run-as com.txw.ielts`
  拷进内部 `files/`；已有的坏会话在设置页清空/删除，别留在「待处理」。
- **设备名**：IP 在 `192.168.236/237` 间漂移，且同网段可能有其他设备；
  优先用 mDNS 名，用 `getprop ro.product.model` 确认是 `PKJ110`。
- **模型首次加载**：冷加载 20-40s，生成 30-100s；出题漏题时 App 会自动重发一次，
  脚本超时相应放宽到 15 分钟。

## 失败判定

```bash
adb -s <设备> logcat -d -s E2E:I | grep "E2E: RESULT ai"
```

- 有 `E2E: RESULT ai <模式> ...`：链路通，检查字段/数字是否合理。
- 只有 `start_route/route`：生成未开始或卡在加载。
- 有 `fail=...`：模型/解析/内存问题，按错误信息排查。
- 8 分钟无结果：看 `dumpsys window | grep mCurrentFocus` 是否仍在雅思。

## 设备发现（Windows mDNS 常为空）

```bash
adb mdns services            # 手机亮屏 + 无线调试开着 + 同网段才会出现
adb connect <手机当前IP>:<调试端口>
```

- IP 会在 `192.168.236/237/137.x` 间漂移，端口每次重开无线调试都会变；优先用 mDNS 名
  `adb-afa99023-q6q7Cb._adb-tls-connect._tcp`。
- 同网段可能有别的不明设备（实测 `192.168.137.162:5555` 是 root shell、没有
  `/system/bin/dumpsys`，**不是 PKJ110**）。连接后用
  `adb shell getprop ro.product.model` 确认是 `PKJ110` 再跑。
- mDNS 为空且不知道 IP：手机解锁 → 开发者选项 → 无线调试页保持打开；
  或插一次 USB，用序列号 `afa99023` 部署。

## 云端回归（不依赖手机）

```bash
PYTHONIOENCODING=utf-8 python tools/ai_probe.py --report docs/ai-probe-report.md
```

- 18 项：写作 T1/T2、强/弱作文区分度、口语、提纲 T1/T2、范例 P1/P2/P3、
  生词卡、阅读/听力 6/8 题、错题精讲、口语追问、图表润色、AI 复盘。
- 直连 CC Switch `127.0.0.1:15721` 的 DeepSeek V4.1 Flash；探针必须带
  `thinking:disabled`，JSON 清洗逻辑与 App 的 `ScoreSanitizer.extractJson` 对齐。

