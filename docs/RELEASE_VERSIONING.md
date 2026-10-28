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
- `versionCode` 是 Android 独立递增整数，不能直接使用日期字符串。必须大于已有分配登记和 E5、Test、Buick 及 Buick-main 兼容通道中可见的既有发布版本码；超过 Android 整数上限则失败，不得静默降级。
- 实验项目未知分支简称采用 `EXP-` + 规范化分支尾部可读片段 + SHA-256(完整分支名)前 12 位大写十六进制摘要，以降低不同长分支名同尾部时的冲突与歧义。**完整 `sourceBranch` 是订阅与来源核验依据，不能用可见简称代替。**
- 旧 14 位数字版本名与早期 EXP 简称可继续解析；Android 是否允许覆盖安装只看实际 `versionCode`、包名与签名，绝不从可见 `versionName` 推测。

## 分配实现与故障恢复

统一脚本：`tools/release/allocate_version.py`。发布工作流必须使用同一个仓库级 GitHub Actions concurrency group `cardeck-global-signed-release-version`，并开启 `cancel-in-progress: false`。脚本读取 Git 标签登记和历史 Release 元数据，原子创建 `refs/tags/cardeck-build/v1/YYMMDDNNN-c<versionCode>`；如果抢占冲突则重新读取、重算并重试。

分配过的编号永远不回收：打包、签名、Lint 或发布失败时，不得删除既有分配标签并复用旧号；修复后触发新构建，重新分配。手工重跑同样使用新号。遇到已安装 APK 版本码高于可用发布时，不建议卸载、降级或变更签名，应该等待同包名、同签名、更高版本码的安全构建。

发布元数据继续保留 `versionName`、`versionCode`、`branch`/实验 `sourceBranch`、`builtAt`、APK 与 SHA-256。实验预发布还须满足 `artifactKind=experiment`、`updaterSchema=2`、`baseBranch` 等约束；下载来源、APK 包名、签名和用户确认安装保护均不得因版本命名改动而削弱。

## 自动化检查与实机验收

分配器及 GitHub API 模拟回归：`python3 -m unittest discover -s tools/release -p 'test_*.py' -v`。Android 回归覆盖新旧版本显示、实验项目来源绑定、发布时间元数据和中文更新说明。每个工作流仍须完成原有的签名、编译及安全检查；在 GitHub Release 中确认 APK 和 `.sha256` 均已发布，并验证显示名与内部版本码有序。

用户验收集中进行，不要求对每个中间构建或实验包重复全套实车测试。优先在最终正式版执行：旧包发现新版本 → 展示正确的中文说明 → 下载与系统确认覆盖安装 → 安装后显示新版本名 → App 正常启动。实验 APK 与 E5 正式 APK 包名相同时尤其不能随意覆盖，避免实验包版本码更高导致无法直接回退正式版。
