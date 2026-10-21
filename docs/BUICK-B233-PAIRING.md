# Buick E5 (B233) — 系统配对确认与 iAP2 二次识别诊断（T19/T20）

## 事实依据

2026-10-19 原生 iPhone sysdiagnose（11:34 初次配对）有系统蓝牙 SSP User Confirmation Request 与 User Confirmation Request Reply，之后才开始 iAP2。该会话在 11:34:13、11:34:14 发出两次 0x4300 后，车机在 11:34:16 **再次发出 0x1D01**；iPhone 重发 0x1D02、MFi 请求和 0x4300，随后收到车机 0x4301。11:47、11:51 已配对重连没有看到新的 SSP 用户确认事件。

CarDeck 2026-10-20 22:29:44 曾成功收到 0x4301；2026-10-21 T18 多次在 0x4300 后停止获得链路 ACK。日志尚不能证明车机在这些失败轮次发送过新的 0x1D01，也不能仅凭车机弹窗判断 Android 是否收到系统级用户确认请求。

## 本次变更

1. **T19（B233 专属）**：应用进程监听 Android `ACTION_PAIRING_REQUEST`、`ACTION_BOND_STATE_CHANGED`、`ACTION_ACL_CONNECTED/DISCONNECTED`。匹配 `BUICK_2427` 名称、已选 B233 地址或本轮已识别 B233 地址（系统首次配对时名称可能为空），不记录 PIN/数字配对码、也不自动接受配对。
2. **T19 连接保护**：如收到系统配对请求，最长 45 秒内暂停 B233 的主动 RFCOMM 重试和 HFP/A2DP 拆链；收到 `BOND_BONDED` / `BOND_NONE` 立即结束等待。未收到事件则原行为不变。
3. **T20（B233 专属）**：只有在 **等待 0x4301 的窗口内，确实再次收到符合 B233 身份的 0x1D01**，才执行最多一次 0x1D02/MFi/0x4300 恢复。期望 0xAA06 或 0xAA01 后再尝试 32B challenge；没有观察到该前置信号不会伪造成功。不修改其他车机、视频/音频 RTP 格式、VPN 路由或触摸代码。
4. 自动更新页继续显示 `## 更新说明`。本轮仍是未经实车验证的专项实验，**CI 成功不等于车机恢复成功**。

## 2026-10-21 11:24 日志复核与 T21

- 实测 APK `20261021111746` 对应 `00a83801`，确认 T19/T20 已装机。
- 三轮 `0x4300` 发生在 11:21:56、11:22:36、11:23:19，分别均没有获取其链路 ACK（序号 110），也没有收到 `0x4301`。
- 每轮 `0x1D01` 初始身份识别、32B MFi 签名响应、`0x4E0C` 的链路 ACK（109）正常；`0x4300` 后无新的二次 `0x1D01`，故 T20 合理地**不触发**。
- T19 捕获了 ACL 建链/拆链，但未捕获 `ACTION_PAIRING_REQUEST` 或 `BOND_STATE_CHANGED`；该日志**不支持**“车机已发送配对确认但 App 未答复”的断言。
- 对照 10 月 20 日 22:29:44 的成功轮次，上一条 `0x4E0C` 同样获 ACK，75B `0x4300` 发出约 60ms 后收到车机 `ACK=110` 和 `0x4301`；因此先保留现有请求格式和 MFi 策略。
- **T21 仅诊断**：记录 `0x4300` 整帧的脱敏 SHA-256 前缀及实时 `sentSeq/peerAck/alive`；在失败时区分**可靠链路关闭**与**应用层等待超时**，输出实际耗时；成功时也记录相同指纹。未改 Wi-Fi、VPN、音频、视频、触摸以及主动重连策略。

## 2026-10-21 11:46:51 T21 完整实测与 T22 A/B

