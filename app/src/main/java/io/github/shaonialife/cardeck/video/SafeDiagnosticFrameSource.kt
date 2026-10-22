package io.github.shaonialife.cardeck.video

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T28: produce a small moving H.264 test pattern directly on MediaCodec's
 * input Surface. NO DisplayManager, VirtualDisplay, Shizuku UserService,
 * SurfaceTexture display input, Activity launch, or touch injection.
 *
 * Purpose: isolate Sony Android 15 system_server's onDisplayAdded() crash
 * while preserving the B233 iAP2/WiFi/AirPlay/RECORD/video TCP handshake.
 * This is a diagnostic card, NOT the CarDeck desktop.
 */
internal class SafeDiagnosticFrameSource(private val log: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    @Volatile private var renderThread: Thread? = null

    fun start(surface: Surface, width: Int, height: Int): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val ready = CountDownLatch(1)
        val hasFirstFrame = AtomicBoolean(false)
        val worker = Thread({
            try {
                runGl(surface, width, height) {
                    hasFirstFrame.set(true)
                    ready.countDown()
                }
            } catch (error: Throwable) {
                log("VIDEO T28: 无虚拟屏测试画面异常：${error.javaClass.simpleName}: ${error.message}")
            } finally {
                ready.countDown()
                running.set(false)
            }
        }, "t28-diagnostic-egl").apply { isDaemon = true }
        renderThread = worker
        worker.start()

        val readyInTime = ready.await(3, TimeUnit.SECONDS)
        if (!readyInTime || !hasFirstFrame.get()) {
            log("VIDEO T28: 首个测试画面未就绪，停止诊断源（未创建 Android 虚拟屏）")
            stop()
            return false
        }
        log("VIDEO T28: 已发送第一帧无虚拟屏测试画面 ${width}x$height")
        return true
    }

    fun stop() {
        running.set(false)
        val worker = renderThread
        worker?.interrupt()
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(1_000L) }
        }
        if (renderThread === worker) renderThread = null
    }

    private fun runGl(surface: Surface, width: Int, height: Int, onFirstFrame: () -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) error("eglGetDisplay failed")
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var window: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            if (!EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) {
                error("eglInitialize failed")
            }
            val configAttributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) ||
                count[0] <= 0
            ) {
                error("recordable EGL config unavailable")
            }
            val config = configs[0] ?: error("recordable EGL config null")
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) error("eglCreateContext failed")
            window = EGL14.eglCreateWindowSurface(
                display, config, surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (window == EGL14.EGL_NO_SURFACE) error("eglCreateWindowSurface failed")
            if (!EGL14.eglMakeCurrent(display, window, window, context)) {
                error("eglMakeCurrent failed")
            }
            var frame = 0L
            while (running.get()) {
                GLES20.glViewport(0, 0, width, height)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                GLES20.glClearColor(0.035f, 0.075f, 0.12f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                // Animated stripe makes a frozen video stream distinguishable
                // from a live transport; no OS display or Android windows.
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                val stripeWidth = (width / 16).coerceAtLeast(1)
                val travel = (width - stripeWidth).coerceAtLeast(1)
                val stripeX = ((frame * stripeWidth / 2L) % travel).toInt()
                GLES20.glScissor(stripeX, 0, stripeWidth, height)
                GLES20.glClearColor(0.04f, 0.62f, 0.74f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                if (!EGL14.eglSwapBuffers(display, window)) {
                    error("eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                }
                if (frame == 0L) onFirstFrame()
                frame++
                try {
                    Thread.sleep(200L) // 5fps low load; H.264 + AirPlay stay alive
                } catch (_: InterruptedException) {
                    break
                }
            }
        } finally {
            runCatching {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
            }
            if (window != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(display, window) }
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(display, context) }
            }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
