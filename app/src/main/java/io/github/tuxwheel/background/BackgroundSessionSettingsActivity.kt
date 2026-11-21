package io.github.tuxwheel.background

import android.Manifest
import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.R
import org.koin.android.ext.android.inject

class BackgroundSessionSettingsActivity : ComponentActivity() {
    private val config: AppConfig by inject()
    private val manager: BackgroundWheelManager by inject()
    private lateinit var content: LinearLayout
    private var enabling = false
    private var associating = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { granted -> granted }) enable()
        else {
            enabling = false
            Toast.makeText(this, R.string.background_session_permissions, Toast.LENGTH_LONG).show()
            render()
        }
    }
    private val association = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        associating = false
        if (it.resultCode == Activity.RESULT_OK) {
            manager.refresh()
            if (enabling) enable()
        } else enabling = false
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.background_session_title)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (enabling && !associating && manager.hasPermissions(includeBackgroundLocation = false)) enable()
        else render()
    }

    private fun render() {
        content.removeAllViews()
        text(R.string.background_session_description)
        text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            R.string.background_session_companion_help
        } else R.string.background_session_legacy_help)
        content.addView(Switch(this).apply {
            setText(R.string.background_session_enable)
            isChecked = config.backgroundSessionEnabled && !config.backgroundSessionPaused
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    enabling = true
                    requestEnable()
                } else {
                    enabling = false
                    config.backgroundSessionEnabled = false
                    manager.refresh()
                    render()
                }
            }
        })
        button(getString(R.string.background_session_timeout, config.backgroundSessionTimeoutMinutes)) {
            val builder = AlertDialog.Builder(this)
            val input = EditText(builder.context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(config.backgroundSessionTimeoutMinutes.toString())
            }
            val dialog = builder.setTitle(R.string.background_session_timeout_title)
                .setView(input).setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val minutes = input.text.toString().toIntOrNull()
                    if (minutes == null || minutes !in 1..120) {
                        input.error = getString(R.string.background_session_timeout_error)
                    } else {
                        config.backgroundSessionTimeoutMinutes = minutes
                        dialog.dismiss()
                        render()
                    }
                }
            }
            dialog.show()
        }
        val policies = resources.getStringArray(R.array.background_disconnect_policies)
        button(getString(R.string.background_session_disconnect, policies[config.backgroundManualDisconnectPolicy])) {
            AlertDialog.Builder(this).setTitle(R.string.background_session_disconnect_title)
                .setSingleChoiceItems(policies, config.backgroundManualDisconnectPolicy) { dialog, index ->
                    config.backgroundManualDisconnectPolicy = index
                    dialog.dismiss()
                    render()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val associated = manager.associations()
            for (wheel in config.rememberedWheels) {
                button(getString(
                    if (wheel.address in associated) R.string.background_session_associated
                    else R.string.background_session_associate, wheel.displayName,
                )) {
                    if (wheel.address !in associated) associate(wheel.address)
                    else Toast.makeText(this, R.string.background_session_already_associated, Toast.LENGTH_SHORT).show()
                }
            }
        }
        button(getString(R.string.background_session_done)) { finish() }
    }

    private fun requestEnable() {
        if (config.rememberedWheels.isEmpty()) {
            enabling = false
            Toast.makeText(this, R.string.wheels_empty, Toast.LENGTH_LONG).show()
            render()
            return
        }
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required += Manifest.permission.BLUETOOTH_SCAN
            required += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            required += Manifest.permission.ACCESS_FINE_LOCATION
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) required += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        else enable()
    }

    private fun enable() {
        if (associating || !manager.hasPermissions(includeBackgroundLocation = false)) return
        if (Build.VERSION.SDK_INT in 29..30 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            enabling = false
            AlertDialog.Builder(this).setMessage(R.string.background_session_location_help)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (Build.VERSION.SDK_INT == 29) {
                        enabling = true
                        permissions.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    } else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.setNegativeButton(android.R.string.cancel, null).show()
            render()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                enabling = false
                Toast.makeText(this, R.string.background_session_companion_unavailable, Toast.LENGTH_LONG).show()
                render()
                return
            }
            val missing = config.rememberedWheels.firstOrNull { it.address !in manager.associations() }
            if (missing != null) {
                associate(missing.address)
                return
            }
        }
        enabling = false
        config.backgroundSessionEnabled = true
        manager.restore()
        manager.startService()
        render()
    }

    @Suppress("DEPRECATION")
    private fun associate(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        associating = true
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(
                ScanFilter.Builder().setDeviceAddress(address).build(),
            ).build())
            .setSingleDevice(true).build()
        try {
            getSystemService(CompanionDeviceManager::class.java).associate(
                request,
                object : CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(chooserLauncher: IntentSender) {
                        association.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                    }

                    override fun onFailure(error: CharSequence?) {
                        associating = false
                        enabling = false
                        manager.reportError(IllegalStateException(error?.toString() ?: getString(R.string.background_session_association_failed)))
                        render()
                    }
                },
                android.os.Handler(mainLooper),
            )
        } catch (e: IllegalArgumentException) {
            associating = false
            enabling = false
            manager.reportError(e)
            render()
        } catch (e: IllegalStateException) {
            associating = false
            enabling = false
            manager.reportError(e)
            render()
        } catch (e: SecurityException) {
            associating = false
            enabling = false
            manager.reportError(e)
            render()
        }
    }

    private fun text(resource: Int) {
        content.addView(TextView(this).apply { setText(resource) })
    }

    private fun button(label: String, action: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
