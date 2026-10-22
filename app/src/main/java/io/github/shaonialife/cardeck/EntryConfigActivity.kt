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
import io.github.shaonialife.cardeck.gateway.GatewayNetworkRoute
import io.github.shaonialife.cardeck.gateway.GatewayProbe
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
    private var testGeneration = 0
    private var probe: GatewayProbe? = null
    private var route: GatewayNetworkRoute? = null

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
        disconnect.isEnabled = testRunning
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
        val generation = ++testGeneration
        testRunning = true
        gatewayStatus.setText(R.string.gateway_connecting)
        refreshControls()
        val appContext = applicationContext
        Bg.run("gateway-probe-start") {
            val prepared = runCatching {
                val selectedRoute = GatewayNetworkRoute.create(appContext)
                val selectedProbe = GatewayProbe(selectedRoute.client, observer = { snapshot ->
                    runOnUiThread {
                        if (testGeneration == generation && !isFinishing && !isDestroyed) renderGateway(snapshot)
                    }
                })
                selectedRoute to selectedProbe
            }
            runOnUiThread {
                if (testGeneration != generation || isFinishing || isDestroyed) {
                    prepared.getOrNull()?.let { (unusedRoute, unusedProbe) ->
                        unusedRoute.close()
                        unusedProbe.close()
                    }
                    return@runOnUiThread
                }
                prepared.onSuccess { (selectedRoute, selectedProbe) ->
                    route = selectedRoute
                    probe = selectedProbe
                    runCatching {
                        selectedRoute.watch { selectedProbe.networkLost() }
                        selectedProbe.start(config)
                    }.onFailure { selectedProbe.networkLost() }
                }.onFailure {
                    testRunning = false
                    gatewayStatus.setText(R.string.gateway_network_failed)
                    refreshControls()
                }
            }
        }
    }

    private fun renderGateway(snapshot: GatewayProbe.Snapshot) {
        gatewayStatus.text = when (snapshot.state) {
            GatewayProbe.State.CONNECTING -> getString(R.string.gateway_connecting)
            GatewayProbe.State.CONNECTED -> getString(R.string.gateway_connected)
            GatewayProbe.State.HEALTHY -> getString(R.string.gateway_healthy, snapshot.heartbeatCount, snapshot.latencyMs)
            GatewayProbe.State.AUTH_FAILED -> getString(R.string.gateway_auth_failed)
            GatewayProbe.State.DISCONNECTED -> getString(R.string.gateway_disconnected)
            GatewayProbe.State.FAILED -> when (snapshot.failure) {
                GatewayProbe.Failure.TLS -> getString(R.string.gateway_tls_failed)
                GatewayProbe.Failure.HANDSHAKE -> getString(R.string.gateway_handshake_failed, snapshot.httpCode ?: 0)
                GatewayProbe.Failure.PROTOCOL -> getString(R.string.gateway_protocol_failed)
                GatewayProbe.Failure.HEARTBEAT -> getString(R.string.gateway_heartbeat_failed)
                GatewayProbe.Failure.CONFIGURATION -> getString(R.string.gateway_save_first)
                else -> getString(R.string.gateway_network_failed)
            }
        }
        if (snapshot.state in setOf(GatewayProbe.State.AUTH_FAILED, GatewayProbe.State.DISCONNECTED, GatewayProbe.State.FAILED)) {
            testRunning = false
            route?.close()
            route = null
            probe = null
            refreshControls()
        }
    }

    private fun stopTest() {
        testGeneration++
        route?.close()
        route = null
        probe?.close()
        probe = null
        if (testRunning) gatewayStatus.setText(R.string.gateway_disconnected)
        testRunning = false
        refreshControls()
    }

    override fun onStop() {
        stopTest()
        super.onStop()
    }
}
