package io.github.shaonialife.cardeck.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiInfo
import android.os.SystemClock

/**
 * T74 / Buick-Test experimental observer.
 *
 * Samples only Android vehicle Wi-Fi. These values are NOT the iPhone
 * WiFiManager counters and do NOT prove receiver audio-buffer occupancy.
 * Android public APIs do not expose 802.11 rxACKUcount/txRTSFail.
 *
 * The 18-second guard is an isolated experiment, NOT a production fix.
 * Neither RSSI nor imagined receiver ACK values trigger teardown.
 */
internal object BuickAudioT74LinkGuard {
    private const val POLL_MS = 1_000L
    private const val OBSERVE_MS = 26_000L
    private const val TEST_DOWNGRADE_MS = 18_000L
    private const val RSSI_DROP_DB = 10

    fun observe(
        context: Context,
        vehicleNetwork: Network?,
        stillCurrent: () -> Boolean,
        tryDowngrade: () -> Boolean,
        log: (String) -> Unit,
        enableTimedDowngrade: Boolean,
    ) {
        Thread(
            {
                val cm =
                    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as? ConnectivityManager
                val startedAt = SystemClock.elapsedRealtime()
                var strongestRssi: Int? = null
                var dropNoted = false
                var degradeAttempted = false
                var noMeasurementNoted = false
                log(
                    "AUDIO T74: E5 车机 Wi-Fi RSSI/速率采样（1秒/次）；" +
                        "802.11 ACK/RTS 驱动计数普通 Android App 不可读，不作为断音依据；" +
                        "18秒定时降级实验=${enableTimedDowngrade}",
                )

                while (stillCurrent()) {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    if (elapsed > OBSERVE_MS) break
                    val info =
                        runCatching {
                            vehicleNetwork?.let {
                                cm?.getNetworkCapabilities(it)?.transportInfo as? WifiInfo
                            }
                        }.getOrNull()
                    val rssi = info?.rssi?.takeIf { it in -100..-1 }
                    val txMbps = info?.txLinkSpeedMbps?.takeIf { it >= 0 }
                    val rxMbps = info?.rxLinkSpeedMbps?.takeIf { it >= 0 }
                    if (rssi == null && !noMeasurementNoted) {
                        noMeasurementNoted = true
                        log("AUDIO T74: 无法读取车机 Wi-Fi RSSI（ROM 未提供或 Network 缺失），继续观察会话")
                    }
                    if (rssi != null) {
                        strongestRssi = maxOf(strongestRssi ?: rssi, rssi)
                        val drop = (strongestRssi ?: rssi) - rssi
                        if (drop >= RSSI_DROP_DB && !dropNoted) {
                            dropNoted = true
                            log("AUDIO T74: RSSI 相对本次峰值下降 ${drop}dB；不能单独认定丢包或车机缓冲满")
                        }
                    }
                    if (elapsed % 5_000L < POLL_MS) {
                        log(
                            "AUDIO T74: +${elapsed}ms Wi-Fi RSSI=${rssi ?: "unknown"}dBm " +
                                "txLink=${txMbps ?: "unknown"}Mbps rxLink=${rxMbps ?: "unknown"}Mbps",
                        )
                    }
                    if (enableTimedDowngrade && !degradeAttempted && elapsed >= TEST_DOWNGRADE_MS) {
                        degradeAttempted = true
                        log("AUDIO T74: 到达18秒窗口，先经 RTSP 撤销音频流；未确认不盲停 RTP")
                        val downgraded = runCatching { stillCurrent() && tryDowngrade() }
                            .onFailure { log("AUDIO T74: 降级异常：${it.javaClass.simpleName}") }
                            .getOrDefault(false)
                        log("AUDIO T74: 18秒降级结果 confirmed=${downgraded}")
                        if (downgraded) break
                    }
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                log("AUDIO T74: Wi-Fi/18秒实验观测结束")
            },
            "buick-t74-link-guard",
        ).apply {
            isDaemon = true
            start()
        }
    }
}
