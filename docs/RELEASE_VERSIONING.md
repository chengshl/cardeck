# CarDeck 跨分支签名发布与版本编号规范（#39）

## 发布范围

本规范用于需要生成已签名 Android APK 并发布 GitHub Release 的分支。当前接入的主要发布线：

| 来源分支 | 可见版本名前缀 | 发布目标 |
|---|---|---|
| `Buick-E5` | `E5` | `buick-e5-latest` |
| `Buick-Test` | `E5-TEST` | `buick-test-latest` |
| `Buick` | `BUICK` | `buick-latest`，兼容 `buick-main-latest` |
| `experiment/buick-e5-updater-b-minimal` | `EXP-可读短名-分支指纹` | 独立实验预发布，不得覆盖正式发布标签 |

`main` 是上游同步分支，不因本规范自动生成签名版。其他历史实验分支如果重新启用发布，**必须先迁移到统一编号分配器与共享发布串行组**；不能把未迁移分支视作已实现全仓库统一发布。

## 版本规则

- `versionName=<分支简称>-YYMMDDNNN`：按 **Asia/Shanghai** 当地日划分；`NNN` 从 001 开始，所有已接入发布工作流共享，当天上限 999。每天重置日流水，不重置内部版本码。
- `versionCode` 是 Android 独立递增整数，不能直接使用日期字符串。必须大于已有分配登记、四个正式/兼容通道，以及**所有已发布且含 APK 资产的独立实验、取证与回滚 Release** 的版本码；分页读取历史发布。含 APK 的 Release 缺失合法 `versionCode` 时拒绝分配，不得静默降级；超过 Android 整数上限同样失败。
- 实验项目未知分支简称采用 `EXP-` + 规范化分支尾部可读片段 + SHA-256(完整分支名)前 12 位大写十六进制摘要，以降低不同长分支名同尾部时的冲突与歧义。**完整 `sourceBranch` 是订阅与来源核验依据，不能用可见简称代替。**
- 旧 14 位数字版本名与早期 EXP 简称可继续解析；Android 是否允许覆盖安装只看实际 `versionCode`、包名与签名，绝不从可见 `versionName` 推测。

## 分配实现与故障恢复

统一脚本：`tools/release/allocate_version.py`。发布工作流必须使用同一个仓库级 GitHub Actions concurrency group `cardeck-global-signed-release-version`，并开启 `cancel-in-progress: false`。脚本通过 `gh api --paginate --slurp` 读取 Git 标签登记以及全部历史签名 APK Release 元数据，原子创建 `refs/tags/cardeck-build/v1/YYMMDDNNN-c<versionCode>`；如果抢占冲突则重新读取、重算并重试。

分配过的编号永远不回收：打包、签名、Lint 或发布失败时，不得删除既有分配标签并复用旧号；修复后触发新构建，重新分配。手工重跑同样使用新号。遇到已安装 APK 版本码高于可用发布时，不建议卸载、降级或变更签名，应该等待同包名、同签名、更高版本码的安全构建。

发布元数据继续保留 `versionName`、`versionCode`、`branch`/实验 `sourceBranch`、`builtAt`、APK 与 SHA-256。实验预发布还须满足 `artifactKind=experiment`、`updaterSchema=2`、`baseBranch` 等约束；下载来源、APK 包名、签名和用户确认安装保护均不得因版本命名改动而削弱。

## 自动化检查与实机验收

分配器及 GitHub API 模拟回归：`python3 -m unittest discover -s tools/release -p 'test_*.py' -v`。Android 回归覆盖新旧版本显示、实验项目来源绑定、发布时间元数据和中文更新说明。每个工作流仍须完成原有的签名、编译及安全检查；在 GitHub Release 中确认 APK 和 `.sha256` 均已发布，并验证显示名与内部版本码有序。

用户验收集中进行，不要求对每个中间构建或实验包重复全套实车测试。优先在最终正式版执行：旧包发现新版本 → 展示正确的中文说明 → 下载与系统确认覆盖安装 → 安装后显示新版本名 → App 正常启动。实验 APK 与 E5 正式 APK 包名相同时尤其不能随意覆盖，避免实验包版本码更高导致无法直接回退正式版。

## 当前迁移状态与保留边界（2026-10-29，北京时间，依 v2.2 治理）

