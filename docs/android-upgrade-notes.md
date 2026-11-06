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
- 验证：单测 + `assembleDebug` 通过；lint 的对应 GradleDependency 告警消失。

### Stage B（只升 compileSdk，不动 targetSdk）

- `compileSdk = 35`，`targetSdk` 保持 34。
- 需要本机安装 Android 15 平台包。
- 验证：`assembleDebug` + 全量单测；确认 AGP 版本支持（当前 AGP 见 `gradle/libs.versions.toml`）。
- 收益：为 core-ktx / lifecycle 更新铺路；**运行时行为不变**，风险低。

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

### Stage D（targetSdk 36，暂不建议）

- Android 16 的 predictive back 默认开启、edge-to-edge 更严格，且依赖 Stage C 的 16KB 验证结果。
- 在 Stage C 真机回归完成前不动。

## 结论

- 现在可做：**Stage A**（patch 依赖）。
- 下一步候选：**Stage B**（compileSdk 35），前提是本机装好平台包。
- **Stage C 的 16KB 页门槛已通过静态检查**（全部 16384 对齐）；剩余是真机安装/加载回归（MNN + Whisper）。AGP 8.7.3 支持 compileSdk 35，Stage B 无需升 AGP。
- Play 上架压力存在，但本 App 当前以自用侧载为主，不阻塞功能迭代。
