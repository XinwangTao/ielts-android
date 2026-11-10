# 迭代计划

> 2026-11-07。本阶段禁止手机端实测；必须上机才能验证的条目统一记在
> `docs/acceptance-checklist.md` 第八节，不在这里等。复盘流程见 `docs/retro-routine.md`。

## 主线

把近期新增的 AI / 音频 / 备份能力做到「可交付」：**失败有原因（可观测）、能重试（可恢复）、有判据（可验证）**。

## 可离线队列

1. [x] **CI 自动回归**（2026-11-07）：`.github/workflows/android-ci.yml`——push/PR 自动跑单测 + lint，
   装 `platforms;android-35` 与 build-tools，失败上传报告。配套 `.github/dependabot.yml`（Gradle 周更 / Actions 月更）。
   **未推送**（推送需用户确认后 CI 才会首次运行）。

暂无其他安全项；无设备期间不新增低价值 UI 改动。
（2026-11-07 第 85 轮复盘确认：5 个提交（对练加固/验收 runbook/冒烟脚本+真机实跑/正文长度兜底/
批次 0 脚本）；回归 499 例 / lint 2 warning / 探针 21/21。
设备 PKJ110 已在线但前台常被微信占用：脚本默认不抢屏，空闲时跑 `bash tools/e2e_batch0.sh`。）

## 进行中

> 2026-11-07 UI/路由复审全部收口：跟读历史错页、Task 类型丢失、精讲跨套串讲、答题旋转丢进度、
> 词典/音频主线程 IO、ShadowResult 加载态、BankResult 旋转重建、ShadowStudy 进度持久化、
> 复习页整轮快照 saveable。旋转/进程恢复链路已无已知缺口。

> 2026-11-07 数据层 / 音频链路复审收口：WavRecorder 采集失败自停并暴露错误码、文件打开先于
> 前台服务（失败不泄漏 AudioRecord/麦克风保活）、stop 与 release 解耦、worker 异常不再杀进程；
> 口语模考录音中禁止提交并提示「某段中断，请重录」；`ai_bank.json` 原子写 + 损坏隔离 `.corrupt`；
> 音频缓存按 assets 长度校验并 `.part` 后改名；词典整库拷贝加锁 + SQLite 头校验。
> TTS `synthesizeOne` 超时/取消时补 `invokeOnCancellation → shutdown`（重试 3 次不再累积 3 个引擎绑定）。
> WhisperAsr：decode/取结果抛异常也释放 native stream；同实例 load/transcribe/release 串行化。
> 已知长尾（待真机）：两个页面同时转写会并发跑两个 recognizer（约 2× 内存），是否加全局串行门
> 需要先看真机后台内存与等待体感再定。

## 长尾离线池

- **AI 任务状态未按会话/生命周期归属**：`stage`、`error`、各 AI busy/结果都是 Activity 级 `remember`。
  ①已修：新增 `activeSessionId`，结果页只显示属于本场的 busy/error（A 评分时 B 页不再显示 A 的文案）。
  ②已修（2026-11-10）：新增 `AiTaskViewModel`，提纲/范例/生词/追问/换题/复盘/出题/精讲/变式的状态与
  生成任务都挪到 ViewModel + `viewModelScope`，旋转屏不再取消进行中的本地生成；10 处入口改 `ai.launch`。
  真机复验通过：生成中旋转 → 无干扰等 100 秒 → 提纲正常回写，PID 不变。
  复验同时抓到旋转引发的 FGS 超时崩溃（重建后重复预热），已修：`preload` 在模型已加载/引擎非 idle 时
  直接返回，不再无意义启停前台服务（`e83a60c`）。
  进程被杀仍会丢（ViewModel 不跨进程），要跨进程需另做持久化，不在本轮范围。

