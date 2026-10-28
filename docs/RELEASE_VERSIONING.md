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

## 当前迁移状态与保留边界（2026-10-29，北京时间）

- 核心四线已通过 001–006 真实签名发布；用户确认 `Buick-E5 E5-261029004` 五项手机验收通过，不要求复测原有 #27/#42。
- PR #51 已将 Buick 历史基线扩展至全部 APK Release，真实双通道发布 `BUICK-261029007` / `versionCode=89142306`（Actions #40738378165 成功）。
- Rescue UI 审查版 PR #52、Rescue 独立回滚包 PR #53 已合并统一分配器、全局并发组。只改发布工作流与测试，未因合并自动产生新的取证 APK，现有 L3 功能及安装版本保持不变。
- Rescue Standalone 正式实验源分支的更新器此前只接受 14 位版本名；PR #54 已隔离实现对该源分支的 SHA-256 指纹命名与历史名称兼容，保留签名、APK、sourceBranch、SHA256 校验；**Draft，未合并、未发布、未经手机验收**。
- E5 PR #55 与 Test PR #56 正在同步同一全 Release 基线分配器（见 #39 最新评论及 Actions）；**仅按实际最终成功的签名发布记录更新状态**。
- 音频 T84 等历史独立实验仍有旧版发布 workflow，且 T84 的源文件白名单隔离不容放宽。冻结/休眠分支不因本文件自动构建或发布：恢复任一签名工作流之前，必须先完成共享分配器、统一并发锁、更新器新名称解析及旧包版本码基线的实际核查；不可直接用旧时间戳编号继续发布。

此节是已核实的迁移/风险登记，不代表所有实验 APK 已完成用户手机验证。详见 [Issue #39](https://github.com/chengshl/cardeck/issues/39)。
