本次更新：实验分支版本简称唯一性与来源校验加强（#39）。

- 正式 Buick-Test 版本名称继续采用 E5-TEST-YYMMDDNNN，跨分支共享北京时间每日三位流水号，Android versionCode 独立递增。
- 未知实验项目采用 EXP-可读简称-分支SHA256短摘要-YYMMDDNNN，防止长分支名末尾相同时产生相同的实验简称。
- 更新中心根据完整 sourceBranch 验证新实验版本名称，继续接受历史 14 位数字版本名称及旧 EXP 命名，保持实验项目精确订阅。
- 编号分配时一并读取 Buick-E5、Buick-Test、Buick 和旧 Buick-main 四条发布通道的既有 versionCode，避免旧包覆盖失败。
- 不改变 CarPlay 连接、视频、音频、蓝牙、触摸、APK 签名、包名、SHA-256 校验和用户确认安装流程。
