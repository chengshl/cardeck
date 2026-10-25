package io.github.shaonialife.cardeck.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg

/**
 * CarPlay dock（独立虚拟屏，含时钟/电量/运行中应用/回桌面）。
 *
 * 两种形态（布局见 res/layout）：
 *  - 横屏车机：dock 是左侧竖条 → activity_dock_sidebar.xml（时钟居顶、回桌面居底）
 *  - 竖屏车机：dock 是底部横条 → activity_dock_bar.xml（⌂ | 应用横排 | 时间/电量）
 * 形态由 dock 虚拟屏自身宽高决定（竖条 height>width；横条 width>height），与车机方向自动对齐。
 */
class DockActivity : AppCompatActivity() {
    @Volatile private var sessionFinishSignal: java.util.concurrent.CountDownLatch? = null
    private var appsContainer: LinearLayout? = null
    private var clockView: TextView? = null
    private var batteryText: TextView? = null
    private var batteryBar: View? = null
    private var batteryFill: View? = null
    private var barForm = false
    private var networkIndicators: DockStatusView? = null

    /**
     * 设计基准密度（420dpi）的当前倍率。dock 屏密度由 SessionController 按画布短边等比
     * 下发（奥迪 1160×540→420 不变；测试台 2320×1080→840，dock 与文字/图标整体 ×2）。
     * 布局 XML 用 dp 自动随密度缩放；字号/模糊半径等按「设计 px × 本倍率」在代码里设，
     * 保证高分辨率车机下 dock 内容与画布同比例（2026-10-17 修复「高分辨率下显得小」）。
     */
    private val designScale: Float
        get() = resources.displayMetrics.densityDpi / 420f

    private val refreshListener: () -> Unit = { runOnUiThread { refresh() } }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable =
        object : Runnable {
            override fun run() {
                refresh()
                updateNetworkIndicators()
                pollHandler.postDelayed(this, 3_000)
            }
        }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker =
        object : Runnable {
            override fun run() {
                // 无秒显示（用户要求）：对齐到下一分钟边界刷新，秒内不做无用重绘
                clockView?.text =
                    android.text.format.DateFormat
                        .format("HH:mm", System.currentTimeMillis())
                clockView?.let { fitClockText(it) }
                val now = System.currentTimeMillis()
                clockHandler.postDelayed(this, 60_000L - now % 60_000L + 80L)
            }
        }

    /** 壁纸背景（全流画布切片，与桌面同源同路径——交界处逐像素连续）。 */
    private var wallpaperView: ImageView? = null
    private var wallpaperShownVersion = -1L

    /** 壁纸变更监听（WallpaperStore 主线程回调；解码在本类自行切后台线程）。 */
    private val wallpaperListener = { _: Long -> refreshWallpaperIfChanged() }

    /** dock 外观偏好监听（设置页拖动滑条即时生效）。 */
    private val dockPrefsListener: () -> Unit = { runOnUiThread { applyDockPrefs() } }

    private val batteryReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = updateBattery(intent)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeSessionActivity = java.lang.ref.WeakReference(this)
        if (!VirtualLauncher.sessionAttached) {
            finish()
            return
        }
        val dm = resources.displayMetrics
        // The expanded dock display may be wider than its functional rail.
        // Derive bar/sidebar from the actual receiver orientation instead.
        barForm = VirtualLauncher.dockAtBottom
        setContentView(if (barForm) R.layout.activity_dock_bar else R.layout.activity_dock_sidebar)

