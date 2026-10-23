# Buick-main T43 — 重启后系统崩溃取证补抓

日期：2026-10-23。问题：18:37 手机蓝牙 ON→OFF，CarDeck/Android 服务疑似系统重启；旧版系统日志按钮仅抓取最近 120 条 main/system 警告、只保留 64 行。重启后上传的 73 条系统日志属于 18:40 之后的普通警告，没有 system_server crash 栈，不能反推不存在崩溃。

## 新诊断入口
- 诊断入口增加「重启崩溃取证 · 找回上轮日志」按钮。仅在 Gateway 已连通且支持日志、上一批上传完成时可使用。
- 系统崩溃取证由用户手动点击触发，先显示内容，再次确认才通过现有加密 Gateway 的 `system` 来源上传；不增加任何自动采集/启动/上传系统权限。
- 第一个数据源：已有的 `files/main_debug.log` 中**上一轮进程**内容。CarDeck 每轮启动在文件中写入 marker，新增 `previousSessionSlice` 定位上一个区间；即使 Shizuku 尚未启动也可预览。
- 第二个数据源：经用户现有 Shizuku 授权，以 shell 读取 `logcat -L -d -b crash`（系统和厂商 ROM 支持时读取上次开机 Crash Buffer）。
- 第三个数据源：读取四类 Android DropBox dump（system_server_crash、system_server_watchdog、SYSTEM_RESTART、SYSTEM_LAST_KMSG），优先挑取可能与 system_server/虚拟屏/蓝牙故障有关的行。
- 每条 shell 命令输出由 tail 截断在 32–36 KB，6.5 秒超时，后台执行。摘要最多 64 行，每行经现有 LogRedactor 脱敏并限制 256 字符，避免原始堆栈、URL、MAC、标识、密钥直接上报。
- 原来的「上传系统日志」按钮保持原行为；系统与应用日志均使用现有 7 天 MCP 查询和确认上传通道。
- 如果手机重启后 Shizuku = NOT_RUNNING，用户需先打开 Shizuku 重新启动服务；此时仍可看到本地上一轮 App 日志及缺失原因。获取不到崩溃条目可能由于 ROM 未保留、Logcat 被清空、DropBox 过期或权限不足，不能据此断言“系统没有崩溃”。
- 不修改 E5 CarPlay 握手、音频 T41、视频渲染、虚拟屏和触摸。Buick-Test 保持独立。

## 验收
1. 在手机「诊断入口」连接 Gateway，进入「重启崩溃取证」，检查脱敏预览和来源标签（上轮App / 上次启动crash / DropBox）。
2. 初次 Shizuku 未运行时，提示启动 Shizuku 并只展示 App 历史；重新开启后再次预览，观察系统历史是否可用。
3. 用户点「取消」不上传；点「确认上传」后 MCP `get_device_logs source=system` 查验每行最长 256 字符，且可继续历史分页。
4. 断网或崩溃无法访问系统日志时，不影响 Gateway App 实时采集，也不触发重连尝试。
5. 所有原有 Android 单元测试 / Gateway 回归测试 / 隐私门禁和 main 独立发布通过。
