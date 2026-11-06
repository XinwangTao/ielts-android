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

---

# 九、2026-11-04 15:00 后：后台评分已证（负面）+ 卡死自救已实施

> 本节补上 §三 未证项。所有结论用 `/proc/<pid>/stat` 的 utime+stime 差值、cgroup 归属、
> 窗口可见性三件硬证据，不用 `top` 的 %CPU、不用线程 S 态。

## 9.1 结论

**在 OPPO PKJ110 / ColorOS 上，端侧评分只要 App 窗口不可见（熄屏 / 按 HOME / 窗口 stopped），就必然停摆。**

| 观测 | 前台 | 不可见 |
|---|---|---|
| cgroup | `cpuset:/foreground cpu:/foreground` | `cpuset:/background cpu:/background` |
| `/proc/PID/stat` utime+stime / 10s | 数百 jiffies | **0** |
| `local gen done` | 70–90 秒出现 | 数分钟不出现，`pending_local` 常驻 |

触发条件不是"后台"这个笼统概念，而是**窗口不可见**。实测三次：
- 15:02 按 HOME：150 秒内 utime 增量为 0、无完成；`am start` 拉回前台后 **57.3 秒**才跑完
  （`601 tokens, 57331206us`）。
- 15:13 按 HOME：连模型加载都卡死（RSS 停在 656MB）；回前台 RSS 立刻跳到 1.86GB，
  但此后 5 分钟 utime 仍为 0、无任何输出 —— **回前台也不一定救得回来**。
- 15:35 冷启动跑 e2e：2 秒后日志出现 `VRI[MainActivity]: setWindowStopped stopped:true`，
  cgroup 随即变 `/background`，utime 增量 0，whisper 转写都跑不完。

设备侧开关都试过：`dumpsys deviceidle whitelist +`、`settings put secure no_frozen`、
`settings put global cached_apps_freezer disabled`。第三个能让 cgroup 变成 `/foreground`，
**但模型加载仍会卡在半途** —— 说明 OPPO 在自己的层还有一套冻结/限速，公开 API 改不动。

## 9.2 已实施的代码修复（面向"失败也要能自愈"）

问题不是"慢"，是**静默永久卡死**：`loaded()` 的 `mutex` 与 `generate()` 的 `generationMutex`
都是**跨阻塞 native 调用持有**的。进程被冻在 `s.load()` / `s.submit()` 里时协程永不返回，
锁永远不释放 → 之后每次评分都在等锁、CPU 为 0、`pending_local` 常驻，
**只有重启 App 能恢复**。

改为（`llm/LocalLlmEngine.kt`）：

