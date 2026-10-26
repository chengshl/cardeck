> **Buick-E5 开发风险分级新规则（2026-10-26）**：详见 [稳定基准 / 短期试验分支规范](BUICK-E5-EXPERIMENT-BRANCH-POLICY.md)。该规范优先于下文早期“UI 可直接发布”口径；中风险更新系统逻辑先试验，真实验收后才合并。记录见 [#17](https://github.com/chengshl/cardeck/issues/17#issuecomment-6408712963)。

> **2026-10-26 · Buick-E5 软件更新 UI 第二轮**：正式发布更新弹窗也统一为深色卡片，包括“已是最新”、发现更新、下载进度、未知应用安装权限及失败重试。原来的系统粉红色 `AlertDialog`、`ProgressDialog` 不再用于正式更新。
>
> 新的 Buick-E5 APK 在构建时将**真正的构建来源**写入 `cardeck_installed_build_origin` 资源：正式发布工作流 `CARDECK_RELEASE_BRANCH=Buick-E5` 标记为正式版；E5 实验发布工作流通过 `SOURCE_BRANCH=experiment/buick-e5-*` 标记为试验版；其他无明确来源构建标记为 `unmarked`，绝不从用户选择的检查通道倒推安装包来源。页面同时显示当前安装包来源、已安装版本、当前检查通道三个不同概念。旧版APK因未内嵌标识，须升级到此版后才可可靠显示来源。
>
> 本次仅更新 Buick-E5 源码，不修改 Buick / Buick-Test / main / 试验分支。

> **2026-10-26 · Buick-E5 独立 UI 优化（仅本分支）**：软件更新页摘要展示“当前已安装版本”，另显示目前的检查渠道。实验列表/版本详情/下载进度/提示改为与主页面一致的深色圆角界面；操作按钮不会随长更新说明滚出视野。
>
> 实验 APK 的 versionCode 不高于当前已安装版本时，改为“仅下载 APK”，从 GitHub 官方预发布获取并完成 SHA-256、包名、versionCode 校验后写到 Android 公共 **下载/CarDeck** 目录（MediaStore），供用户日后手动处理。普通手动安装不会绕过 Android 降级限制；需要卸载高版本时应提示数据及授权丢失。新版本仍可正常下载并触发系统安装。
>
> 此提交不改变 CarPlay/iAP2、视频、音频与其他分支的行为；Buick、Buick-Test、main、experiment 均不修改。
# CarDeck 多分支安装包更新与实验版本发布规范

更新时间：2026-10-26（北京时间）。总清单：[#12](https://github.com/chengshl/cardeck/issues/12)；专项任务：[#17](https://github.com/chengshl/cardeck/issues/17)；音频实验：[#11](https://github.com/chengshl/cardeck/issues/11)。

## 用户确认的机制

- 所有 Buick 开发/测试正式线：`Buick-E5`、`Buick-main`、`Buick-Test` 各保留独立APK包名、签名及已有正式更新 Release：`buick-e5-latest`、`buick-main-latest`、`buick-test-latest`。上游同步 `main` 不参与别克在线升级。
- **2026-10-26 11:46 用户最新确认（覆盖旧规则）**：各分支「别克设置 → 软件更新」直接展示「正式分支 / 试验分支」两张卡片，附渠道差异小字；选择立刻保存到当前APK自身的SharedPreferences，默认正式分支。**自动检查更新只检查所选分支**：选正式查各自独立`*-latest`；选试验查归属于当前包名的最新合法GitHub实验prerelease；自动检查只提醒**绝不自动下载安装**。手动按钮同样检查所选分支，另可浏览试验历史包。原来的单独“自动检查更新”一级菜单移入统一软件更新页。
- 实验仅显示本分支对应的 GitHub 官方独立 **prerelease**：`artifactKind=experiment`、`updaterSchema=2`、`baseBranch` 必须等于当前正式分支、`sourceBranch` 必须以 `experiment/buick-e5-` / `experiment/buick-main-` / `experiment/buick-test-` 对应前缀开头，且 Release 必须有匹配APK与SHA256资产。Actions Artifacts 不能直接作为无需身份认证的App下载源。
- 安装时检查 SHA256、APK包名、版本号，并交由 Android 系统核验包签名；不绕过签名或降级限制。安装前通过既有会话控制器断开车机，尽可能避免车机虚拟显示屏抢占系统安装确认窗口。
- Release元数据的 `builtAt` 是**真实 APK 构建后时间**（UTC RFC3339），显示时转换为北京时间；旧正式 Release 无 `builtAt` 则只显示“版本时间”并注明不是实际构建完成时间，绝不能拿提交/发布时间代替。用户明确要求在选择列表和更新详情中显示安装包构建更新时间。
- 实验包覆盖当前测试APK并可能因 Android `versionCode` 单调限制无法立刻装回旧正式包。必须提示此风险；下一次正式版的 `versionCode` 高于实验包时才能正常覆盖返回。不应擅自修改APK签名或强制降级。
- 实验包内也应携带新版实验选择器（`updaterSchema=2`）。没有它的旧T74、早期T75构建不展示为可在线安装，避免实验后丢失新入口。
- 不修改主线的 CarPlay/iAP2/音频协议/视频触摸行为；音频 P0 #11 实车3次各≥3分钟且有声前禁止移植未经验证的实验协议到 Buick-main。

## 软件更新页最新界面与CI交接\n\n- 面向手机的更新页为`UpdateCenterActivity`，不再弹原生粉色渠道选择菜单；卡片纵向排版，标题、说明允许自然换行；别克菜单描述允许最多两行。正式和实验检查/更新提示使用AppCompat对话框。\n- `UpdatePreference`保存`formal`或`experiment`，`BuickUpdater.checkSelected`供手动和自动检查共用。`ExperimentalUpdates.check`只查询最新合法同包实验，发现更高versionCode才弹安装确认；`ExperimentalUpdates.browse`展示可选历史包和构建时间。\n- 入口注册、代码与单测应逐分支CI确认；**提交≠构建成功、构建成功≠用户手机已验收**。Build失败须在#17留痕；不要因界面修改覆盖音视频及T75协议实验。\n\n- **兼容性门槛升级**：2026-10-26 统一软件更新页后实验安装包必须包含`UpdateCenterActivity`、`UpdatePreference`及按所选渠道自动检查的更新器，因此元数据升为`updaterSchema=2`。旧`updaterSchema=1`实验包仍留在GitHub历史供取证，不在新版可安装列表出现，以免试验覆盖后退回粉色旧弹窗。T75源分支已同步更新器并按schema2独立重建。旧说明如仍提schema1为历史规则，最新规则以本段为准。

## 发布方式

1. 管理员在仓库默认分支 `main` 的 GitHub Actions 手动运行 `.github/workflows/publish-buick-experiment.yml`，选择对应的 `base_branch` 并填写 `experiment/<对应slug>-*` 源分支。
2. CI 校验分支匹配、实验包带选择器、签名及APK包名，使用原正式签名构建，并创建**独立预发布标签** `exp-<对应slug>-<14位北京时间构建版本>`，资源附 `.apk` 与 `.apk.sha256`。发布正文应包含以下机器可读字段（行首精确匹配）：

   ```text
   artifactKind=experiment
   updaterSchema=2
   baseBranch=Buick-Test
   sourceBranch=experiment/buick-test-t75-rtcp-observe
   versionName=20261026110000
   versionCode=<实际APK整数versionCode>
   builtAt=2026-10-26T03:00:00Z
   apkName=CarDeck-buick-test-EXP-20261026110000.apk
   commit=<实验SHA>
   ```

   以上时间、版本码只是字段范例，不能作为已经发布的真实记录。真实数据一律以CI/Release核验为准。
3. T75 另有专用工作流 `.github/workflows/t75-rtcp-observe.yml`，只对该实验分支发布带 `updaterSchema=2` 的独立预发布；旧T74失败实验不自动上架。
4. 正式分支构建继续发布各自 `*-latest`，实验发布绝不写这三个标签。任何回滚/失败必须保留原实验记录。

## 进度与下一步

本规范的代码基础设施已提交到三分支；不以提交或CI启动等同实车验收。正式与实验APK CI状态、GitHub Release实际上架状态请查看 #17 的逐次实验评论和 Actions 链接。用户下一次聊天要求继续处理时，先读 #12 → #17 → 最新分支提交/CI/Release，避免重复讨论已定方案。
