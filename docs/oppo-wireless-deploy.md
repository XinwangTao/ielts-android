# 雅思 App 部署到 OPPO 手机 + MNN 本地模型联调

状态：**配对已完成，卡在调试端口不可达**。APK/模型/脚本全部就绪。
两个脚本已在平板（Android 13）上跑通全链路验证（推送 → 字节校验 → 设备侧自检）。
OPPO 配对凭据已持久化到 `~/.android/adbkey`，重开无线调试后**不需要再输配对码**。
日期：2026-11-03 ｜ 工程：`F:\projects\ielts-android`

---

## 0. 一句话结论

库里唯一"成功过"的无线调试方法是**平板 Magisk 模块方案（需 root）**，OPPO 上不可照搬；
OPPO 走 **Android 11+ 原生无线调试配对**（`adb pair` → `adb connect`），免 root，
配套脚本已写好并在平板上验证过语法与预检逻辑，**只差手机侧的 IP/配对码**。

---

## 1. 库内溯源：无线调试到底怎么成功的

来源：`D:\Users\陶鑫旺\Documents\Obsidian Vault\inbox\小米平板5Pro-ADB调试环境.md`

| 项 | 库内记录 | 能否用于 OPPO |
|---|---|---|
| 方法 | 自制 Magisk 模块 `pad_adb_tcpip v1.0`：开机 `setprop service.adb.tcp.port 5555; stop/start adbd` | ❌ 需 root + Magisk |
| 优点 | 重启后 `adb connect <IP>:5555` 直接可用，固定端口，无需插线 | — |
| 前置 | 平板已 root，YABP 模块白名单防失联 | ❌ OPPO 未 root |
| 已知坑① | 重启后 ADB 授权可能掉，USB/无线双双 unauthorized，必须人工在设备上点「一律允许」 | ✅ 同样适用 |
| 已知坑② | 有线无线同时在线报 `more than one device`，命令必须 `-s <序列号或IP:端口>` | ✅ 同样适用 |
| 已知坑③ | 有线/无线是同一台设备，地址变了要插线 `adb shell ip addr show wlan0` 查 | ✅ 同样适用 |

来源：`Codex专区\安卓工具链.md`
- `Git Bash` MSYS 路径坑：`/sdcard/x` 被改写成 `D:/Program Files/Git/sdcard/x` → 必须 `export MSYS_NO_PATHCONV=1`
- 本机 adb：`C:\Users\UserX\AppData\Local\Android\Sdk\platform-tools\adb.exe`，v37.0.0（`adb pair` 需 ≥30，满足）

**结论：库里的"成功"是 root 方案的特例，不含免 root 配对链路。**按库规范此处不臆造为已验证。

---

## 2. OPPO（ColorOS）适用方法

### 2.1 手机侧开启（需人工，一次性）
1. 设置 → 关于本机 → 版本信息 → 连点「版本号」7 次 → 开发者选项
2. 设置 → 系统设置/其他设置 → 开发者选项：
   - 打开「USB 调试」
   - 打开「无线调试」
   - 打开「**通过 USB 安装**」（否则 `adb install` 静默失败）
   - 关掉「**权限监控**」（ColorOS 会拦 adb 安装/授权，表现为安装器无响应）
3. 手机与 PC 连**同一 WiFi**（注意本机跨网段：PC 在 `192.168.237.7`，平板在 `192.168.236.61`，
   说明该路由器有多个 /24 段；跨段设备无法用 mDNS 自动发现，**必须手填 IP**）
4. 点「使用配对码配对设备」→ 记下 **配对端口 + 6 位配对码**；
   主页面另有一个 **调试端口**（与配对端口不同，每次重开都会变）

### 2.2 PC 侧（已封装成脚本）
```bash
cd /f/projects/ielts-android
bash tools/oppo_wireless_setup.sh pair    192.168.236.X:<配对端口> <六位配对码>
bash tools/oppo_wireless_setup.sh connect 192.168.236.X:<调试端口>
bash tools/oppo_wireless_setup.sh list     # 确认设备在线
```

