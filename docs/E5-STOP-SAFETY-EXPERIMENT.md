# E5 #34 iAP2 Stop-Safety 实验

- 上游兼容基线：`experiment/buick-e5-bluetooth-a2dp-preserve@8e324f5ab3cae2212002307c9aad593d6e2c3eea`，当前用户已安装T22，已验证程序不主动断B233配置文件，但#24音源端到端/#33握手未通过。
- **唯一行为改动 #34**：T37 等待 1.5s 和获取有效MAC重试 0.6s 遇线程中断时，保留中断标志并转化为 IOException，已有 `runSession` catch+finally 负责正常停止/释放；用户停止不再将 InterruptedException 冒泡到Java线程顶部成为 AndroidRuntime FATAL。
- **严格不动** T22识别、T39退避、RFCOMM/iAP2消息字段顺序、0x4300 ACK109等待策略、T26、AAC、虚拟显示。独立CI、签名预发布、新实验来源标签，不能以此宣布#33已修复。
- **L3手机验收建议**：将E5旧应用诊断日志先导出，直接覆盖签名新包；Test独立取证助手持续保持采集。停车下在CarPlay握手T37等待时用户主动断开，验证App未出现FATAL/可正常再启动；用Gateway对照断开时间，重复至少3轮。观察原有T22“未主动Profile断开”仍存在、应用重启更新诊断正常。未进行手机测试前不得合并稳定E5。
