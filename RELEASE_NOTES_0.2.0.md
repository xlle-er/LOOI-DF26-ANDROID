# Android 0.2.0 · 设备管理 / Device management

## 简体中文

- 新增 BLE 配网、Wi-Fi 设备发现及手动 IP 连接。
- 新增电机方向校准、限时点动、控制权接管与立即停止。
- App 内支持本地 `.bin` 和在线 OTA；在线固件来自 LOOI-DF26-Firmware 的稳定 Release。
- 移除俯仰舵机指令和动作；迁移已保存的旧场景。
- 版本号 0.2.0（versionCode 2），沿用 0.1.0 的调试签名，可覆盖同签名的旧版安装。

附件 `LOOI-Android-0.2.0-debug.apk` 为 Android 7.0+ 调试签名实验预览版。58 项单元测试通过，Lint 0 error，APK 签名、资源哈希及 ARM64 对齐检查通过。本轮未进行真机设备管理/OTA 验收，早期模拟器结果不代表此次新增流程已通过真机测试。SHA256SUMS.txt 可用于下载校验。

## English

- BLE Wi-Fi provisioning, LAN discovery and manual-IP connections.
- Persistent motor calibration, bounded tests, explicit control takeover and immediate stop.
- Local `.bin` and online OTA using stable firmware Releases from LOOI-DF26-Firmware.
- Removed pitch-servo commands and migrated saved legacy scenes.
- Version 0.2.0 (code 2), signed with the same debug certificate as 0.1.0 for in-place updates of matching installations.

The APK is a debug-signed experimental preview for Android 7.0+. All 58 unit tests passed; lint has zero errors. APK signing, resource hashes and ARM64 alignment were verified. Physical device-management and OTA acceptance remain pending. Use SHA256SUMS.txt to verify the downloads.