        clockView = findViewById(R.id.dockClock)
        batteryText = findViewById(R.id.dockBatteryText)
        batteryBar = findViewById(R.id.dockBatteryBar)
        batteryFill = findViewById(R.id.dockBatteryFill)
        appsContainer = findViewById(R.id.dockApps)
        networkIndicators = findViewById(R.id.dockNetworkIndicators)
        wallpaperView = findViewById(R.id.dockWallpaper)
        // T34: keep the dock wallpaper full-bleed, but shift only its actual
        // controls out of the curved receiver's left/top unsafe strips.
        if (!barForm) {
            VirtualLauncher.safeViewport?.let { rect ->
                val contentWidth = (VirtualLauncher.dockWidthPx - rect.left).coerceAtLeast(1)
                val bottom = (VirtualLauncher.streamHeightPx - rect.top - rect.height).coerceAtLeast(0)
                val params = findViewById<View>(R.id.dockContent).layoutParams as FrameLayout.LayoutParams
                params.leftMargin = rect.left
                params.topMargin = rect.top
                params.width = contentWidth
                params.height = (VirtualLauncher.streamHeightPx - rect.top - bottom).coerceAtLeast(1)
                findViewById<View>(R.id.dockContent).layoutParams = params
                val scrim = findViewById<View>(R.id.dockScrim)
                val scrimParams = scrim.layoutParams as FrameLayout.LayoutParams
                scrimParams.leftMargin = rect.left
                scrimParams.topMargin = rect.top
                scrimParams.width = contentWidth
                scrimParams.height = params.height
                scrim.layoutParams = scrimParams
                // T61: the visual capsule follows the functional rail bounds,
                // preserving the established input geometry and safe viewport.
                findViewById<View>(R.id.dockRail)?.let { rail ->
                    rail.layoutParams = FrameLayout.LayoutParams(
                        contentWidth, params.height,
                    ).apply {
                        leftMargin = rect.left
                        topMargin = rect.top
                    }
                }
            }
        }
        findViewById<ImageView>(R.id.dockHome).apply {
            setOnClickListener { Bg.run("dock-home") { VirtualLauncher.onHomePressed() } }
            // 图标随模式切换：仪表盘激活=房屋（点按回桌面）；否则=田字格（点按进仪表盘）。
            // 应用中与桌面同为田字格——两种场景下的动作都是进仪表盘，语义一致。
            VirtualLauncher.addDashboardListener { dashboard ->
                runOnUiThread {
                    setImageResource(
                        if (dashboard) R.drawable.ic_dock_home else R.drawable.ic_dock_dashboard,
                    )
                }
            }
            setImageResource(
                if (VirtualLauncher.dashboardMode) R.drawable.ic_dock_home else R.drawable.ic_dock_dashboard,
            )
        }

