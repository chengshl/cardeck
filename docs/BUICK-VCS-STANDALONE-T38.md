# Buick-Test T38: 安全模式背景黑边诊断与修复

## 用户现象（2026-10-23）
E5 实车，单独启动高德地图，T37 安全模式已生效但画面左右出现深色/黑色条带，紫色用户壁纸在部分外缘仍可见。此前日志曾报告 `BUICK T37: mode=safe fit=BuickViewport(left=399,top=69,width=1287,height=425)`。安全模式必然出现留白，但留白应使用完整 wallpaper，而不是错误地暴露 GL 清屏色。

## 根因风险（待实车进一步验证）
T37 在 GL 合成线程开始后才通过 Bg.run 异步生成 `safeBackgroundBitmap`，且原代码直接忽略 null 和纹理上传状态；safe-fit 时空白区域可裸露深灰 `glClear`。仅凭照片不能排除 Android 高德窗口自身存在独立 letterbox，因此分开记录背景就绪与 GPU 纹理上传状态。

## T38 改动
- 只在 Buick Safe Area 生效时，于本轮 compositor.start 之前准备同一张完整 aspectFill 壁纸位图，与 Grid/Dock 共用 WallpaperStore 缓存。
- 记录 `BUICK T38: 安全区壁纸就绪=<bool> bitmap=<size>`，方便查明无法绘制的原因。
- 打开 safe-fit 时输出 `mode=safe fit=... 背景就绪=<bool>`；若位图不存在，在独立后台线程尝试补加载。
- GL 上传安全模式壁纸纹理后校验 GLES 错误、输出成功或失败记录；旧版本所有失败都静默。
- 视频尺寸、uniform fit 位置、HID touch 逆变换、普通蓝牙音频和 iAP2 重连逻辑不变。关闭会话时释放额外的 GL 背景纹理。
- 保持按车型及按 App 单独选择 safe / full。全铺满模式天然没有这种等比例 safe-fit 的边缘留白。

## 下一轮实车
1. 进入高德安全模式，Gateway 查 `BUICK T38` 两条：壁纸 CPU 准备与 GL 上传均成功。
2. 若 CPU/GL 均成功但黑色条带依旧，说明黑色来自独立 App 虚拟屏内容/Android letterbox，继续定位 Android 层；不能再把黑条全部归因于 compositor 空背景。
3. 对比全铺满模式：如果无黑条，但顶部/右侧按钮因弧面裁切，则属 safe-fit 的预期取舍。
4. 对比 Dock / 主页紫色壁纸与 safe-fit 边缘是否连续，检查应用触摸区域外不误触。