- **今日新卡额度计数不准**：`countFirstLearnedSince` 用 `reps=1 AND lastReviewed>=今天` 代理，
  会把「昨天忘掉、今天重新评」的旧卡算成新卡，也漏掉「今天新学但评了 Again」的卡。
  ①已修（2026-11-10）：Card 新增 `firstLearnedAt`，评级时经 `FirstLearned.next` 只在首次写入；
  DB v4 + 迁移 3→4（加列、旧卡按 lastReviewed 回填）；备份导出/导入兼容旧文件。
  单测 5 例、schema 4.json 已导出。**真机迁移已验**：旧库升级后 `user_version=4`、启动与首页正常；
  设备上暂无已学卡（`reps>0` 为 0），「旧卡重评不算新卡」由单测锁住。

## 阻塞（写清解除条件，不在本阶段空等）

1. **备份导入写库**（L1）
   - 解除条件：确认三项决策（冲突策略 / 设置是否覆盖 / 单事务还是分批）。
   - 就绪度：图 `docs/archify/ielts-backup-restore_workflow.{html,json}`；实现设计
     `docs/backup-import-implementation.md`；纯逻辑已落地 `BackupImporter.mergeAiSets / parseSettings / modelWarning`
     （7 例单测）。确认后可照单接线。
2. **Stage C（targetSdk 35）**
   - 解除条件：真机回归 edge-to-edge、前台服务、安装与 MNN/Whisper 加载。
   - 就绪度：`compileSdk 35` 已就位（targetSdk 仍 34），16KB 对齐静态验证通过，迁移步骤见
     `docs/android-upgrade-notes.md`。
3. **推送 CI / Dependabot**
   - 解除条件：用户确认推送（本机规则：推送属对外动作）。
   - 就绪度：两份 YAML 已校验，workflow 内命令与 `verify_all` 一致。
4. **真机验收约 75 项**
   - 状态：已授权 adb 无线调试（2026-11-07），设备稍后接入；已备好分批 runbook，
     接入后照单执行即可，不再现场整理。
   - 入口：`docs/device-acceptance-runbook.md`（连接/安装/日志/失败注入命令 + 4 个批次 + 判据），
     结果回填 `docs/acceptance-checklist.md` 第八节。

## 已完成（按主题，不按提交顺序）

**后台与评分**
- 三轮核心链路评审修复（2026-11-07）：迟到失败不再清空已出分记录（条件更新 + done 守卫）、同会话单飞、
  引擎 epoch 代次防旧协程复活、等闸门按心跳判活、Guard 引用计数对称、看门狗不再掐其它 job、
  START_STICKY 恢复带看门狗；云端重试只对格式异常；partial wake 20 分钟。
- 录音缺失可见化：播放失败显示「录音不可用 / 文件已不存在」。
- 评分 watchdog 按状态分阈值；失败原因存盘（并行会话）。

**AI**
- AI 界面状态复审（2026-11-07）：7 项发现修 5 项——①换一批生词后 `vocabAdded` 复位（按钮不再永久禁用）
  ②精讲失败按「作用域#题号」显示（不再写全局 error 跑到无关结果页，题库页也有提示）
  ③追问/换题入口加 busy 守卫，跨场点击不再改写 sessionId、不再排队白等
  ④跨场 busy 显示「本地模型正忙」而不是一个无理由的死按钮
  ⑤窄屏题库结果页旋转/进程重建改为按 session 重建（不再踢回列表）。
  另：AI 出题 8 题档模型偶发漏 answers/explain_en（两次探针形态不同），
  `generateReading/Listening` 增加一次完整性重试并取更完整的一份（同 client 不重复扣额度）；
  探针本身单发，不作为产品降级依据。
  未修（需 ViewModel 化）：评分 busy/error 仍为全局 `stage/error`，A 篇评分时 B 篇结果页会显示 A 的阶段文案；
  旋转时在跑的本地生成会丢失（协程随组合取消）。见长尾池。
- llm 层独立复审（2026-11-07）：7 项发现全部修复——①`JSONException` 视为格式失败（此前真实坏 JSON
  不重试直接掉本地）②额度按逻辑请求只扣一次（重试不再重复扣到上限）③云端分数封顶 9.0
  ④1210 降级不再把 8000 预算压到 6000（`maxOf`）⑤ScoreService 的失败/完成记录走 `editJson`
  ⑥兜底解析不再强制 overall（调用方本就重算）⑦云端评分复用 `parseObject` 字段级兜底。
  新增 3 例单测（JSONException 重试、9.0 封顶、漏 overall 可救回）。
