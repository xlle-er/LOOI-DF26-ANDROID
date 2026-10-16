# Android 0.2.3 · 默认身体运动 / Companion motion by default

## 简体中文

- 每次开始 AI 陪伴时默认开启身体运动，连接机器人后无需先手动打开运动许可。
- 停止、断连、故障和手动遥控仍会撤销当前许可；在陪伴控制中可以重新开启。默认开启不会自动恢复旧动作或视觉跟随。
- 固件更新增加明确选择的预发布渠道，并根据设备板型选择 OTA 包；默认仍使用稳定渠道。

Android 7.0+ 调试签名实验预览版，versionCode 5，沿用 0.2.2 的签名，可覆盖安装。AI 移动仍依赖模型实际返回动作调用、有效的设备连接和设备确认。

验证结果见随附 checksums-and-tests.json。当前版本未进行实体机器人运动验收。

## English

Each new AI companion session enables body motion by default. Stops, disconnects, faults and manual remote control still revoke session permission; re-enable it in the companion controls. Previous actions and visual following are not resumed automatically. Firmware updates add an explicit preview-channel option and select OTA packages for the device board; stable remains the default.

Debug-signed experimental preview for Android 7.0+, versionCode 5. Uses the same certificate as 0.2.2 for in-place updates. AI movement requires an actual model action call, a connected robot and device acknowledgement. Validation results are attached in checksums-and-tests.json; physical robot motion has not been validated for this build.
