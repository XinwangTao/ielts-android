# 依赖与 targetSdk 评估（2026-11-06，2026-11-10 补 Stage C 实施）

> 2026-11-10：Stage C 已实施并完成真机回归（UI/键盘、MNN 加载、Whisper 转写、本地评分/前台服务）。

## 现状

| 项 | 当前值 | 备注 |
|---|---|---|
| compileSdk / targetSdk | 35 / 35 | 2026-11-10 升 targetSdk；Play 新提交/更新自 2025-08 起要求 target ≥ 35 |
| minSdk | 26 | 通知渠道、`startForegroundService` 等已按 26 简化过 |
| Compose BOM | 2024.10.01 | 与 Kotlin/AGP 版本绑定，单独升级风险高 |
| AGP | 8.7.3 | 支持 compileSdk 35，Stage B 不需要升 AGP |
| lifecycle-runtime-ktx | 2.8.6 | 可升 2.8.7（patch） |
| core-ktx | 1.13.1 | 1.16+ 起要求 compileSdk ≥ 35，升它必然连带动 compileSdk |
| 原生库 | MNN（`libmnnllmapp.so`）、sherpa-onnx AAR | **已验证 16KB 对齐**：解包 debug APK，8 个 `.so` 的 PT_LOAD `p_align` 全为 16384 |

## 分阶段建议

### Stage A（安全，已完成 2026-11-06）

- `lifecycle-runtime-ktx` 2.8.6 → 2.8.7：patch 级，无 API 变化。
- 验证：单测 + `assembleDebug` 通过；lint 由 **5 warning 降到 4**（lifecycle 的 GradleDependency 告警消失）。

### Stage B（只升 compileSdk，不动 targetSdk）— 已完成 2026-11-06

- `compileSdk = 35`，`targetSdk` 保持 34；已通过 `sdkmanager` 安装 `platforms;android-35`。
- 验证：`assembleDebug` 通过、456 例单测全绿、lint 仍 0 error / 4 warning。
- 编译期新增若干 deprecation 警告；其中 `LocalLifecycleOwner` 已迁移到 `lifecycle-runtime-compose`（已消除），
  其余（`SCREEN_DIM_WAKE_LOCK`、`statusBarColor`/`navigationBarColor`）属 API 35 提示，逐步处理。
- 收益：为 core-ktx / lifecycle 更新铺路；**运行时行为不变**（targetSdk 未动）。

### Stage C（升 targetSdk 35）— 2026-11-10 实施

实施内容：
- `app/build.gradle.kts`：`targetSdk = 35`（compileSdk 已是 35）。
- `MainActivity`：`onCreate` 调 `enableEdgeToEdge()`；`Surface` 内根容器加
  `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`，一次性覆盖状态栏/导航栏/刘海/IME。
- `AndroidManifest.xml`：MainActivity 加 `android:windowSoftInputMode="adjustResize"`。
  实测不写时系统选 `adjust=pan`，键盘弹出会整窗上移、内容跑到状态栏下面（第一次装机截图复现）。
- `ui/Theme.kt`：删除 API 35 已失效的 `statusBarColor` / `navigationBarColor`，只保留图标明暗设置。

真机验证（PKJ110，APK `0.1-spike`，lastUpdate 2026-11-10 10:54:38）：
- 装机、启动、截图三件事均发生；首页/写作/设置底部按钮都在导航栏上方，没有被系统栏压住。
- 键盘场景：输入框完整显示在键盘上方，页面不再平移进状态栏。
- 截图：`F:/projects/_agent-runs/ielts-stagec-home2.png`、`ielts-stagec-writing.png`、
  `ielts-stagec-settings-end.png`、`ielts-stagec-ime4.png`。

合并后主分支复验（2026-11-10 19:18，main `9eef260`，DB v4）：
- 装机 `0.1-spike`（targetSdk 35，lastUpdate 19:18:51）启动正常，首页截图
  `F:/projects/_agent-runs/ielts-main-9eef260-home.png`；
- `tools/e2e_batch0.sh` 通过（预热通知出现/返回消失、口语页深链、无崩溃）；
- `e2e_local_ai.sh outline` 连续两次通过（stance=222/153、args=3、phrases=5）。

