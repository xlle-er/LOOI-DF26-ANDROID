# 隐私说明 / Privacy

更新 / Updated: 2026-10-16

## 简体中文

- 离线陪伴与手机本地目标检测无需 AI 服务连接。本应用没有额外接入项目运营方的统计或媒体上传服务；第三方 Android / Google SDK 仍适用其各自条款。
- 开启在线 AI 后，输入文字、用户显式选择的图片，以及 Live 中已开启的麦克风音频/相机帧发送给你配置的服务商。请求可包含人格、会话上下文及工具结果。服务商可按其政策处理这些数据，请自行选择端点及凭据。
- 普通设置、机器人别名和本机选择标识保存在 DataStore；凭据用设备 Android Keystore AES-GCM 加密。清除应用数据或卸载可移除本机配置。凭据输入留空会保留旧值，删除需使用独立删除操作。
- 麦克风用于语音输入，相机用于预览/本地识别/已开启的视频对话，附近设备权限用于机器人 BLE；旧 Android 扫描要求位置权限。无需后台位置或长期后台音视频。
- 机器人完整标识用于本机选择和别名关联；源码与发布附件不包含实际用户设备列表。媒体与完整标识不主动写入应用诊断日志。用户自行截屏、系统崩溃报告或服务端日志可能含信息，分享前请检查。
- 本仓库不提供共享 API 密钥，不收集 GitHub 下载者的应用会话数据。GitHub 下载与问题反馈适用 GitHub 自身的隐私政策。

## English

- Offline companionship and local detection need no AI connection. No additional operator analytics or media-upload service is integrated; third-party Android/Google SDKs remain subject to their own terms.
- Online AI sends entered text, explicitly attached images and enabled Live microphone/camera data to your configured provider. Requests may include personality instructions, conversation context and tool results. Choose providers and credentials with their privacy terms in mind.
- DataStore holds settings, robot aliases and local selection identifiers. Credentials are encrypted with Android Keystore AES-GCM. Clear app data or uninstall to remove local configuration. Leaving a credential field empty preserves its value; use the explicit delete action to remove it.
- Microphone is used for voice, camera for preview/detection/enabled video conversations, and nearby-device access for BLE. Older Android versions need location permission for scanning. Long-running background media and background location are not required.
- Full robot identifiers stay local for selection and alias matching. The repository and release attachments contain no real user device inventory. Media and full identifiers are not deliberately logged by application diagnostics. Review screenshots, system crash reports and server logs before sharing.
- No shared API key is provided and this project operates no download-user conversation collection service. GitHub downloads and reports are governed by GitHub's own privacy terms.
