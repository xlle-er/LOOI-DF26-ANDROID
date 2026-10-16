<p align="center">
  <img src="docs/icon-rounded.svg" alt="LOOI icon" width="160" height="160" />
</p>
<h1 align="center">LOOI-DF26-ANDROID</h1>
<p align="center">原生 Android 机器人陪伴应用 · Native Android robot companion</p>
<p align="center"><a href="#简体中文">简体中文</a> · <a href="#english">English</a> · <a href="https://github.com/xlle-er/LOOI-DF26-ANDROID/releases">下载 APK / Download APK</a></p>

## 简体中文

Kotlin + Jetpack Compose 原生应用。本地 WebView 保留原始眼睛及动作编排；音频、相机、AI 网络、BLE 和视觉相对跟随由原生层管理。运行 APK 不需要 Node.js、网页服务器或 Roboflow。

### 下载与使用

在 [Releases](https://github.com/xlle-er/LOOI-DF26-ANDROID/releases) 下载 `LOOI-Android-0.2.1-debug.apk`，在 Android 7.0（API 24）或更新设备上安装。允许当前下载来源安装应用；校验下载附件的 `SHA256SUMS.txt`。这是**调试签名的实验预览版**，没有商店发布，也未完成机器人真机验收。

1. 默认离线陪伴，无需 API 密钥或机器人即可查看眼睛。在线 AI 使用自己的服务凭据。
2. 设置中选择跟随系统、简体中文或 English。**AI 回复优先遵循当前语音或文字的语言，界面语言仅作回退。**
3. 在“我的机器人”扫描并明确选择设备后连接；运动需要另行开启本次许可。
4. Gemini / 标准 Vertex 支持 Live 语音与相机；Qwen、Groq、Fireworks、Ollama 和兼容服务使用文字。图片只由用户显式附加。
5. 按需授权麦克风、相机和附近设备；旧 Android 扫描需前台位置权限。进入后台结束采集并请求身体停止。

### 功能与实验边界

- 原始本地眼睛、离线人格/待机、设置、机器人列表、文字输入、显式图片附件、组件诊断与测试入口。
- 原生连续音视频、CameraX 共用相机会话、串行 GATT、应用层 ACK、停止优先的运动仲裁。
- 随包 EfficientDet-Lite0 int8，CPU 本地目标检测，最多 10 Hz，不需 Roboflow。
- **相对视觉跟随为实验功能**：选择具体目标、稳定后设定当前框面积参考、单独授予运动许可，再开始。偏离中心只转向，框变小时短脉冲前进，达到参考大小时停止，不自动倒车。
- 丢失、歧义、检测超过 300 ms、相机切换或 BLE 异常使跟随失效；需要重新选择目标。
- **不测量厘米距离，不提供避障或防跌落能力。实际速度和停止效果须用实体机器人标定。**

### 验证状态

| 项目 | 当前记录 |
| --- | --- |
| 逻辑测试 | 59 项通过（0.2.1） |
| 眼睛 / WebView 资源回归 | 5 项通过 |
| Android 模拟器界面冒烟 | 1 项通过，含双语、Activity 重建和实际眼睛像素 |
| 本地模型执行 | 1 项明确跳过；当前 Apple Silicon ARM64 模拟器存在 SVE/SME 兼容问题 |
| Lint / APK | 0 error；已构建并校验调试签名 |
| 真机 Live、BLE、目标检测及前进跟随 | 尚未验收；20 轮对话、30 分钟运行等均待测 |

完整条件见 [验收记录](docs/acceptance.md)，架构见 [architecture.md](docs/architecture.md)。跳过不计作通过。

### 构建

准备 JDK 21、Android SDK Platform 36 / Build Tools 36.0.0，设置 `JAVA_HOME`，用 Android Studio 或本机 `local.properties` 指定 SDK。最低 API 24，compile/target 36。依赖通过 Gradle 和 npm lockfile 固定。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。资源已随源码提交，普通 APK 构建不需要 Node。如修改眼睛或人格：

```sh
npm ci
npm run build:resources
npx playwright install chromium
npm run test:face
python3 scripts/check-artifacts.py
# 连接设备后执行
./gradlew :app:connectedDebugAndroidTest
```

`LOOI_CHROME_PATH` 可指定已有 Chrome。macOS ARM64 可选用 `scripts/bootstrap-sdk.py`，脚本使用 `JAVA_HOME` 并会请求接受 Android SDK 条款；也可使用 Android Studio SDK Manager。图标来自根目录 `icon.png`，可用 `python3 scripts/build-icons.py` 重建（需 Pillow）。

### 服务配置与隐私

凭据在设备上用 Android Keystore AES-GCM 加密，普通设置存 DataStore。仓库、APK 不附带 API 密钥、个人模型配置、完整设备地址、录音或签名私钥。在线对话会把当前请求及已开启的音视频发送给**用户所选服务**，由其隐私政策处理；本地识别本身不需要网络。详见 [隐私说明](PRIVACY.md)。

Vertex Google 授权需要自行注册 Android OAuth 客户端：包名 `app.looi.companion`、当前安装签名 SHA-1（`./gradlew :app:signingReport`），并启用 API / 账号权限。无 Play services 时可输入短期 access token。不要使用 iOS Client ID。

Ollama 默认可通过 `adb reverse tcp:11434 tcp:11434` 访问 `http://127.0.0.1:11434`。局域网明文 HTTP 需自行构建主机白名单：

```sh
./gradlew :app:assembleDebug -PlooiLanHosts=192.168.1.20,looi.local
```

仅接受私有 IPv4 / `.local`；公网使用 HTTPS。示例地址不是内置个人配置。

### 开源协议与来源

**本项目新增的 Android 原生代码、测试、构建脚本及文档使用 MIT**，具体范围和例外见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。原始眼睛、网页参考、其生成资源及图标来自 [LOOI_DF26](https://github.com/xlle-er/LOOI_DF26) / iOS 工程；上游尚未声明许可证，本次不替上游或第三方授予权利，**不能把整个资源包视为 MIT**。商标与图标权利不因代码许可证转让。模型与依赖保留各自协议，完整文本见 [licenses](licenses)。

提交问题前请删除密钥、令牌、设备地址、个人图片和对话；漏洞请使用仓库的私密安全报告入口，见 [SECURITY.md](SECURITY.md)。

## English

A native Kotlin + Jetpack Compose companion app. A restricted local WebView renders the original eyes and choreography; native code owns audio, CameraX, AI networking, BLE and relative visual following. The installed app needs no Node.js, web server or Roboflow.

### Download and first use

Download `LOOI-Android-0.2.1-debug.apk` from [Releases](https://github.com/xlle-er/LOOI-DF26-ANDROID/releases). Requires Android 7.0 / API 24 or later. Allow installation from your download source and verify `SHA256SUMS.txt`. This is a **debug-signed experimental preview**, with physical robot acceptance still pending.

1. Offline companionship works without credentials or a robot. Online AI uses your own provider credentials.
2. Choose system language, Simplified Chinese or English. **AI prioritizes the language of your current spoken or written message**, with UI language as fallback.
3. Scan and explicitly choose a robot in My robots. Enable session motion permission separately.
4. Gemini and standard Vertex support Live voice/video; Qwen, Groq, Fireworks, Ollama and compatible endpoints use text. Images are attached only by explicit user action.
5. Grant microphone, camera and nearby-device permissions when needed. Older Android versions require foreground location for BLE scanning. Backgrounding ends capture and requests a body stop.

### Features and experimental following

Includes local eyes, offline personality/idle behavior, settings, robot selection, text/image input and diagnostics. Native media, serialized GATT with application ACKs, and a shared motion arbiter coordinate robot control.

Bundled EfficientDet-Lite0 int8 runs on CPU at up to 10 Hz without network inference. To follow, select a specific target, set its apparent area as reference once stable, explicitly authorize session motion, and start. The robot turns toward an off-center target and uses short forward pulses when its box becomes smaller. It stops near the reference size and never automatically reverses. Stale detections over 300 ms, ambiguity, target loss, lens changes or BLE faults invalidate tracking and require reselection.

**Following estimates relative apparent size only. It does not measure physical distance, avoid obstacles or detect drop-offs. Actual speed and physical stopping require calibration on your robot.**

### Verification status

59 logic tests passed for 0.2.1; 5 face regression tests passed in the earlier acceptance run. One API 35 emulator UI smoke test passed, covering both languages, Activity recreation and visible eye pixels. Lint reported zero errors; the debug APK signature was verified. One local model execution test was explicitly skipped because the current Apple Silicon ARM64 emulator reports incompatible SVE/SME capabilities. Live media, physical BLE, real camera detection and forward following remain unverified on hardware. The 20-turn and 30-minute acceptance runs are pending. See [acceptance record](docs/acceptance.md) and [architecture](docs/architecture.md).

### Build and configuration

Install JDK 21, Android SDK Platform 36 and Build Tools 36.0.0. Set `JAVA_HOME` and configure your SDK in Android Studio or an untracked `local.properties`. Minimum API is 24; compile/target SDK is 36. Gradle and npm dependencies are locked.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Bundled face resources are committed, so normal APK builds need no Node. To regenerate them, run `npm ci` and `npm run build:resources`. For browser checks run `npx playwright install chromium` and `npm run test:face`; `LOOI_CHROME_PATH` selects an existing Chrome installation. With an attached test device, run `./gradlew :app:connectedDebugAndroidTest`. Validate asset hashes using `python3 scripts/check-artifacts.py`.

The optional macOS ARM64 SDK bootstrap script uses `JAVA_HOME` and asks you to accept Android SDK terms. Regenerate launcher icons from the supplied `icon.png` with `python3 scripts/build-icons.py` (Pillow required).

For Vertex authorization, register your own Android OAuth client for `app.looi.companion` and the installed signing SHA-1 (`./gradlew :app:signingReport`); enable the API and account permissions. Do not reuse an iOS client ID. Manual short-lived access tokens are supported on devices without Play services.

For Ollama use `adb reverse tcp:11434 tcp:11434` and `http://127.0.0.1:11434`. To allow private LAN HTTP, build with `-PlooiLanHosts=192.168.1.20,looi.local`. Only private IPv4 / `.local` hosts are accepted; use HTTPS for public endpoints.

### Privacy and licensing

Credentials are encrypted on device using Keystore AES-GCM; ordinary settings use DataStore. No API credentials, personal provider configuration, recordings or private signing keys are shipped. Online requests and enabled audio/video go to **your selected provider** under its privacy terms. Local object detection itself does not require network access. See [PRIVACY.md](PRIVACY.md).

**New Android native code, tests, build scripts and documentation are MIT licensed**, subject to the scope and exceptions in [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md). Imported eyes, web reference code, generated derivatives and the icon originate from [LOOI_DF26](https://github.com/xlle-er/LOOI_DF26) / its iOS project, whose upstream has not declared a license. This repository does not grant third-party rights over them; **the complete asset bundle must not be treated as MIT**. Trademarks and artwork retain their owners' rights. Models and dependencies keep their own terms; see [licenses](licenses).

Remove secrets, full device identifiers, private images and conversations before filing an issue. Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## DF26 设备管理 / Device management

“我的机器人”中的设备管理可通过 BLE 配网或 Wi-Fi 连接设备，支持局域网发现、方向校准、300 毫秒点动、停止和本地/在线 OTA。设置保存在 ESP；连接后不再自动点头。首次安装、分区和管理密码见 [Firmware 仓库](https://github.com/xlle-er/LOOI-DF26-Firmware)。

Device management in My robots provides BLE provisioning, LAN discovery/Wi-Fi connection, motor calibration, 300 ms tests, stop, and local/online OTA. Settings are persisted on the ESP. Connection no longer triggers an identification nod. See the Firmware repository for installation, partitions and the management password.

HTTP device connections accept only private IPv4 addresses. DHCP addresses are validated at runtime rather than enumerated in the Android network security XML. Online release downloads require trusted HTTPS.

## 热点升级与联网恢复 / Hotspot update and internet recovery

先在有互联网时检查版本、下载并校验固件，再连接 ESP 热点上传。网页使用本机浏览器存储，App 使用自己的文件存储；已下载文件可在页面或 App 重启后继续使用。重新上传前再次校验，失败下载不会替换上一个完整包。

“升级后关闭 ESP 热点”默认开启，需要设备固件 2.0.1 或更新版本支持。只有成功校验并切换到 OTA 目标分区后，下次启动才跳过自动开启热点；不会删除网络或方向设置，BLE 仍可用，长按 BOOT 3 秒可重新打开热点。该设置只影响升级后的这次启动，普通断电重启仍按原配网策略工作。关闭设备热点不保证手机自动恢复互联网，启动 AI 陪伴前应检查手机流量或可上网的 Wi-Fi。

从仅热点 OTA 初版或 2.0.0 首次升级时，旧固件无法保存这个选项：先下载 `.bin`，再通过 `http://192.168.4.1/` 的设备面板上传，完成后手动断开热点。App/网页管理接口不保证兼容仅热点 OTA 初版。2.0.1 的关闭开关对安装该版本之后发起的 OTA 生效。

Download and verify firmware while online, then join the ESP hotspot to upload. Prepared packages survive page/app restarts and are verified again before upload. Failed downloads preserve the last complete package.

Close ESP hotspot after update is enabled by default and requires device firmware 2.0.1+. After a verified OTA activates its target partition, the next boot skips automatic hotspot startup. Network and calibration settings remain intact; BLE stays available and holding BOOT for three seconds reopens the hotspot. This is a one-boot choice; ordinary subsequent power cycles use normal provisioning behavior. Your phone decides whether internet reconnects automatically. Restore mobile data or internet Wi-Fi before AI companionship.

The initial hotspot-only firmware and 2.0.0 cannot store this option. For the first upgrade, download the application `.bin`, join the hotspot and upload it through the device's own panel at `http://192.168.4.1/`, then leave the hotspot manually. Modern management APIs are not guaranteed on the initial firmware. The 2.0.1 option applies to OTA operations initiated after that firmware is installed.
