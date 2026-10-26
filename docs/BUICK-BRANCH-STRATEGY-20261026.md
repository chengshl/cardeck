> **统一规则已迁移**：此文件是历史分支定位快照。项目约定的唯一现行版本见 [`docs/PROJECT-GOVERNANCE.md`](PROJECT-GOVERNANCE.md)（Buick 权威、Buick-E5 / Buick-Test 同步；main 不参与）。如有冲突，以统一规则和用户最新明确决定为准。

# Buick 分支规则（2026-10-26）
- `Buick`：最终正式版，仅合并 Buick-Test 已经验证的功能；当前由旧 Buick-main 头提交创建。
- `Buick-Test`：过渡集成测试分支，按需吸收上游更新并融合 Buick-E5 稳定功能；未来可由短期集成分支替代。
- `Buick-E5`：当前稳定基准，同时持续改进 UI 和易用性；不自动接受实验协议变更。
- `experiment/*`：独立试验，保持和正式版隔离，实车验证后再考虑合并。

## 兼容性
- 新正式 tag: `buick-latest` / `CarDeck-Buick.apk`, Release 元数据 `branch=Buick`。
- 旧正式 updater 保留镜像 tag `buick-main-latest` / `CarDeck-Buick-main.apk`，其元数据 `branch=Buick-main`；二者由同一次构建输出。
- APK 保留 `io.github.buick.main.cardeck` 以覆盖原安装，避免清除原数据及 Shizuku 授权。应用显示名更名为 `CarDeck-Buick`。
- 新实验前缀 `experiment/buick-*`；原 `experiment/buick-main-*` 保留 GitHub 历史，但不作为已经通过新版验收的实验。
- GitHub 仓库默认 `main` 仍是上游同步线，不等同于正式的 Buick 分支。
- Github connector 不提供真正 branch rename/delete；旧 `Buick-main` 暂时保留，后续需通过 GitHub 分支管理移除。
- GitHub CI 构建/实车验收应分别验证，提交不等于已成功发布。
