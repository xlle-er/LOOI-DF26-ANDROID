# Android 0.2.1 · 离线热点升级 / Offline hotspot updates

## 简体中文

- 将固件下载、上传拆分：先联网下载并校验，再连 ESP 热点上传，无需热点提供互联网。
- 已验证的固件缓存在 App 文件中，重启 App 后仍可使用；损坏的下载不会覆盖旧包，上传前重新校验。
- 新增默认开启的“升级后关闭 ESP 热点”选项（需设备固件 2.0.1+），以及升级前、升级后恢复互联网的 AI 陪伴提醒。
- 从旧版首次升级仍需手动断开热点；初版仅热点 OTA 使用 192.168.4.1 的设备面板上传。

Android 7.0+ 调试签名实验预览版，沿用原签名，versionCode 3。59 项单元测试通过，Lint 0 error；真实热点切换、断线和 OTA 仍待真机验证。

## English

Prepare and verify firmware while online, then join the ESP hotspot and upload the cached package without internet. Prepared packages survive app restarts, corrupted downloads do not replace the last valid package, and upload revalidates the bytes. A default-on close-hotspot option requires firmware 2.0.1+; reminders explain restoring mobile data/internet Wi-Fi before AI companionship. Initial hotspot-only firmware uses its own 192.168.4.1 panel; the first upgrade still requires manually leaving the hotspot.

Debug-signed experimental preview for Android 7.0+, same signing identity, versionCode 3. All 59 unit tests passed with zero lint errors. Physical network switching and OTA acceptance remain pending.