- 版本 `20261021113450`、Sony XQ-DQ72、Android 15、纯视频模式。
- 9 次 75B `0x4300` 的 SHA256 前缀均为 `242202e1b7c81cb1`，发前 `sentSeq=109 peerAck=109 alive=true`；8 次于约 20s 后因 `packet 110 not acknowledged` 断开；另 1 次由用户手动取消。**无 `ACK=110`、无 `0x4301`、无二次 `0x1d01`**。
- 每次前续 `0x1d01/0x1d02`、`0xaa00/0xaa01/0xaa02/0xaa03/0xaa05`、`0x4e0c` 的 ACK 可见。**`0xaa03` 签名仍是跳过校验**，所以不能将该交互等同于严格密码学认证。
- 保留当前 `0x4300` 与 MFi 顺序不变，先验证奥迪 MIB3 来源的主动 HFP/A2DP 断开策略在 B233 上是否适用。
- **T22 单变量实验 A**：仅对经过 `BuickB233Route.isKnownReceiver` 识别的 B233，保留 HFP/A2DP/LE 音频配置文件，不调用 `BtProfiles.disconnect`；其他车型保留旧逻辑。新增 `BT-C T22: B233 实验：保留 ...` 诊断日志。
- 成功标准：先确认带音频配置文件的 RFCOMM 能否建立，再观察 `ACK=110` 和 `0x4301`，最后确认 AirPlay 视频。不触发并不证明 HFP/A2DP 没影响；该实验可通过安装上一版撤回。
- 用户在停车状态操作；本实验可能让普通蓝牙音频与车机同时保持连接，**不代表车机音频已被 CarDeck 接管**。

## 2026-10-21 12:16:49 T22 完整验证与 T23 虚拟链路修复实验

- T22 `20261021115535`：保留 HFP/A2DP 确认工作；12:11:21、12:12:42 两轮 `0x4300` 成功，`ACK=110` 且 `0x4301` 返回 130ms / 31ms。失败轮次与成功轮次 SHA256 前缀均为 `242202e1b7c81cb1`，排除了报文内容必然不合规的推断。保留 HFP/A2DP **不保证** 每次启动成功。
- 纯视频 + 车机手动切换蓝牙音源：用户验证能正常播放，音频实际走 Android 普通蓝牙 A2DP，独立于 CarPlay 音频推送；纯视频会话日志持续工作数分钟。
- 12:15:22 音频实验：`t102/media AAC-LC 48k`、约 943 个 RTP 包。采集输入持续 `非零0%` / `musicActive=false`，因此**尚无证据表明 CarPlay 音频捕获到真实音乐**。RTCP 6011 `ECONNREFUSED` 后已有代码仅停止同步包，而非立刻结束 RTP。
- **真正的 20s 自动断连触发**：B233 虚拟 iAP2 `seq=113`、`CSM=0x4171`，约 4 秒重传一次，`12:15:42.998` 因 `packet 113 not acknowledged` 主动杀掉虚拟链路。随后才出现视频 Broken pipe、音频 UDP refused 与蓝牙 ACL 断开。与此前 `0x5001` 的同类故障一致。
- T23 **仅 B233**：将 `0x4171` ListUpdate 和原先的 `0x5001` NowPlayingUpdate 视为单次投递、不触发本地致命重传看门狗；原消息照常发送。严格 ACK 仍适用于身份、MFi、`0x4E0C`、`0x4155`、`0x4158` 与其他车型。测试重点是音频开启后能否越过 20 秒，并继续保持画面；**不等于已解决音频静音或车机接受状态**。
- 如 T23 仍断开，先记录新的第一条失败事件、是否仍有 `packet ... not acknowledged`，以及 B233 是否主动 RESET；不能将音频 UDP refused 自动归因于 AAC 编码。
- 断连后自动重试仍会遇到 `0x4300 ACK=109`，应在虚拟链路稳定后再独立做“旧会话清理 / 车机重连节流”专项。

## 2026-10-21 12:30:26 T22 VPN 切换故障 / T24 网络路由专项

- 上传日志应用版本 `20261021115535`（**T22，不是 T23**），仅视频模式。12:23:46 B233 `0x4300 ACK=110`、`0x4301` 在 150ms 返回；AirPlay /pair-verify/SETUP/RECORD 成功，画面和事件通道持续到 12:27:08。
- 用户反映**关闭 VPN 时画面断开**；日志 12:27:08 首个断链信号为 `EVENT: 读取结束 - Software caused connection abort`，随后 VIDEO socket abort、iAP2 RFCOMM/ACL 断开。日志旧版**没有 VPN 状态广播/默认网络切换事件**，所以只能认定时间相关，尚不能认定由 VPN 直接杀掉 socket。
- 后续重连进入 RFCOMM、`0x1d01`、MFi、`0x4e0c ACK=109`，但 `0x4300 seq110` 反复不获 ACK，约 20s 后本地看门狗关闭。更早 12:21～12:23 已出现相同故障，因此 VPN 并非其必要条件。
- **已确认代码缺陷**：`WifiCarPlayConnector.bindCurrentWifi()` 即使 `connectivityManager.bindProcessToNetwork(network)` 返回 `false`，仍通过 `onResult(true)` 报告“绑定成功”。日志确实显示 `WiFi network 120 bindProcessToNetwork=false`。无法据此断言路由已经被系统正确绑定。
- **T24 试验**：B233 专属：显式将 AirPlay RTSP/事件/iAP 隧道/视频 TCP socket 通过车机 `Network.socketFactory` 创建；Timing UDP、音频 RTP/RTCP 使用 `Network.bindSocket`，尽量在 VPN 默认路由切换时保持原 WiFi 通道。其他车型网络行为不变，创建绑定失败提供带警告的默认路由回退。
- **T24 诊断**：增加进程绑定是否成功、车机 WiFi 网络句柄、系统默认网络是否 VPN，以及 VPN 开关过程系统默认网络回调；同时保留车机 WiFi 丢失独立诊断。不能保证 Android VPN lockdown/系统主动杀 socket 场景自动恢复。
- 当前无法修复证据不足的 B233 旧会话拒绝 `0x4300`，**不通过删除配对或无限重试掩盖故障**。T24 首先验证在已成功推视频时开/关 VPN，确认视频是否继续、下一条断线原因。纯视频+蓝牙 A2DP 为目前车内稳定路线。

