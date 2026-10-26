本次更新：残留 CarPlay 虚拟屏按当前安装包包名隔离检查（多分支共存安全修复）。

- 旧规则只按 CarPlayDock / CarPlayApp / CarPlayAppHi / CarPlayMap 四个通用名字扫描，系统中的 Shizuku 虚拟屏又统一归属 com.android.shell；Buick-E5、Buick-main、Buick-Test 同时安装会把其它分支的屏幕误判为自己残留。
- 新规则在可信 Shizuku 建屏和应用回退建屏时都使用 `CarPlayDock@<当前包名>`、`CarPlayApp@<当前包名>` 等明确标识；启动时扫描、自动清理、手动清理、系统复位清理只过滤当前安装包标记。
- 对旧版无包名标记的匿名屏，无法证明真实来源，**不会自动清理或弹出当前包残留提示**；历史本包 Shizuku UserService 旧进程仍只按本包 `:car_input` 进程名回收。绝不扫描/杀掉其他 CarDeck 包名的服务。
- 新增跨分支（E5/Test）、Shizuku/fallback 混合与旧匿名屏不误报等单元测试。Buick-E5 与 Buick-main 同步该隔离方案，T75 音频实验分支也继承这次界面与屏幕归属规则。
- 更新页面继续保留正式/实验两个入口、独立签名安装包更新和真实构建时间（北京时间）显示。
- 不修改 CarPlay 连接、AAC/RTP、视频帧、触摸注入及蓝牙音源；不代表 T75 已通过音频 3–5 分钟稳定性验证。

此改动尚需对三个分支实际安装、Shizuku旧残留与多包并存场景做实车验收。长期记录见 GitHub Issue #18、总清单 #12。