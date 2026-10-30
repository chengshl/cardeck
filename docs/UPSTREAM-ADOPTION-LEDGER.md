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
| **Test Updater-B 精准项目订阅 → E5** | **内部来源** Test [PR #26](https://github.com/chengshl/cardeck/pull/26) / `5f2f82e` → E5 实验 [`297adcf4`](https://github.com/chengshl/cardeck/commit/297adcf4b3e72c1ff97d20fb860041001f228b43)，[集中 #27](https://github.com/chengshl/cardeck/issues/27)，[Draft PR #29](https://github.com/chengshl/cardeck/pull/29) | **已选择性借鉴改写、实验签名构建/独立预发布通过，正式 E5 尚未合并，手机/实车未测**：[CI #40316104201](https://github.com/chengshl/cardeck/actions/runs/40316104201)，[实验版 `20261026193811`](https://github.com/chengshl/cardeck/releases/tag/exp-buick-e5-test-convergence-20261026193811)，`versionCode=88947491`；保留 E5 来源标记、旧版仅下载与SHA256/版本防护。**P0 E5 #28 RECORD后无视频帧是稳定版融合阻断，未排除前不准合并**。该功能来自内部 Test，非上游 `main`。 |

## D. 下一次更新台账时必做

1. 对比新的 `main` HEAD 与本次 `503ed373`，按上游提交和功能线分组，避免重复登记“只修改版本号/说明”的提交。
2. 对每条源 SHA 核查目标分支具体代码与相关测试，补齐三分支当前状态；源码内容完全相同也需注明“同源”而非“实车完成”。
3. 选用上游优化/借鉴思路或从 E5 移植成功后，**在 #23 + 本文档中留下源→目标 commit 与验证证据**，保持状态从候选、实验、CI 到设备验收和最终合并逐级晋升。
4. 根据风险分级建立目标分支试验，禁止将 main 全部合并进 E5 或 Test；不修改 `main`。
5. 不论采用、弃用、被后续上游推翻或回滚，都保留原判断和时间，新增修订记录。

## E. 2026-10-30 上游实时同步和 E5/T89 针对性复审

**实时来源校验**：2026-10-30 再查上游 `shaonialife/cardeck@main` 与 Fork `chengshl/cardeck@main`：两端均为 `503ed3730d5a9365272294b79306cea5fcce115b`，上游最近 push 是 **2026-10-24 07:07 UTC**，源码最新版本号提交为 **0.1.28**；当前上游 GitHub Release 列表最新公开版本为 **v0.1.13（2026-10-22）**，不得混淆 Git 源码版本和可安装发布版。上游 PR #19/#17 已于 10-24 合并，之后未发现更新的 main commit 或新合并 PR。本轮只是源码、提交、Release、Issue 及目标分支的只读对照，**没有代码移植、合并、CI、APK 或新实车验收**。权威治理 v2.2 与 E5 单一非音频主线优先。

| 条目与新核对 | 最新针对性判断 | 采用/验收门禁 |
|---|---|---|
| **U01 GM/Cinemo 延迟 `0x4301`**：[上游 PR #19](https://github.com/shaonialife/cardeck/pull/19) / `09b39199` | 上游 `Iap2SenderSession` 允许车机已承诺但窗口内缺少 `0x4301` 时返回空端点、不拆链，并在 `SessionController` 增加最长5分钟等待、晚到事件重触发及人工确认 Wi-Fi 兜底。E5 `SessionController` 确无 `PENDING_START_SESSION_WAIT_MS`/该观察器。**但本车 E5 #33 已见 0x4300/seq109 本身未获链路 ACK，属于更前置故障；只加5分钟等待不会让 ACK109 自动回来**。 | **有价值，L3 备选**：先在 #33 现有 Draft ACK 诊断线补完整链路 seq109→ACK→0x4301 分层回归；后续只有实证“ACK已有、0x4301只是迟到”才考虑最小空端点等待试验。不移植整个 SessionController，不增加驾驶时弹窗。 |
| **U02 MFi 错误成功记忆纠正**：`1cd903c4` | 上游 `MfiLengthMemory.recordHardRejected` 会在挑战导致硬断链时推翻旧成功长度；E5/Test 均无此方法。当前 #33 真实失败出现在已经完成 MFi 的后续 seq109 阶段，**不是已证实的 MFi 记忆问题**。 | **条件采用，L3**：仅在未来日志证明认证长度记忆错误/同长度硬拒绝时，做隔离回归与选择性移植，暂不影响现有 #33 实车队列。 |
| **U03 跨车型重放**：`3095d64e` | 上游 `BtLinkReplayTest`、`CarFixture`、`ScriptedCarStream` 可将已脱敏的真实字节流喂到 iAP2 生产路径，适合对 0x4300/ACK109/0x4301 做非车载负向回归；E5/Test 暂无同框架。**注意上游测试在 fixture 缺席时会直接打印“跳过”并返回，不能把无 fixture 的绿色 CI 当真实录制回归通过**。 | **优先借鉴，L2 测试基础设施**：在隔离测试线上改造为适合 B233 的去标识化确定性 fixture，同时 CI 显式校验 fixture 存在和测试实际执行，不上传原始秘密、MAC、密钥或完整私人帧，不需要新的用户实车验收包。 |
| **Honda 音频协商**：[上游 PR #9](https://github.com/shaonialife/cardeck/pull/9) | 核心是不同车机的 PCM/AAC/type/采样率候选、失败退档和协议回应。当前 Test/T89 已有候选和持久化记忆；最新 T89 仍在 PCM/type100、手机反向RTCP端口有效、车机真实有声后首RTP约 **20.267秒 RTSP EOF**。所以**不是本 E5 20秒问题的新修复**。 | **现有同类能力、不得重复移植/重复要求同包验收**，音频维持 #11 根因独立诊断。 |
| **上游手机静音**：`AudioSender.mutePhoneMedia` | 上游默认静音 `STREAM_MUSIC` 并在停止时恢复；Test/T89 已有同函数，但 B233 T12 特设 `mutePhoneOutput=false` 保留本地音量，用户已实车确认手机、车机同时响。T12 曾有 Sony 静音使捕获全零的疑虑。 | **不可直接照搬上游默认静音**。先单独核验非零 PlaybackCapture、车机有声、其它媒体/通话及失败/退出后的原音量恢复，不能与20秒EOF协议变量合发。 |
| **视频关键帧请求**：[上游 PR #10](https://github.com/shaonialife/cardeck/pull/10) | 上游 `VideoSender.requestKeyFrame` 可响应车机 `forceKeyFrame`；E5 尚无此方法。但 E5 #28 曾出现的是 RECORD 后数据端口/编码器未起、0 FPS，**强制关键帧不能修复尚未启动的编码器**，且旧 #28 的015/016验收已完成。 | **条件保留，L3**：仅在 B233 真实 `forceKeyFrame` 或有帧但不显示的证据下另行评估；不重测既有 #28。 |
| **应用列表漏扫**：[上游 `b11ec84d`](https://github.com/shaonialife/cardeck/commit/b11ec84db4867c4f8b868dd9aaa454e29a31bd94) | E5 `VirtualLauncher.scanLaunchableApps` 仍存在排序前 `.take(200)`，上游修复移除截断、隔离个别 APK 的标签/图标异常并同包去重，**是实在的非音频改进差异**。 | **暂缓**：尚无本车应用漏扫实证，属 v2.2 冻结的非核心 UI/新功能；不应为它额外创建 E5 实验包/实车测试。 |
| **上游别克 4.0 用户报告**：[Issue #24](https://github.com/shaonialife/cardeck/issues/24) / [#20](https://github.com/shaonialife/cardeck/issues/20) | 同集团部分其他车机报告“声明会发0x4301但仍走旧0x5702/等待0x5703”，可用于分类新旧代际能力，**不等于 2023 E5 B233 的同一失败阶段，也不属于上游已确认的新代码修复**。 | 仅作协议画像参考，不直接变更 E5 当前连接路径。 |

**本轮建议次序**：①优先把 U03 真实链路流回放方法适配到 #33 的离线验证（可直接减少重复实车）；②对 U01 建立“seq109未ACK”和“ACK已确认而0x4301迟到”两类判据，前者不应用后者5分钟兜底；③双端同时出声另行研究安全输出路由；④MFi、关键帧、应用扫描按实际故障证据再启动。**所有上游源码仍只留在 main，不做跨分支整合并。**