## 2026-10-21 13:01:38 T24 真车复核 / T25 蓝牙开关专项

- 用户确认 T24 在 VPN 开/关时**仍然断开**，但断开后**大多能自动重连**。
- Sony XQ-DQ72 Android 15，APK `20261021124408`，仅视频模式：12:56:27 首次成功建会话；12:59:47 VPN 从开→关时 EVENT/VIDEO 发生 `Software caused connection abort`，12:59:56 重新获 `0x4301`；13:00:48 VPN 从关→开时同样发生 socket abort，13:00:58 再次成功获 `0x4301`。
- T24 的明确限制：VPN 开启时 `bindProcessToNetwork=false`，B233 `Network.socketFactory.createSocket()` 与 `Network.bindSocket(UDP)` 均返回 `EPERM`；因此它们实际退回默认网络，VPN 切换仍会触发已有 TCP socket abort。VPN 关闭后绑定 WiFi Network 125 成功。避免将“代码尝试绑定”描述成“已真正绑定”。
- 事件次序：首先 EVENT/VIDEO socket abort 与 Android 默认网络从 `VPN` 切换为 `WiFi`（及反向）；约 0.5–1.5s 后 iAP2/蓝牙 ACL 才断开，之后 ACL 建立、`0x4300` 收到链路 ACK 和 `0x4301`。不能把这一现象推定为蓝牙无线电总开关 OFF。
- **T25 仅诊断**：原有 `B233PairingMonitor` 只记录配对/ACL，未订阅 `BluetoothAdapter.ACTION_STATE_CHANGED`。现加入只读 `BT-ADAPTER T25: ON/TURNING_OFF/OFF/TURNING_ON` 初始状态和状态变化日志。不关闭/开启蓝牙，不改 HFP/A2DP/配对/重试行为。修复 VideoSender 在 socketFactory `EPERM` 退回默认路由时仍打印“指定 WiFi”的错误文案。
- 待验证：若出现用户所见的“蓝牙自己关掉”，检查当时日志是否有明确 `BT-ADAPTER T25: ON -> TURNING_OFF -> OFF`。如仅 `BT-PAIR T19: ACL ... 断开` 而始终无适配器 OFF，说明只观察到车机链路断开，并非手机蓝牙开关真正关闭。若确有 OFF，需 Android 系统事件日志确认触发者，不能直接归因 CarDeck。

## 2026-10-21 13:04 用户纠正：蓝牙音频连接掉线，而非手机蓝牙总开关

- 用户确认：目前 CarDeck 仅视频模式，车机切到蓝牙音源即可播放手机普通 A2DP 音乐；VPN 切换时除了 CarPlay 重连，**普通蓝牙音乐也会断音**，并非 Android 的 BluetoothAdapter 开关 OFF。
- 实测对比：12:55:26 `BT-C T22` 观察到 B233 `通话(HFP)/音频(A2DP)` 同时在线，12:59:47 VPN 切换造成 EVENT/VIDEO socket abort，12:59:48 ACL 断开，12:59:50 RFCOMM 重连时只显示 `通话(HFP)`。**CarPlay 的 `0x4301` 成功不等于 A2DP 已恢复**。
- 已检查源码：B233 T22 主动连接循环不调用 `BtProfiles.disconnect`；`retireSession()` 也不主动拆 HFP/A2DP。不能把这个 A2DP 断开断言为 App 主动执行；可能由车机/Android 在旧 CarPlay 会话结束时释放普通音乐配置文件。
- **T25 扩展**：除了蓝牙适配器只读监测，还为 B233 监听 `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED` 和 `BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED`，记录 `BT-AUDIO T25: A2DP/HFP CONNECTED/DISCONNECTED`；不自动重连或切换蓝牙音源。
- 测试目的：在 VPN 切换前后，看 `BT-AUDIO` 是否独立恢复到 A2DP CONNECTED；即使 RFCOMM/CarPlay 成功，A2DP 仍断开则需专项研究恢复机制，而非重建 CarPlay 配对。

