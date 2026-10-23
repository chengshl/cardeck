# Buick-main T41 — 暂时屏蔽 CarPlay 声音连接

**原因**：E5 当前稳定工作方案为仅视频 + 车机原生蓝牙 A2DP 音源；CarPlay 音频推送尚未调通，启用后可能在约 20–30 秒断连，并导致无法重连。用户明确要求 Buick-main 暂停该功能，Buick-Test 保留实验用途。

## 用户侧
- 从「启动连接」流程移除「开启声音」选项，点击连接直接使用仅视频模式，不再弹出选择声音的对话框，也不会申请 RECORD_AUDIO / MediaProjection 权限。
- 原车机侧声音卡片改为「CarPlay 声音推送 · 未开放」，禁用授权按钮并解释仍可通过普通蓝牙播放音乐。底部已连接状态也标记未开放。
- 不影响视频、触控、Dock/地图、蓝牙配对、普通蓝牙 A2DP 音乐和车机本身的音频路线。

## 连接层强制保护
- 新增 `BuickMainAudioPolicy`，在主线中始终返回未开启 CarPlay 音频、拒绝 Claim 主音频资源。
- `SessionController.audioEnabledForSession` 的 setter 强制过滤任何残留的启用命令。
- `startAudioChannel` 首行检查策略，杜绝 Audio SETUP/RTP；`maybeStartAudioNow` 也先行退出，避免迟到的投屏授权触发动态补启。
- 初始 `assertModes` 和后续车机 `changeModes` 均不抢占 CarPlay 音频资源，保留普通蓝牙 A2DP。
- 独立单元测试验证 legacy requested=true/false 均不能启用、不能声明音频资源。
- 保留音频底层源码，今后调通后可在 Test 经过独立验证再按控制流程移植；**不修改 Buick-Test 和其他分支**。

## 发布校验
- Buick-main 隐私检查、Gateway/MCP、Android 单元测试、签名与单独发布。
- 实车验证连接后为纯视频，`AUDIO T41` 记录跳过音频 SETUP，车机蓝牙 A2DP 音乐可播放。