        VirtualLauncher.addRunningAppsListener(refreshListener)
        pollHandler.postDelayed(pollRunnable, 3_000)
        clockHandler.post(clockTicker)
        updateNetworkIndicators()
        // 字号按「设计 px × designScale」设置（不进 XML：荣耀 ROM 上 autoSize 对窄 dock
        // 不生效，仍需布局完成后按实测宽度手工缩钟表——见 fitClockText；13/22 为原型值）
        clockView?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 22f * designScale)
        batteryText?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13f * designScale)
        clockView?.post { fitClockText(clockView!!) }
        setupWallpaper()
        // 注册变更通知 + 立即对账一次（兜底注册前发生的更换——事件不补发）
        WallpaperStore
            .addListener(wallpaperListener)
        refreshWallpaperIfChanged()
        // dock 外观（暗幕/模糊）：应用当前值 + 监听设置页调整
        DockPrefs.addListener(dockPrefsListener)
        applyDockPrefs()
        // 系统受保护广播：ACTION_BATTERY_CHANGED 为 sticky，注册即回填当前电量
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDestroy() {
        if (activeSessionActivity?.get() === this) activeSessionActivity = null
        sessionFinishSignal?.countDown()
        sessionFinishSignal = null
        VirtualLauncher.removeRunningAppsListener(refreshListener)
        pollHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacks(clockTicker)
        WallpaperStore
            .removeListener(wallpaperListener)
        DockPrefs.removeListener(dockPrefsListener)
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!VirtualLauncher.sessionAttached ||
            display?.displayId == android.view.Display.DEFAULT_DISPLAY
        ) {
            VirtualLauncher.logSink?.invoke("LAUNCHER T9: stale DockActivity on physical phone; finishing")
            finish()
            return
        }
        refresh()
    }

    /**
     * 壁纸切片：dock 窗口内画「全流画布壁纸」中本条带对应的部分。
     * dock 虚拟屏与流 1:1（420dpi），canvas 视图 = 流画布尺寸：
     *  - 侧栏（横屏车机）：流左条，偏移 (0, 0)
     *  - 底栏（竖屏车机）：流底条，偏移 (0, -(appHeight))
     * 窗口必须不透明：半透明窗口下系统会往这块虚拟屏合成手机自身壁纸
     * （曾致 dock 显示手机壁纸、与桌面壁纸错位——2026-10-12 实测根因）。
     */
    private fun setupWallpaper() {
        val launcher = VirtualLauncher
        val streamW = launcher.streamWidthPx
        val streamH = launcher.streamHeightPx
        if (streamW <= 0 || streamH <= 0) return
        val view = wallpaperView ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (barForm) {
            params.topMargin = -(streamH - launcher.dockHeightPx)
        } else {
            params.leftMargin = 0
            params.topMargin = 0
        }
        params.width = streamW
        params.height = streamH
        view.layoutParams = params
        refreshWallpaperIfChanged()
    }

    /**
     * 应用 dock 外观偏好：暗幕强度（scrim View 的黑 alpha）+ 背景高斯模糊
     * （RenderEffect，API 31+；半径按 dock 物理像素，最大 40px——乘 designScale
     * 保证高分辨率车机上模糊观感与奥迪基准一致）。
     */
    private fun applyDockPrefs() {
        val scrim = findViewById<View>(R.id.dockScrim) ?: return
        val alpha = (DockPrefs.scrimOpacity(this) * 255 / 100).coerceIn(0, 255)
        if (barForm) {
            scrim.background =
                android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.argb(alpha, 0, 0, 0),
                )
        } else {
            // Dark tint only *inside* the inset capsule: no hard rectangular bar.
            // This is plain semi-transparent colour, not a frosted-glass blur.
            val d = resources.displayMetrics.density
            val shape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 19f * d
                setColor(android.graphics.Color.argb(alpha, 15, 23, 42))
            }
            scrim.background = android.graphics.drawable.InsetDrawable(
                shape, (4f * d).toInt(), (5f * d).toInt(),
                (4f * d).toInt(), (5f * d).toInt(),
            )
        }
        val wallpaper = wallpaperView ?: return
        // Buick E5 landscape: user wants crisp original wallpaper behind Dock.
        // Portrait/bottom dock continues to respect the existing blur preference.
        val percent = if (barForm) DockPrefs.blur(this) else 0
        if (percent <= 0) {
            wallpaper.setRenderEffect(null)
        } else {
            val radius = percent / 100f * 40f * designScale
            wallpaper.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    /** 壁纸版本变化时后台重解码并换图（监听器模式下与桌面同步更新）。 */
    private fun refreshWallpaperIfChanged() {
        val view = wallpaperView ?: return
        val current = WallpaperStore.version
        if (current == wallpaperShownVersion) return
        wallpaperShownVersion = current
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        Bg.run("wallpaper-dock") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@DockActivity,
                        params.width,
                        params.height,
                    )
                }.getOrNull()
            if (bitmap != null) {
                VirtualLauncher.logSink?.invoke(
                    "WALLPAPER-DOCK: bitmap=${bitmap.width}x${bitmap.height} " +
                        "view=${params.width}x${params.height} top=${params.topMargin}",
                )
                runOnUiThread { view.setImageBitmap(bitmap) }
            } else {
                VirtualLauncher.logSink?.invoke("WALLPAPER-DOCK: 解码失败")
            }
        }
    }

    /** 逐级缩小字号直至文本宽度放得下（设计 px × designScale；行程 22→12，对照设计原型）。 */
    private fun fitClockText(view: TextView) {
        val available = view.width - view.paddingLeft - view.paddingRight
        if (available <= 0) return
        val scale = designScale
        var size = 22f * scale
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        val paint = view.paint
        while (size > 12f * scale && paint.measureText(view.text.toString()) > available) {
            size -= 0.5f * scale
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    private fun updateBattery(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        runOnUiThread {
            batteryText?.text = (if (charging) "⚡" else "") + "$percent%"
            val bar = batteryBar
            val fill = batteryFill
            if (bar != null && fill != null) {
                bar.post {
                    val width = bar.width
                    if (width > 0) {
                        fill.layoutParams =
                            (fill.layoutParams as FrameLayout.LayoutParams).apply {
                                this.width =
                                    (width * percent / 100)
                                        .coerceAtLeast((3 * resources.displayMetrics.density).toInt())
                            }
                    }
                }
                fill.setBackgroundColor(if (percent <= 20) 0xFFFF6B6B.toInt() else 0xFF34C759.toInt())
            }
        }
    }

    /**
     * Read the handset networks independently of CarPlay's bound process network.
     * No network binding, permission request or session changes.
     */
    private fun updateNetworkIndicators() {
        val view = networkIndicators ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networks = runCatching {
            cm?.allNetworks?.mapNotNull { n -> cm.getNetworkCapabilities(n) }.orEmpty()
        }.getOrDefault(emptyList())
        val wifi = networks.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        val cellular = networks.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
        val wifiLevel = if (wifi) runCatching {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val rssi = manager.connectionInfo?.rssi ?: -127
            when {
                rssi <= -100 || rssi >= 0 -> null
                rssi >= -60 -> 3
                rssi >= -74 -> 2
                else -> 1
            }
        }.getOrNull() else null
        val cellularLevel = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28 &&
                packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
            ) {
                (getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                    ?.signalStrength?.level?.coerceIn(0, 4)
            } else null
        }.getOrNull()
        view.setIndicators(wifi, wifiLevel, cellular || cellularLevel != null, cellularLevel)
    }

    private var lastRendered: List<String> = emptyList()

    private fun refresh() {
        val container = appsContainer ?: return
        val current =
            synchronized(VirtualLauncher.runningApps) {
                VirtualLauncher.runningApps.map { it.flattenToString() }
            }
        if (current == lastRendered) return
        lastRendered = current
        container.removeAllViews()
        val density = resources.displayMetrics.density
        // E5 sidebar: render 24dp glyphs in unchanged ~42dp hit targets.
        // Bottom bar remains at its established size and spacing.
        val iconPx = ((if (barForm) 32 else 24) * density).toInt()
        val pad = ((if (barForm) 6 else 9) * density).toInt()
        val apps = synchronized(VirtualLauncher.runningApps) { VirtualLauncher.runningApps.toList() }
        val iconCache = VirtualLauncher.iconCache
        apps.forEach { component ->
            val drawable = iconCache[component] ?: packageManager.defaultActivityIcon
            // 位图化渲染（与桌面网格同一路径）：直接 setImageDrawable 会让 ImageView
            // 长期持有共享 Drawable 实例，其 bounds 被网格重绑改写后渲染错位
            // （实机故障：点 home 后 dock 图标变小且偏左）
            val bitmap = AppIcons.rounded(drawable, iconPx)
            container.addView(
                ImageView(this).apply {
                    setImageBitmap(bitmap)
                    setPadding(pad, pad, pad, pad)
                    contentDescription = component.shortClassName
                    setOnClickListener {
                        Bg.run("launch-app") { VirtualLauncher.launchApp(component) }
                    }
                },
                LinearLayout.LayoutParams(iconPx + pad * 2, iconPx + pad * 2).apply {
                    if (!barForm) bottomMargin = (3 * density).toInt()
                },
            )
        }
    }

    override fun onBackPressed() = Unit // dock 常驻，不响应返回

    companion object {
        @Volatile private var activeSessionActivity: java.lang.ref.WeakReference<DockActivity>? = null

        internal fun finishForSessionEnd(): java.util.concurrent.CountDownLatch? {
            val activity = activeSessionActivity?.get() ?: return null
            val signal = java.util.concurrent.CountDownLatch(1)
            activity.sessionFinishSignal = signal
            activity.runOnUiThread {
                if (activity.isDestroyed) signal.countDown()
                else if (!activity.isFinishing) activity.finish()
            }
            return signal
        }
    }
}
