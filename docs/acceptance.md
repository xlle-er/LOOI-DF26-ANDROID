# Android 0.1.0 验收记录

日期：2026-10-15。此记录区分源码实现、自动检查、模拟器验证与真机验证，**不能据此认定 P0/P1 真机验收全部通过**。

## 本轮结果

| 项目 | 结果 | 证据 / 限制 |
|---|---|---|
| 调试 APK 构建、签名校验 | 通过 | Gradle assembleDebug、apksigner verify；包名 app.looi.companion，versionCode 1 |
| 原生逻辑 | 55/55 通过 | 会话代次、BLE 字节解码与参数、运动租约/优先级、音频实际进度账本、重采样、视觉阈值/歧义/坐标、请求和语言策略 |
| 原始眼睛与动作桥接 | 5/5 通过 | Chrome/Playwright：尺寸、原始转向参数、ACK 失败不能完成、禁用相机不能拍照、取消动作、Android 宿主高度恢复 |
| Android 界面冒烟 | 1/1 通过 | API 35 ARM64 模拟器：英文设置→离线陪伴→Activity 重建→实际眼睛蓝色像素检查→眼睛桥重新就绪→退出→界面切换中文 |
| 模型执行测试 | 1 项明确跳过 | 当前 ARM 模拟器 HWCAP 同时报告 sve2/无 sve、sme2/无 sme，执行本地库发生 SIGILL；未计入通过 |
| Lint | 0 error | 仍有依赖更新提示和兼容旧 API 的弃用提示；没有屏蔽错误或创建 baseline |
| 资源与隐私检查 | 通过 | 56 项资源 SHA-256；源码凭据模式扫描；ARM64 原生 ELF LOAD 段至少 16 KB 对齐 |
| 图标 | 已打包 | 使用用户提供 icon.png；普通各密度 PNG＋API 26 自适应图标，未重画图形 |
| 冷启动参考 | 模拟器单次 1362 ms | adb am start -W；只供后续对照，不代表手机性能、热启动或统计基线 |
| 连续音视频、BLE 机器人和实际跟随 | 未真机验证 | 没有使用真实凭据发起对话、没有连接实体机器人、没有执行车轮运动 |

复现命令见 README。原生 XML/HTML 报告在 `app/build/test-results/`、`app/build/reports/`，模拟器报告在 `app/build/outputs/androidTest-results/`。这些构建目录不包含在可分享源码包中。

## 已实现但仍需设备验收

- 原生设置、机器人列表、横屏眼睛、相机预览、文字与显式图片附件、声音/AI/动作测试及恢复入口。
- 应用层持有连续录音、播放、相机和 Live；旋转不重新建立会话。进入后台结束采集并撤销运动许可。
- Live 握手、多 part 收包、转写、打断、工具取消、有限发送队列、服务器确认的恢复 handle。
- AudioTrack 按实际播放进度出队，部分写入、回绕、缓冲边界、两次自动恢复以及测试成功条件。
- BLE 扫描、稳定列表、同名设备区分、别名/我的/上次连接、串行 GATT、通知订阅、应用层 ACK、停止优先及并发停止合并。
- 手机本地 EfficientDet-Lite0 int8、RGB letterbox 输入、跟踪歧义处理、显式目标参考和运动许可、150 ms 相对跟随脉冲与独立新鲜度 watchdog。
- 多提供商配置、Google 原生授权入口、Keystore 凭据加密、局域网 HTTP 主机白名单、中英界面和优先遵循用户语言的 AI 提示词。

## 模拟器视觉限制