运行时回归（2026-11-10 17:50–17:54，PKJ110，targetSdk 35 + DB v4 测试包）：
- MNN 提纲：`E2E: RESULT ai outline stance=153 args=3 phrases=5`；
- Whisper 转写：`E2E: RESULT speaking status=pending_eval`，ASR 释放后 RSS 439MB；
- 本地评分（Guard 前台服务）：`overall=7.5 tr=8.0 cc=7.0 lr=8.0 gra=7.5 fixes=3 sentences=2`。
- 三项均无 `ForegroundServiceDidNotStartInTime`、无崩溃；16KB 原生库加载正常。
- 注：本次装在 `worktree-batch1-notes`（DB v4）上打的「v4 + Stage C」测试包，未合入 main；
  main 的 v3 库装到 v4 设备会触发 Room 降级崩溃，合并前不能混装。

行为变化清单（**必须逐项验，不能只看编译通过**）：

1. **Edge-to-edge 强制生效**：已按上表迁移并截图验证 UI；长列表/录音按钮组随后续页面抽查。
2. **16KB 页大小**：target ≥ 35 且带原生库时的上架门槛。
   **已静态验证通过**：解包 `app-debug.apk`，8 个 `.so`（MNN / mnnllm / onnxruntime / sherpa / c++_shared / graphics.path）
   的 PT_LOAD 段 `p_align` 均为 16384。仍需在真机上做一次安装/加载回归（MNN 加载 + Whisper 转写）。
3. **前台服务类型**：已声明 `dataSync` / `mediaPlayback` / `microphone`，需在真机跑一次评分确认通知与保活。
4. **广播/隐式 Intent**：target 35 起部分隐式广播限制收紧；目前只在设置页拉起系统页，风险低。
5. **通知**：渠道已建；权限流程已按 33+ 处理，预计无变化。

### Edge-to-edge 迁移评估（离线，2026-11-06）

- **现状**：`Theme.kt` 在 `SideEffect` 里设置 `window.statusBarColor` / `navigationBarColor`（API 35 弃用）；
  全仓库 grep 无 `WindowInsets` / `systemBarsPadding` / `Scaffold` / `enableEdgeToEdge`。
- **影响**：targetSdk 升到 35 后系统强制 edge-to-edge —— 系统栏透明、内容绘制到栏下；
  现在没有任何 insets 补偿，页头、底部录音按钮组、结果页长列表都会被系统栏压住。
- **迁移步骤（需真机）**：
  1. `Theme.kt` 删除两行颜色设置；Activity 改用 `enableEdgeToEdge()`（androidx.activity 1.9.3 已含）。
  2. 在 `MainActivity` 的根容器统一加 `Modifier.windowInsetsPadding(WindowInsets.systemBars)`（或每页 `contentPadding`）。
  3. 逐页确认：首页顶部标题、写作业顶部计时/底部「提交」、口语录音按钮组、结果页长列表底部、设置页底部按钮。
  4. 键盘（IME）场景：输入框用 `imePadding()`，避免被键盘遮挡。
- **结论**：代码改动集中但回归面覆盖全部页面；**2026-11-10 已按上述步骤实施并装机验证**（见 Stage C 实施记录）。
  已把「系统栏遮挡」加入 `docs/acceptance-checklist.md` 第八节。

### Stage D（targetSdk 36，暂不建议）

- Android 16 的 predictive back 默认开启、edge-to-edge 更严格，且依赖 Stage C 的 16KB 验证结果。
- 在 Stage C 真机回归完成前不动。

## 结论

- 现在可做：**Stage A**（patch 依赖）。
- 下一步候选：**Stage B**（compileSdk 35），前提是本机装好平台包。
- **Stage C 的 16KB 页门槛已通过静态检查**（全部 16384 对齐）；剩余是真机安装/加载回归（MNN + Whisper）。AGP 8.7.3 支持 compileSdk 35，Stage B 无需升 AGP。
- Play 上架压力存在，但本 App 当前以自用侧载为主，不阻塞功能迭代。
