# Test → E5 收敛融合 · 第一批 Updater-B 试验（2026-10-26）

## 基线与范围
- **治理**：v1.5 / L2。目标 `Buick-E5`（E5 稳定线），从 `00a9ea733594d2fdf34bbd1a06f772b77167d3e3` 派生 `experiment/buick-e5-test-convergence`。来源 Test [PR #26](https://github.com/chengshl/cardeck/pull/26)，Test merge SHA `5f2f82e`，专项 #17，融合入口 #27。
- **实际融合**：保留 E5 的 InstalledBuildIdentity 安装来源标识、深色 `UpdateDialogUi`、旧版本 APK “仅下载并保存到 Downloads/CarDeck”机制、签名/包名/元数据/版本限制。在此基础上**借鉴移植** Test 已手机初步验收的“按实验 sourceBranch 精准订阅、记录选择、候选分组、仅该功能线检查/历史版本”、深色项目选择 UI。
- **隔离**：不借入 Test 的 `SessionController`、音频 RTP/RTCP/AAC、Wi-Fi、蓝牙、GL、虚拟屏或登录。Test 正式 APK 构建/实车情况不等于 E5 已通过。
- **验证**：独立工作流仅 `testDebugUnitTest/assembleDebug`，**不正式发布、不篡改任何 `*-latest` 标签**。实验签名 prerelease 和 `updaterSchema=2`、builtAt、版本码安全交付另行核对；未发布前用户无此测试版可安装。

## 专项验收
1. 实际 E5 APK 的 “别克设置 → 软件更新”：正式/试验两卡片，保留**已安装包来源**及当前选择来源，均能自然换行。
2. 试验渠道可选择且记住**具体功能线**；退出/重进后仍记住。没有实验Release时显示空状态，不跳别的项目。
3. 自动/手动仅检查已选项目；历史版本列表只显示对应 `sourceBranch`，不跨项目更换。
4. 旧版号 APK 保留 E5 的“仅下载到 Downloads/CarDeck”路径，不能绕过 Android 签名和 versionCode 限制；适合的新版 APK 要 SHA256、包名/版本一致后手动确认安装，安装前安全断开 CarPlay。
5. 手机更新页面不会因目录加载失败卡死，断网有反馈。E5 车机视频/触摸/QQ音乐/地图/最近任务连续断开重连3轮无回归。
6. 发现任何跨包候选、异常静默安装、E5 provenance 丢失或视频触摸异常即判失败，不选择性合入稳定 E5。

## 当前状态
- [x] 基线/风险/差异评估与第一批隔离代码已提交。
- [ ] CI unit/debug 正式完成。
- [ ] 独立签名实验 APK / 安装及回退路径。
- [ ] E5 真机及车机专项验收。
- [ ] 经证据支持后选择性合入 E5。
