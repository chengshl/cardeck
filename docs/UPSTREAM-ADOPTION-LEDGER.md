# CarDeck 上游优化采纳与历史融合台账

> **唯一台账维护位置：`Buick` 本文件 + [GitHub #23](https://github.com/chengshl/cardeck/issues/23)。**  
> 初次核查：2026-10-26，北京时间。  
> 上游候选源：本仓库 `main`，检查 HEAD `503ed3730d5a9365272294b79306cea5fcce115b`，对应 fork 的源仓库 `shaonialife/cardeck`。此时间点不代表外部源仓库已经完全同步。  
> 本次仅进行代码/提交比对与状态登记，**没有运行新增实验、没有发布 APK，也没有完成新的手机或实车验收**。

## 使用及更新规则

台账有两种入口：
- 本文件保留**当前决策快照与验证证据**，仅在 `Buick` 权威维护。E5、Test 不分别建立内容不同的台账。
- [#23](https://github.com/chengshl/cardeck/issues/23) 通过追加评论保存**每轮上游评估、实际移植或借鉴、CI/实车结果、撤回和取舍理由**；[#12](https://github.com/chengshl/cardeck/issues/12) 是项目导航索引。

上游同步/发现修复时按 `main` 的新增 SHA 逐项评估；每次采纳后必须更新对应状态，不允许只写“待融合”而遗漏“已融合/借鉴”的事实。区分：`待评估`、`不采用`、`代码同源/已有实现`、`借鉴改写-实验中`、`直接移植-实验中`、`目标代码已合入`、`目标签名包发布`、`设备/实车通过`、`撤回/已取代`。一次融合涉及多个目标分支时逐分支登记，严禁把相同文件 SHA 误认为证明某次 cherry-pick 的历史，或把 CI 成功当作实测成功。

每条至少留：`来源(main SHA)`、`变更原理及模块`、`E5/Test/Buick 差异`、`优先级/风险`、`评估决定`、`实际目标 SHA/PR`、`CI/Release`、`验收证据/失败`、`回退和下一步`。必要时追踪上游同一机制后续修订，避免再次引入已经被推翻的旧方案。

## A. 新的上游优化候选（已查到真实 main 提交）

| 编号、上游 main 证据 | 作用及主要代码 | E5 / Test 现状（截至基线） | 判断与后续 |
|---|---|---|---|
| U01 [`09b39199`](https://github.com/chengshl/cardeck/commit/09b391994f8bf5a1fce514351bc4c1b39c5ff7aa) | GM/Cinemo 0x4301 未按短窗口及时到达时不要过早拆链；修改 `SessionController`、`Iap2SenderSession` 与单测 | 两线 `SessionController` 均没有 `PENDING_START_SESSION_WAIT_MS`，该具体等待机制**未融合** | **P0 候选 / L3**。与 Buick E5 0x4301 实车日志高度相关，先验证接入时序和超时/重试冲突，不直接移植完整 SessionController |
| U02 [`1cd903c4`](https://github.com/chengshl/cardeck/commit/1cd903c415ac7b61c4ff313a1e854445e32d6238) | MFi 迟到应答造成错误“成功长度”记忆，后续遇硬断链须推翻记忆；`MfiLengthMemory` + 测试 | E5/Test 当前 `MfiLengthMemory` 文件 SHA 一致，均不包含 `recordHardRejected`，**未融合此修复** | **P0/P1 候选 / L3**。适用于异常认证/无法重连排障；需先检查当前车机 MFi 挑战行为和回退 |
| U03 [`3095d64e`](https://github.com/chengshl/cardeck/commit/3095d64ed1fbe2ea6f52b202a80f28d7dd7749d1) | 基于真实日志的车型录制与回放回归测试，加入 replay 测试代码与 `tools/extract_car_fixtures.py` | main 有 `BtLinkReplayTest`；E5/Test 尚无此测试文件，**未融合** | **P1 / L2 测试基建**。适合先单独移植脱敏且无真车身份数据的测试框架，辅助后续所有高风险 iAP2 修改 |
| U04 [`af98832a`](https://github.com/chengshl/cardeck/commit/af98832a8bccd19008f55bef5d70dde83184851f) | Dock 虚拟屏加入独立显示组，避免物理屏幕关闭时被动黑屏 | `CarInputServiceImpl` main 有 `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP`，E5/Test 无此标记，**该具体机制未融合** | **P1 / L3**。结合手机重启、锁屏和虚拟屏资源回收风险，另开显示屏专项试验，切勿顺带进音频实验 |
| U05 [`0a009919`](https://github.com/chengshl/cardeck/commit/0a009919960c71540c5234d557c5b4e9c376bbe7) | Wi-Fi 入网兜底按车机窗口调整、失败清理、应用方向覆盖提前；修改 `SessionController`、`WifiCarPlayConnector`、`VirtualLauncher` | 尚未完成逐行为对照；E5/Test 部分模块存在独立定制 | **P1 / L3 待评估**。结合 E5 VPN 切换断连及车辆热点路径，但不能宣称可直接修复 |
| U06 [`6a09ff94`](https://github.com/chengshl/cardeck/commit/6a09ff9403d76dd995ecb24fa66581bbcfd43927) 与 [`c8c58826`](https://github.com/chengshl/cardeck/commit/c8c58826f7c1e639db7e60851ee06034b99f9cd1) | EA 协议名/设备状态问候、CarPlay 链路代际画像与协商区分 | E5/Test 独立 iAP2/蓝牙分支已分化，尚未逐项核验功能等价性 | **P1 / L3 待评估**，对 GM 兼容需按 B233/0x4301 日志逐事件审阅 |
| U07 [`f72bfa7b`](https://github.com/chengshl/cardeck/commit/f72bfa7b9d1f923ef8c43fd9c7950e90f33fb776) | 奥迪 MIB3 资源协商完成、`modesChanged` 重申、防 10 秒拆链 | 尚未核对 E5/Test 是否已有同等保护；车型条件不同 | **P2 / L3 借鉴候选**，可提炼“车机协商完成才布防”思想，但不得照搬 Audi 特定状态机 |
| U08 [`0bd660ec`](https://github.com/chengshl/cardeck/commit/0bd660ec8563b869046ce90cddc2e021eaf77db2) / [`ed7fd8e0`](https://github.com/chengshl/cardeck/commit/ed7fd8e0a3894706fd43ca8bbe585cfb8569be58) | 面板电源控制集中、唤醒改由暗幕触摸承载且默认关闭 | 不具备 E5/Test 同等实现与实车结论证据 | **P2 / L3 暂不引入**；需先有更稳定的 Xperia 锁屏/断连证据 |
| U09 [`4921a7fb`](https://github.com/chengshl/cardeck/commit/4921a7fb7db101a0da7ff0e226bdf2b547be602e) / [`c1936ebc`](https://github.com/chengshl/cardeck/commit/c1936ebc1db48f1165ba75f9be11646eb13a10e6) | 上游 remote 变体日志文件远程拉取、差分更新客户端 | 来源主要是 `app/src/remote` 与独立 cardeck-bridge 配套；E5/Test 目前有自己的一套 Gateway/MCP 和 OTA | **P2 / L2 借鉴候选**。优先借鉴增量同步/诊断可观测思路，不直接覆盖本项目 Gateway 或独立在线更新通道 |

**特别注意：** `main` 的 2026-10-24 提交后，还可能有外部上游的新提交尚未同步；以上仅代表我们从本仓库已同步的 `main` 读到的内容。上游协议修复即使由某车型验证通过，也不能代替 E5/索尼 Xperia 的设备验收。

## B. 已有代码同源（不再作为“新待移植”重复做）

| 能力与源码核对 | E5 状态 | Test 状态 | 证据边界 |
|---|---|---|---|
| 仪表盘地图/音乐卡分屏拖拽把手 `launcher/DashSplitHandle.kt` | 已有同源代码 | 已有同源代码 | `main`、E5、Test 文件 blob SHA 均为 `21bd3c2f1b...`；仅证明**当前文件内容一致**，并不证明是某个具体提交被 cherry-pick，也不代表布局整体同源 |
| 多项协议/加密基础模块（如 `airplay/AirPlayCrypto.kt`、`airplay/PairingClients.kt`） | 已有同源代码 | 已有同源代码 | 2026-10-26 文件树逐项 SHA 一致，属于现存上游同源基础，不重复当作新增功能导入 |
| 常用暗色提示组件 `ui/DarkMessageDialog.kt` | 已有同源代码 | 已有同源代码 | 三线同 SHA；E5 独立更新弹窗和 Test 更新界面需要另行追踪，不能由这一文件推断整个 UI 已经融合 |

后续对确实完成的上游移植，要将状态更新为“已直接移植/已借鉴改写”，并补：上游源 commit、接收分支 commit、差异/安全修改、CI、APK Release、实车测试结果。**不能因以前未登记就删除已经融合的记录。**

## C. 跨我们自己分支的“借鉴/融合”记录（不冒充 upstream main）

| 来源 → 目标 | 目标证据 | 已经到哪一步 |
|---|---|---|
| E5 T62/T66 任务隔离与复用 → Buick-Test | `experiment/buick-test-e5-task-isolation` / [PR #20](https://github.com/chengshl/cardeck/pull/20) / [实验 commit `dbea08f1`](https://github.com/chengshl/cardeck/commit/dbea08f1a50dc6f85e0624672940874cab74b4b1) / [独立 Release](https://github.com/chengshl/cardeck/releases/tag/exp-buick-test-task-20261026144650) | **已借鉴适配至 Test 实验分支，CI通过，已发布独立签名实验APK；手机/实车未测，正式 Test 未合并** |
| E5 T57 触控 DOWN/UP 状态恢复 → Buick-Test | `experiment/buick-test-e5-touch-recovery` / [PR #22](https://github.com/chengshl/cardeck/pull/22) / [实验 commit `1fd1ebad`](https://github.com/chengshl/cardeck/commit/1fd1ebad5d3ebbb418c8dffa163f39a24cbd61c7) | **已借鉴适配至 Test 实验分支、Debug CI通过；独立签名APK未发布，实车未测，正式 Test 未合并** |
| E5 更新 UI、身份标识 → Test | [更新架构 #17](https://github.com/chengshl/cardeck/issues/17) | **待实验评估**，不能把 E5 已发布等同于 Test 已融入 |

## D. 下一次更新台账时必做

1. 对比新的 `main` HEAD 与本次 `503ed373`，按上游提交和功能线分组，避免重复登记“只修改版本号/说明”的提交。
2. 对每条源 SHA 核查目标分支具体代码与相关测试，补齐三分支当前状态；源码内容完全相同也需注明“同源”而非“实车完成”。
3. 选用上游优化/借鉴思路或从 E5 移植成功后，**在 #23 + 本文档中留下源→目标 commit 与验证证据**，保持状态从候选、实验、CI 到设备验收和最终合并逐级晋升。
4. 根据风险分级建立目标分支试验，禁止将 main 全部合并进 E5 或 Test；不修改 `main`。
5. 不论采用、弃用、被后续上游推翻或回滚，都保留原判断和时间，新增修订记录。