- 设置项并发写（2026-11-07）：`Prefs.editJson` 串行化 JSON 读改写；`studyAiCache`、`localScoreStarted/Errors`、
  `bankProgress`、`mutedFixes/vocabEdits` 的多线程落盘不再互相覆盖（两个评分/两次生成同时完成时，
  后写者不再丢前者的条目）。
- 追问、错题变式、写作换题；提纲/范例/生词/变式缓存（含模型维度）；格式类失败自动重试一次。
- `StudyAiCache` 解析记忆化（按存储串内容）；8 题出题档 `max_tokens=8000`。

**音频**
- 跟读对比算法抽纯函数（2026-11-07）：`ShadowCompare` 从 `ShadowRepo` 抽出并补 7 例单测；
  分词改用 `\p{L}\p{N}` 并统一弯引号——此前 café 会被切成 caf、don’t 会变成 don+t，
  读对也判漏读。算法本身（LCS/准确率分母）核对无错。
- 回放单实例、倍速、进度、拖动、切后台暂停；写作口述也可回放。
- 录音清理：引用集并入对练 turns；`SessionDao.audioPaths()` 只读 DISTINCT；保留期判定抽 `RecordingCleanup`。

**备份**
- 导出：补 AI 套题/关键设置/`task`/`audioPath`；NaN 归零；文件名带条目数；撞名 `uniqueName`；写后自检。
- 分享最近备份（FileProvider 仅暴露 backup）；列表（单删/展开）；清理旧备份（保留 5）；存储占用含备份。
- 导入（只读）：`BackupRestore.parse/keepNew/summary` + SAF 预览 + 按本机数据去重显示；去重键契约测试。
- 导入（纯逻辑）：`BackupImporter.mergeAiSets / parseSettings / modelWarning`。

**安全**
- 系统备份排除敏感/大体积数据（2026-11-07）：`allowBackup=true` 但没有规则时，云备份/换机会把
  `ielts_prefs`（含 API Key）和 `files/models`（数 GB）一起带上。新增 `backup_rules.xml` +
  `data_extraction_rules.xml`：排除 prefs、filesDir 全部、external 备份目录，只留 Room 数据库。
  行为变化：换机恢复后需重新填 API Key/设置；AI 套题/范文走应用内 JSON 导入。
- 主线程 IO 扫描（2026-11-07）：写作/模考页图表改为 assets 异步解码（`rememberAssetImage`），
  不再把 PNG 解码塞进首帧；设置页「清空所有数据」的删录音/范文移入 IO 线程。
- `CLAUDE.md`、`tools/verify_all.sh`、验收清单、archify 图、复盘流程；compileSdk 35 + lifecycle 2.8.7。

**工程**
- 真机批次 0 抓到的崩溃（2026-11-07）：预热在主线程阻塞，`GuardService` 5 秒内来不及
  `startForeground`，系统抛 `ForegroundServiceDidNotStartInTimeException` 杀进程（PKJ110 实机日志）。
  `LocalLlmEngine.preload` 改为 `withContext(Dispatchers.IO)`，主线程保持可响应；
  已重新打包安装，批次 0 实机复验通过（通知出现→返回消失→无崩溃）。
- 模考结果聚合（2026-11-07）：①Task1/Task2 只有一段出分时不再把单篇当「模考总分」，
  显示「未全部出分，暂不合成」；②老记录无 task 字段才按顺序回退，避免 Task2 被当两次使用；
  ③非法 Band（0/10）不再显示为成绩（与 ScoreGate 同口径）；④记录被删时显示明确空态。
  合成逻辑抽 `MockScore` 并补 3 例单测（总 502）。
