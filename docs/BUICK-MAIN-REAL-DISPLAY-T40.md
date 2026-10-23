# Buick-main T40 — 恢复真实画面与触摸（带一键 T28 回退）

2026-10-23。基线：Buick-main 95367f954f5602d9cbdec2b76413b5939fed0290。

## 原因
前次已移植 T33–T39 UI 以及对应的安全区和触摸映射，但 Buick-main 在 Xperia XQ-DQ72 / Android 15 / B233 条件下仍强制 SonyB233CrashGuard.useDisplaylessDiagnostic 为 true，转用 EGL-only SafeDiagnosticFrameSource。该路径从不创建 Android 虚拟屏、不绘制 Launcher/Dock/第三方 App，也不做实际 HID 触摸注入，因此 UI 虽存在但无法正常显示和触控。

## 本次变更
- Buick-main 默认开启完整桌面与触摸（用户明确要求）：仍计算 SonyB233CrashGuard 的风险布尔值，但 t28Displayless = risk && !BuickMainDisplayMode.enabled(context)。正常路径调用原有 HiddenDisplaySource 与 VirtualLauncher，配合已移植的 T33–T39 UI/GL/HID 处理。
- 手机别克设置新增 **启用真实桌面及车机触摸** 开关，默认开启、独立于 CarPlay 曲面屏安全区域总开关。本次更改在下一轮 CarPlay 会话时生效。
- 对历史上系统重启的 Sony Xperia 1 V / Android 15 / E5 条件记录清晰风险警告。遇到系统重启，用户可在手机的别克设置关闭开关，恢复 T28 EGL 诊断视频（无虚拟屏/无车机触摸）以便排障。
- 不合并 Buick-Test T31 重连策略、T32 视频停滞策略、实验音频修改，保留 main 独立包名/更新地址。保持 Gateway/MCP 和匿名车辆记录、HID 参数兼容。
- 添加条件选择单元测试：Sony B233 默认真实桌面、显式关闭回退、其他设备默认使用正常虚拟屏。
- 注意：这并不修复潜在 system_server WindowContextListenerImpl 空指针风险；这里只按用户指令恢复功能并提供可操作的回退，真车仍需验证。
- 车机不应在行驶中调试；先连接视频模式+蓝牙音源，检查桌面、地图、触摸位置，再单独测试其他功能。

## 现场验收
1. 升级 Buick-main，手机别克设置确认“启用真实桌面及车机触摸”已开启。
2. E5 首次连接的日志应为 `BUICK-MAIN T40 WARNING`（若匹配 Xperia 风险）以及 `MODE: 隐形屏 已启动`、正常 VIDEO 出帧；车机出现 CarDeck 桌面且触摸注入有成功事件。
3. 主页面切换网格/地图，检查是否有位置偏移、0 帧黑屏或系统重启；请保留原始日志和时间戳。
4. 若发生再次手机系统重启，恢复手机后关掉 T40 开关并重连，出现 T28 EGL 测试画面而非虚拟桌面。
5. 安全区域总开关独立测试：关闭安全区时仍应有完整桌面与触摸。
