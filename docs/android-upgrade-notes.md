# 依赖与 targetSdk 评估（2026-11-06）

> 只做评估，不改版本。结论先行：**Stage A/B 可做且低风险；Stage C（targetSdk 35）有一个硬门槛要先验——原生库 16KB 页对齐。**

## 现状

| 项 | 当前值 | 备注 |
|---|---|---|
| compileSdk / targetSdk | 34 | lint `OldTargetApi` 持续告警；Play 新提交/更新自 2025-08 起要求 target ≥ 35 |
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

### Stage C（升 targetSdk 35，需真机回归）

行为变化清单（**必须逐项验，不能只看编译通过**）：

1. **Edge-to-edge 强制生效**：Activity 默认绘制到系统栏后面。
   本 App 大量全屏 Compose 页面，需要确认 `WindowInsets` / `systemBarsPadding` 覆盖；
   尤其底部输入框、结果页长列表、录音按钮组。
2. **16KB 页大小**：target ≥ 35 且带原生库时的上架门槛。
   **已静态验证通过**：解包 `app-debug.apk`，8 个 `.so`（MNN / mnnllm / onnxruntime / sherpa / c++_shared / graphics.path）
   的 PT_LOAD 段 `p_align` 均为 16384。仍需在真机上做一次安装/加载回归（MNN 加载 + Whisper 转写）。
3. **前台服务类型**：已声明 `dataSync` / `mediaPlayback` / `microphone`，但需确认后台启动限制的新细则。
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
- **结论**：代码改动集中但回归面覆盖全部页面，**留到 Stage C 与真机一起做**；
  已把「系统栏遮挡」加入 `docs/acceptance-checklist.md` 第八节。

### Stage D（targetSdk 36，暂不建议）

- Android 16 的 predictive back 默认开启、edge-to-edge 更严格，且依赖 Stage C 的 16KB 验证结果。
- 在 Stage C 真机回归完成前不动。

## 结论

- 现在可做：**Stage A**（patch 依赖）。
- 下一步候选：**Stage B**（compileSdk 35），前提是本机装好平台包。
- **Stage C 的 16KB 页门槛已通过静态检查**（全部 16384 对齐）；剩余是真机安装/加载回归（MNN + Whisper）。AGP 8.7.3 支持 compileSdk 35，Stage B 无需升 AGP。
- Play 上架压力存在，但本 App 当前以自用侧载为主，不阻塞功能迭代。