- 批次 0 免点击脚本（2026-11-07）：`tools/e2e_batch0.sh` 用 `route` 深链直达写作/口语页，
  加载窗口内轮询 `dumpsys notification` 判定预热通知，并查前台与崩溃缓冲；
  默认检测前台 App，用户正在用别的应用时退出 3（不抢屏），`--force` 才强跑。
  已在手机上验证「占用检测」路径（前台为微信/飞书时脚本正确拒绝执行）。
  2026-11-07 修正：通知判定改用 ASCII 通道名 `pkg=com.txw.ielts…channel=guard`
  （中文标题在无线 adb 下会被截断/转义，此前造成假失败）；无通知权限时回退看
  `loading local model` 日志；补「返回后通知消失」判据，并对仍在加载的情况只记录不判失败。
  同日实机复验又抓到脚本自身 bug：`set -o pipefail` 下 `printf 大字符串 | grep -q` 会因 grep
  提前退出把 printf 打成 SIGPIPE(141)，整个管道判失败 → 明明有通知却报「未显示」。
  改为 here-string（`grep -q … <<< "$VAR"`），`e2e_smoke.sh` 的崩溃检查同坑一并修。
  复验通过：通知出现 → 返回后消失 → 口语页直达 → 崩溃缓冲为空。
- 出题正文长度兜底（2026-11-07）：探针复跑发现模型偶发给出 92 词正文（要求 300–550）；
  `generateReading/Listening` 把正文长度并入完整性判定，不足时重发一次，
  并用 `overallScore`（题数完整度优先、其次正文长度）在两次结果中取更完整的一份；
  探针同步对「词数=」失败重试一次。
- 真机冒烟脚本（2026-11-07）：`tools/e2e_smoke.sh` 一条命令完成安装 → 冷启动 → 截图 → 日志 →
  首页渲染/崩溃检查；自动跳过历史遗留的非 Android TCP 端点（曾误选电脑 ICS 地址导致假失败），
  Git Bash 下关闭路径转换再读 `/sdcard`。已在 PKJ110 实跑通过（冷启动 485–536ms）。
- 真机验收 runbook（2026-11-07）：把验收清单第八节拆成 5 个批次（冒烟/核心 AI/录音回放/数据备份/失败注入），
  每批写清操作与判据，并附无线连接、安装、logcat、坏数据与只读存储的注入/恢复命令；
  设备接入后按批次执行并回填勾选。
- 口语对练链路（2026-11-07）：核对录音启动/转写失败/追问回退/退出清理均有提示或保底；
  修两处：①「结束并评分」是异步回调，连点会插入两条会话 → 加 `finishing` 锁，保存失败时解锁可重试；
  ②`onFinish` 写库失败会抛到协程外 → 捕获并在本页提示「保存失败，本页对话仍在」；
  另 `begin()` 重置累计秒数，重开一轮不再带上一次时长。
- 复习页失败/去向（2026-11-07）：空队列有 `QueueNotice`、评完/中途退出/删空都回语料页并带本轮小结
  （核对无缺口）；唯一缺口是删卡写库失败会抛到协程外 → 捕获后提示「删除失败」，不崩、不假装删成功。
- 跟读结果空/坏态（2026-11-07）：逐句结果改为逐条兜底（一条坏数据不再让整页变空）；
  总分缺失/越界时按逐句准确率均值重算，不再一律 0%；无逐句结果时显示「记录可能不完整，可重练」。
- AI 出题失败可见性（2026-11-07）：①听力 TTS 合成失败原来设置 `aiGenError` 后立刻跳进答题页，
  提示无处显示 → 新增 `quizNotice` 带到答题页顶部；回首页再进入也按 setId 暂存；
  ②`saveAiSet` 写盘失败被外层 catch 统一报成「生成失败」→ 单独提示「生成成功但保存失败」并清理音频文件。
- 约定自动化（2026-11-07）：`tools/check_prefs_rmw.py` 扫描 `prefs.x = f(prefs.x, …)` 且未包
  `editJson` 的写法，接入 `verify_all` 第 3/5 步与 CI；用参数位置区分「读改写」和「旧值兜底」
  （`snapBand(input, prefs.x)` 不误报），并用临时文件做过正/负向验证。
- 首页四科口径（2026-11-07）：写作/口语平均改用 `ScoreGate.isValidBand`（0.5–9.0）过滤，
  与首页单条记录同口径，10 分坏记录不再拉高均分；听读正确率把 correct 夹在 0..total，
  坏数据不再算出 >100%。新增 2 例单测（总 495）。
