# 原生架构与接口

## 所有权与生命周期

Application 的 `LooiRuntime` 持有设备、网络、协程 scope 和会话代次；AndroidViewModel 给 UI 暴露同一实例。Activity 重建只重建 UI 与眼睛视图，不重新启动会话。CameraX 绑定 ProcessLifecycleOwner；前后台判断也由 ProcessLifecycleOwner 驱动，避免旋转或权限弹窗结束采集。

`SessionGate` 为 idle → starting → active / testing → stopping → idle。开始、权限结果、文字请求和恢复检查代次。退出使代次失效，取消动作和网络，停止音视频并优先请求身体停止。WebView 进程失败只使视觉界面和动作失败，不直接销毁 Live。

## 数据流

- AudioRecord：优先 16 kHz PCM16；回退 48 kHz 时流式 3:1 转换，跨读块保留剩余样本。回声消除按设备能力启用，效果待实测。
- Live：setupComplete 后启动采集；20 秒心跳；应用发送 channel 48 条，并限制 OkHttp 发送积压；视频拥塞时丢弃新帧。单条接收消息上限 4 MB。
- AudioTrack：24 kHz PCM16，80 ms 预缓冲，单次写入最多 200 ms，已调度最多 600 ms，总队列 60 秒。分块存储，按 unsigned playback head 统计实际完成，处理计数回绕与恢复代次。
- CameraX：一个 Preview＋ImageAnalysis。原生预览、最多 10 Hz 的本地检测、最多 1 Hz 的 JPEG 上传共享镜头；模型输入使用正向未镜像画面，前置预览框单独镜像。
- 检测：`Detection(id,label,score,Box(x,y,w,h),timeMs)` 坐标归一化，时间使用 elapsedRealtime。跟随锁定后用同类框 IoU 延续本地 ID，歧义即失效。

## 消息桥

只允许 `https://appassets.androidplatform.net` 主框架向 `LooiNative` 发消息，禁止外部导航、文件访问和远程资源。CSP 禁止网络连接。

JS → native：`{id, kind, payload, runId}`；kind 为 ready / command / photo / scenarioDone。native → JS：state、reply、run、cancel。动作请求使用原始 scenario 白名单，native 校验 operation ID、运动许可和参数；原生 ACK 返回后，适配器才向原始命令队列确认。原始编排的 partial/取消/失败不报告完成。

## 运动接口与停止

`RobotTransport.send(JSONObject)` 必须返回相同 ID 的固件应用层确认；Gatt 写成功仅是传输完成。通知按字节直到换行解码，缓冲上限 65536 字节。GATT 操作串行，包长以协商 MTU 减 3 决定，默认 20。

`MotionArbiter` 的租约防止旧控制来源继续运动，优先级为手动遥控 > 测试 > 显式动作 > 跟随 > 待机；停止取消当前指令、使排队旧代次失效，并合并并发停止请求。原生再次限制速度、时长、ramp 和舵机 pulse；不会发送 raw PWM / config_update。

跟随固定 150 ms 脉冲，控制意图不形成历史队列。横向死区 14%；目标面积比 <0.8 时开始前进，>=0.95 时停止；不倒车。独立 50 ms watchdog 检查 300 ms 新鲜度，失效时不等待普通 ACK 就发起停止。

## 双语、权限与诊断

双语字符串在 `L10n.t(zh,en)` 相邻声明，语言选择保存在 DataStore。AI 提示词优先遵循当前用户语音/文字的语言；系统/界面语言只作为回退。

权限按使用时请求：开始语音要麦克风，相机可降级，打开机器人列表才扫描。Android 12+ 请求附近设备；旧系统前台扫描位置权限。没有后台位置、后台通话或后台相机服务。

UI 诊断显示会话、录音、实际音频缓冲、输出路由、Live 连接及视频计数、机器人就绪、跟随状态。完整设备地址只作为本机选择/别名键；不写到日志和导出文件。

## UI tokens

Material 3 深色主题：primary `#8DE8CB` / onPrimary `#00382B`，background `#101716`，surface `#182220`，正文 `#E3EEE8`。使用 Material typography、8/12/16/24 dp 间距、16/24 dp 圆角和标准至少 48 dp 控件。控制区、设置和弹窗可滚动；无障碍启用时不自动隐藏控制，文字模式始终保留输入。

Android 的宿主页用当前视口的明确像素高度设置 html/body/faceCanvas，避免 WebView 的百分比高度与 size containment 将 body 算为 0；原始眼睛样式不改写。进入/退出陪伴只重建眼睛视图，音视频与 Live 仍由会话容器持有。


## 手动遥控 / Manual remote

`RemoteControl` 与界面分离，由应用 scope 持有，状态通过 StateFlow 订阅。设置页与陪伴页共享同一遥控实例；Activity 重建不会创建第二个控制循环，旧视图 pointerInput 的 finally 会释放手势并请求停止。真正进入后台关闭遥控，保持现有应用级清理流程。

遥控打开时撤销原运动许可、取消动作和跟随；等待跟随 finally / scenarioLock 下旧动作清理完成，再发送停止并等待真实 ACK。只有停止确认且机器人就绪时进入 READY。运行中暂停 AI/待机身体指令和动作测试；AI 对话不受此许可影响。关闭后运动许可保持撤销，不自动恢复跟随或 AI 身体动作。

`INACTIVE → PREPARING → READY → FAILED / STOPPING`；epoch 隔离旧 ACK，单个手势 ID 拒绝第二根手指和失效的输入。松手、移出范围、手势取消、模式改变、关闭和后台均停止。速度 0.05–0.30、默认 0.15；摇杆中心死区 12%，线性/角向输出绝对值之和不超过所选上限。运动租期 400 ms、ramp 60 ms；一个 ACK 返回后延迟 100 ms 再续发，不排历史输入。停止与运动确认最多等待 6 秒，超时或拒绝进入 FAILED 并请求停止；短运动租期限制没有续发时的固件运动时长。

D-pad 与摇杆都处理原始触摸的释放/取消；TalkBack 方向按钮和摇杆自定义动作使用 200 ms 输入脉冲并走相同 ACK/停止逻辑。固定版本的 iOS `LooiRemoteControl` / `LooiRemoteView` 作为行为参考，不导入 Swift 源码。

Physical speed, direction and stopping remain hardware acceptance items. Firmware ACK does not prove mechanical stopping.
