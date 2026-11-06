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
2. [x] **导入预览（只读）**（2026-11-06/07）：设置页 SAF 选文件 → `BackupRestore` 解析/摘要/去重 → 仅展示；
   仍不写库（写库见「阻塞」）。
4. [x] **备份保留策略**（2026-11-06）：设置页「清理旧备份（保留最近 5 个）」，二次确认后按修改时间删除更早的 JSON；
   判定抽成纯函数 `BackupFiles.selectDeletionCandidates`（3 例单测，含 keep=0/数量不足边界）。
3. [x] **导出体验**（2026-11-06）：设置页新增「分享最近备份」——FileProvider（`${applicationId}.fileprovider`
   + `res/xml/backup_paths.xml`，只暴露 external-files/backup）把最新 JSON 交给系统分享；真机行为记入验收清单。

## 长尾离线池（队列清空时从这里挑，低风险优先）

1. [x] **备份列表 UI**（2026-11-06）：设置页按修改时间列出备份（名称 + KB），单条「删除」带确认；
   导出/清理/删除后自动刷新。
2. [x] **导出文件名可读化**（2026-11-06）：`ielts_backup_<时间>_w<练习数>_c<卡片数>.json`。
3. [x] **导入预览入口（只读）**（2026-11-06）：设置页「预览备份文件（只读）」→ 系统文件选择器 →
   `BackupRestore.parse/summary` 只显示「将新增 N 条练习…」；**不写库**，写库仍待确认。
4. **剩余弃用**：系统栏颜色迁移随 Stage C；`SCREEN_DIM_WAKE_LOCK` 等真机验证后再评估替代。
11. [x] **预览去重**（2026-11-07）：预览时对照本机现有数据（新增两条只读 DAO 查询 `dedupeKeys()`，
    与 `BackupRestore.sessionKey/cardKey` 同格式），显示「真正会新增多少 / 本机已有多少将跳过」。
10. [x] **导出自检**（2026-11-07）：写完立刻用 `BackupRestore.parse` 回读校验；解析失败或条目数不符则删文件并报错，
    不再留下「看起来成功」的坏备份。
9. [x] **撞名逻辑抽纯函数**（2026-11-07）：`BackupFiles.uniqueName`（`a.json`→`a_2.json`，无扩展名也可），
   `Backup.export` 改为传入目录现有文件名集合；1 例单测，总 **462 例**。
8. [x] **备份列表展开**（2026-11-06）：超过 10 个时提供「显示全部 / 收起」，不再静默隐藏。
7. [x] **预览信息补全**（2026-11-06）：摘要增加「设置 N 项」；预览结果带文件名，便于确认选对文件。
6. [x] **导出撞名防护**（2026-11-06）：同一分钟内重复导出自动加 `_2`/`_3` 序号，不再静默覆盖。
5. [x] **存储占用补备份**（2026-11-06）：设置页存储行加入「备份 N 个 / X MB」，导出/删除/清理后随
   `backupRefresh` 自动重算。

> 2026-11-07 复盘结论：主功能已收敛，7 个连续提交均为备份/设置小修；剩余价值集中在真机验收与导入写库。
> 无设备期间优先「抽纯函数 + 补单测」，不再新增 UI 开关。

## 阻塞（不在本阶段空等）

- **备份导入写库**（L1）：图 `docs/archify/ielts-backup-restore_workflow.{html,json}`；
  **实现级设计已就绪**：`docs/backup-import-implementation.md`（默认：新增不覆盖 / 设置默认不导入 / 单事务，
  含导入前自动快照与测试计划）。仍待确认三项决策后落地。
- **Stage C（targetSdk 35）**：需真机回归 edge-to-edge、前台服务、安装与 MNN/Whisper 加载。
- **真机验收 28 项**：`docs/acceptance-checklist.md` 第八节。

## 复盘

- 固定流程见 `docs/retro-routine.md`（六步：归零 / 回归 / 盘点 / 沉淀 / 更新计划 / 归档）。

## 判断标准

- 每一步落地后跑 `bash tools/verify_all.sh`（单测 + lint + AI 探针），全绿才算完成。
- 涉及 UI 的步骤，若当期不能上机，必须在 `docs/acceptance-checklist.md` 写清「看什么、怎么判」。
