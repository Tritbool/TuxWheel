package io.github.tuxwheel

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.github.tuxwheel.ble.RememberedWheel

internal class RememberedWheelsDialog(
    private val context: Context,
    private val config: AppConfig,
) {
    fun show(
        connectedAddress: String,
        onDisconnect: (() -> Unit)?,
        onConnect: ((RememberedWheel) -> Unit)?,
        onScan: (() -> Unit)?,
    ) {
        fun refresh() = show(connectedAddress, onDisconnect, onConnect, onScan)
        val wheels = config.rememberedWheels
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.my_wheels)
            .setPositiveButton(R.string.wheels_done, null)
            .setNegativeButton(R.string.wheels_add) { _, _ ->
                if (onScan == null) showManualAdd(::refresh) else showAddChooser(onScan, ::refresh)
            }
        if (onDisconnect != null) {
            dialog.setNeutralButton(R.string.disconnect_from_wheel) { _, _ -> onDisconnect() }
        }
        if (wheels.isEmpty()) {
            dialog.setMessage(R.string.wheels_empty)
        } else {
            val nameCounts = wheels.groupingBy { it.displayName }.eachCount()
            val labels = wheels.mapIndexed { index, wheel ->
                val name = if ((nameCounts[wheel.displayName] ?: 0) > 1) {
                    context.getString(R.string.wheels_name_with_address, wheel.displayName, wheel.address)
                } else wheel.displayName
                val label = context.getString(R.string.wheels_priority_item, index + 1, name)
                if (wheel.address.equals(connectedAddress, ignoreCase = true)) {
                    context.getString(R.string.wheels_connected_item, label)
                } else label
            }
            dialog.setTitle(R.string.wheels_priority_title)
            dialog.setItems(labels.toTypedArray()) { _, index ->
                val wheel = wheels[index]
                fun update(change: MutableList<RememberedWheel>.() -> Unit) {
                    config.rememberedWheels = wheels.toMutableList().apply(change)
                    refresh()
                }
                val actions = buildList<Pair<Int, () -> Unit>> {
                    if (onConnect != null) add(R.string.connect_to_wheel to { onConnect(wheel) })
                    if (index > 0) add(R.string.wheels_move_up to { update { add(index - 1, removeAt(index)) } })
                    if (index < wheels.lastIndex) {
                        add(R.string.wheels_move_down to { update { add(index + 1, removeAt(index)) } })
                    }
                    add(R.string.wheels_rename to {
                        val input = EditText(context).apply {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            setSingleLine()
                            setText(wheel.displayName)
                            selectAll()
                        }
                        AlertDialog.Builder(context)
                            .setTitle(R.string.wheels_rename)
                            .setView(input)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val name = input.text.toString().trim()
                                // Keeping the BLE/placeholder name unchanged must not pin it as a custom name.
                                update { this[index] = wheel.copy(alias = if (name == wheel.name) "" else name) }
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> refresh() }
                            .setOnCancelListener { refresh() }
                            .show()
                    })
                    add(R.string.wheels_forget to {
                        AlertDialog.Builder(context)
                            .setMessage(context.getString(R.string.wheels_forget_confirm, wheel.displayName))
                            .setPositiveButton(R.string.wheels_forget) { _, _ -> update { removeAt(index) } }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> refresh() }
                            .setOnCancelListener { refresh() }
                            .show()
                    })
                }
                AlertDialog.Builder(context)
                    .setTitle(wheel.displayName)
                    .setItems(actions.map { context.getString(it.first) }.toTypedArray()) { _, action ->
                        actions[action].second()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> refresh() }
                    .setOnCancelListener { refresh() }
                    .show()
            }
        }
        dialog.show()
    }

    private fun showAddChooser(onScan: () -> Unit, refresh: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.wheels_add)
            .setItems(
                arrayOf(context.getString(R.string.wheels_add_scan), context.getString(R.string.wheels_add_manual))
            ) { _, which -> if (which == 0) onScan() else showManualAdd(refresh) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> refresh() }
            .setOnCancelListener { refresh() }
            .show()
    }

    private fun showManualAdd(refresh: () -> Unit, address: String = "", name: String = "") {
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val addressInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            hint = "AA:BB:CC:DD:EE:FF"
            setText(address)
        }
        val nameInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            setHint(R.string.wheels_manual_name_hint)
            setText(name)
        }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(addressInput)
            addView(nameInput)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.wheels_add_manual)
            .setView(form)
            .setPositiveButton(R.string.wheels_add_confirm) { _, _ ->
                val enteredAddress = addressInput.text.toString()
                val enteredName = nameInput.text.toString()
                when (config.addManualWheel(enteredAddress, enteredName)) {
                    AppConfig.ManualWheelResult.ADDED -> refresh()
                    AppConfig.ManualWheelResult.INVALID_ADDRESS -> {
                        Toast.makeText(context, R.string.wheels_invalid_address, Toast.LENGTH_SHORT).show()
                        showManualAdd(refresh, enteredAddress, enteredName)
                    }
                    AppConfig.ManualWheelResult.ALREADY_SAVED -> {
                        Toast.makeText(context, R.string.wheels_already_saved, Toast.LENGTH_SHORT).show()
                        showManualAdd(refresh, enteredAddress, enteredName)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> refresh() }
            .setOnCancelListener { refresh() }
            .show()
    }
}
