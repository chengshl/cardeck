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
import io.github.shaonialife.cardeck.util.Bg

class EntryConfigActivity : AppCompatActivity() {
    private lateinit var address: EditText
    private lateinit var credential: EditText
    private lateinit var status: TextView
    private lateinit var save: Button
    private lateinit var clear: Button
    private var stored: EntryConfig? = null

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
        val draft = savedInstanceState?.getString("entry_address_draft")
        busy(true)
        val appContext = applicationContext
        Bg.run("entry-config-load") {
            val result = runCatching { EntryConfigStore.load(appContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy(false)
                result.onSuccess {
                    stored = it
                    address.setText(draft ?: it?.gatewayUrl.orEmpty())
                    credential.hint = getString(if (it == null) R.string.entry_token_hint else R.string.entry_token_kept)
                    status.setText(if (it == null) R.string.entry_unconfigured else R.string.entry_configured)
                }.onFailure {
                    save.isEnabled = false
                    status.setText(R.string.entry_load_failed)
                }
            }
        }
        save.setOnClickListener { saveConfig() }
        clear.setOnClickListener { clearConfig() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("entry_address_draft", address.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun busy(value: Boolean) {
        address.isEnabled = !value
        credential.isEnabled = !value
        save.isEnabled = !value
        clear.isEnabled = !value
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
                    stored = EntryConfig(gatewayUrl, token)
                    address.setText(gatewayUrl)
                    credential.text.clear()
                    credential.hint = getString(R.string.entry_token_kept)
                    status.setText(R.string.entry_saved)
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
                    stored = null
                    address.text.clear()
                    credential.text.clear()
                    credential.hint = getString(R.string.entry_token_hint)
                    status.setText(R.string.entry_unconfigured)
                }.onFailure { status.setText(R.string.entry_save_failed) }
            }
        }
    }
}
