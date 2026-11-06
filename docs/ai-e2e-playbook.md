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
#   outline | sample | vocab | review | followup
#   gen_reading | gen_listening
#   score_writing | score_speaking
```

脚本会 `force-stop` → 带 `--es e2e_ai <模式>` 启动 → 轮询
`E2E: RESULT ai ...` 日志（最长 8 分钟），命中即打印该行并退出 0。

## 已验证结果（PKJ110 / qwen3-4b-instruct）

| 模式 | 结果 | 耗时（含冷加载） | 备注 |
|---|---|---|---|
| `outline` | stance 222 字 / 3 论点 / 5 短语 / JSON 直出 | ~60s | |
| `sample` | 195 词 / 5 关键词 / 3 结构 | ~90s | 首版 146 词，自动重试后达标 |
| `vocab` | 6 条搭配，中文释义 | ~40s | |
| `score_writing` | overall 7.5（TR8/CC7/LR8/GRA7.5），fixes 3 / 逐句 2 | ~100s | 299 词强作文 |
| `score_speaking` | overall 7.5（FC8/LR7.5/GRA7/P7.5），fixes 3 / 逐句 2 | ~90s | 149 词 Part 2 |
| `gen_reading` | 4 题 / 文章 180 词 / JSON 可解析 | ~55s | 4 题档 |
| `gen_listening` | **待补** | — | 被前台 App 抢占未跑完 |
| `review` / `followup` | 由并行会话验证（提交 `6071a7e`） | — | |

## 前置条件

1. 设置页评分通道 = `local`，`localModel` 已投递（外部或内部完整）。
2. 屏幕亮着；本地生成期间 App 必须在前台，ColorOS 在窗口不可见时会冻结推理
   （见 `docs/oppo-background-scoring-findings.md` §九）。
3. 手机前台是桌面或雅思 App。脚本预检会拒绝「明确是别的 App」的情况；
   `mCurrentFocus=null`（转场/刚亮屏）不算占用。确实要强跑加 `--force`。

## 已知坑

- **前台被抢**：并行会话/其他 App（TickFlow、飞书、语音助手）会把雅思切走；
  进程被冻结后日志永远不出现。脚本会告警/快速失败，先回桌面再跑。
- **安装授权弹窗**：ColorOS 的 `InstallGrantActivity` 会盖住 App；安装后如出现先返回。
- **主线程 ANR**：e2e 钩子必须在 `withContext(Dispatchers.IO)` 里调引擎；
  主线程直接 `submit` 会 5 秒 ANR（已修，`14c7352`）。
- **设备名**：IP 在 `192.168.236/237` 间漂移，且同网段可能有其他设备；
  优先用 mDNS 名，用 `getprop ro.product.model` 确认是 `PKJ110`。
- **模型首次加载**：冷加载 20-40s，生成 30-100s；脚本超时 8 分钟。

## 失败判定

```bash
adb -s <设备> logcat -d -s E2E:I | grep "E2E: RESULT ai"
```

- 有 `E2E: RESULT ai <模式> ...`：链路通，检查字段/数字是否合理。
- 只有 `start_route/route`：生成未开始或卡在加载。
- 有 `fail=...`：模型/解析/内存问题，按错误信息排查。
- 8 分钟无结果：看 `dumpsys window | grep mCurrentFocus` 是否仍在雅思。
