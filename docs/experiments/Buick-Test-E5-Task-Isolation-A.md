# Buick-Test ← E5：T62/T66 任务隔离试验 A 阶段

**状态：代码实现 / CI与真实设备尚待验证；禁止在未实测前合并 Test。**

- 统一规则：Buick 权威 PROJECT-GOVERNANCE v1.1。
- Test 基线：`e6c0665b4936bc6c31fcc5a6e52c28c7bac1adc6`；来源 E5 `a65f502cdbb1af5c7ba902a1579e5322854bb723`。
- 本轮只引入新车机任务 `NEW_TASK|MULTIPLE_TASK|EXCLUDE_FROM_RECENTS`、已成功启动任务的单次会话内复用判断和默认地图首次新任务隔离；包含原 E5 单测。
- 保留 Test 专有的 iAP2、音频、连接方式、页面布局、APP ID `io.github.buick.test.cardeck` 和 Test Release。
- **未引入** E5 T63/65 `DESTROY_CONTENT_ON_REMOVAL`、TouchRouter 状态机、更新/媒体/UI 等，与本试验隔离。后续阶段单独实验，避免因同时修改虚拟屏使 Android 崩溃原因难以识别。
- 风险：不同 Android ROM 的任务栈/最近任务处理不同；shell 的 `Starting:` 代表启动请求成功而非实际显示成功。
- 验收：单测通过后，先使用独立签名实验包，车机 QQ 音乐打开/返回共5次、地图全屏/分屏10次、断开重连3次；车机任务不在手机 Recents 生成新卡片，手机原有任务不被关闭，触摸/视频/A2DP 无回归或新增系统重启。
- 若仅完成 Debug CI，没有单独的 schema2 签名 prerelease，用户设备**不能直接通过试验版本入口安装**；还需签名和 versionCode/升级退回核验。
- 专项进度记录于 GitHub Issue #15，项目交接总索引 #12。
