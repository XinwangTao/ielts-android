# 迭代计划

> 2026-11-06。本阶段禁止手机端实测；必须上机才能验证的条目统一记在
> `docs/acceptance-checklist.md` 第八节，不在这里等。

## 主线

把近期新增的 AI / 音频能力做到「可交付」：**失败有原因（可观测）、能重试（可恢复）、有判据（可验证）**。

## 已完成

1. [x] **录音缺失可见化**：`RecordingPlayer` 播放失败改为「录音不可用 / 文件已不存在（可能被清理）」。
2. [x] **备份导出数值健壮性**：范文 `band` 为 NaN/Inf 时归零，不再让整次导出失败。
3. [x] **导入的解析/预演层**：`BackupRestore.parse / keepNew`（版本校验、坏条目跳过、去重预演，5 例单测）；写库部分见「阻塞」。
4. [x] **依赖与 targetSdk 评估 + Stage A/B**：lifecycle 2.8.7；已装 `platforms;android-35`，`compileSdk = 35`（targetSdk 仍 34）；
   16KB 页对齐静态验证通过（8 个 .so 全 16384）；`LocalLifecycleOwner` 已迁 `lifecycle-runtime-compose`。
5. [x] **AI 缓存解析记忆化**（2026-11-06）：`StudyAiCache` 按存储串内容记忆解析结果，连续读取不再重复解析整串 JSON；
   新增 1 例单测（不同存储串之间不串用）。

6. [x] **导入预览摘要**（2026-11-06）：`BackupRestore.summary` 返回「将新增 N 条练习、M 张卡片、K 套 AI 题；跳过 X 条」，
   1 例单测。当前单测总数 **458 例**。

## 可离线队列（按顺序，下一项从这里挑）

1. [x] **剩余弃用评估**（2026-11-06）：系统栏颜色 → edge-to-edge 的迁移步骤与风险已写入
   `docs/android-upgrade-notes.md`「Edge-to-edge 迁移评估」；结论是**留到 Stage C 与真机一起做**。
   `Guard.kt` 的 `SCREEN_DIM_WAKE_LOCK` 属保活关键路径，同样等真机。
2. **导入预览（只读）**：纯逻辑部分已完成（`BackupRestore.summary`，返回「将新增 N 条练习、M 张卡片…」，
   1 例单测）；剩余是选文件与展示的 UI，属 L1 流程的一部分，待确认后再接。
4. [x] **备份保留策略**（2026-11-06）：设置页「清理旧备份（保留最近 5 个）」，二次确认后按修改时间删除更早的 JSON；
   判定抽成纯函数 `BackupFiles.selectDeletionCandidates`（3 例单测，含 keep=0/数量不足边界）。
3. [x] **导出体验**（2026-11-06）：设置页新增「分享最近备份」——FileProvider（`${applicationId}.fileprovider`
   + `res/xml/backup_paths.xml`，只暴露 external-files/backup）把最新 JSON 交给系统分享；真机行为记入验收清单。

## 阻塞（不在本阶段空等）

- **备份导入写库**（L1）：图见 `docs/archify/ielts-backup-restore_workflow.{html,json}`；
  待确认三项：冲突策略（默认新增不覆盖）、设置是否随导入覆盖、单事务还是分批。
- **Stage C（targetSdk 35）**：需真机回归 edge-to-edge、前台服务、安装与 MNN/Whisper 加载。
- **真机验收 28 项**：`docs/acceptance-checklist.md` 第八节。

## 判断标准

- 每一步落地后跑 `bash tools/verify_all.sh`（单测 + lint + AI 探针），全绿才算完成。
- 涉及 UI 的步骤，若当期不能上机，必须在 `docs/acceptance-checklist.md` 写清「看什么、怎么判」。