1. 两把 `Mutex` 换成原子闸门 `engineBusy` + `acquireEngine()`（有 10 分钟等待上限，超时报错而非静默等待）。
2. 新增 `lastBeatAt` 心跳：加载开始/加载完成/**每收到一段 token** 都刷新。
3. 新增 `forceReset()`：丢弃 session 并把闸门打开，让下一次评分重新加载模型。
   故意不对可能仍在 native 里跑的 session 调 `release()`（会崩），宁可泄漏那段内存。
4. `ScoreService` 增加看门狗 `watchdogLoop()`：心跳断 180 秒即判死 → `forceReset()` +
   会话退 `pending_eval`（可重试）+ 发「系统把后台评分中断了」通知 + 替卡死协程收尾。

## 9.3 真机验证（15:34）

```
15:34:04.680 W ScoreService: watchdog: engine stalled 272407ms, forceReset id=18
15:34:04.726 W LocalLlm: forceReset: state=generating idle_ms=272472
```
会话 18 从 `pending_local`（永远卡住）变成 `pending_eval`（可重试），符合预期。

## 9.4 产品结论（给用户的话）

- 想拿到分数，**评分期间别熄屏、别切后台**（文案已在 `LocalWait.retryLabel` 提示）。
- 真被打断也不会再"永远评分中"：最多 3 分钟会变成「评分被中断，重新评分」，点一下即可。
- 想后台也能跑，需要系统级白名单（设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台运行），
  本机实测未必生效，不建议当作可靠路径。

---

# 十、2026-11-05：口语评分「缺少 P」整次作废（已修）

## 10.1 现象

OPPO 真机（session 21）本地口语评分完成一次模型生成后，通知显示
`本地评分失败 / 评分缺少 P：{...}`，会话停回 `pending_local`。模型返回了完整 JSON，
但漏了 `P`（发音）字段；`ScoreSanitizer.toSpeakingEval` 对该字段做硬校验，缺项直接抛错，
导致整次评分作废、用户白等一下午。

## 10.2 修复

- `ScoreSanitizer.toSpeakingEval` 增加别名 `P / PR / pron / pronunciation / pronunciation_score`；
  仍缺时按 `(FC + LR + GRA) / 3` 估算 P，并通过 `SpeakingEval.estimatedP` 单独标记；
  `ScoreService` 把标记写进 `fixesJson.metrics.p_estimated`，结果页在发音说明处显示
  「本次模型未单独给出 P，已按…估算」。**不写进 fixes**——fixes 会进「高频问题」统计，
  把模型漏项算成学生「发音（估计）」弱点是错误归因。
- 本地与云端口语提示词都加了一句：
  `All five score keys (FC, LR, GRA, P, overall) are mandatory. Never omit P.`
- 单测：`ScoreSanitizerTest` 新增「缺 P 估算」「PR 别名」两例，16/16 通过。

## 10.3 真机回归

装新 APK 后 force-stop 重启，启动清理把 session 21 重新排队并完成：
`Band 7.0 / FC 7.0 / LR 7.0 / GRA 6.5 / P 7.5`，通知与结果页正常。
说明提示词约束 + 缺项兜底生效；P 本次由模型给出，不是估算值。

---

# 十一、2026-11-06：后台推理不再中断——mediaPlayback Guard（已验证）

## 11.1 机制

`Guard.kt` 新增 `GuardService`：`mediaPlayback` 类型前台服务 + 8kHz 静音 AudioTrack 循环，
用引用计数包住 `LocalLlmEngine.generate` / `preload`（模型加载 + 推理整段）。ColorOS 对媒体型
前台服务的优先级高于 dataSync FGS，窗口不可见时不再把进程降到 background cgroup。

## 11.2 真机验证（OPPO PKJ110）

- 13:35:21 启动 `e2e_ai=outline`，模型开始加载；
- 8 秒后 `input keyevent 3` 回桌面（App 窗口不可见）；
- 13:35:55 日志 `local gen done: 354 tokens, 23763489us`，`E2E: RESULT ai outline stance=153 args=3 phrases=5 raw=false`；
- 全程未回前台，生成完整完成。

这修正了 §9 的绝对结论：加上 Guard 后，**AI 生成链路可以在后台完成**。
评分链路共用 `LocalLlmEngine.generate`，并额外有 ScoreService 的 dataSync FGS，预期同样受益；
`score_writing` 后台 e2e 待补。

## 11.3 边界

- `Guard.start` 走 `startForegroundService`；Android 12+ 后台启动受限，生成由前台用户操作触发时可以正常启动。
  启动失败会静默回退到「评分时保持 App 可见」。
- `GuardService` 自带 15 分钟兜底超时；引擎单次生成上限 8 分钟，正常不会撞上。
- 静音 AudioTrack 构建失败时静默降级，不阻塞主流程。

## 11.4 边界修正：OPPO 自家 power analyzer 仍会冻结长任务（2026-11-06 13:38 实测）

紧接着做 `score_writing` 后台测试（同样的 Guard）：
- 13:38:39 启动，模型开始加载；8 秒后 HOME；
- 100 秒后仍停在 `loading local model`，RSS 2.2GB；
- cgroup 是 **`/foreground`**（Guard 生效），但 `/proc/PID/stat` 的 utime+stime 5 秒内 **完全不变**；
- logcat：`OGuardManager_AppPowerAnalyzer: read abnormal_task pid:3079 uid:10309`。

结论：**mediaPlayback Guard 只能保住 cgroup，保不住 OPPO 自己的冻结层**。短任务（提纲 23.8s）在被标记前能跑完；
长评分（加载 + 1-3 分钟生成）会被 `OGuardManager` 识别为 abnormal task 并停 CPU。应用代码无法覆盖这一层。

可落地对策（已在设置页加入入口）：
1. 系统「电池优化」白名单：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（设置 → 后台评分 → 允许后台运行）。
2. ColorOS 自家白名单：设置 → 电池 → 应用耗电管理 → 雅思随身练 → **允许后台运行 / 允许自启动 / 允许关联启动**。
3. 未开白名单时，长评分请保持 App 在前台（KeepScreenOn 已生效）；后台被中断后由看门狗退 `pending_eval`，回前台可重试。

因此产品文案不能承诺「任何情况下后台不中断」；准确说法是：**短 AI 任务后台通常可完成，长评分建议前台或开启系统白名单**。

