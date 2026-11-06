# 迭代计划

> 2026-11-06。本阶段禁止手机端实测；必须上机才能验证的条目统一记在
> `docs/acceptance-checklist.md` 第八节，不在这里等。

## 主线

把近期新增的 AI / 音频能力做到「可交付」：**失败有原因（可观测）、能重试（可恢复）、有判据（可验证）**。

## 步骤（按顺序）

1. [x] **录音缺失可见化**（2026-11-06）：`RecordingPlayer` 播放失败时只静默回退，用户看到「点了没反应」。
       现在按钮变「录音不可用」，行内提示「录音文件已不存在（可能被清理）」。对应清理误删场景的兜底反馈。
2. [x] **备份导出数值健壮性**（2026-11-06）：范文 `band` 为 NaN/Inf 时 `JSONObject.put` 会抛异常，
       整次导出失败并误报「存储不可写」。现在非有限值归零，其余照常导出。
3. [~] **备份导入/恢复**：图已出（`docs/archify/ielts-backup-restore_workflow.{html,json}`）；
       **不写库的部分已完成**：`BackupRestore.parse / keepNew`（版本校验、坏条目跳过、按四元组去重预演，5 例单测）。
       真正写库仍**待确认**；确认点：冲突策略、设置是否覆盖、单事务还是分批。
4. [x] **依赖与 targetSdk 评估 + Stage A**（2026-11-06）：lifecycle 已升 2.8.7 并通过构建；结论在 `docs/android-upgrade-notes.md`——
       可先做 lifecycle patch；compileSdk 35 无 AGP 阻碍；**16KB 页对齐静态验证通过**（8 个 .so 全 16384）；
       targetSdk 35 仍待真机回归（edge-to-edge、FGS、安装加载）。
       **Stage B 前置**：本机只装了 android-34 / android-36，缺 android-35；AGP 8.7.3 最高支持 35，
       因此「compileSdk 35」需先 `sdkmanager "platforms;android-35"`（联网下载），暂记为待办。
5. [ ] **真机验收**：见 `docs/acceptance-checklist.md` 第八节（约 25 项）。

## 判断标准

- 每一步落地后跑 `bash tools/verify_all.sh`（单测 + lint + AI 探针），全绿才算完成。
- 涉及 UI 的步骤，若当期不能上机，必须在 `docs/acceptance-checklist.md` 写清「看什么、怎么判」。
