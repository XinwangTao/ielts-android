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
（2026-11-07 第 70 轮复盘确认：4 个提交（静默失败/播放器/跟读算法/判分归一化）；回归 493 例 /
lint 2 warning / 探针 21/21。下一离线项：答题提交链路复审。）

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
  ②未修：旋转/进程重建时在跑的本地生成随组合取消，几分钟推理白跑（云端还可能重复点击扣额度）。
  正确做法是把进行中任务移到 ViewModel 或 SessionScoped 状态容器；解除条件：允许较大改动的重构窗口
  （需真机验证配置变更行为）。

- **今日新卡额度计数不准**：`countFirstLearnedSince` 用 `reps=1 AND lastReviewed>=今天` 代理，
  会把「昨天忘掉、今天重新评」的旧卡算成新卡，也漏掉「今天新学但评了 Again」的卡。
  正确做法是加 `firstLearnedAt` 列（v4 + 迁移），但迁移必须在真机/仪器测试里验证后再上；
  解除条件：允许 androidTest 或真机跑 MigrationTestHelper。

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
4. **真机验收约 34 项**
   - 解除条件：允许设备实测。
   - 入口：`docs/acceptance-checklist.md` 第八节（变式题 UI、回放、预热、清理/分享/预览等）。

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
- 当前基线：单测 **493 例**、lint 0 error / 2 warning、探针 21/21、打包通过（`verify_all` EXIT=0）。
