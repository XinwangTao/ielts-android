# OPPO 后台评分：已证 / 未证 与真实根因

> 2026-11-04 汇总。证据来源：设备库快照 `F:/cache/mine/ielts.db`（10:21 拉取）、
> 分支报告 `55750adeefb9463a1d5d9e53`、本机代码。
> **本文修正了分支报告里「进程被 ColorOS 冻结（isFrozen=true）」的结论。**

## 一句话结论

口语评分链路**是通的**（id=14 完整跑完并落库）。观测到的「卡住」不是进程冻结，
而是 **评分被外部 `am force-stop` 打断 → 状态停在 `pending_local` → 结果页计时器无上限地数下去**。
叠加 `ScoreService` 没有超时、且 fixes 为空时会**再跑一次模型**，等待时间可达 5–8 分钟。

## 一、已证（有设备库落库证据）

| id | 模块 | 状态 | startedAt | 分数 | 说明 |
|---|---|---|---|---|---|
| 13 | speaking | `pending_local` | 10:17:59 | — | 评到一半被 force-stop |
| **14** | speaking | **`done`** | 10:19:58 | FC7 / LR7 / GRA6.5 / P7.5 / **overall 7** | **链路成功样例** |

对应的 `LocalLlm` 日志：`10:21:37 local gen done (qwen3-4b-instruct): 685 tokens, 70022852us`
→ 从 10:19:58 到 10:21:37 约 **99 秒**完成。

**因此「进程被冻结」不成立**：同一进程在 10:21:37 正常产出生成结果。
分支在 10:27 观测到的「34 线程全 S 态、CPU 0%」是**评分已经结束后的正常空闲态**。

补充：`top -b -n 1 -o PID,%CPU` 在 toybox 下是单次快照，长期报 `0.0`，
不能作为「无进展」的判据。

## 二、真实根因（代码可查）

1. **force-stop 之后 FGS 不会复活。** `ScoreService` 是 `START_STICKY`，但 force-stop
   会把应用置为 stopped 状态，系统不再重启其服务 → 该 session 永远停在 `pending_local`。
2. **结果页计时器无上限且基准会退化成录音时刻。**
   `ResultScreen.kt:61-71` 是 `while (true)`，基准为 `waitStartedAt`，
   为 0（force-stop / 重启后内存丢失）时回退到 `session.startedAt` **（录音起点，不是评分起点）**，
   于是「已等待 N 分」从录音开始一路数下去 —— 用户看到的就是「永久卡死」。
3. **`ScoreService.runScore` 没有任何超时。** 全仓库 `withTimeoutOrNull` 只出现在
   `MainActivity.kt:1088`（语音助手那条路），评分链路没有。
4. **单次提交最多跑两次模型。** `ScoreService.kt` 里 `eval.fixes` 为空时会再调一次
   `scoreWriting` / `scoreSpeaking`；单次生成实测 70–89 秒，加 2.7GB 模型加载，
   再叠 `generationMutex` 串行排队 → 5 分钟以上是可达的，而 UI 承诺「约 1–3 分钟」。
5. **有自愈，但门槛高。** `MainActivity.kt:333-365`：下次启动时（且引擎 idle），超过 10 分钟的
   `pending_local` 且**有文本**的会自动重排（最多 2 条，超出部分直接退 `pending_eval`），没文本的退回可重试。
   所以 id=13 会自愈，但需要「等 10 分钟 + 重新打开 App」，且 1.5 秒延迟内引擎状态必须是 idle。

## 三、未证（不要当成结论用）

- **后台评分（HOME 后）是否可行**：唯一成功样例 id=14 当时 App 在前台
  （分支正在 dump 结果页）。10:23 那次「HOME 后盯后台」没有产出任何 session 行，
  即**没有拿到后台完成的证据**。这是当前最大的未验证项。
- **ColorOS 是否冻结 FGS 进程**：无有效证据；分支在「待确认」处中断，
  且其测量窗口被三方并发 force-stop 污染。

## 四、观测窗口被三方争用（重要）

同一台设备 `192.168.237.175:32913` 在 10:01–10:27 被三个执行者交替
`am force-stop` / `am start`：用户本人、本会话、并行分支。
分支自己在 10:18:58 就记录过「很可能是另一会话同时 `am start` 抢了」。
**该窗口内任何「卡死/冻结」结论都不可信**，重测必须单执行者。

## 五、复测协议（单执行者、可判定）

1. `adb shell am force-stop` 后清 logcat，冷启动一次，不做其他任何 adb 操作。
2. 用自带入口跑口语全链路：
   `am start -n com.txw.ielts/.MainActivity --es e2e_wav <wav> --ez e2e_speaking true`
3. 只读 `E2E: RESULT speaking status=... scores=...` 这一行做判定：
   `status=done` 且 scores 非空 = 通过；`pending_local` + 空 scores = 失败。
4. 后台场景：`RESULT` 之前按 HOME，保持屏幕熄灭 5 分钟，回来读 DB 里该 session 的 status。
5. 判据不要用 `top` 的 %CPU 或线程 S 态。

## 六、云端通道现状
讯飞 `POST /v1/chat/completions` 实测 **HTTP 403 + code 11200**
（无有效订单/额度用尽）。因此 `channel=auto` 现在等价于「每次都先等一次必然失败的往返再回退本地」。
`LlmClient.friendlyHttpError()` 已把 11200/401/403/429 翻成可操作中文（提交 `514d4e5`）。

建议（未实施）：把 11200 这类「配额死亡」与「瞬时失败」分开，记粘性标记后本轮直接走本地。

## 七、部署链路（已交付的部分）

- 无线调试方法：Obsidian `Codex专区/安卓工具链.md` §51「OPPO 无线调试部署（2026-11-04 实测通过）」。
  关键坑：配对端口 ≠ 调试端口；手机 ping 通 ≠ 有监听端口（锁屏会停 adb-over-wifi）。
- 仓库部署文档：`docs/oppo-wireless-deploy.md`（提交 `514d4e5`），含 §7 结果表与性能数据。
- 未交付：`mnnchat` 本地大模型联调只产出观测结论，无代码改动。

## 八、待办

- [ ] `ScoreService` 给每次生成加 `withTimeoutOrNull`（建议 5 分钟/次），超时退 `pending_eval` 而非静默卡住。
- [ ] 结果页计时基准改用「评分开始时间」持久化字段，并对 `pending_local` 超过阈值给出明确「已中断，重新评分」。
- [ ] 验证后台评分（唯一成功样例是前台跑的）。
- [ ] `pending_local` 的自愈不要在启动时静默重排，应显式提示。
