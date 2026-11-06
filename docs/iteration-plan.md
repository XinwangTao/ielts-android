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
   新增 1 例单测（不同存储串之间不串用），总 457 例全绿。

## 可离线队列（按顺序，下一项从这里挑）

1. **剩余弃用清理**：`Theme.kt` 的 `statusBarColor` / `navigationBarColor`（API 35 弃用）评估边到边适配；
   `Guard.kt` 的 `SCREEN_DIM_WAKE_LOCK` 属保活关键路径，**改动需真机验证，先不动**。
2. **导入预览 UI（只读）**：选文件 → 解析 → 显示「新增 N / 跳过 M」，不写库；可直接复用 `BackupRestore`。
   是否算 L1 流程的一部分需在实现前判断，默认先只做「数量预览」。
3. **导出体验**：导出后提供「打开备份目录」入口（FileProvider 或 DocumentsUI），需评估清单改动。

## 阻塞（不在本阶段空等）

- **备份导入写库**（L1）：图见 `docs/archify/ielts-backup-restore_workflow.{html,json}`；
  待确认三项：冲突策略（默认新增不覆盖）、设置是否随导入覆盖、单事务还是分批。
- **Stage C（targetSdk 35）**：需真机回归 edge-to-edge、前台服务、安装与 MNN/Whisper 加载。
- **真机验收 28 项**：`docs/acceptance-checklist.md` 第八节。

## 判断标准

- 每一步落地后跑 `bash tools/verify_all.sh`（单测 + lint + AI 探针），全绿才算完成。
- 涉及 UI 的步骤，若当期不能上机，必须在 `docs/acceptance-checklist.md` 写清「看什么、怎么判」。