在本次 Apple Silicon ARM64 Android 模拟器中，MediaPipe 执行时分别遇到 SVE `addvl` 和 SME `rdsvl` 非法指令。上游有相关 [MediaPipe issue 6293](https://github.com/google-ai-edge/mediapipe/issues/6293)；本轮针对目标检测和 int8 模型的实测不能直接用该问题中的手部模型结果替代。

当前固定 MediaPipe 0.10.29。应用只对 `ranchu` ARM64 模拟器检查已观察到的错误指令集组合，显示明确提示并不启动本地检测，避免直接崩溃。该检查不禁用实体手机。本地模型的实际推理、识别精度和跟随性能均待兼容真机确认；跳过执行测试不等于模型已验证。

## 真机验收步骤与通过条件

| 测试 | 最低通过条件 | 当前 |
|---|---|---|
| 首次授权、拒绝、取消与再次开始 | 旧授权结果不启动新会话；拒绝相机可降级 | 待测 |
| 开始 / 退出 / 返回各 20 次 | 无重复采集、残留旧音频或退出后媒体上传 | 待测；模拟器仅完成单次流程与重建 |
| 20 轮连续语音 | 回复结束后仍能听、看、继续回应 | 待测 |
| 连续 30 分钟 | 记录队列、线程、内存、温度与耗电，无持续增长 | 待测 |
| 30 秒音频快速返回 | 顺序完整、实际排空；故障清理不能误报测试成功 | 待测；账本边界单元测试已通过 |
| 来电、音频焦点、耳机和蓝牙耳机切换 | 状态符合实际采集/输出，恢复后不永久静音 | 待测 |
| 摄像头方向、前置镜像、切换与被占用 | 小窗与上传方向正确，一台相机，不积压分析帧 | 待测 |
| 两台同名机器人 | 选择、别名和所有权不串设备，不自动选择最强设备 | 待测 |
| BLE 分包、拒绝、超时、断线 | 正确匹配 ACK，失败撤销许可，停止未确认有提示 | 待测；纯协议与仲裁单元测试已通过 |
| 视觉选择、交叉遮挡、失去目标 | 不换人，过期检测停止，需要重新选择 | 待测；控制策略单元测试已通过 |
| 实验性前进跟随 | 完成实际 PWM/速度和 150 ms 脉冲标定；不倒车、不堆积旧意图 | 待测 |
| Google OAuth 与各模型服务 | 自己注册 Android OAuth 包名/签名，真实账号端点调用成功 | 待测 |
| TalkBack、大字体、大屏、多窗口 | 所有控制可达，退出不遮挡，不依赖强制横屏 | 待真机；模拟器做过基础布局检查 |

## 边界

相对视觉跟随没有测距、避障和防跌落数据。停止 ACK 只代表固件确认，不证明物理动作已停止。旧固件最小 PWM 使小参数也可能产生明显车速，实际速度尚未标定。

首版不做后台长期通话、固件烧录、跨手机机器人身份同步或商店发布。没有配置真实账号、签名私钥或个人机器人地址；源码包不携带构建缓存和模拟器状态。

## 2026-10-16 GitHub 发布复核 / Publication checks

发布版本仍为 0.1.0 实验预览。此次仅完善发布文档、隐私排除、许可证资产与 SDK 引导脚本；没有把待真机项目改为通过。APK 重建后确认许可证资产齐全、资源哈希和调试签名有效；55 项逻辑测试通过（Gradle 可复用先前通过结果），Lint 0 error。此前 5 项眼睛测试和 1 项模拟器 UI 测试结果沿用 2026-10-15 记录，本轮没有重新运行模拟器。

Publication updates add bilingual documentation, privacy exclusions, license assets and a portable SDK bootstrap script. The APK was rebuilt; bundled notices, resource hashes and debug signature were verified. All 55 logic tests remain passing (Gradle may reuse prior successful results); lint has zero errors. Browser/emulator results above are from 2026-10-15, not a new hardware validation.

Git 暂存清单不含缓存、local.properties、签名私钥、录音、日志或 APK。Gitleaks 8.30.1 对实际待发布源码快照扫描无发现，Git 作者使用 GitHub noreply 地址。发布包附 SHA-256 和独立第三方声明压缩包；APK 仅存于 GitHub Release，不进入 Git 历史。新增代码 MIT；导入资源、图标与第三方组件的许可例外见 LICENSE/NOTICE.md。

The staged source snapshot excludes caches, local.properties, signing secrets, recordings, logs and APKs. Gitleaks 8.30.1 reported no findings; Git authorship uses a GitHub noreply address. Release attachments include SHA-256 checksums and separate license notices. APKs are release assets, outside Git history. MIT covers new code subject to the imported-material exceptions in LICENSE/NOTICE.md.

## 0.2.0 设备管理发布验证 / Device management release verification

2026-10-16：58 项单元测试通过，Lint 0 error；0.2.0 / versionCode 2 APK 构建通过，资源哈希与 ARM64 对齐通过。已将新旧 APK 签名证书摘要比对，结果相同；包名不变，满足覆盖安装的版本和签名条件，未在真实手机执行覆盖安装。新配网、Wi-Fi 控制、方向校准及 OTA 仍待真机验收。此前模型执行跳过及硬件待测状态保留。

58 unit tests passed; lint has zero errors. APK build, packaged resources, ARM64 alignment, version increment and matching old/new signing certificates were verified. Physical in-place installation, provisioning, Wi-Fi control, calibration and OTA remain untested. Earlier model execution was skipped; that result has not been changed.

## 0.2.1 离线热点升级 / Offline hotspot updates

2026-10-16：59 项单元测试通过，Lint 0 error，APK 构建通过。新增真实磁盘缓存测试：保存后重新创建缓存对象仍可读取、错误下载不覆盖已校验包、缓存篡改会在上传前拒绝。versionCode 增至 3；签名与之前版本一致。网页模拟升级不算 Android/iOS 真机测试；真实热点切换、自动恢复互联网及 OTA 仍待验收。

59 unit tests passed; lint has zero errors and the APK builds. Disk-cache tests cover persistence, preservation after failed verification and corruption rejection. Version code is 3 with the original signing identity. Physical network switching and OTA remain pending.