- 历史听读结果一致性（2026-11-07）：①历史会话不存文章/听力稿，按标题回原始套题（手写或 AI）补上
  原文与听力稿；②旧记录缺 `correct` 时按每题对错重算，不再一律显示 0 分；
  ③旋转/进程恢复路径同样带原始套题重建。
- 结果页未答展示（2026-11-07）：未作答与答错同按错计分，但头部补「其中 N 题未作答」，
  每题正文已显示「（未作答）」；空套题（total=0）不再显示「全对」。
- 答题提交链路复审（2026-11-07）：①限时自动交卷与手动点击可能同时触发（两个标志位）→ 统一 `submitting` 锁；
  ②交卷写库异常会让协程异常直接杀进程 → try/catch 留在答题页提示、答案保留、按钮解锁；
  ③套题被删后交卷静默无反应 → 明确提示「已被删除」；④用时夹在 0..24h，避免进程重建后写入荒唐时长。
- 答案判分归一化（2026-11-07）：`AnswerKey` 补三类输入法/书写差异——千分位逗号（1,000 = 1000）、
  重音字母（café = cafe，NFD 去音标）、弯/直/省略撇号（don’t = dont）；全角折算逻辑不变。
  新增 3 例、改写 1 例单测（总 493）。
- 静默失败扫描（2026-11-07）：①写作模考评分异常会把用户送回首页、错误文案无处显示 → 留在模考页展示原因；
  ②设置页「清空所有数据」即使清库抛错也提示成功 → 区分成功/失败并给出原因。
  ③答题页听力音频「继续播放」失败仍显示正在播 → 复位并提示「请点重播」；倍速不支持也提示。
  ④结果页录音恢复播放失败仍显示「暂停录音」→ 标为不可用并释放播放器。
  其余候选（录音/TTS 探测）属可选能力，失败已有降级或提示，不强行加弹窗。
- Learn/词汇/首页独立复审（2026-11-07）：8 项发现修 7 项——①同卡本轮二次评级按库里最新状态算 SM-2
  ②淘汰池排除错题/跟读/查词卡（不再静默删用户资产）③AI 生词卡重复加入时更新释义/例句
  ④到期时间落本地日界线（「明天」不再等于 24 小时后）⑤复练提醒按自然日
  ⑥长批注判重用完整原文（同前缀不再误吞）⑦首页连续天数/距考天数跨午夜自动刷新
  （首页与主界面各加每分钟日期 ticker）。未修：今日新卡计数代理（需 v4 加 `firstLearnedAt` 列 +
  真机迁移验证，见长尾）。
- Room 结构快照（2026-11-07）：`exportSchema=false` 改为导出到 `app/schemas/`，v3（sessions 13 列 / cards 15 列）
  入库成基线；今后改表必须配 Migration，并可用 MigrationTestHelper 做迁移测试（需 androidTest，暂不跑）。
- lint 基线收敛（2026-11-07）：4 warning → 2。电池优化直连对话框按既有设计保留（不上架 Play，
  `@SuppressLint("BatteryLife")` 注明原因与 fallback）；只打 arm64，关闭 ChromeOS ABI 提示。
  剩余：OldTargetApi（等 Stage C）、core-ktx 可升级（1.17 需 compileSdk 36，随 Stage C 一起动）。
- 设置项并发写（2026-11-07）：`Prefs.editJson` 串行化 JSON 读改写；`studyAiCache`、
  `localScoreStarted/Errors`、`bankProgress`、`mutedFixes/vocabEdits` 的并发落盘不再互相覆盖。

## 判断标准

- 每一步落地后跑 `bash tools/verify_all.sh`（单测 + lint + AI 探针），全绿才算完成。
- 涉及 UI 的步骤，若当期不能上机，必须在 `docs/acceptance-checklist.md` 写清「看什么、怎么判」。
- 当前基线：单测 **502 例**、lint 0 error / 2 warning、探针 21/21、打包通过（`verify_all` EXIT=0）。
