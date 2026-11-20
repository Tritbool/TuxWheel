package io.github.tuxwheel

import android.content.Context
import android.text.InputType
import android.widget.EditText
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
        onAdd: () -> Unit,
    ) {
        val wheels = config.rememberedWheels
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.my_wheels)
            .setPositiveButton(R.string.wheels_done, null)
        if (onDisconnect != null) {
            dialog.setNeutralButton(R.string.disconnect_from_wheel) { _, _ -> onDisconnect() }
        } else {
            dialog.setNeutralButton(R.string.wheels_add) { _, _ -> onAdd() }
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
                fun refresh() = show(connectedAddress, onDisconnect, onConnect, onAdd)
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
                                update { this[index] = wheel.copy(alias = input.text.toString().trim()) }
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
}