## 2026-10-21 13:26 实车 A/B：VPN 不直接导致蓝牙音乐断线 / T26 资源所有权实验

- 用户确认：**不运行 CarDeck、仅普通蓝牙 A2DP 音乐**时切换 VPN，音乐完全不受影响。故不应将普通蓝牙断线归因于 VPN 开关本身。
- T25 实车日志：B233 在 13:21:31 建 A2DP、13:21:48 CarDeck 手动断开后 13:21:49 ACL/A2DP/HFP 一起断开；13:22:24 A2DP 恢复。13:23:06 VPN OFF 时 CarPlay EVENT/VIDEO socket abort，13:23:08 ACL/A2DP/HFP 断开，13:23:41 A2DP 恢复。表明问题与 CarPlay/iAP2 会话终止过程相关；具体由车机还是 Android 发起 ACL 断开仍待蓝牙 HCI 确认。
- 另发现 CarDeck `AirPlayClient.assertModes()` 一直声明主屏资源 `resourceID=1` 和主音频资源 `resourceID=2` 都归手机 CarPlay（`entity=permanentEntity=1`），**包括用户已经选“仅视频”、让普通蓝牙 A2DP 播放音乐时**。是否因此导致 B233 对普通蓝牙的异常资源仲裁尚未证实。
- **T26 受控单变量试验**：仅当 B233 且本次`audioEnabledForSession=false`时，`modesChanged` 只声明主屏 `resourceID=1`，不声明 `resourceID=2`；车机 `changeModes` 再次要求资源时亦按相同规则。B233 推送 CarPlay 音频模式、其他车型原双资源不变。
- **失败边界**：有些车机要求屏幕 SETUP 必须同时声明音频资源，本版可能出现屏幕拒绝 SETUP / 视频无法启动；如复现，应立刻回退 T25，不可将 T26 当成已证实修复。即使视频能启动，仍需以 A2DP/ACL 事件和 VPN 开/关后的音乐是否中断判断效果。
- 首次验收应看日志 `AIRPLAY T26: resource experiment B233=true sessionAudio=false claimCarPlayAudio=false`，`modesChanged` 状态 200，随后 `VIDEO` 成功；再分别测视频内切 VPN 和手动断开 CarDeck，观察 A2DP 连接是否保持。此实验不改 HFP/A2DP 操作、BT RFCOMM 关闭、音视频 RTP、VPN 路由。

## 真车验证（仅停车状态）

- **A：首次连接**：手机已配对 E5、已连热点，启动 CarDeck，检查 `0x4300` 是否获得链路 ACK、是否出现 `0x4301`。
- **B：车机再次显示“请在手机端确认”**：保持该界面，查看手机系统蓝牙配对弹窗并手动确认；不要立即重启、删除配对或开启多轮重试。导出 CarDeck 日志，重点核对 `BT-PAIR T19` 的 `PAIRING_REQUEST` / `BONDING` / `BONDED` 顺序。如系统不下发广播，日志缺席**不能证明**没有配对请求。
- **C：0x4300 无回应**：检查 `iap2 B233 T20: 收到第二次 0x1d01` / `第二次 MFi` / `二次认证结束` / `0x4301 accepted`。没有新的 0x1D01 就不会触发 T20。
- **D：视频稳定性**：重复进行纯视频启动、退出、自动重连；再单独测试音频。避免把已有音频约 20 秒断连问题与 SSP 确认混为一谈。

## 诊断边界

系统 SSP 确认由 Android 蓝牙栈负责：本应用不会调用 `setPairingConfirmation`、`setPin`、`createBond` 或 `removeBond`。请保留车机弹窗出现的大致时间，以便比对系统事件与 iAP2 消息。某些 Android 定制系统可能不向三方应用转发完整的系统蓝牙事件；届时需另做受控 HCI/蓝牙系统日志验证。

**仍待验证**：T20 的 0x1D01 再识别是否在 B233 断连后真实出现、以及 HFP/A2DP 的频繁断开是否与 SSP/0x4300 非 ACK 相关。所有这些暂不能写成已解决。