> 说明：`adb pair` 只需一次（写入 `~/.android/adbkey` 信任）；之后每次重开无线调试
> 只需按新端口 `adb connect`。若手机重启后掉授权，仍需手机端点一次「允许调试」。

---

## 3. 部署 + 联调

### 3.1 一键部署
```bash
bash tools/deploy_oppo.sh 192.168.236.X:<调试端口>
# 空间紧张（<6GB 可用）换成 2B：
bash tools/deploy_oppo.sh 192.168.236.X:<调试端口> F:/cache/models/qwen35-2b-mnn qwen35-2b
```
脚本做 5 件事：预检机型/ABI/RAM → `assembleDebug` → `install -r -g` → 推模型并校验字节数 → 拉起 App。

### 3.2 已就绪的资产
| 项 | 值 |
|---|---|
| APK | `app/build/outputs/apk/debug/app-debug.apk`，31MB，**arm64-v8a only**（`abiFilters` 写死） |
| minSdk / targetSdk | 26 / 34 |
| 模型投递目录 | `/sdcard/Android/data/com.txw.ielts/files/models/mnn/<模型目录名>` |
| 目录名必须匹配 | 默认值 `qwen3-4b-instruct`（`Prefs.localModel` / `LocalLlmEngine.DEFAULT_MODEL`）；2B 用 `qwen35-2b` |
| 模型体积 | `qwen3-4b-instruct` 权重 2.70GB，设备实际占 2.53GB；`qwen35-2b` 权重 1.12GB |
| 本地引擎 | MNN JNI 复用 `libmnnllmapp.so`，静态注册，宿主需同名类 `com.alibaba.mnnllm.android.llm.LlmSession`（工程内已有） |
| 生成参数 | `max_new_tokens=3500`、`thread_num=6`、`temperature=0.2`（硬编码在 `LocalLlmEngine.loaded()`） |

### 3.3 联调验收清单（手机屏幕上操作，adb 无法代替）
1. 设置 → 评分通道 = **仅本地（离线）**（否则云端 403/配额会掩盖本地链路）
2. 设置 → 本地评分模型 → 选中刚推的模型；页面应显示「当前模型：…」而非 adb push 提示
3. 提交一篇 Task2 写作 → 首次会先「拷贝模型到内部」（2-3 分钟）→ 评分中 → 通知 → 结果页有 Band
4. 日志：`adb -s <地址> logcat -s LocalLlm ScoreService`
5. 判定通过：结果页出现四项分 + overall，且 `LocalLlm` 日志有 `loading local model` 与生成耗时

---

## 4. 坑清单（按踩中概率排序）

| # | 坑 | 处理 |
|---|---|---|
| 1 | OPPO 8GB RAM 上 4B 模型 + 其他大模型 App 抢内存 → lmkd 杀评分进程（平板实测 swap 2.6GB 后被 kill） | 评分时别开 MNN Chat/语音助手；或换 `qwen35-2b`；`Prefs.releaseAfterScore` 设 true |
| 2 | 4B 模型要占 2×2.7GB（外部投递 + 内部拷贝） | 脚本预检 `/data` 可用空间，不足直接拒推并提示换 2B |
| 3 | `install` 无声失败 | ColorOS「权限监控」+「通过 USB 安装」两道开关，见 §2.1 |
| 4 | Git Bash 改写 `/sdcard/...` | 所有脚本已 `export MSYS_NO_PATHCONV=1` |
| 4b | **反作用**：`MSYS_NO_PATHCONV=1` 下本地路径也不再转换，`/tmp/x` 会被 adb 当成 `C:\tmp\x` 报 `cannot stat` | 本地模型目录一律写 Windows 风格 `F:/cache/models/...`（原 `push_mnn_model.sh` 正是因此这么写） |
| 5 | 平板 + OPPO 同时在线 → `more than one device` | 脚本一律 `-s`；手工命令同样 |
| 6 | 无线调试端口每次重开都变 | `connect` 用新端口；或临时插 USB 一次性完成部署 |
| 7 | 首次评分慢（4B 平板实测 89-101s/299-328 tokens） | 属预期；手机 CPU 可能更慢，需实测 |

