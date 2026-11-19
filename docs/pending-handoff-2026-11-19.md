# 待办交接（2026-11-19）

> 按用户要求：需要设备或需要用户确认的步骤先停在这里，不自行推进。

## 需要设备（手机回线且空闲后执行）

1. **验证 `fix/listening-qa-scope`（c97b36a）**：安装该分支 APK，确认
   「A 套听力生成听题口述 → 打开 B 套不显示 A 的题/判定/参考答案 → 回 A 仍保留」。
   通过后合并到 main（合并/推送需要用户确认）。
2. **备份导出/导入验收（runbook 3.13–3.16）**：导出 → 同份导入 → 再导入幂等；
   勾选恢复设置；含范文备份导入；坏/高版本 JSON 不动库。
3. **清理测试残留**：session 29（speaking, `pending_eval`）来自
   `/sdcard/test_en.wav` 的 `EACCES`，请在结果页删除或重录，避免长期挂在待处理。
4. **（新发现，待验证）写作「AI 框架」跨题残留**：`WritingScreen` 打开骨架面板时
   直接显示全局 `writingSkeleton`，切换题号后会看到上一题的骨架；判断依据是
   `MainActivity` 无条件传 `skeleton = writingSkeleton`。需真机复现后再修（按题目+Task 作用域）。

## 需要用户确认

- 合并/推送 `fix/listening-qa-scope`（Android 改动，先过第 1 项真机验证）。
- Dependabot 大版本升级（Kotlin 2.4 / AGP 9.5 / Room 2.8 / actions v6-v7）是否单独立项。

## 已完成（本轮，无需设备）

- main `2756789` 全量回归 `tools/verify_all.sh` **EXIT=0**：
  单测 **526 例 / 0 失败**、打包通过、约定检查通过、lint **0 error / 2 warning**、
  AI 探针 **21/21**。
- 文档：`e2e_wav` 必须用 App 可读路径（`2756789`，已推送）。