### 已经过真实发布验证的统一编号入口

- Buick / Buick-main 双兼容 Release：PR #51，同一签名 APK，版本码 `89142306`，Actions [#40738378165](https://github.com/chengshl/cardeck/actions/runs/40738378165) 成功。
- Buick-E5：PR #55，版本码 `89142773`，Actions [#40739580065](https://github.com/chengshl/cardeck/actions/runs/40739580065) 成功。用户此前明确确认的 E5 正式版 004 五项手机验收仍有效，不重复要求旧版验收；本次 Python 分配器升级不等于重新取得实车验证。
- Buick-Test：PR #56，更新中文发布说明后版本码 `89143185`，Actions [#40740442345](https://github.com/chengshl/cardeck/actions/runs/40740442345) 成功。旧的中文说明门禁失败记录保留、不得降低门槛。
- Updater-B 的编号验证版本 003/010 曾完成独立签名，但依用户 v2.2 退役决定已从活跃实验订阅退出（`artifactKind=historical`）。**保留历史 APK、SHA256 和 tag，不再把已完成的 Updater-B 当成活跃实验重新签发**。
- Rescue UI 审查、Rescue rollback：PR #52/#53 已统一发布分配器与共享并发组；仅工作流改动，没有因此自动签发取证 APK。

### 按现行治理等待专项验收或恢复前迁移的入口

| 入口与分支 | 现状 / 恢复条件 |
|---|---|
| 第三独立取证 `experiment/cardeck-rescue-standalone` | 旧更新器仅识别14位数字名称；[Draft PR #54](https://github.com/chengshl/cardeck/pull/54) 已有 Android/Python CI 成功。更新器新旧 EXP 解析尚未手机验收、未合并，不可将 CI 写作正式发布。 |
| 活跃 P0 音频 `experiment/buick-test-t76-aac-causality-observe` | T85 仍为独立实车待验版本，历史签名包 code `89165049`，未证明音频解决。[Draft PR #58](https://github.com/chengshl/cardeck/pull/58) 已为 T85 准备共享分配器与新旧名称来源绑定，完整 [CI #40805960921](https://github.com/chengshl/cardeck/actions/runs/40805960921) 成功；**未签发新 APK、未改协议运行代码、未合并**。 |
| E5 A2DP、iAP2 stop、会话 teardown、详细诊断、Updater-B 旧融合等历史独立功能分支 | 仓库树审计显示仍保留无统一分配器的 publish 工作流。遵守 v2.2 冻结/独立专项门禁，不批量重启旧实验；确需再次签名时先迁移分配器、共用串行组、解析、旧版本覆盖基线，再做该功能线必要 CI/手机验证。 |
| 旧 Test T75/T84 等前序音频工作流 | 保留历史证据与音频实验对照，尚不能声称全线迁移。禁止把旧 14 位脚本直接当成已统一的发行通道使用；音频协议修改继续由 #11 单变量实车门禁控制。 |

### 恢复旧实验签名发布前的检查

1. 确认 v2.2 冻结状态、是否仍有用户认可的活跃专项，不将历史分支视为可直接复用的发布目标。
2. 核对所用 `tools/release/allocate_version.py` 与当前权威实现完全一致；读取完整 Release 历史 APK `versionCode`，共享 `cardeck-global-signed-release-version`，关闭运行中任务自动取消。
3. 实验 `versionName`、`sourceBranch`、`baseBranch`、tag、APK 文件名、签名和 SHA-256 均匹配；老更新器须先具备新 EXP 哈希名兼容，不得发布后让更新中心过滤。
4. 保留该专项既有 lint、证书对比、路径白名单和稳定 CarPlay 回归，不通过扩大白名单或更换签名逃避门禁。
5. 只有 CI/签名 APK/元数据真实发布成功才登记“发布通过”，手机与实车验收必须另行明确记录。对正式包的纯 Python 分配器改动不重复请求已完成的 CarPlay 回归。
6. 不删除既有 Release、APK、历史失败证据或 Git 分支；不修改 `main` 上游同步流程。

此节为**迁移与风险清单**，不代表旧实验已经统一编号或 #39 全部完结。真实逐轮 CI/签名与用户手机验收请以 [Issue #39](https://github.com/chengshl/cardeck/issues/39) 和对应专项 Issue 为准。
