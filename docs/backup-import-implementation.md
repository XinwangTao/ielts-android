# 备份导入写库：实现级设计（待确认后落地）

> 前置：`docs/archify/ielts-backup-restore_workflow.{html,json}` 已出图，等待三项确认。
> 本文只写“确认后怎么改代码”，不改任何实现，确保决策一来就能动手。

## 一、确认项与默认选择（确认时可逐条改）

| 决策 | 默认建议 | 影响 |
|---|---|---|
| 冲突策略 | **新增不覆盖**（id 重新分配，按 key 去重） | 不会破坏本机已有练习；重复导入不会翻倍 |
| 设置是否导入 | **默认不覆盖**，确认页勾选才应用 | 避免换机时误把旧目标分/模型覆盖 |
| 写入方式 | **单事务**（Room `withTransaction`） | 全成或全败；失败时界面直接报错，不留半套 |

## 二、代码改动清单（确认后照此落地）

1. **DAO**（`data/Session.kt` / `data/Card.kt`）
   - 复用现有 `insert(session): Long` / `insert(card)`；无需新 SQL。
   - 已在导入预览用的 `dedupeKeys()` 可直接用于合并前过滤。

2. **写入器**（新增 `data/BackupImporter.kt`）
   ```kotlin
   object BackupImporter {
       data class Outcome(val sessions: Int, val cards: Int, val aiSets: Int, val settingsApplied: Boolean)
       suspend fun apply(context: Context, db: AppDb, prefs: Prefs, plan: BackupRestore.Plan,
                         existingSessionKeys: Set<String>, existingCardKeys: Set<String>,
                         applySettings: Boolean): Outcome
   }
   ```
   - `plan = BackupRestore.keepNew(plan, existingSessionKeys, existingCardKeys)`
   - `db.withTransaction { plan.sessions.forEach { db.sessions().insert(it) }; plan.cards.forEach { db.cards().insert(it) } }`
   - AI 套题：`plan.aiSets` 与 `files/ai_bank.json` 现有的 `sets` 按 `id` 合并去重后整体写回。
   - 设置：仅 `applySettings` 时写入 `targetBand/examDate/dailyNewCards/scoreChannel/localModel/readerFontSize/readerLineHeight`，
     **不导入** `studyPlanOverrides`（日期相关的个人覆盖，跨机无意义）。
   - 成功后调用 `prefs.clearDerivedCaches()`（复盘/提纲缓存指向旧数据，必须失效）。

3. **UI**（`ui/SettingsScreen.kt`）
   - 现有「预览备份文件（只读）」流程增加第二步：预览摘要下加「确认导入」按钮（默认关闭，需勾选设置才覆盖）。
   - 二次确认文案：不删除现有记录；重复条目按「模块+题目+开始时间」/「类型+正面」跳过；建议先导出当前数据。
   - 完成后显示 `Outcome` 摘要并刷新首页数据（Room Flow 自动）。

4. **回滚**
   - 导入前自动调用 `Backup.export(context)` 生成一份“导入前快照”；失败时提示路径。
   - 若用户误导入：引导用「预览」核对，或恢复导入前快照（恢复本身仍依赖本功能，文档里写明这点）。

## 三、测试计划（无需真机）

- `BackupImporterTest`（Room 不在 JVM，测试纯逻辑部分）：
  - 去重后计数正确；
  - 设置为空/缺失时 `applySettings=false` 不写；
  - AI 套题按 id 合并去重。
- 逻辑全部下沉到可测函数；`withTransaction` 与 UI 的接线只做编译验证。
- 真机项：导入后首页/语料页/复盘数据一致、重复导入幂等、导入前快照可分享。

## 四、风险

- **重复导入**：key 去重 + 新增 id，理论上幂等；但 `startedAt` 精度为毫秒，重复导出同一记录仍会命中。
- **设置覆盖**：用户勾选后旧模型名可能在本机不存在 → 导入后加一条「本地模型名在本机不可用」提示。
- **音频缺失**：导入的练习 `audioPath` 为空（导出不含音频），结果页不显示回放按钮——已有逻辑天然兼容。
