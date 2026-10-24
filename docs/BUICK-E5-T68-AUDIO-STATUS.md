# Buick E5/B233 · T68 音频状态显示修复与 2026-10-24 实车诊断

2026-10-24 17:19（北京时间），Buick-Test 版本 20261024171238（T67）已上传日志。

## 实车证据

- 17:18:56 用户选择开启声音并申请 MediaProjection；17:19:00 获授权。
- 17:19:46 车机接受 AAC-LC 48kHz / type=102 音频 SETUP（RTSP 200），返回 RTP dataPort=6000、RTCP controlPort=6011。
- 17:19:46 B233 的 RTP Wi-Fi network.bindSocket 失败 EPERM；RTCP Wi-Fi network.bindSocket 也失败 EPERM。不能确认 UDP 实际走向，需对照 VPN/default network。
- 17:19:47 音频 RECORD 成功；PCM 捕获显示 musicActive=false、全部样本为零。没有证据表明当时捕获到可听音乐。
- 17:19:53 RTCP 同步端口出现 ECONNREFUSED；车机接受 audio-only TEARDOWN type=102（RTSP 200）；重新声明视频资源、不声明主音频，modesChanged 成功。
- 17:19:56–57 即使音频 TEARDOWN 和资源降级都成功，视频/事件 TCP 仍 Connection reset，CarPlay 整个会话退出，随后普通蓝牙 A2DP/HFP 断开。
- 日志不足以认定是 AAC 参数、RTCP 端口、Android VPN/EPERM、车机音频资源状态中的哪一项独立导致整链终止；不要把 teardown=200 当成已稳定。

## T68 实际修改范围

- 此次**不修改音频 UDP/RTCP 报文与 CarPlay 协议时序**，避免同时改动多个变量使实车原因不可分辨。
- 首页原先只检查 `audioEnabledForSession && audioSender.isRunning`，因而把用户选择开启后发生的自动降级错误标记为「关闭」。
- 新增独立状态：未选择、已选等待启动、推送中、启动失败、异常已降级为仅视频。
- 保留 `audioRequestedByUserForSession`（用户选择）及本轮实际已尝试、自动降级标记，保证 T64 把 `audioEnabledForSession=false` 时界面仍如实说明故障，而不是宣称用户关了声音。
- B233 自动降级后的日志也明确记录「用户曾选择开启声音，但异常后降级」；添加纯单元测试。
- 仅 Buick-Test；不影响 Buick-main 音频禁用的稳定策略。

## 待验证与处理（独立于 UI 修复）

1. 真实播放允许被 Android AudioPlaybackCapture 捕获的音频，确认 `musicActive=true`、非零 PCM 峰值；如果始终是零，需要先查来源应用和 Android 播放路由/捕获政策。
2. 在车机热点上核对 `Network.bindSocket` 的 `EPERM` 及默认网络/VPN 走向（不做未经验证的进程全局绑网）。
3. 比较音频 SETUP/RTCP controlPort=6011 的正常 iPhone 抓包，解释为何车机返回可用端口却报告 UDP ECONNREFUSED。
4. 现有 T63/T64 stream-scoped TEARDOWN / modesChanged 成功仍挡不住车机约 20 秒窗口断连，需要优先检查 B233 对音频流生命周期的实际要求。
5. 如伴随 Xperia system_server / RescueParty 重启，独立取证，不把系统崩溃混为车机 RTCP 拒绝。