---

## 5. 未解决 / 待确认

1. **阻塞项**：OPPO 未连接。需要的输入（手机屏幕上看）：
   - 无线调试主页面的 `IP:调试端口`
   - 「使用配对码配对设备」弹窗里的 `配对端口` + 6 位码
   或：直接插 USB 线一次（脚本同样可用序列号跑）
2. OPPO 型号/系统版本未记录（库内无该机档案），ABI/RAM/SDK 待接入后由脚本打印
3. ColorOS 是否限制 `adb shell` 访问 `/sdcard/Android/data/<pkg>` 未实测
   （平板 Android 13 正常；若被拒，改用 `run-as` 或 `adb push` 到 `/data/local/tmp` 再 `su` 迁位——无 root 时仅有前者）
4. 手机端本地评分真实耗时/内存峰值未测（平板数据不可外推）
5. 库内暂无 OPPO 无线调试条目；实测通过后应归档一条 OC（当前不写，避免把未验证结论入库）

---

## 6. 本次已实测（在平板上，非 OPPO）

```
SKIP_BUILD=1 SKIP_INSTALL=1 bash tools/deploy_oppo.sh 192.168.236.61:5555 F:/cache/tmp/mnn_selftest selftest_tmp
→ 预检 M2105K81AC/arm64-v8a/SDK33/RAM7609MB  OK
→ df -k /data 可用 67719MB                    OK（toybox df 无 -m，已修正）
→ push 5 文件 + llm.mnn.weight                 OK
→ local=1048576 remote=1048576 字节一致        OK
→ 设备侧 ls/du 自检                            OK（已清理测试目录）
```

未实测项：`install -r -g`（未装到 OPPO）、ColorOS 的三道开关。

---

## 7. OPPO 实测进展（2026-11-03）

| 步 | 结果 |
|---|---|
| `adb pair 192.168.237.175:40011 310437` | ✅ `Successfully paired`，`guid=adb-afa99023-q6q7Cb`（设备序列号前缀 `afa99023`） |
| 配对后 mDNS 自动发现 | ❌ `adb mdns services` 空（Windows 侧 mDNS 不可靠，不能依赖） |
| 手机可达性 | ✅ ping 通（187-259ms，偏高=WiFi 省电） |
| 全端口扫描 1-65535 | ❌ **零开放端口**（扫描器已用平板 5555 自证有效） → 无线调试的调试服务没在监听 |
| 配对端口 40011 直连 | ❌ `积极拒绝`（配对成功后该端口即关闭，符合预期） |

**判定**：配对这一半已经完成，缺的是「调试端口在监听」。这不是脚本问题，
是手机侧「无线调试」开关/屏幕状态问题（关掉配对弹窗、锁屏、或页面退出都可能让 adb-over-wifi 服务停）。

### 恢复步骤（手机端，然后一条命令）
```
手机：解锁 → 设置 → 开发者选项 → 打开「无线调试」→ 停在这个页面别退
PC ：bash tools/oppo_wireless_setup.sh autoconnect 192.168.237.175
```
`autoconnect` 会扫 30000-50000 找调试端口并 connect（配对已持久化，无需配对码）。
定位到端口后一键部署：
```bash
bash tools/deploy_oppo.sh <连上的 IP:端口>
```

### 备注
- 若手机 IP 变了（DHCP），`autoconnect` 换新 IP 即可；也可插一次 USB 用序列号 `afa99023` 直接部署
- 高延迟 187-259ms 会让 2.7GB 权重推送变慢（约 30-60 分钟量级），建议首次部署走 USB，或直接上 1.12GB 的 `qwen35-2b`
