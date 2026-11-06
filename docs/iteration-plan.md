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

## 进行中

> 2026-11-07 UI/路由复审全部收口：跟读历史错页、Task 类型丢失、精讲跨套串讲、答题旋转丢进度、
> 词典/音频主线程 IO、ShadowResult 加载态、BankResult 旋转重建、ShadowStudy 进度持久化、
> 复习页整轮快照 saveable。旋转/进程恢复链路已无已知缺口。

> 2026-11-07 数据层 / 音频链路复审收口：WavRecorder 采集失败自停并暴露错误码、文件打开先于
> 前台服务（失败不泄漏 AudioRecord/麦克风保活）、stop 与 release 解耦、worker 异常不再杀进程；
> 口语模考录音中禁止提交并提示「某段中断，请重录」；`ai_bank.json` 原子写 + 损坏隔离 `.corrupt`；
> 音频缓存按 assets 长度校验并 `.part` 后改名；词典整库拷贝加锁 + SQLite 头校验。
> TTS `synthesizeOne` 超时/取消时补 `invokeOnCancellation → shutdown`（重试 3 次不再累积 3 个引擎绑定）。

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
- 追问、错题变式、写作换题；提纲/范例/生词/变式缓存（含模型维度）；格式类失败自动重试一次。
- `StudyAiCache` 解析记忆化（按存储串内容）；8 题出题档 `max_tokens=8000`。

**音频**
- 回放单实例、倍速、进度、拖动、切后台暂停；写作口述也可回放。
- 录音清理：引用集并入对练 turns；`SessionDao.audioPaths()` 只读 DISTINCT；保留期判定抽 `RecordingCleanup`。

**备份**
- 导出：补 AI 套题/关键设置/`task`/`audioPath`；NaN 归零；文件名带条目数；撞名 `uniqueName`；写后自检。
- 分享最近备份（FileProvider 仅暴露 backup）；列表（单删/展开）；清理旧备份（保留 5）；存储占用含备份。
- 导入（只读）：`BackupRestore.parse/keepNew/summary` + SAF 预览 + 按本机数据去重显示；去重键契约测试。
- 导入（纯逻辑）：`BackupImporter.mergeAiSets / parseSettings / modelWarning`。

**工程**
- 主线程 IO 扫描（2026-11-07）：写作/模考页图表改为 assets 异步解码（`rememberAssetImage`），
  不再把 PNG 解码塞进首帧；设置页「清空所有数据」的删录音/范文移入 IO 线程。
- `CLAUDE.md`、`tools/verify_all.sh`、验收清单、archify 图、复盘流程；compileSdk 35 + lifecycle 2.8.7。

## 判断标准

- 每一步落地后跑 `bash tools/verify_all.sh`（单测 + lint + AI 探针），全绿才算完成。
- 涉及 UI 的步骤，若当期不能上机，必须在 `docs/acceptance-checklist.md` 写清「看什么、怎么判」。
- 当前基线：单测 **477 例**、lint 0 error / 4 warning、探针 21/21、打包通过（`verify_all` EXIT=0）。
