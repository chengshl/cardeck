本次更新：全仓库已发布签名 APK 的 versionCode 基线保护（#39）。

- Buick-Test 继续采用 E5-TEST-YYMMDDNNN 可见版本名，与 E5、Buick、实验发布共享北京时间每日流水号。
- 除正式 E5/Test/Buick 及 Buick-main 旧通道外，分配器现完整分页读取所有具有 APK 资产的独立实验、取证与回滚 Release 的历史 versionCode。
- 若签名 APK 发布记录缺少有效 versionCode，立即停止分配，避免旧版覆盖或签名包版本码倒退。
- 保留 Git 标签原子抢号、冲突重试、跨日复算、SHA-256 实验短名及旧 14 位版本名兼容。
- 保持原应用包名、签名、实验 sourceBranch 绑定、APK SHA-256 校验及用户确认安装流程；不改动 CarPlay 音频、视频、蓝牙、触摸和 iAP2。
