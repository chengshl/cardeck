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
import io.github.shaonialife.cardeck.gateway.CombinedDiagnosticSelector
import io.github.shaonialife.cardeck.gateway.OfflineDiagnosticExporter
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.service.GatewayLogService
import android.app.Dialog
import io.github.shaonialife.cardeck.ui.DiagnosticPreviewDialog
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
    private lateinit var localAppLogs: Button
    private lateinit var localBundle: Button
    private var observer: AutoCloseable? = null
    private var previewDialog: Dialog? = null
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
        localAppLogs = findViewById(R.id.saveAppLogsLocally)
        localAppLogs.setOnClickListener { exportAppLogsLocally() }
        localBundle = findViewById(R.id.saveDiagnosticBundle)
        localBundle.setOnClickListener { exportDiagnosticBundle() }
        systemLogs = findViewById(R.id.uploadSystemLogs)
        systemLogs.setOnClickListener { previewCombinedDiagnostics() }
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
        // Saving local files never depends on the diagnostic server.
        localAppLogs.isEnabled = !captureBusy
        localBundle.isEnabled = !captureBusy
        // Still useful after a reboot when Shizuku is not running: keep app breadcrumbs.
        systemLogs.isEnabled = !captureBusy
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
                if (snapshot.systemPending && snapshot.systemUploadTotal > 0)
                    "\n正在分批上传系统日志：${snapshot.systemUploadAcknowledged}/${snapshot.systemUploadTotal} 行（每批最多8行，服务器逐批确认）"
                else if (snapshot.systemUploadTotal > 0)
                    "\n本次已确认上传：${snapshot.systemUploadAcknowledged}/${snapshot.systemUploadTotal} 行"
                else ""
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

    private fun previewCombinedDiagnostics() {
        val target = GatewayLogRuntime.probe
        val token = stored?.token.orEmpty()
        if (captureBusy) return
        val generation = ++previewGeneration
        captureBusy = true
        logStatus.text = "正在汇总系统日志、上轮会话及崩溃证据…"
        refreshControls()
        val appContext = applicationContext
        Bg.run("system-log-preview") {
            val result = runCatching {
                val redactor = LogRedactor(listOf(token))
                // Both sources are sampled only after this user action. A system-log
                // failure must not discard the app's persisted crash breadcrumbs.
                val crash = runCatching {
                    SystemLogCapture.recoveryPreview(appContext, redactor)
                }.getOrElse { listOf("[取证状态] 重启证据读取失败，请检查本地诊断包") }
                val shizukuReady = ShizukuGate.state(appContext) == ShizukuGate.State.READY
                val system = if (shizukuReady) runCatching {
                    SystemLogCapture.preview(appContext, redactor)
                }.getOrDefault(emptyList()) else emptyList()
                CombinedDiagnosticSelector.merge(crash, system, shizukuReady)
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
                val eventId = CrashEvidenceSelector.datedCrashId(lines)
                val crashStack = lines.filter {
                    it.startsWith("[DropBox:system_server_crash]") ||
                        it.startsWith("[上次启动crash]")
                }
                val fingerprintSource = eventId ?: crashStack.take(18).joinToString("\n")
                val crashSignature = if (fingerprintSource.isNotEmpty()) {
                    MessageDigest.getInstance("SHA-256")
                        .digest(fingerprintSource.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                } else null
                val fingerprints = getSharedPreferences("buick-crash-evidence-fingerprints", Context.MODE_PRIVATE)
                val sameStack = crashSignature != null &&
                    crashSignature == fingerprints.getString("last_submitted_crash_signature_v2", null)
                val expires = SystemClock.elapsedRealtime() + 120_000
                // 诊断UI由独立深色弹层承载，先看摘要再按需展开精选日志。
                // 保持既有256行脱敏边界、确认上传、120秒快照有效期与签名指纹判断。
                val uploadReady = target != null && stored != null && GatewayLogRuntime.running &&
                    GatewayLogRuntime.snapshot?.state == GatewayProbe.State.HEALTHY &&
                    GatewayLogRuntime.snapshot?.logsSupported == true &&
                    GatewayLogRuntime.snapshot?.systemPending != true
                previewDialog = DiagnosticPreviewDialog.show(
                    this,
                    DiagnosticPreviewDialog.Model(
                        lines = lines,
                        eventId = eventId,
                        uploadedBefore = sameStack,
                        shizukuReady = ShizukuGate.state(this) == ShizukuGate.State.READY,
                        uploadReady = uploadReady,
                    ),
                    onSave = {
                        saveLocalPreview(OfflineDiagnosticExporter.Kind.DIAGNOSTIC, lines)
                    },
                    onUpload = {
                        val valid = target != null && GatewayLogRuntime.running &&
                            GatewayLogRuntime.probe === target &&
                            GatewayLogRuntime.snapshot?.state == GatewayProbe.State.HEALTHY &&
                            SystemClock.elapsedRealtime() <= expires
                        val accepted = valid && target?.uploadConfirmedSystemLogs(lines) == true
                        if (accepted && crashSignature != null) {
                            fingerprints.edit()
                                .putString("last_submitted_crash_signature_v2", crashSignature)
                                .apply()
                        }
                        logStatus.setText(
                            if (accepted) R.string.gateway_system_pending else R.string.gateway_system_expired,
                        )
                    },
                )
            }
        }
    }

    private fun exportDiagnosticBundle() {
        if (captureBusy) return
        captureBusy = true
        refreshControls()
        logStatus.text = "正在整理完整本地诊断包（按需读取系统证据，不上传）…"
        val token = stored?.token.orEmpty()
        Bg.run("offline-diagnostic-bundle") {
            val app = applicationContext
            val redactor = LogRedactor(listOf(token))
            val hasShizuku = ShizukuGate.state(app) == ShizukuGate.State.READY
            val crash = runCatching {
                SystemLogCapture.recoveryPreview(app, redactor)
            }.getOrElse { listOf("[取证状态] 本次读取异常；请查看 App 历史与阶段记录") }
            val system = if (hasShizuku) runCatching {
                SystemLogCapture.preview(app, redactor)
            }.getOrDefault(emptyList()) else emptyList()
            val result = runCatching {
                OfflineDiagnosticExporter.exportBundle(app, redactor, crash, system, hasShizuku)
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                captureBusy = false
                refreshControls()
                showLocalSaveResult(result)
            }
        }
    }

    private fun exportAppLogsLocally() {
        if (captureBusy) return
        captureBusy = true
        refreshControls()
        logStatus.text = "正在整理本地 App 历史日志（脱敏，不上传）…"
        val token = stored?.token.orEmpty()
        Bg.run("offline-app-export") {
            val result = runCatching {
                OfflineDiagnosticExporter.exportApp(applicationContext, LogRedactor(listOf(token)))
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                captureBusy = false
                refreshControls()
                showLocalSaveResult(result)
            }
        }
    }

    private fun saveLocalPreview(kind: OfflineDiagnosticExporter.Kind, lines: List<String>) {
        logStatus.text = "正在保存本地诊断文件（不上传）…"
        Bg.run("offline-system-export") {
            val result = runCatching {
                OfflineDiagnosticExporter.exportPreview(applicationContext, kind, lines)
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showLocalSaveResult(result)
            }
        }
    }

    private fun showLocalSaveResult(result: OfflineDiagnosticExporter.Result?) {
        if (result == null) {
            logStatus.text = "本地保存失败或没有可导出的日志，请检查存储空间后重试"
            return
        }
        logStatus.text = "已保存到 下载/CarDeck：${result.fileName}"
        AlertDialog.Builder(this)
            .setTitle("本地日志保存成功")
            .setMessage("文件位置：\n${result.displayPath}\n\n大小：${result.bytes} 字节。\n\n文件只保存在手机，不会自动上传；可通过文件管理器自行分享。")
            .setPositiveButton("知道了", null)
            .show()
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
