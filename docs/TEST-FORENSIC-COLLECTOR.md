# Test 包名独立系统取证实验

- 构建来源：`experiment/buick-e5-detailed-diagnostics` 已实测过的本地取证实现；**不是**合入冻结中的 `Buick-Test` 分支。
- Android应用ID：`io.github.buick.test.cardeck`，与 E5（`io.github.buick.e5.cardeck`）并存；如果同设备安装了以前的Test应用，此包会覆盖Test，但不更改E5。旧Test数据可能留在应用私有目录，退出前须导出，且版本号不能无损降级。
- 取证入口：`ForensicOnlyActivity`，独立前台 `RescueTraceService`。Manifest **没有** CarPlayService、MainActivity、BT/Wi-Fi/音频采集权限、媒体通知监听或Gateway服务；系统启动不会触发旧程序里的CarPlay服务，不申请蓝牙权限，不自动网络上传。
- Shizuku：不同 Android 包名需要独立授权；采集必须用户手动开启。4×512KiB 滚动脱敏日志，手动导出至 下载/CarDeck，断电重启后已写入内容留存，自动采集本身不跨重启复活。
- 只为用户个人诊断 Sony Android 15/RescueParty；不声称具备 root 或能读取所有内核pstore。
- 目标：采集软件保持固定版本，E5 功能融合试验可以单独升级/回归。原始日志不得上传公共Issue。
