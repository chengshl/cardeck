package io.github.shaonialife.cardeck

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.config.EntryAddress
import io.github.shaonialife.cardeck.config.EntryConfig
import io.github.shaonialife.cardeck.config.EntryConfigStore
import io.github.shaonialife.cardeck.gateway.GatewayProbe
import io.github.shaonialife.cardeck.gateway.GatewayLogRuntime
import io.github.shaonialife.cardeck.gateway.LogRedactor
import io.github.shaonialife.cardeck.gateway.SystemLogCapture
import io.github.shaonialife.cardeck.gateway.CrashEvidenceSelector
import io.github.shaonialife.cardeck.service.GatewayLogService
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import android.os.SystemClock
import android.content.Context
import java.security.MessageDigest
import io.github.shaonialife.cardeck.util.Bg

class EntryConfigActivity : AppCompatActivity() {
    private lateinit var address: EditText
    private lateinit var credential: EditText
    private lateinit var status: TextView
    private lateinit var save: Button
    private lateinit var clear: Button
    private lateinit var test: Button
    private lateinit var disconnect: Button
    private lateinit var gatewayStatus: TextView
    private var stored: EntryConfig? = null
    private var configLoaded = false
    private var storageBusy = false
    private var testRunning = false
    private lateinit var logStatus: TextView
    private lateinit var systemLogs: Button
    private lateinit var recoveryLogs: Button
    private var observer: AutoCloseable? = null
    private var previewDialog: AlertDialog? = null
    private var previewGeneration = 0
    private var captureBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_entry_config)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        address = findViewById(R.id.entryAddress)
        credential = findViewById(R.id.entryCredential)
        status = findViewById(R.id.entryStatus)
        save = findViewById(R.id.saveEntry)
        clear = findViewById(R.id.clearEntry)
        test = findViewById(R.id.testEntry)
        disconnect = findViewById(R.id.disconnectEntry)
        gatewayStatus = findViewById(R.id.gatewayStatus)
        logStatus = findViewById(R.id.gatewayLogStatus)
        systemLogs = findViewById(R.id.uploadSystemLogs)
        systemLogs.setOnClickListener { previewSystemLogs(recovery = false) }
        recoveryLogs = findViewById(R.id.recoverCrashLogs)
        recoveryLogs.setOnClickListener { previewSystemLogs(recovery = true) }
        val draft = savedInstanceState?.getString("entry_address_draft")
        busy(true)
        val appContext = applicationContext
        Bg.run("entry-config-load") {
            val result = runCatching { EntryConfigStore.load(appContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy(false)
                result.onSuccess {
                    configLoaded = true
                    stored = it
                    address.setText(draft ?: it?.gatewayUrl.orEmpty())
                    credential.hint = getString(if (it == null) R.string.entry_token_hint else R.string.entry_token_kept)
                    status.setText(if (it == null) R.string.entry_unconfigured else R.string.entry_configured)
                    refreshControls()
                }.onFailure {
                    save.isEnabled = false
                    test.isEnabled = false
                    status.setText(R.string.entry_load_failed)
                }
            }
        }
        save.setOnClickListener { saveConfig() }
        clear.setOnClickListener { clearConfig() }
        test.setOnClickListener { startTest() }
        disconnect.setOnClickListener { stopTest() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("entry_address_draft", address.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun busy(value: Boolean) {
        storageBusy = value
        refreshControls()
    }

    private fun refreshControls() {
        val editable = !storageBusy && !testRunning
        address.isEnabled = editable
        credential.isEnabled = editable
        save.isEnabled = editable && configLoaded
        clear.isEnabled = editable
        test.isEnabled = editable && configLoaded && stored != null
        disconnect.isEnabled = testRunning || GatewayLogRuntime.running
        val snapshot = GatewayLogRuntime.snapshot
        systemLogs.isEnabled = !captureBusy && GatewayLogRuntime.running && snapshot?.state == GatewayProbe.State.HEALTHY && snapshot.logsSupported && !snapshot.systemPending
        recoveryLogs.isEnabled = systemLogs.isEnabled
    }

    private fun saveConfig() {
        val gatewayUrl = runCatching { EntryAddress.normalize(address.text.toString()) }.getOrElse {
            address.error = getString(R.string.entry_invalid_address)
            return
        }
        val token = credential.text.toString().ifEmpty { stored?.token.orEmpty() }
        if (token.isBlank() || token.length > 4096 || token.any { it <= ' ' || it > '~' }) {
            credential.error = getString(R.string.entry_invalid_token)
            return
        }
        // 改入口时不得把旧服务器的凭证默默发送给新服务器。
        if (stored != null && gatewayUrl != stored?.gatewayUrl && credential.text.isEmpty()) {
            credential.error = getString(R.string.entry_changed_address_token)
            return
        }
        stopTest()
        busy(true)
        val appContext = applicationContext
        Bg.run("entry-config-save") {
            val result = runCatching { EntryConfigStore.save(appContext, gatewayUrl, token) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy(false)
                result.onSuccess {
                    configLoaded = true
                    stored = EntryConfig(gatewayUrl, token)
                    address.setText(gatewayUrl)
                    credential.text.clear()
                    credential.hint = getString(R.string.entry_token_kept)
                    status.setText(R.string.entry_saved)
                    refreshControls()
                }.onFailure { status.setText(R.string.entry_save_failed) }
            }
        }
    }

    private fun clearConfig() {
        stopTest()
        busy(true)
        val appContext = applicationContext
        Bg.run("entry-config-clear") {
            val result = runCatching { EntryConfigStore.clear(appContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy(false)
                result.onSuccess {
                    configLoaded = true
                    stored = null
                    address.text.clear()
                    credential.text.clear()
                    credential.hint = getString(R.string.entry_token_hint)
                    status.setText(R.string.entry_unconfigured)
                    gatewayStatus.setText(R.string.gateway_idle)
                    refreshControls()
                }.onFailure { status.setText(R.string.entry_save_failed) }
            }
        }
    }

    private fun startTest() {
        val config = stored ?: return
        if (testRunning || storageBusy) return
        if (address.text.toString().trim() != config.gatewayUrl || credential.text.isNotEmpty()) {
            gatewayStatus.setText(R.string.gateway_save_first)
            return
        }
        testRunning = true
        gatewayStatus.setText(R.string.gateway_connecting)
        refreshControls()
        runCatching { GatewayLogService.start(this) }.onFailure {
            testRunning = false
            gatewayStatus.setText(R.string.gateway_log_start_failed)
            refreshControls()
        }
    }

    private fun renderGateway(snapshot: GatewayProbe.Snapshot) {
        gatewayStatus.text = when (snapshot.state) {
            GatewayProbe.State.CONNECTING -> getString(R.string.gateway_connecting)
            GatewayProbe.State.CONNECTED -> getString(R.string.gateway_connected)
            GatewayProbe.State.HEALTHY -> getString(R.string.gateway_healthy, snapshot.heartbeatCount, snapshot.latencyMs) +
                "\n" + if (snapshot.reportsSupported) getString(R.string.gateway_reports, snapshot.reportCount)
                else getString(R.string.gateway_reports_unsupported)
            GatewayProbe.State.AUTH_FAILED -> getString(R.string.gateway_auth_failed)
            GatewayProbe.State.DISCONNECTED -> getString(if (GatewayLogRuntime.running) R.string.gateway_reconnecting else R.string.gateway_disconnected)
            GatewayProbe.State.FAILED -> when (snapshot.failure) {
                GatewayProbe.Failure.TLS -> getString(R.string.gateway_tls_failed)
                GatewayProbe.Failure.HANDSHAKE -> getString(R.string.gateway_handshake_failed, snapshot.httpCode ?: 0)
                GatewayProbe.Failure.PROTOCOL -> getString(R.string.gateway_protocol_failed)
                GatewayProbe.Failure.HEARTBEAT -> getString(R.string.gateway_heartbeat_failed)
                GatewayProbe.Failure.CONFIGURATION -> getString(R.string.gateway_save_first)
                GatewayProbe.Failure.LOG_UPLOAD -> getString(R.string.gateway_log_timeout)
                else -> getString(R.string.gateway_network_failed)
            }
        }
        logStatus.text = if (snapshot.state == GatewayProbe.State.HEALTHY && snapshot.logsSupported) {
            getString(R.string.gateway_log_counts, snapshot.appLogLines, snapshot.systemLogLines, snapshot.droppedLogLines) +
                if (snapshot.systemPending) "\n" + getString(R.string.gateway_system_pending) else ""
        } else getString(if (snapshot.state == GatewayProbe.State.HEALTHY) R.string.gateway_log_unsupported else R.string.gateway_log_waiting)
        testRunning = GatewayLogRuntime.running
        refreshControls()
    }

    private fun stopTest() {
        previewGeneration++
        previewDialog?.dismiss()
        previewDialog = null
        GatewayLogService.stop(applicationContext)
        gatewayStatus.setText(R.string.gateway_disconnected)
        testRunning = false
        refreshControls()
    }

    private fun previewSystemLogs(recovery: Boolean) {
        val target = GatewayLogRuntime.probe ?: return
        val config = stored ?: return
        if (captureBusy) return
        val generation = ++previewGeneration
        captureBusy = true
        logStatus.text = if (recovery) "正在读取上次会话与系统崩溃线索…" else getString(R.string.gateway_system_collecting)
        refreshControls()
        val appContext = applicationContext
        Bg.run("system-log-preview") {
            val result = runCatching {
                val redactor = LogRedactor(listOf(config.token))
                if (recovery) SystemLogCapture.recoveryPreview(appContext, redactor)
                else SystemLogCapture.preview(appContext, redactor)
            }
            runOnUiThread {
                if (generation != previewGeneration || isFinishing || isDestroyed) return@runOnUiThread
                captureBusy = false
                refreshControls()
                val lines = result.getOrNull()
                if (lines.isNullOrEmpty()) {
                    logStatus.setText(R.string.gateway_system_unavailable)
                    return@runOnUiThread
                }
                // Dated DropBox event ID is stronger than a repeated crash stack.
                val eventId = if (recovery) CrashEvidenceSelector.datedCrashId(lines) else null
                val crashStack = lines.filter {
                    it.startsWith("[DropBox:system_server_crash]") ||
                        it.startsWith("[上次启动crash]")
                }
                val fingerprintSource = eventId ?: crashStack.take(18).joinToString("\n")
                val crashSignature = if (recovery && fingerprintSource.isNotEmpty()) {
                    MessageDigest.getInstance("SHA-256")
                        .digest(fingerprintSource.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                } else null
                val fingerprints = getSharedPreferences("buick-crash-evidence-fingerprints", Context.MODE_PRIVATE)
                val sameStack = crashSignature != null &&
                    crashSignature == fingerprints.getString("last_submitted_crash_signature_v2", null)
                val expires = SystemClock.elapsedRealtime() + 120_000
                val body = TextView(this).apply {
                    text = lines.joinToString("\n")
                    setTextIsSelectable(true)
                    textSize = 12f
                    setPadding(24, 16, 24, 16)
                }
                val scroll = ScrollView(this).apply { addView(body) }
                previewDialog = AlertDialog.Builder(this)
                    .setTitle(if (recovery) "重启崩溃取证 · 脱敏预览" else getString(R.string.gateway_system_preview_title))
                    .setMessage(
                        if (recovery) "关键取证 ${lines.size} 行（上限64行，优先保留事件时间、异常首行与调用栈）。" +
                            (if (eventId != null) "\n已识别崩溃事件：$eventId" else "\n未识别到带时间的崩溃事件，不能判定是新故障。") +
                            (if (sameStack) "\n注意：与上次提交的崩溃事件或堆栈相同，可能重复读取旧DropBox。" else "") +
                            "\n系统日志需要 Shizuku；历史记录可能重复。没有新记录不代表没有崩溃。" +
                            "\n仅点击“确认上传”才会发送以下脱敏内容。"
                        else getString(R.string.gateway_system_preview_help, lines.size)
                    )
                    .setView(scroll)
                    .setNegativeButton(R.string.gateway_system_cancel, null)
                    .setPositiveButton(R.string.gateway_system_confirm) { _, _ ->
                        val valid = GatewayLogRuntime.running && GatewayLogRuntime.probe === target && SystemClock.elapsedRealtime() <= expires
                        val accepted = valid && target.uploadConfirmedSystemLogs(lines)
                        if (accepted && crashSignature != null) {
                            fingerprints.edit().putString("last_submitted_crash_signature_v2", crashSignature).apply()
                        }
                        logStatus.setText(if (accepted) R.string.gateway_system_pending else R.string.gateway_system_expired)
                    }.create().also { dialog ->
                        dialog.show()
                        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        scroll.layoutParams = scroll.layoutParams.apply { height = (320 * resources.displayMetrics.density).toInt() }
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        observer = GatewayLogRuntime.subscribe { snapshot ->
            runOnUiThread { if (!isFinishing && !isDestroyed) renderGateway(snapshot) }
        }
        testRunning = GatewayLogRuntime.running
        refreshControls()
    }

    override fun onStop() {
        observer?.close()
        observer = null
        previewGeneration++
        captureBusy = false
        previewDialog?.dismiss()
        previewDialog = null
        super.onStop()
    }
}
